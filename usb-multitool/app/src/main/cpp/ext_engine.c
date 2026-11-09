/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * EXT2/3/4 engine over usb_block using pinned lwext4.
 * Relative LBAs only. Dirty/unsupported features refuse instead of risky write.
 */
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Prefer abort-on-assert over silent while(1) hang on Android. */
#ifndef CONFIG_USE_DEFAULT_CFG
#define CONFIG_USE_DEFAULT_CFG 1
#endif
#ifndef CONFIG_DEBUG_ASSERT
#define CONFIG_DEBUG_ASSERT 0
#endif
#ifndef CONFIG_EXT4_MOUNTPOINTS_COUNT
#define CONFIG_EXT4_MOUNTPOINTS_COUNT 8
#endif

#include <stdbool.h>
#include <ext4.h>
#include <ext4_blockdev.h>
#include <ext4_errno.h>
#include <ext4_fs.h>
#include <ext4_mkfs.h>
#include <ext4_super.h>
#include <ext4_types.h>

#include "ext_engine.h"
#include "usb_block.h"

#define EXT_MP_MAX 8
#define EXT_NAME_MAX 32
#define EXT_PATH_MAX 768

typedef struct ExtVolumeContext {
	uint64_t block_handle;
	UsbBlockOps *ops;
	struct ext4_blockdev bdev;
	struct ext4_blockdev_iface iface;
	uint8_t *ph_bbuf;
	char dev_name[EXT_NAME_MAX];
	char mount_point[EXT_NAME_MAX];
	bool read_only;
	bool mounted;
	bool registered;
	/* Set by any failed block transfer. lwext4's directory iterator reports
	 * an I/O failure as end-of-directory, so list() must check this to avoid
	 * returning a short listing as if it were complete. */
	bool io_error;
} ExtVolumeContext;

static pthread_mutex_t g_ext_mu = PTHREAD_MUTEX_INITIALIZER;
static ExtVolumeContext *g_slots[EXT_MP_MAX];

static int map_lw(int rc)
{
	if (rc == EOK) return 0;
	if (rc < 0) return rc;
	return -rc;
}

static int bdev_open(struct ext4_blockdev *bdev)
{
	(void)bdev;
	return EOK;
}

static int bdev_close(struct ext4_blockdev *bdev)
{
	(void)bdev;
	return EOK;
}

static int bdev_lock(struct ext4_blockdev *bdev)
{
	(void)bdev;
	return EOK;
}

static int bdev_unlock(struct ext4_blockdev *bdev)
{
	(void)bdev;
	return EOK;
}

static int bdev_bread(struct ext4_blockdev *bdev, void *buf, uint64_t blk_id,
		      uint32_t blk_cnt)
{
	ExtVolumeContext *v = (ExtVolumeContext *)bdev->bdif->p_user;
	if (!v) return EIO;
	if (usb_block_read(v->block_handle, blk_id, buf, blk_cnt) != 0) {
		v->io_error = true;
		return EIO;
	}
	return EOK;
}

static int bdev_bwrite(struct ext4_blockdev *bdev, const void *buf,
		       uint64_t blk_id, uint32_t blk_cnt)
{
	ExtVolumeContext *v = (ExtVolumeContext *)bdev->bdif->p_user;
	if (!v) return EIO;
	if (v->read_only) return EROFS;
	if (usb_block_write(v->block_handle, blk_id, buf, blk_cnt) != 0) {
		v->io_error = true;
		return EIO;
	}
	return EOK;
}

static void free_volume(ExtVolumeContext *v)
{
	if (!v) return;
	free(v->ph_bbuf);
	free(v);
}

static ExtVolumeContext *alloc_volume(uint64_t block_handle, bool read_only)
{
	UsbBlockOps *ops = usb_block_ops(block_handle);
	if (!ops || ops->sector_size < 512 || ops->sector_count == 0) return NULL;

	ExtVolumeContext *v = calloc(1, sizeof(*v));
	if (!v) return NULL;
	v->ph_bbuf = calloc(1, ops->sector_size);
	if (!v->ph_bbuf) {
		free(v);
		return NULL;
	}
	v->block_handle = block_handle;
	v->ops = ops;
	v->read_only = read_only;

	v->iface.open = bdev_open;
	v->iface.bread = bdev_bread;
	v->iface.bwrite = bdev_bwrite;
	v->iface.close = bdev_close;
	v->iface.lock = bdev_lock;
	v->iface.unlock = bdev_unlock;
	v->iface.ph_bsize = ops->sector_size;
	v->iface.ph_bcnt = ops->sector_count;
	v->iface.ph_bbuf = v->ph_bbuf;
	v->iface.p_user = v;

	v->bdev.bdif = &v->iface;
	v->bdev.part_offset = 0;
	v->bdev.part_size = (uint64_t)ops->sector_size * ops->sector_count;
	return v;
}

static int take_slot(ExtVolumeContext *v)
{
	for (int i = 0; i < EXT_MP_MAX; i++) {
		if (g_slots[i] == NULL) {
			g_slots[i] = v;
			snprintf(v->dev_name, sizeof(v->dev_name), "usbext%d", i);
			/* lwext4 requires a trailing slash on mount_point (else ENOTSUP). */
			snprintf(v->mount_point, sizeof(v->mount_point), "/mp%d/", i);
			return 0;
		}
	}
	return -ENOMEM;
}

static void drop_slot(ExtVolumeContext *v)
{
	for (int i = 0; i < EXT_MP_MAX; i++) {
		if (g_slots[i] == v) {
			g_slots[i] = NULL;
			return;
		}
	}
}

static ExtVolumeContext *from_handle(uint64_t h)
{
	return (ExtVolumeContext *)(uintptr_t)h;
}

/* Build mount_point + user path into out. User path may be "/" or "/foo". */
static int join_path(char *out, size_t out_sz, const ExtVolumeContext *v,
		     const char *path)
{
	const char *p = path ? path : "/";
	while (*p == '/') p++;
	int n;
	if (*p == '\0') {
		n = snprintf(out, out_sz, "%s", v->mount_point);
	} else {
		/* mount_point already ends with '/' */
		n = snprintf(out, out_sz, "%s%s", v->mount_point, p);
	}
	if (n < 0 || (size_t)n >= out_sz) return -ENAMETOOLONG;
	return 0;
}

/* Read on-disk superblock BEFORE mount. Never call after ext4_mount:
 * rw mount mutates s_state to ERROR_FS in memory and on disk. */
static int refuse_if_dirty_or_unsupported_sb(struct ext4_sblock *sb)
{
	if (!sb || !ext4_sb_check(sb)) return -ENOTSUP;

	uint32_t incom = ext4_get32(sb, features_incompatible);
	/* lwext4 ignores RECOVER in feature checks — we must not. */
	if (incom & EXT4_FINCOM_RECOVER) return -EBUSY;

	uint16_t state = ext4_get16(sb, state);
	/* On-disk ERROR_FS / ORPHAN means prior unclean session (journal-less
	 * crash, or orphan recovery pending). Refuse — do not auto-recover. */
	if (state & (EXT4_SUPERBLOCK_STATE_ERROR_FS |
		     EXT4_SUPERBLOCK_STATE_ORPHAN_FS)) {
		return -EBUSY;
	}

	uint32_t unsupported =
		incom & ~(CONFIG_SUPPORTED_FINCOM | EXT_FINCOM_IGNORED);
	if (unsupported) return -ENOTSUP;

	/* Fail-closed on unsupported ro_compat: lwext4 would silently force
	 * read-only; that looks like a successful mount while writes EROFS. */
	uint32_t rocom = ext4_get32(sb, features_read_only);
	uint32_t unsupported_ro = rocom & ~CONFIG_SUPPORTED_FRO_COM;
	if (unsupported_ro) return -ENOTSUP;
	return 0;
}

static int precheck_superblock(ExtVolumeContext *v)
{
	struct ext4_sblock sb;
	memset(&sb, 0, sizeof(sb));
	/* ext4_sb_read needs ph_refctr > 0 (ext4_block_init). Fini after so
	 * a rejected mount never leaves the bdev open; ext4_mount re-inits. */
	int rc = ext4_block_init(&v->bdev);
	if (rc != EOK) return map_lw(rc);
	rc = ext4_sb_read(&v->bdev, &sb);
	(void)ext4_block_fini(&v->bdev);
	if (rc != EOK) return map_lw(rc);
	return refuse_if_dirty_or_unsupported_sb(&sb);
}

uint64_t ext_engine_mount(uint64_t block_handle, bool read_only, int *out_errno)
{
	if (out_errno) *out_errno = 0;
	ExtVolumeContext *v = alloc_volume(block_handle, read_only);
	if (!v) {
		if (out_errno) *out_errno = EINVAL;
		return 0;
	}

	pthread_mutex_lock(&g_ext_mu);
	int rc = take_slot(v);
	if (rc != 0) {
		pthread_mutex_unlock(&g_ext_mu);
		free_volume(v);
		if (out_errno) *out_errno = -rc;
		return 0;
	}

	rc = ext4_device_register(&v->bdev, v->dev_name);
	if (rc != EOK) {
		drop_slot(v);
		pthread_mutex_unlock(&g_ext_mu);
		free_volume(v);
		if (out_errno) *out_errno = rc > 0 ? rc : -rc;
		return 0;
	}
	v->registered = true;

	/* Pre-mount dirty/feature check — no sb write on reject. */
	rc = precheck_superblock(v);
	if (rc != 0) {
		ext4_device_unregister(v->dev_name);
		v->registered = false;
		drop_slot(v);
		pthread_mutex_unlock(&g_ext_mu);
		free_volume(v);
		if (out_errno) *out_errno = -rc;
		return 0;
	}

	rc = ext4_mount(v->dev_name, v->mount_point, read_only);
	if (rc != EOK) {
		ext4_device_unregister(v->dev_name);
		v->registered = false;
		drop_slot(v);
		pthread_mutex_unlock(&g_ext_mu);
		free_volume(v);
		if (out_errno) *out_errno = rc > 0 ? rc : -rc;
		return 0;
	}
	v->mounted = true;

	/* Journal start only when clean; never call recover. Do not fail the
	 * mount on a journal-start error: ext4_umount() can itself fail, which
	 * would leave lwext4's mountpoint pointing at this context after we
	 * free it. Upstream's best-effort start is kept here until a teardown
	 * that cannot leave a live lwext4 reference exists. */
	if (!read_only) {
		(void)ext4_journal_start(v->mount_point);
	}

	pthread_mutex_unlock(&g_ext_mu);
	return (uint64_t)(uintptr_t)v;
}

int ext_engine_close(uint64_t vol_handle)
{
	ExtVolumeContext *v = from_handle(vol_handle);
	if (!v) return -EINVAL;

	int first_err = 0;
	pthread_mutex_lock(&g_ext_mu);
	if (v->mounted) {
		if (!v->read_only) {
			int rc = ext4_cache_flush(v->mount_point);
			if (rc != EOK && first_err == 0) first_err = map_lw(rc);
			rc = ext4_journal_stop(v->mount_point);
			if (rc != EOK && first_err == 0) first_err = map_lw(rc);
		}
		int rc = ext4_umount(v->mount_point);
		if (rc != EOK && first_err == 0) first_err = map_lw(rc);
		v->mounted = false;
	}
	/* Push umount sb write (VALID_FS) through to media. */
	if (usb_block_sync(v->block_handle) != 0 && first_err == 0) {
		first_err = -EIO;
	}
	if (v->registered) {
		(void)ext4_device_unregister(v->dev_name);
		v->registered = false;
	}
	drop_slot(v);
	pthread_mutex_unlock(&g_ext_mu);
	free_volume(v);
	return first_err;
}

int ext_engine_sync(uint64_t vol_handle)
{
	ExtVolumeContext *v = from_handle(vol_handle);
	if (!v || !v->mounted) return -EINVAL;
	int rc = ext4_cache_flush(v->mount_point);
	if (rc != EOK) return map_lw(rc);
	if (usb_block_sync(v->block_handle) != 0) return -EIO;
	return 0;
}

int ext_engine_list(uint64_t vol_handle, const char *path,
		    ExtDirEntry **out_entries, size_t *out_count)
{
	ExtVolumeContext *v = from_handle(vol_handle);
	if (!v || !v->mounted || !out_entries || !out_count) return -EINVAL;
	*out_entries = NULL;
	*out_count = 0;

	char full[EXT_PATH_MAX];
	int j = join_path(full, sizeof(full), v, path);
	if (j != 0) return j;

	v->io_error = false;
	ext4_dir dir;
	int rc = ext4_dir_open(&dir, full);
	if (rc != EOK) return map_lw(rc);

	ExtDirEntry *arr = NULL;
	size_t cap = 0, n = 0;
	const ext4_direntry *de;
	while ((de = ext4_dir_entry_next(&dir)) != NULL) {
		if (de->name_length == 0) continue;
		if (de->name[0] == '.' &&
		    (de->name_length == 1 ||
		     (de->name_length == 2 && de->name[1] == '.'))) {
			continue;
		}
		if (n >= EXT_LIST_MAX_ENTRIES) {
			free(arr);
			ext4_dir_close(&dir);
			return -EOVERFLOW;
		}
		if (n == cap) {
			size_t ncap = cap ? cap * 2 : 16;
			ExtDirEntry *na = realloc(arr, ncap * sizeof(*na));
			if (!na) {
				free(arr);
				ext4_dir_close(&dir);
				return -ENOMEM;
			}
			arr = na;
			cap = ncap;
		}
		size_t copy = de->name_length;
		if (copy >= EXT_DIR_ENTRY_NAME_MAX) copy = EXT_DIR_ENTRY_NAME_MAX - 1;
		memcpy(arr[n].name, de->name, copy);
		arr[n].name[copy] = '\0';
		arr[n].is_directory = (de->inode_type == EXT4_DE_DIR);
		arr[n].size = 0;
		if (!arr[n].is_directory) {
			char child[EXT_PATH_MAX];
			/* full may already end with '/' (root = mount_point). */
			size_t flen = strlen(full);
			int cn;
			if (flen > 0 && full[flen - 1] == '/') {
				cn = snprintf(child, sizeof(child), "%s%s", full,
					      arr[n].name);
			} else {
				cn = snprintf(child, sizeof(child), "%s/%s", full,
					      arr[n].name);
			}
			if (cn > 0 && (size_t)cn < sizeof(child)) {
				ext4_file f;
				if (ext4_fopen(&f, child, "r") == EOK) {
					arr[n].size = ext4_fsize(&f);
					ext4_fclose(&f);
				}
			}
		}
		n++;
	}
	ext4_dir_close(&dir);
	/* ext4_dir_entry_next() returns NULL on an I/O error too: a short
	 * listing must never masquerade as a complete one. */
	if (v->io_error) {
		free(arr);
		return -EIO;
	}
	*out_entries = arr;
	*out_count = n;
	return 0;
}

int ext_engine_stat(uint64_t vol_handle, const char *path, ExtStat *out_stat)
{
	ExtVolumeContext *v = from_handle(vol_handle);
	if (!v || !v->mounted || !path || !out_stat) return -EINVAL;
	memset(out_stat, 0, sizeof(*out_stat));

	char full[EXT_PATH_MAX];
	int j = join_path(full, sizeof(full), v, path);
	if (j != 0) return j;

	ext4_file f;
	int rc = ext4_fopen(&f, full, "r");
	if (rc == EOK) {
		out_stat->is_directory = false;
		out_stat->size = ext4_fsize(&f);
		ext4_fclose(&f);
		return 0;
	}
	ext4_dir d;
	rc = ext4_dir_open(&d, full);
	if (rc == EOK) {
		out_stat->is_directory = true;
		out_stat->size = 0;
		ext4_dir_close(&d);
		return 0;
	}
	return map_lw(rc);
}

int ext_engine_read(uint64_t vol_handle, const char *path, uint64_t offset,
		    void *buf, size_t count, size_t *out_read)
{
	ExtVolumeContext *v = from_handle(vol_handle);
	if (!v || !v->mounted || !path || !buf || !out_read) return -EINVAL;
	*out_read = 0;

	char full[EXT_PATH_MAX];
	int j = join_path(full, sizeof(full), v, path);
	if (j != 0) return j;

	ext4_file f;
	int rc = ext4_fopen(&f, full, "rb");
	if (rc != EOK) return map_lw(rc);
	rc = ext4_fseek(&f, (int64_t)offset, SEEK_SET);
	if (rc != EOK) {
		ext4_fclose(&f);
		return map_lw(rc);
	}
	size_t got = 0;
	rc = ext4_fread(&f, buf, count, &got);
	ext4_fclose(&f);
	if (rc != EOK) return map_lw(rc);
	*out_read = got;
	return 0;
}

int ext_engine_write(uint64_t vol_handle, const char *path, uint64_t offset,
		     const void *buf, size_t count, bool create,
		     size_t *out_written)
{
	ExtVolumeContext *v = from_handle(vol_handle);
	if (!v || !v->mounted || !path || !buf || !out_written) return -EINVAL;
	*out_written = 0;
	if (v->read_only) return -EROFS;

	char full[EXT_PATH_MAX];
	int j = join_path(full, sizeof(full), v, path);
	if (j != 0) return j;

	/* create+offset0 → truncate ("w+b"); else open existing for r/w. */
	const char *flags;
	if (create && offset == 0) {
		flags = "w+b";
	} else if (create) {
		flags = "r+b";
	} else {
		flags = "r+b";
	}

	ext4_file f;
	int rc = ext4_fopen(&f, full, flags);
	if (rc != EOK && create && offset != 0) {
		/* Missing file with append: create then seek. */
		rc = ext4_fopen(&f, full, "w+b");
	}
	if (rc != EOK) return map_lw(rc);

	if (offset != 0) {
		rc = ext4_fseek(&f, (int64_t)offset, SEEK_SET);
		if (rc != EOK) {
			ext4_fclose(&f);
			return map_lw(rc);
		}
	}
	size_t wrote = 0;
	rc = ext4_fwrite(&f, buf, count, &wrote);
	ext4_fclose(&f);
	if (rc != EOK) return map_lw(rc);
	*out_written = wrote;
	return 0;
}

int ext_engine_mkdir(uint64_t vol_handle, const char *path)
{
	ExtVolumeContext *v = from_handle(vol_handle);
	if (!v || !v->mounted || !path) return -EINVAL;
	if (v->read_only) return -EROFS;
	char full[EXT_PATH_MAX];
	int j = join_path(full, sizeof(full), v, path);
	if (j != 0) return j;
	return map_lw(ext4_dir_mk(full));
}

int ext_engine_delete(uint64_t vol_handle, const char *path)
{
	ExtVolumeContext *v = from_handle(vol_handle);
	if (!v || !v->mounted || !path) return -EINVAL;
	if (v->read_only) return -EROFS;
	char full[EXT_PATH_MAX];
	int j = join_path(full, sizeof(full), v, path);
	if (j != 0) return j;

	ext4_dir d;
	if (ext4_dir_open(&d, full) == EOK) {
		/* Match FAT: non-empty directory → ENOTEMPTY, never recurse. */
		const ext4_direntry *de;
		while ((de = ext4_dir_entry_next(&d)) != NULL) {
			if (de->name[0] == '.' &&
			    (de->name_length == 1 ||
			     (de->name_length == 2 && de->name[1] == '.'))) {
				continue;
			}
			ext4_dir_close(&d);
			return -ENOTEMPTY;
		}
		ext4_dir_close(&d);
		return map_lw(ext4_dir_rm(full));
	}
	return map_lw(ext4_fremove(full));
}

int ext_engine_rename(uint64_t vol_handle, const char *from, const char *to)
{
	ExtVolumeContext *v = from_handle(vol_handle);
	if (!v || !v->mounted || !from || !to) return -EINVAL;
	if (v->read_only) return -EROFS;
	char a[EXT_PATH_MAX], b[EXT_PATH_MAX];
	int j = join_path(a, sizeof(a), v, from);
	if (j != 0) return j;
	j = join_path(b, sizeof(b), v, to);
	if (j != 0) return j;
	int rc = ext4_frename(a, b);
	if (rc != EOK) rc = ext4_dir_mv(a, b);
	return map_lw(rc);
}

int ext_engine_mkfs(uint64_t block_handle)
{
	ExtVolumeContext *v = alloc_volume(block_handle, false);
	if (!v) return -EINVAL;

	pthread_mutex_lock(&g_ext_mu);
	int rc = take_slot(v);
	if (rc != 0) {
		pthread_mutex_unlock(&g_ext_mu);
		free_volume(v);
		return rc;
	}
	rc = ext4_device_register(&v->bdev, v->dev_name);
	if (rc != EOK) {
		drop_slot(v);
		pthread_mutex_unlock(&g_ext_mu);
		free_volume(v);
		return map_lw(rc);
	}
	v->registered = true;

	struct ext4_fs fs;
	memset(&fs, 0, sizeof(fs));
	struct ext4_mkfs_info info;
	memset(&info, 0, sizeof(info));
	info.len = v->bdev.part_size;
	info.block_size = v->ops->sector_size >= 1024 ? v->ops->sector_size : 1024;
	info.journal = false; /* no journal → no FINCOM_RECOVER under lwext4 */
	info.label = "usbext";

	rc = ext4_mkfs(&fs, &v->bdev, &info, F_SET_EXT4);
	ext4_device_unregister(v->dev_name);
	v->registered = false;
	drop_slot(v);
	pthread_mutex_unlock(&g_ext_mu);
	free_volume(v);
	if (rc != EOK) return map_lw(rc);
	/* Formatting is not complete until the metadata reaches the device. */
	if (usb_block_sync(block_handle) != 0) return -EIO;
	return 0;
}

int ext_engine_probe(uint64_t block_handle)
{
	UsbBlockOps *ops = usb_block_ops(block_handle);
	if (!ops || ops->sector_size < 512) return -EINVAL;
	if (ops->sector_count * ops->sector_size < 2048) return 0;

	uint8_t sector[4096];
	if (ops->sector_size > sizeof(sector)) return -EINVAL;
	/* Superblock at byte 1024. */
	uint64_t lba = 1024 / ops->sector_size;
	uint32_t off = 1024 % ops->sector_size;
	if (usb_block_read(block_handle, lba, sector, 1) != 0) return -EIO;
	if (off + 2 > ops->sector_size) {
		/* Rare: need next sector for magic. */
		uint8_t next[4096];
		if (usb_block_read(block_handle, lba + 1, next, 1) != 0) return -EIO;
		uint16_t magic = (uint16_t)next[0] | ((uint16_t)next[1] << 8);
		return magic == 0xEF53 ? 1 : 0;
	}
	uint16_t magic = (uint16_t)sector[off + 0x38] | ((uint16_t)sector[off + 0x39] << 8);
	return magic == 0xEF53 ? 1 : 0;
}
