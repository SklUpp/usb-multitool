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
} FatVolume;

static FatVolume g_vols[FF_VOLUMES];
static BYTE g_next_pdrv;
static pthread_mutex_t g_vols_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_fat_mu = PTHREAD_MUTEX_INITIALIZER;

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

	if (!v) return (int)FR_INVALID_OBJECT;
	pthread_mutex_lock(&g_fat_mu);
	pthread_mutex_lock(&g_vols_mu);
	snprintf(path, sizeof(path), "%u:", (unsigned)v->pdrv);
	fr = f_mount(NULL, path, 0);
	fat_diskio_unbind(v->pdrv);
	memset(v, 0, sizeof(*v));
	pthread_mutex_unlock(&g_vols_mu);
	pthread_mutex_unlock(&g_fat_mu);
	return map_fr(fr);
}

int fat_engine_sync(uint64_t volume)
{
	pthread_mutex_lock(&g_fat_mu);
	FatVolume *v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_OBJECT;
	}
	uint64_t bh = v->block_handle;
	pthread_mutex_unlock(&g_fat_mu);
	return usb_block_sync(bh) == 0 ? 0 : (int)FR_DISK_ERR;
}

int fat_engine_list(uint64_t volume, const char *path, FatDirEnt *out, size_t cap, size_t *out_count)
{
	FatVolume *v;
	DIR dir;
	FILINFO fno;
	char full[512];
	FRESULT fr;
	size_t n = 0;

	if (!out_count) return (int)FR_INVALID_PARAMETER;
	*out_count = 0;

	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
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
	char full[512];
	FRESULT fr;
	const char *p;

	if (!out || !path) return (int)FR_INVALID_PARAMETER;

	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
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
	char full[512];
	FRESULT fr;
	if (!path) return (int)FR_INVALID_PARAMETER;
	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
	}
	if ((fr = (FRESULT)make_path(full, sizeof(full), v->pdrv, path)) != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	fr = f_mkdir(full);
	pthread_mutex_unlock(&g_fat_mu);
	return map_fr(fr);
}

int fat_engine_delete(uint64_t volume, const char *path)
{
	FatVolume *v;
	char full[512];
	FRESULT fr;
	if (!path) return (int)FR_INVALID_PARAMETER;
	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
	}
	if ((fr = (FRESULT)make_path(full, sizeof(full), v->pdrv, path)) != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	fr = f_unlink(full);
	pthread_mutex_unlock(&g_fat_mu);
	return map_fr(fr);
}

int fat_engine_rename(uint64_t volume, const char *from, const char *to)
{
	FatVolume *v;
	char a[512], b[512];
	FRESULT fr;
	if (!from || !to) return (int)FR_INVALID_PARAMETER;
	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
	}
	if ((fr = (FRESULT)make_path(a, sizeof(a), v->pdrv, from)) != FR_OK ||
	    (fr = (FRESULT)make_path(b, sizeof(b), v->pdrv, to)) != FR_OK) {
		pthread_mutex_unlock(&g_fat_mu);
		return map_fr(fr);
	}
	fr = f_rename(a, b);
	pthread_mutex_unlock(&g_fat_mu);
	return map_fr(fr);
}

int fat_engine_read_file(uint64_t volume, const char *path, uint64_t offset,
			 void *buf, size_t len, size_t *out_read)
{
	FatVolume *v;
	FIL fil;
	char full[512];
	FRESULT fr;
	UINT br = 0;

	if (!path || (!buf && len > 0) || !out_read) return (int)FR_INVALID_PARAMETER;
	*out_read = 0;

	pthread_mutex_lock(&g_fat_mu);
	v = vol_from(volume);
	if (!v) {
		pthread_mutex_unlock(&g_fat_mu);
		return (int)FR_INVALID_PARAMETER;
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
			  uint64_t planned_total_size, int create)
{
	FatVolume *v;
	FIL fil;
	char full[512];
	FRESULT fr;
	UINT bw = 0;
	BYTE mode;

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
	if (create) {
		mode = FA_WRITE | FA_CREATE_ALWAYS;
	} else {
		mode = FA_WRITE | FA_OPEN_EXISTING;
	}
	fr = f_open(&fil, full, mode);
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
		fr = f_write(&fil, buf, (UINT)len, &bw);
	}
	if (fr == FR_OK) fr = f_sync(&fil);
	f_close(&fil);
	*out_written = (size_t)bw;
	pthread_mutex_unlock(&g_fat_mu);
	if (fr == FR_OK && bw < len) {
		/* Disk full / truncated write: surface as disk error so callers do not
		 * treat partial success as complete. */
		return (int)FR_DISK_ERR;
	}
	return map_fr(fr);
}

int fat_engine_mkfs(uint64_t block_handle, uint32_t sector_size, uint64_t sector_count,
		    FatMkfsType type)
{
	FatVolume *v;
	MKFS_PARM opt;
	BYTE *work;
	char path[8];
	FRESULT fr;

	if (!block_handle || sector_size < 512 || sector_size > 4096 || sector_count == 0) {
		return (int)FR_INVALID_PARAMETER;
	}

	work = (BYTE *)malloc(FF_MAX_SS * 4);
	if (!work) {
		return (int)FR_NOT_ENOUGH_CORE;
	}

	pthread_mutex_lock(&g_fat_mu);
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
	opt.au_size = 0;

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
