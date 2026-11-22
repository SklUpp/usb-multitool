#include "fat_engine.h"

#include "ff.h"
#include "diskio.h"
#include "usb_block.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>


/* Each logical volume maps 1:1 to a physical drive; pt=0 = whole relative space. */
PARTITION VolToPart[FF_VOLUMES] = {
	{0, 0}, {1, 0}, {2, 0}, {3, 0}
};

#define FAT32_MAX_FILE ((uint64_t)0xFFFFFFFFu)
#define EXFAT_MAX_FILE ((uint64_t)0xFFFFFFFFFFFFuLL) /* practical guard */

typedef struct FatVolume {
	int in_use;
	BYTE pdrv;
	FATFS fs;
	uint64_t block_handle;
	uint32_t sector_size;
	uint64_t sector_count;
	FatFsKind kind;
	int mutated; /* a write-path op was attempted; sticky until unmount */
	int barrier_pending; /* CTRL_SYNC not confirmed since the last flush; sticky */
} FatVolume;

static FatVolume g_vols[FF_VOLUMES];
static BYTE g_next_pdrv;
static pthread_mutex_t g_vols_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_fat_mu = PTHREAD_MUTEX_INITIALIZER;

/*
 * Sequential chunk writes keep one FIL open. Reopening per chunk made every
 * f_lseek walk the FAT from the first cluster, turning a linear transfer into
 * O(N^2) USB reads. The mutex serializes the session; sync/close/structure
 * changes flush or abort it before another operation can see the file.
 */
typedef struct {
	int active;
	uint64_t volume;
	FIL fil;
	char full[1024];
	uint64_t offset;
	int exclusive_new;
	/* A write/seek left the FIL in an unknown state and f_close failed. Keep the
	 * handle so a later close can retry, but never append through it. */
	int poisoned;
	/* Created-exclusive file whose close failed; retry unlink on the next close/sync. */
	int unlink_pending;
} FatWriteSession;

static FatWriteSession g_write;

static int map_fr(FRESULT fr)
{
	return (int)fr; /* FR_OK == 0; non-zero = error */
}

static FatVolume *vol_from(uint64_t volume)
{
	FatVolume *v = (FatVolume *)(uintptr_t)volume;
	if (!v) return NULL;
	if (v < &g_vols[0] || v >= &g_vols[FF_VOLUMES]) return NULL;
	if (!v->in_use) return NULL;
	return v;
}

/*
 * True only when the medium may hold state that has not reached it yet.
 * wflag is the FatFs dirty-window bit: a failed mkdir/unlink/write leaves it set
 * and the sectors must be flushed before unmount. fsi_flag is advisory FSInfo /
 * exFAT PercInUse: f_getfree sets it on a read-only browse, so it must not cause
 * a write unless this mount actually attempted a mutation. barrier_pending keeps
 * an unconfirmed CTRL_SYNC alive: sync_fs writes the window and FSInfo and
 * clears their flags *before* the transport barrier, and f_sync drops
 * FA_MODIFIED regardless of the result, so a CTRL_SYNC failure leaves no other
 * trace. A clean volume needs no I/O at teardown at all (important after
 * detach, when writes/CTRL_SYNC fail).
 */
static int vol_needs_flush(const FatVolume *v)
{
	return v->barrier_pending || v->fs.wflag != 0 || (v->mutated && v->fs.fsi_flag == 1);
}

/* Failed write-path ops may have failed at CTRL_SYNC after wflag/fsi_flag were
 * already cleared; remember it so a later sync/close still issues the barrier. */
static void vol_note_fr(FatVolume *v, FRESULT fr)
{
	if (fr == FR_DISK_ERR) v->barrier_pending = 1;
}

static FRESULT retry_pending_unlink_locked(uint64_t volume)
{
	FRESULT fr;

	if (!g_write.unlink_pending || g_write.volume != volume) return FR_OK;
	fr = f_unlink(g_write.full);
	if (fr == FR_OK || fr == FR_NO_FILE) {
		/* FR_NO_FILE means the previous attempt removed the directory entry but
		 * failed later (for example in CTRL_SYNC); cleanup is satisfied. */
		g_write.unlink_pending = 0;
		if (fr == FR_NO_FILE) fr = FR_OK;
	} else {
		FatVolume *v = vol_from(volume);
		if (v) vol_note_fr(v, fr);
	}
	return fr;
}

static FRESULT close_write_session_locked(void)
{
	FRESULT fr;

	if (!g_write.active) return FR_OK;
	fr = f_close(&g_write.fil);
	/* f_close leaves the FIL and its share lock open on failure. Keeping the
	 * session lets the next sync/delete retry instead of leaking FR_LOCKED. */
	if (fr == FR_OK) {
		g_write.active = 0;
		/* A failed abort may have closed the file but left the exclusive
		 * cleanup pending; the now-closed path can be unlinked. */
		if (g_write.unlink_pending) fr = retry_pending_unlink_locked(g_write.volume);
	} else {
		g_write.poisoned = 1;
	}
	return fr;
}

/* The volume is being destroyed; a handle that still failed to close cannot be
 * retried after f_mount(NULL), so drop the session record with the volume. */
static void discard_write_session_for_volume_locked(uint64_t volume)
{
	if (g_write.volume == volume) {
		g_write.active = 0;
		g_write.unlink_pending = 0;
	}
}

static int close_write_session_for_volume_locked(uint64_t volume)
{
	FRESULT fr;

	if (!g_write.active) {
		fr = retry_pending_unlink_locked(volume);
		return fr == FR_OK ? 0 : map_fr(fr);
	}
	if (g_write.volume != volume) return 0;
	fr = close_write_session_locked();
	if (fr != FR_OK) {
		FatVolume *v = vol_from(volume);
		if (v) vol_note_fr(v, fr);
		return map_fr(fr);
	}
	return 0;
}

static int abort_write_session_locked(FatVolume *v, FRESULT failure)
{
	FRESULT close_fr;
	FRESULT unlink_fr = FR_OK;
	int exclusive;

	if (!g_write.active) return map_fr(failure);
	exclusive = g_write.exclusive_new;
	close_fr = f_close(&g_write.fil);
	if (close_fr == FR_OK) {
		g_write.active = 0;
		/* The file is closed; only now is unlinking safe. */
		if (exclusive) unlink_fr = f_unlink(g_write.full);
		if (unlink_fr != FR_OK) g_write.unlink_pending = 1;
	} else {
		/* Keep the failed handle for a retry. Any reuse must close it first. */
		g_write.poisoned = 1;
		if (exclusive) g_write.unlink_pending = 1;
	}
	if (failure == FR_OK) failure = close_fr;
	if (failure == FR_DISK_ERR || close_fr == FR_DISK_ERR || unlink_fr == FR_DISK_ERR) {
		vol_note_fr(v, FR_DISK_ERR);
	}
	if (exclusive && (close_fr != FR_OK || unlink_fr != FR_OK)) {
		return FAT_ENGINE_CLEANUP_FAILED;
	}
	return map_fr(failure);
}

/* Build "N:/path". Returns 0, or FR_INVALID_NAME when the result would not fit
 * out_sz (snprintf truncation). Callers must abort before any FatFs op. */
static int make_path(char *out, size_t out_sz, BYTE pdrv, const char *path)
{
	const char *p = path ? path : "/";
	int n;

	while (*p == '/') p++;
	if (*p == '\0') {
		n = snprintf(out, out_sz, "%u:", (unsigned)pdrv);
	} else {
		n = snprintf(out, out_sz, "%u:/%s", (unsigned)pdrv, p);
	}
	if (n < 0 || (size_t)n >= out_sz) {
		if (out_sz > 0) out[0] = '\0';
		return (int)FR_INVALID_NAME;
	}
	return 0;
}

uint64_t fat_engine_max_file_size(FatFsKind kind)
{
	switch (kind) {
	case FAT_KIND_FAT12:
	case FAT_KIND_FAT16:
	case FAT_KIND_FAT32:
		return FAT32_MAX_FILE;
	case FAT_KIND_EXFAT:
		return EXFAT_MAX_FILE;
	default:
		return FAT32_MAX_FILE;
	}
}

static FatFsKind kind_from_fs(const FATFS *fs)
{
	switch (fs->fs_type) {
	case FS_FAT12: return FAT_KIND_FAT12;
	case FS_FAT16: return FAT_KIND_FAT16;
	case FS_FAT32: return FAT_KIND_FAT32;
	case FS_EXFAT: return FAT_KIND_EXFAT;
	default: return FAT_KIND_UNKNOWN;
	}
}

static FatVolume *alloc_vol_locked(void)
{
	for (int i = 0; i < FF_VOLUMES; i++) {
		BYTE pdrv = (BYTE)((g_next_pdrv + (BYTE)i) % FF_VOLUMES);
		if (!g_vols[pdrv].in_use) {
			g_next_pdrv = (BYTE)((pdrv + 1) % FF_VOLUMES);
			memset(&g_vols[pdrv], 0, sizeof(g_vols[pdrv]));
			g_vols[pdrv].pdrv = pdrv;
			g_vols[pdrv].in_use = 1;
			return &g_vols[pdrv];
		}
	}
	return NULL;
}

int fat_engine_mount(uint64_t block_handle, uint32_t sector_size, uint64_t sector_count,
		     FatFsKind *out_kind, uint64_t *out_volume)
{
	FatVolume *v;
	char path[8];
	FRESULT fr;

	if (!block_handle || sector_size < 512 || sector_size > 4096 || sector_count == 0
	    || !out_kind || !out_volume) {
		return (int)FR_INVALID_PARAMETER;
	}

	pthread_mutex_lock(&g_fat_mu);
	pthread_mutex_lock(&g_vols_mu);
	v = alloc_vol_locked();
	if (!v) {
		pthread_mutex_unlock(&g_vols_mu);
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_TOO_MANY_OPEN_FILES;
	}

	v->block_handle = block_handle;
	v->sector_size = sector_size;
	v->sector_count = sector_count;
	fat_diskio_bind(v->pdrv, block_handle, sector_size, sector_count);

	snprintf(path, sizeof(path), "%u:", (unsigned)v->pdrv);
	fr = f_mount(&v->fs, path, 1);
	if (fr != FR_OK) {
		fat_diskio_unbind(v->pdrv);
		v->in_use = 0;
		pthread_mutex_unlock(&g_vols_mu);
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}

	v->kind = kind_from_fs(&v->fs);
	*out_kind = v->kind;
	*out_volume = (uint64_t)(uintptr_t)v;
	pthread_mutex_unlock(&g_vols_mu);
	pthread_mutex_unlock(&g_fat_mu);
	return 0;
}

int fat_engine_umount(uint64_t volume)
{
	FatVolume *v = vol_from(volume);
	char path[8];
	FRESULT fr;
	uint64_t bh;
	int rc = 0;

	if (!v) return (int)FR_INVALID_OBJECT;
	pthread_mutex_lock(&g_fat_mu);
	pthread_mutex_lock(&g_vols_mu);
	/* Retry a pending exclusive cleanup before the volume object disappears. */
	rc = close_write_session_for_volume_locked(volume);
	bh = v->block_handle;
	/* Flush only when the medium may be missing state: f_mount(NULL,*,0) drops
	 * the volume object without calling sync_fs, but a clean read-only volume
	 * must not issue any I/O here (detach closes after the endpoint is gone).
	 * An unconfirmed CTRL_SYNC keeps the gate closed and is retried here.
	 * Teardown below always runs; only the first error is kept (later errors
	 * cannot be retried once the drive is unbound). */
	if (vol_needs_flush(v)) {
		int sync_rc = (int)f_sync_volume(&v->fs);
		if (sync_rc != 0) (void)usb_block_sync(bh); /* sync_fs may have aborted before CTRL_SYNC */
		if (rc == 0) rc = sync_rc;
	}
	snprintf(path, sizeof(path), "%u:", (unsigned)v->pdrv);
	fr = f_mount(NULL, path, 0);
	if (rc == 0) rc = map_fr(fr);
	/* f_mount(NULL) destroyed the volume object; a still-open FIL cannot be
	 * retried, so forget it only after the mount is gone. */
	discard_write_session_for_volume_locked(volume);
	fat_diskio_unbind(v->pdrv);
	memset(v, 0, sizeof(*v));
	pthread_mutex_unlock(&g_vols_mu);
	pthread_mutex_unlock(&g_fat_mu);
	return rc;
}

int fat_engine_sync(uint64_t volume)
{
	FatVolume *v;
	uint64_t bh;
	int rc = 0;

	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_OBJECT;
	}
	rc = close_write_session_for_volume_locked(volume);
	bh = v->block_handle;
	/* No pending state -> no I/O. A read-only browse may have set the advisory
	 * FSInfo flag via f_getfree, and a fully synced write leaves the window
	 * clean; neither may write or fsync the medium here. barrier_pending forces
	 * the transport barrier when a previous CTRL_SYNC failed after the window
	 * and FSInfo flags were already cleared. On success the barrier is
	 * confirmed; on failure it stays pending for the next call. */
	if (!vol_needs_flush(v)) {
		pthread_mutex_unlock(&g_fat_mu);
		return rc;
	}
	{
		int sync_rc = (int)f_sync_volume(&v->fs);
		if (sync_rc != 0) {
			v->barrier_pending = 1;
			(void)usb_block_sync(bh); /* best effort when sync_fs aborted early */
			if (rc == 0) rc = sync_rc;
		} else {
			v->barrier_pending = 0;
		}
	}
	pthread_mutex_unlock(&g_fat_mu);
	return rc;
}

int fat_engine_usage(uint64_t volume, uint64_t *out_total_bytes, uint64_t *out_free_bytes)
{
	FatVolume *v;
	char path[8];
	FATFS *fs = NULL;
	DWORD free_clusters = 0;
	FRESULT fr;
	uint64_t clusters;
	uint64_t cluster_bytes;
	int rc;

	if (!out_total_bytes || !out_free_bytes) return (int)FR_INVALID_PARAMETER;

	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_OBJECT;
	}
	rc = close_write_session_for_volume_locked(volume);
	if (rc != 0) {
		pthread_mutex_unlock(&g_fat_mu);
		return rc;
	}
	if ((fr = (FRESULT)make_path(path, sizeof(path), v->pdrv, "/")) != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	/* f_getfree reads FAT/bitmap sectors and may flag the in-memory FSInfo for a
	 * later sync. Teardown only flushes an unfinished mutation (see
	 * vol_needs_flush), so a read-only browse still leaves the media untouched. */
	fr = f_getfree(path, &free_clusters, &fs);
	if (fr == FR_OK) {
		clusters = (uint64_t)(fs->n_fatent - 2);
		cluster_bytes = (uint64_t)fs->csize * v->sector_size;
		if (cluster_bytes == 0 || clusters > UINT64_MAX / cluster_bytes ||
		    (uint64_t)free_clusters > clusters) {
			fr = FR_INT_ERR;
		} else {
			*out_total_bytes = clusters * cluster_bytes;
			*out_free_bytes = (uint64_t)free_clusters * cluster_bytes;
		}
	}
	pthread_mutex_unlock(&g_fat_mu);
	return map_fr(fr);
}

int fat_engine_list(uint64_t volume, const char *path, FatDirEnt *out, size_t cap, size_t *out_count)
{
	FatVolume *v;
	DIR dir;
	FILINFO fno;
	char full[1024];
	FRESULT fr;
	size_t n = 0;
	int rc;

	if (!out_count) return (int)FR_INVALID_PARAMETER;
	*out_count = 0;

	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
	}
	rc = close_write_session_for_volume_locked(volume);
	if (rc != 0) {
		pthread_mutex_unlock(&g_fat_mu);
		return rc;
	}
	if ((fr = (FRESULT)make_path(full, sizeof(full), v->pdrv, path)) != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	fr = f_opendir(&dir, full);
	if (fr != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	for (;;) {
		fr = f_readdir(&dir, &fno);
		if (fr != FR_OK || fno.fname[0] == 0) break;
		if (n < cap && out) {
			memset(&out[n], 0, sizeof(out[n]));
#if FF_LFN_UNICODE == 2
			/* UTF-8 API */
			strncpy(out[n].name, (const char *)fno.fname, sizeof(out[n].name) - 1);
#else
			strncpy(out[n].name, (const char *)fno.fname, sizeof(out[n].name) - 1);
#endif
			out[n].is_dir = (fno.fattrib & AM_DIR) ? 1 : 0;
			out[n].size = (uint64_t)fno.fsize;
			out[n].attrib = fno.fattrib;
		}
		n++;
	}
	f_closedir(&dir);
	*out_count = n;
	pthread_mutex_unlock(&g_fat_mu);
	return map_fr(fr == FR_OK ? FR_OK : fr);
}

int fat_engine_stat(uint64_t volume, const char *path, FatStat *out)
{
	FatVolume *v;
	FILINFO fno;
	char full[1024];
	FRESULT fr;
	const char *p;
	int rc;

	if (!out || !path) return (int)FR_INVALID_PARAMETER;

	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
	}
	rc = close_write_session_for_volume_locked(volume);
	if (rc != 0) {
		pthread_mutex_unlock(&g_fat_mu);
		return rc;
	}

	/* FatFs f_stat rejects bare drive roots ("0:"); synthesize directory stat. */
	p = path;
	while (*p == '/') p++;
	if (*p == '\0') {
		memset(out, 0, sizeof(*out));
		out->name[0] = '/';
		out->name[1] = '\0';
		out->is_dir = 1;
		out->size = 0;
		out->attrib = AM_DIR;
		pthread_mutex_unlock(&g_fat_mu);
		return 0;
	}

	if ((fr = (FRESULT)make_path(full, sizeof(full), v->pdrv, path)) != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	fr = f_stat(full, &fno);
	if (fr != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	memset(out, 0, sizeof(*out));
	strncpy(out->name, (const char *)fno.fname, sizeof(out->name) - 1);
	out->is_dir = (fno.fattrib & AM_DIR) ? 1 : 0;
	out->size = (uint64_t)fno.fsize;
	out->attrib = fno.fattrib;
	pthread_mutex_unlock(&g_fat_mu);
	return 0;
}

int fat_engine_mkdir(uint64_t volume, const char *path)
{
	FatVolume *v;
	char full[1024];
	FRESULT fr;
	int rc;
	if (!path) return (int)FR_INVALID_PARAMETER;
	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
	}
	rc = close_write_session_for_volume_locked(volume);
	if (rc != 0) {
		pthread_mutex_unlock(&g_fat_mu);
		return rc;
	}
	if ((fr = (FRESULT)make_path(full, sizeof(full), v->pdrv, path)) != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	v->mutated = 1;
	fr = f_mkdir(full);
	vol_note_fr(v, fr);
	pthread_mutex_unlock(&g_fat_mu);
	return map_fr(fr);
}

int fat_engine_delete(uint64_t volume, const char *path)
{
	FatVolume *v;
	char full[1024];
	FRESULT fr;
	int rc;
	if (!path) return (int)FR_INVALID_PARAMETER;
	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
	}
	rc = close_write_session_for_volume_locked(volume);
	if (rc != 0) {
		pthread_mutex_unlock(&g_fat_mu);
		return rc;
	}
	if ((fr = (FRESULT)make_path(full, sizeof(full), v->pdrv, path)) != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	v->mutated = 1;
	fr = f_unlink(full);
	vol_note_fr(v, fr);
	pthread_mutex_unlock(&g_fat_mu);
	return map_fr(fr);
}

int fat_engine_rename(uint64_t volume, const char *from, const char *to)
{
	FatVolume *v;
	char a[1024], b[1024];
	FRESULT fr;
	int rc;
	if (!from || !to) return (int)FR_INVALID_PARAMETER;
	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
	}
	rc = close_write_session_for_volume_locked(volume);
	if (rc != 0) {
		pthread_mutex_unlock(&g_fat_mu);
		return rc;
	}
	if ((fr = (FRESULT)make_path(a, sizeof(a), v->pdrv, from)) != FR_OK ||
	    (fr = (FRESULT)make_path(b, sizeof(b), v->pdrv, to)) != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	v->mutated = 1;
	fr = f_rename(a, b);
	vol_note_fr(v, fr);
	pthread_mutex_unlock(&g_fat_mu);
	return map_fr(fr);
}

int fat_engine_read_file(uint64_t volume, const char *path, uint64_t offset,
			 void *buf, size_t len, size_t *out_read)
{
	FatVolume *v;
	FIL fil;
	char full[1024];
	FRESULT fr;
	UINT br = 0;
	int rc;

	if (!path || (!buf && len > 0) || !out_read) return (int)FR_INVALID_PARAMETER;
	*out_read = 0;

	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
	}
	rc = close_write_session_for_volume_locked(volume);
	if (rc != 0) {
		pthread_mutex_unlock(&g_fat_mu);
		return rc;
	}
	if ((fr = (FRESULT)make_path(full, sizeof(full), v->pdrv, path)) != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	fr = f_open(&fil, full, FA_READ);
	if (fr != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	if (offset > 0) {
		fr = f_lseek(&fil, (FSIZE_t)offset);
		if (fr != FR_OK) {
			f_close(&fil);
			pthread_mutex_unlock(&g_fat_mu);
			return map_fr(fr);
		}
	}
	if (len > 0) {
		fr = f_read(&fil, buf, (UINT)len, &br);
	}
	f_close(&fil);
	*out_read = (size_t)br;
	pthread_mutex_unlock(&g_fat_mu);
	return map_fr(fr);
}

int fat_engine_write_file(uint64_t volume, const char *path, uint64_t offset,
			  const void *buf, size_t len, size_t *out_written,
			  uint64_t planned_total_size, int create_mode)
{
	FatVolume *v;
	char full[1024];
	FRESULT fr;
	UINT bw = 0;
	BYTE mode;
	int exclusive_new = 0;

	if (!path || (!buf && len > 0) || !out_written) return (int)FR_INVALID_PARAMETER;
	*out_written = 0;

	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
	}

	/* Reject oversized files before creating destination. */
	if (planned_total_size > fat_engine_max_file_size(v->kind)) {
		pthread_mutex_unlock(&g_fat_mu);
		return FAT_ENGINE_FILE_TOO_LARGE;
	}

	if ((fr = (FRESULT)make_path(full, sizeof(full), v->pdrv, path)) != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	if (g_write.active &&
	    (g_write.volume != volume || strcmp(g_write.full, full) != 0 ||
	     g_write.offset != offset || create_mode != 0 || g_write.poisoned)) {
		uint64_t writer_volume = g_write.volume;
		FRESULT close_fr = close_write_session_locked();
		if (close_fr != FR_OK) {
			/* f_sync may have cleared FA_MODIFIED before CTRL_SYNC failed.
			 * Mark the volume whose FIL was closed, not the new target. */
			FatVolume *writer = vol_from(writer_volume);
			if (writer) vol_note_fr(writer, close_fr);
			pthread_mutex_unlock(&g_fat_mu);
			return map_fr(close_fr);
		}
	}
	if (!g_write.active && g_write.unlink_pending) {
		/* The pending path may belong to the previous volume; retry that one
		 * before replacing the global session with a new target. */
		FRESULT cleanup_fr = retry_pending_unlink_locked(g_write.volume);
		if (cleanup_fr != FR_OK) {
			pthread_mutex_unlock(&g_fat_mu);
			return map_fr(cleanup_fr);
		}
	}
	if (!g_write.active) {
		if (create_mode == 2) {
			mode = FA_WRITE | FA_CREATE_NEW;
			exclusive_new = 1;
		} else if (create_mode) {
			mode = FA_WRITE | FA_CREATE_ALWAYS;
		} else {
			mode = FA_WRITE | FA_OPEN_EXISTING;
		}
		v->mutated = 1;
		fr = f_open(&g_write.fil, full, mode);
		if (fr != FR_OK) {
			vol_note_fr(v, fr);
			pthread_mutex_unlock(&g_fat_mu);
			return map_fr(fr);
		}
		g_write.active = 1;
		g_write.volume = volume;
		g_write.offset = offset;
		g_write.exclusive_new = exclusive_new;
		g_write.poisoned = 0;
		g_write.unlink_pending = 0;
		memcpy(g_write.full, full, strlen(full) + 1);
		if (offset > 0) {
			fr = f_lseek(&g_write.fil, (FSIZE_t)offset);
			if (fr != FR_OK) {
				int rc = abort_write_session_locked(v, fr);
				pthread_mutex_unlock(&g_fat_mu);
				return rc;
			}
		}
	}
	if (len > 0) fr = f_write(&g_write.fil, buf, (UINT)len, &bw);
	if (fr != FR_OK || (len > 0 && bw < len)) {
		FRESULT failure = fr != FR_OK ? fr : FR_DISK_ERR;
		int rc = abort_write_session_locked(v, failure);
		*out_written = 0;
		pthread_mutex_unlock(&g_fat_mu);
		return rc;
	}
	g_write.offset = offset + (uint64_t)bw;
	*out_written = (size_t)bw;
	vol_note_fr(v, FR_OK);
	pthread_mutex_unlock(&g_fat_mu);
	return 0;
}

int fat_engine_mkfs(uint64_t block_handle, uint32_t sector_size, uint64_t sector_count,
		    FatMkfsType type, uint32_t au_size_bytes)
{
	FatVolume *v;
	MKFS_PARM opt;
	BYTE *work;
	char path[8];
	FRESULT fr;
	FRESULT close_fr;

	if (!block_handle || sector_size < 512 || sector_size > 4096 || sector_count == 0) {
		return (int)FR_INVALID_PARAMETER;
	}

	work = (BYTE *)malloc(FF_MAX_SS * 4);
	if (!work) {
		return (int)FR_NOT_ENOUGH_CORE;
	}

	pthread_mutex_lock(&g_fat_mu);
	close_fr = close_write_session_locked();
	if (close_fr != FR_OK) {
		FatVolume *writer = vol_from(g_write.volume);
		if (writer) vol_note_fr(writer, close_fr);
		pthread_mutex_unlock(&g_fat_mu);
		free(work);
		return map_fr(close_fr);
	}
	pthread_mutex_lock(&g_vols_mu);
	v = alloc_vol_locked();
	if (!v) {
		pthread_mutex_unlock(&g_vols_mu);
		pthread_mutex_unlock(&g_fat_mu);
		free(work);
		return (int)FR_TOO_MANY_OPEN_FILES;
	}

	v->block_handle = block_handle;
	v->sector_size = sector_size;
	v->sector_count = sector_count;
	fat_diskio_bind(v->pdrv, block_handle, sector_size, sector_count);

	memset(&opt, 0, sizeof(opt));
	/* VirtualPartition is already a partition: format as super-floppy in relative LBA space. */
	opt.fmt = (BYTE)((BYTE)type | FM_SFD);
	opt.n_fat = 1;
	opt.align = 0;
	opt.n_root = 0;
	opt.au_size = au_size_bytes;

	snprintf(path, sizeof(path), "%u:", (unsigned)v->pdrv);
	/* Mount work area without immediate mount so f_mkfs can bind the drive. */
	fr = f_mkfs(path, &opt, work, FF_MAX_SS * 4);

	fat_diskio_unbind(v->pdrv);
	memset(v, 0, sizeof(*v));
	pthread_mutex_unlock(&g_vols_mu);
	pthread_mutex_unlock(&g_fat_mu);
	free(work);
	return map_fr(fr);
}

int fat_engine_set_label(uint64_t block_handle, uint32_t sector_size, uint64_t sector_count,
			 const char *label)
{
	FatFsKind kind;
	uint64_t vol = 0;
	// f_setlabel takes "N:label" — passing only "N:" clears the label instead.
	char drive_label[8 + 64];
	FRESULT fr;

	if (!label) return (int)FR_INVALID_PARAMETER;
	int rc = fat_engine_mount(block_handle, sector_size, sector_count, &kind, &vol);
	if (rc != 0) return rc;

	FatVolume *v = vol_from(vol);
	if (!v) {
		fat_engine_umount(vol);
		return (int)FR_INVALID_PARAMETER;
	}
	// Truncation must fail, never drop the label silently.
	int n = snprintf(drive_label, sizeof(drive_label), "%u:%s", (unsigned)v->pdrv, label);
	if (n < 0 || (size_t)n >= sizeof(drive_label)) {
		fat_engine_umount(vol);
		return (int)FR_INVALID_NAME;
	}

	pthread_mutex_lock(&g_fat_mu);
	{
		FatVolume *lv = vol_from(vol);
		if (lv) lv->mutated = 1; /* f_setlabel writes the label directory entry */
	}
	fr = f_setlabel(drive_label);
	if (fr == FR_DISK_ERR) v->barrier_pending = 1;
	pthread_mutex_unlock(&g_fat_mu);

	int urc = fat_engine_umount(vol);
	if (fr == FR_OK) fr = (FRESULT)urc;
	return map_fr(fr);
}

int fat_engine_get_label(uint64_t block_handle, uint32_t sector_size, uint64_t sector_count,
			 char *out, size_t cap)
{
	FatFsKind kind;
	uint64_t vol = 0;
	char path[8];
	char label[64];
	FRESULT fr;

	if (!out || cap == 0) return (int)FR_INVALID_PARAMETER;
	out[0] = '\0';
	int rc = fat_engine_mount(block_handle, sector_size, sector_count, &kind, &vol);
	if (rc != 0) return rc;

	FatVolume *v = vol_from(vol);
	if (!v) {
		fat_engine_umount(vol);
		return (int)FR_INVALID_PARAMETER;
	}
	snprintf(path, sizeof(path), "%u:", (unsigned)v->pdrv);
	memset(label, 0, sizeof(label));

	pthread_mutex_lock(&g_fat_mu);
	fr = f_getlabel(path, label, NULL);
	pthread_mutex_unlock(&g_fat_mu);

	int urc = fat_engine_umount(vol);
	if (fr == FR_OK) {
		size_t n = strlen(label);
		if (n >= cap) n = cap - 1;
		memcpy(out, label, n);
		out[n] = '\0';
		fr = (FRESULT)urc;
	}
	return map_fr(fr);
}

FatFsKind fat_engine_probe(uint64_t block_handle, uint32_t sector_size, uint64_t sector_count)
{
	FatFsKind kind = FAT_KIND_UNKNOWN;
	uint64_t vol = 0;
	if (fat_engine_mount(block_handle, sector_size, sector_count, &kind, &vol) == 0) {
		fat_engine_umount(vol);
		return kind;
	}
	return FAT_KIND_UNKNOWN;
}
                            