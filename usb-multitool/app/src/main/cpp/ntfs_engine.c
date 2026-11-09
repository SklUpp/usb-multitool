/* SPDX-License-Identifier: GPL-2.0-or-later */
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <pthread.h>
#include <time.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define HAVE_CONFIG_H 1
#include "config.h"

#ifndef __timespec_defined
#define __timespec_defined 1
#endif

#include "ntfs_engine.h"
#include "usb_block.h"
#include "ntfs-3g/types.h"
#include "ntfs-3g/layout.h"
#include "ntfs-3g/device.h"
#include "ntfs-3g/device_io.h"
#include "ntfs-3g/volume.h"
#include "ntfs-3g/logging.h"
#include "ntfs-3g/dir.h"
#include "ntfs-3g/attrib.h"
#include "ntfs-3g/inode.h"
#include "ntfs-3g/unistr.h"
#define NTFS_CLEAR_DIRTY_CONFIRMATION "CONFIRM_NTFS_CLEAR_DIRTY"
#ifndef EUCLEAN
#define EUCLEAN 117
#endif

/* mkntfs asks the device for its geometry through these ioctls. Define the
 * Linux numbers when <linux/fs.h> was not pulled in by the surrounding build. */
#ifndef BLKGETSIZE64
#define BLKGETSIZE64 _IOR(0x12, 114, size_t)
#endif
#ifndef BLKSSZGET
#define BLKSSZGET _IO(0x12, 104)
#endif

/* mkntfs keeps formatter state in file-static globals: one at a time. */
static pthread_mutex_t g_ntfs_mkfs_mu = PTHREAD_MUTEX_INITIALIZER;

/* Provided by third_party/ntfs-3g/ntfsprogs/mkntfs.c under MKNTFS_LIBRARY.
 * Takes ownership of the device and frees it. */
extern int mkntfs_format_device(struct ntfs_device *dev, const char *label, int quick);

/* System metadata parents ($Extend, $MFT, …) refuse create/link; FILE_root allowed. */
static int refuse_system_parent(ntfs_inode *dir_ni)
{
	if (!dir_ni) return -EINVAL;
	if (dir_ni->mft_no != FILE_root && dir_ni->mft_no < FILE_first_user)
		return -EPERM;
	return 0;
}

#ifdef NTFS_TEST_HOOKS
static int g_ntfs_test_fail_rename_unlink;
static int g_ntfs_test_fail_still_from_lookup;

void ntfs_test_set_fail_rename_unlink(int enabled)
{
	g_ntfs_test_fail_rename_unlink = enabled ? 1 : 0;
}

void ntfs_test_set_fail_still_from_lookup(int enabled)
{
	g_ntfs_test_fail_still_from_lookup = enabled ? 1 : 0;
}
#endif


typedef struct NtfsDeviceContext {
    uint64_t block_handle;
    uint32_t sector_size;
    uint64_t sector_count;
    s64 pos;
} NtfsDeviceContext;

typedef struct NtfsVolumeContext {
    ntfs_volume *vol;
    struct ntfs_device *dev;
    NtfsDeviceContext dev_ctx;
    uint64_t block_handle;
} NtfsVolumeContext;

/* Low-level device operations translating byte pread/pwrite to usb_block sectors */

static int dev_op_open(struct ntfs_device *dev, int flags)
{
    (void)flags;
    /* ntfs_device_free() refuses to free an "open" device, so the flag must
     * be tracked here: no descriptor exists to manage it for us. */
    NDevSetOpen(dev);
    return 0;
}

static int dev_op_close(struct ntfs_device *dev)
{
    NDevClearOpen(dev);
    return 0;
}

static s64 dev_op_seek(struct ntfs_device *dev, s64 offset, int whence)
{
    NtfsDeviceContext *ctx = (NtfsDeviceContext *)dev->d_private;
    s64 total = (s64)(ctx->sector_count * ctx->sector_size);
    s64 np = ctx->pos;

    if (whence == SEEK_SET) {
        np = offset;
    } else if (whence == SEEK_CUR) {
        np += offset;
    } else if (whence == SEEK_END) {
        np = total + offset;
    } else {
        errno = EINVAL;
        return -1;
    }

    if (np < 0 || (total > 0 && np > total)) {
        errno = EINVAL;
        return -1;
    }
    ctx->pos = np;
    return np;
}

static s64 dev_op_pread(struct ntfs_device *dev, void *buf, s64 count, s64 offset)
{
    NtfsDeviceContext *ctx = (NtfsDeviceContext *)dev->d_private;
    if (count < 0 || offset < 0) {
        errno = EINVAL;
        return -1;
    }
    if (count == 0) return 0;

    uint64_t total = ctx->sector_count * ctx->sector_size;
    if (ctx->sector_count > 0 && (uint64_t)offset >= total) {
        return 0; /* EOF */
    }
    if (ctx->sector_count > 0 && (uint64_t)(offset + count) > total) {
        count = (s64)(total - (uint64_t)offset);
    }

    uint32_t ss = ctx->sector_size;
    uint64_t first_lba = (uint64_t)offset / ss;
    uint32_t first_offset = (uint32_t)((uint64_t)offset % ss);
    uint64_t last_byte = (uint64_t)offset + (uint64_t)count - 1;
    uint64_t last_lba = last_byte / ss;
    uint8_t bounce[4096];

    if (ss > sizeof(bounce)) {
        errno = ENOMEM;
        return -1;
    }

    if (first_lba == last_lba) {
        if (first_offset == 0 && (uint32_t)count == ss) {
            if (usb_block_read(ctx->block_handle, first_lba, buf, 1) != 0) {
                errno = EIO;
                return -1;
            }
        } else {
            if (usb_block_read(ctx->block_handle, first_lba, bounce, 1) != 0) {
                errno = EIO;
                return -1;
            }
            memcpy(buf, bounce + first_offset, (size_t)count);
        }
        return count;
    }

    uint8_t *cur_buf = (uint8_t *)buf;
    uint64_t cur_lba = first_lba;

    if (first_offset != 0) {
        uint32_t head_bytes = ss - first_offset;
        if (usb_block_read(ctx->block_handle, first_lba, bounce, 1) != 0) {
            errno = EIO;
            return -1;
        }
        memcpy(cur_buf, bounce + first_offset, head_bytes);
        cur_buf += head_bytes;
        cur_lba++;
    }

    uint32_t tail_bytes = (uint32_t)(((uint64_t)offset + (uint64_t)count) % ss);
    uint64_t mid_sectors = (tail_bytes == 0) ? (last_lba - cur_lba + 1) : (last_lba - cur_lba);

    if (mid_sectors > 0) {
        if (usb_block_read(ctx->block_handle, cur_lba, cur_buf, (uint32_t)mid_sectors) != 0) {
            errno = EIO;
            return -1;
        }
        cur_buf += mid_sectors * ss;
        cur_lba += mid_sectors;
    }

    if (tail_bytes != 0) {
        if (usb_block_read(ctx->block_handle, last_lba, bounce, 1) != 0) {
            errno = EIO;
            return -1;
        }
        memcpy(cur_buf, bounce, tail_bytes);
    }

    return count;
}

static s64 dev_op_pwrite(struct ntfs_device *dev, const void *buf, s64 count, s64 offset)
{
    NtfsDeviceContext *ctx = (NtfsDeviceContext *)dev->d_private;
    if (count < 0 || offset < 0) {
        errno = EINVAL;
        return -1;
    }
    if (count == 0) return 0;

    uint64_t total = ctx->sector_count * ctx->sector_size;
    if (ctx->sector_count > 0 && (uint64_t)(offset + count) > total) {
        errno = ENOSPC;
        return -1;
    }

    uint32_t ss = ctx->sector_size;
    uint64_t first_lba = (uint64_t)offset / ss;
    uint32_t first_offset = (uint32_t)((uint64_t)offset % ss);
    uint64_t last_byte = (uint64_t)offset + (uint64_t)count - 1;
    uint64_t last_lba = last_byte / ss;
    uint8_t bounce[4096];

    if (ss > sizeof(bounce)) {
        errno = ENOMEM;
        return -1;
    }

    if (first_lba == last_lba) {
        if (first_offset == 0 && (uint32_t)count == ss) {
            if (usb_block_write(ctx->block_handle, first_lba, buf, 1) != 0) {
                errno = EIO;
                return -1;
            }
        } else {
            if (usb_block_read(ctx->block_handle, first_lba, bounce, 1) != 0) {
                errno = EIO;
                return -1;
            }
            memcpy(bounce + first_offset, buf, (size_t)count);
            if (usb_block_write(ctx->block_handle, first_lba, bounce, 1) != 0) {
                errno = EIO;
                return -1;
            }
        }
        return count;
    }

    const uint8_t *cur_buf = (const uint8_t *)buf;
    uint64_t cur_lba = first_lba;

    if (first_offset != 0) {
        uint32_t head_bytes = ss - first_offset;
        if (usb_block_read(ctx->block_handle, first_lba, bounce, 1) != 0) {
            errno = EIO;
            return -1;
        }
        memcpy(bounce + first_offset, cur_buf, head_bytes);
        if (usb_block_write(ctx->block_handle, first_lba, bounce, 1) != 0) {
            errno = EIO;
            return -1;
        }
        cur_buf += head_bytes;
        cur_lba++;
    }

    uint32_t tail_bytes = (uint32_t)(((uint64_t)offset + (uint64_t)count) % ss);
    uint64_t mid_sectors = (tail_bytes == 0) ? (last_lba - cur_lba + 1) : (last_lba - cur_lba);

    if (mid_sectors > 0) {
        if (usb_block_write(ctx->block_handle, cur_lba, cur_buf, (uint32_t)mid_sectors) != 0) {
            errno = EIO;
            return -1;
        }
        cur_buf += mid_sectors * ss;
        cur_lba += mid_sectors;
    }

    if (tail_bytes != 0) {
        if (usb_block_read(ctx->block_handle, last_lba, bounce, 1) != 0) {
            errno = EIO;
            return -1;
        }
        memcpy(bounce, cur_buf, tail_bytes);
        if (usb_block_write(ctx->block_handle, last_lba, bounce, 1) != 0) {
            errno = EIO;
            return -1;
        }
    }

    return count;
}

static s64 dev_op_read(struct ntfs_device *dev, void *buf, s64 count)
{
    NtfsDeviceContext *ctx = (NtfsDeviceContext *)dev->d_private;
    s64 res = dev_op_pread(dev, buf, count, ctx->pos);
    if (res > 0) ctx->pos += res;
    return res;
}

static s64 dev_op_write(struct ntfs_device *dev, const void *buf, s64 count)
{
    NtfsDeviceContext *ctx = (NtfsDeviceContext *)dev->d_private;
    s64 res = dev_op_pwrite(dev, buf, count, ctx->pos);
    if (res > 0) ctx->pos += res;
    return res;
}

static int dev_op_sync(struct ntfs_device *dev)
{
    NtfsDeviceContext *ctx = (NtfsDeviceContext *)dev->d_private;
    return usb_block_sync(ctx->block_handle);
}

static int dev_op_stat(struct ntfs_device *dev, struct stat *buf)
{
    NtfsDeviceContext *ctx = (NtfsDeviceContext *)dev->d_private;
    memset(buf, 0, sizeof(*buf));
    buf->st_size = (off_t)(ctx->sector_count * ctx->sector_size);
    buf->st_blksize = (blksize_t)ctx->sector_size;
    buf->st_mode = S_IFBLK | 0644;
    return 0;
}

static int dev_op_ioctl(struct ntfs_device *dev, unsigned long request, void *argp)
{
    NtfsDeviceContext *ctx = (NtfsDeviceContext *)dev->d_private;
#ifdef BLKGETSIZE64
    if (request == BLKGETSIZE64 && argp) {
        *(uint64_t *)argp = ctx->sector_count * ctx->sector_size;
        return 0;
    }
#endif
#ifdef BLKSSZGET
    if (request == BLKSSZGET && argp) {
        *(int *)argp = (int)ctx->sector_size;
        return 0;
    }
#endif
    errno = ENOTTY;
    return -1;
}

static struct ntfs_device_operations ntfs_usb_block_device_ops = {
    .open = dev_op_open,
    .close = dev_op_close,
    .seek = dev_op_seek,
    .read = dev_op_read,
    .write = dev_op_write,
    .pread = dev_op_pread,
    .pwrite = dev_op_pwrite,
    .sync = dev_op_sync,
    .stat = dev_op_stat,
    .ioctl = dev_op_ioctl,
};

static NtfsVolumeContext *alloc_volume_ctx(uint64_t block_handle)
{
    UsbBlockOps *bops = usb_block_ops(block_handle);
    if (!bops || bops->sector_size == 0) return NULL;

    NtfsVolumeContext *v = (NtfsVolumeContext *)calloc(1, sizeof(NtfsVolumeContext));
    if (!v) return NULL;

    v->block_handle = block_handle;
    v->dev_ctx.block_handle = block_handle;
    v->dev_ctx.sector_size = bops->sector_size;
    v->dev_ctx.sector_count = bops->sector_count;
    v->dev_ctx.pos = 0;

    v->dev = ntfs_device_alloc("usb_block", 0, &ntfs_usb_block_device_ops, &v->dev_ctx);
    if (!v->dev) {
        free(v);
        return NULL;
    }
    return v;
}

uint64_t ntfs_engine_mount(uint64_t block_handle, bool read_only)
{
    ntfs_log_set_handler(ntfs_log_handler_null);
    NtfsVolumeContext *v = alloc_volume_ctx(block_handle);
    if (!v) return 0;

    unsigned long flags = read_only ? NTFS_MNT_RDONLY : 0;
    v->vol = ntfs_device_mount(v->dev, flags);
    if (!v->vol) {
        ntfs_device_free(v->dev);
        free(v);
        return 0;
    }
    if (!read_only && (v->vol->flags & VOLUME_IS_DIRTY)) {
        /* Fail closed: never hand out a writable handle on a dirty volume. */
        ntfs_umount(v->vol, 0);
        free(v);
        errno = EBUSY;
        return 0;
    }
    return (uint64_t)(uintptr_t)v;
}

int ntfs_engine_close(uint64_t vol_handle)
{
    NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
    if (!v) return -EINVAL;

    int rc = 0;
    if (v->vol) {
        rc = ntfs_umount(v->vol, 0);
        v->vol = NULL;
    }
    free(v);
    return rc;
}

int ntfs_engine_sync(uint64_t vol_handle)
{
    NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
    if (!v || !v->vol) return -EINVAL;

    if (v->vol->dev) {
        ntfs_device_sync(v->vol->dev);
    }
    return usb_block_sync(v->block_handle);
}

typedef struct ListContext {
    NtfsDirEntry *entries; /* grown with realloc; owned by the caller on success */
    size_t max_entries;
    size_t count;
    bool overflow;
    ntfs_volume *vol;
} ListContext;

static bool list_reserve(ListContext *ctx)
{
    if (ctx->count < ctx->max_entries) return true;
    size_t next = ctx->max_entries ? ctx->max_entries * 2 : 64;
    if (next > NTFS_LIST_MAX_ENTRIES) next = NTFS_LIST_MAX_ENTRIES;
    if (next <= ctx->max_entries) {
        ctx->overflow = true;
        return false;
    }
    NtfsDirEntry *grown = realloc(ctx->entries, next * sizeof(NtfsDirEntry));
    if (!grown) {
        ctx->overflow = true;
        return false;
    }
    ctx->entries = grown;
    ctx->max_entries = next;
    return true;
}

static int list_filldir(void *dirent, const ntfschar *name, const int name_len,
                        const int name_type, const s64 pos, const MFT_REF mref,
                        const unsigned dt_type)
{
    (void)pos;
    ListContext *ctx = (ListContext *)dirent;
    if (name_type == FILE_NAME_DOS) return 0;

    /* Skip "." and ".." */
    if (name_len == 1 && name[0] == cpu_to_le16('.')) return 0;
    if (name_len == 2 && name[0] == cpu_to_le16('.') && name[1] == cpu_to_le16('.')) return 0;

    char *mbs = NULL;
    int mbs_len = ntfs_ucstombs(name, name_len, &mbs, 0);
    /* A name we cannot represent is an error, never a silently skipped entry. */
    if (mbs_len <= 0 || !mbs) {
        free(mbs);
        return -1;
    }
    if ((size_t)mbs_len >= sizeof(((NtfsDirEntry *)0)->name)) {
        free(mbs);
        return -1;
    }

    if (!list_reserve(ctx)) {
        free(mbs);
        return -1;
    }

    NtfsDirEntry *e = &ctx->entries[ctx->count];
    memset(e, 0, sizeof(*e));
    memcpy(e->name, mbs, (size_t)mbs_len);
    e->name[mbs_len] = '\0';
    free(mbs);

    e->is_directory = (dt_type == 4);
    ntfs_inode *ni = ntfs_inode_open(ctx->vol, mref);
    if (ni) {
        if (ni->mrec && (ni->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
            e->is_directory = true;
        }
        e->size = (uint64_t)ni->data_size;
        ntfs_inode_close(ni);
    }

    ctx->count++;
    return 0;
}

int ntfs_engine_list(uint64_t vol_handle, const char *path,
                     NtfsDirEntry **out_entries, size_t *out_count)
{
    NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
    if (!v || !v->vol || !path || !out_entries || !out_count) return -EINVAL;

    *out_entries = NULL;
    *out_count = 0;
    errno = 0;
    ntfs_inode *dir_ni = ntfs_pathname_to_inode(v->vol, NULL, path);
    if (!dir_ni) return (errno == ENOENT) ? -ENOENT : -EIO;


    if (!(dir_ni->mrec && (dir_ni->mrec->flags & MFT_RECORD_IS_DIRECTORY))) {
        ntfs_inode_close(dir_ni);
        return -ENOTDIR;
    }

    ListContext ctx = {
        .entries = NULL,
        .max_entries = 0,
        .count = 0,
        .overflow = false,
        .vol = v->vol,
    };

    s64 pos = 0;
    int rc = ntfs_readdir(dir_ni, &pos, &ctx, list_filldir);
    ntfs_inode_close(dir_ni);

    if (ctx.overflow) {
        free(ctx.entries);
        return -EOVERFLOW;
    }
    if (rc < 0) {
        /* Corrupt or unreadable directory: a short listing is not a listing. */
        free(ctx.entries);
        return -EIO;
    }
    *out_entries = ctx.entries;
    *out_count = ctx.count;
    return 0;
}

int ntfs_engine_stat(uint64_t vol_handle, const char *path, NtfsStat *out_stat)
{
    NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
    if (!v || !v->vol || !path || !out_stat) return -EINVAL;

    errno = 0;
    ntfs_inode *ni = ntfs_pathname_to_inode(v->vol, NULL, path);
    if (!ni) return (errno == ENOENT) ? -ENOENT : -EIO;

    out_stat->size = (uint64_t)ni->data_size;
    out_stat->is_directory = (ni->mrec && (ni->mrec->flags & MFT_RECORD_IS_DIRECTORY)) != 0;
    out_stat->flags = (uint32_t)ni->flags;

    ntfs_inode_close(ni);
    return 0;
}

int ntfs_engine_read(uint64_t vol_handle, const char *path,
                     uint64_t offset, void *buf, size_t count,
                     size_t *out_read)
{
    NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
    if (!v || !v->vol || !path || !buf || !out_read) return -EINVAL;

    *out_read = 0;
    errno = 0;
    ntfs_inode *ni = ntfs_pathname_to_inode(v->vol, NULL, path);
    if (!ni) return (errno == ENOENT) ? -ENOENT : -EIO;
    if (ni->mft_no < FILE_first_user) {
        ntfs_inode_close(ni);
        return -EPERM;
    }

    ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    if (!na) {
        ntfs_inode_close(ni);
        return -EIO;
    }

    s64 got = ntfs_attr_pread(na, (s64)offset, (s64)count, buf);
    ntfs_attr_close(na);
    ntfs_inode_close(ni);

    if (got < 0) return -EIO;
    *out_read = (size_t)got;
    return 0;
}

static void split_path(const char *path, char *parent, size_t parent_sz, const char **filename)
{
    const char *slash = strrchr(path, '/');
    if (!slash) {
        snprintf(parent, parent_sz, "/");
        *filename = path;
    } else if (slash == path) {
        snprintf(parent, parent_sz, "/");
        *filename = slash + 1;
    } else {
        size_t len = (size_t)(slash - path);
        if (len >= parent_sz) len = parent_sz - 1;
        memcpy(parent, path, len);
        parent[len] = '\0';
        *filename = slash + 1;
    }
}

int ntfs_engine_write(uint64_t vol_handle, const char *path,
		      uint64_t offset, const void *buf, size_t count,
		      bool create, size_t *out_written)
{
	NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
	if (!v || !v->vol || !path || !out_written) return -EINVAL;
	if (!buf && count != 0) return -EINVAL;

	*out_written = 0;
	/* libntfs-3g silently skips the device write under NVolReadOnly() yet
	 * reports the full length as written; refuse instead of faking success. */
	if (NVolReadOnly(v->vol)) return -EROFS;

	int created = 0;
	errno = 0;
	ntfs_inode *ni = ntfs_pathname_to_inode(v->vol, NULL, path);
	if (!ni) {
		int e = errno;
		if (!create) return (e == ENOENT) ? -ENOENT : -EIO;
		if (e != 0 && e != ENOENT) return -EIO;

		char parent[512];
		const char *filename = NULL;
		split_path(path, parent, sizeof(parent), &filename);
		if (!filename || !*filename) return -EINVAL;

		errno = 0;
		ntfs_inode *dir_ni = ntfs_pathname_to_inode(v->vol, NULL, parent);
		if (!dir_ni) return (errno == ENOENT) ? -ENOENT : -EIO;
		{
			int deny = refuse_system_parent(dir_ni);
			if (deny != 0) {
				ntfs_inode_close(dir_ni);
				return deny;
			}
		}


		ntfschar *uname = NULL;
		int ulen = ntfs_mbstoucs(filename, &uname);
		if (ulen < 0 || !uname) {
			ntfs_inode_close(dir_ni);
			return -EINVAL;
		}

		ni = ntfs_create(dir_ni, 0, uname, (u8)ulen, S_IFREG);
		free(uname);
		ntfs_inode_close(dir_ni);
		if (!ni) return -EIO;
		created = 1;
	}

	if (ni->mft_no < FILE_first_user) {
		ntfs_inode_close(ni);
		return -EPERM;
	}

	ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
	if (!na) {
		ntfs_inode_close(ni);
		return -EIO;
	}

	/* create=true on an existing file at offset 0 matches FAT FA_CREATE_ALWAYS:
	 * replace contents. Non-zero offset keeps append/overwrite-at-offset. */
	if (create && !created && offset == 0) {
		if (ntfs_attr_truncate(na, 0) != 0) {
			ntfs_attr_close(na);
			ntfs_inode_close(ni);
			return -EIO;
		}
	}

	s64 written = 0;
	if (count > 0) {
		written = ntfs_attr_pwrite(na, (s64)offset, (s64)count, buf);
	}
	ntfs_attr_close(na);
	ntfs_inode_close(ni);

	if (written < 0) return -EIO;
	*out_written = (size_t)written;
	return 0;
}

int ntfs_engine_mkdir(uint64_t vol_handle, const char *path)
{
    NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
    if (!v || !v->vol || !path) return -EINVAL;
    if (NVolReadOnly(v->vol)) return -EROFS;

    errno = 0;
    ntfs_inode *existing = ntfs_pathname_to_inode(v->vol, NULL, path);
    if (existing) {
        ntfs_inode_close(existing);
        return -EEXIST;
    }
    if (errno != 0 && errno != ENOENT) return -EIO;

    char parent[512];
    const char *filename = NULL;
    split_path(path, parent, sizeof(parent), &filename);
    if (!filename || !*filename) return -EINVAL;

    errno = 0;
    ntfs_inode *dir_ni = ntfs_pathname_to_inode(v->vol, NULL, parent);
    if (!dir_ni) return (errno == ENOENT) ? -ENOENT : -EIO;
    {
        int deny = refuse_system_parent(dir_ni);
        if (deny != 0) {
            ntfs_inode_close(dir_ni);
            return deny;
        }
    }


    ntfschar *uname = NULL;
    int ulen = ntfs_mbstoucs(filename, &uname);
    if (ulen < 0 || !uname) {
        ntfs_inode_close(dir_ni);
        return -EINVAL;
    }

    ntfs_inode *ni = ntfs_create(dir_ni, 0, uname, (u8)ulen, S_IFDIR);
    free(uname);
    ntfs_inode_close(dir_ni);
    if (!ni) return -EIO;
    ntfs_inode_close(ni);
    return 0;
}

int ntfs_engine_delete(uint64_t vol_handle, const char *path)
{
	NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
	if (!v || !v->vol || !path) return -EINVAL;
	if (NVolReadOnly(v->vol)) return -EROFS;
	if (!path[0] || (path[0] == '/' && path[1] == '\0')) return -EINVAL;

	errno = 0;
	ntfs_inode *ni = ntfs_pathname_to_inode(v->vol, NULL, path);
	if (!ni) return (errno == ENOENT) ? -ENOENT : -EIO;

	/* Refuse system metadata inodes ($MFT, $LogFile, ...). */
	if (ni->mft_no < FILE_first_user) {
		ntfs_inode_close(ni);
		return -EPERM;
	}

	if (ni->mrec && (ni->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
		errno = 0;
		if (ntfs_check_empty_dir(ni) != 0) {
			int e = errno;
			ntfs_inode_close(ni);
			return (e == ENOTEMPTY) ? -ENOTEMPTY : -EIO;
		}
	}

	char parent[512];
	const char *filename = NULL;
	split_path(path, parent, sizeof(parent), &filename);
	if (!filename || !*filename) {
		ntfs_inode_close(ni);
		return -EINVAL;
	}

	errno = 0;
	ntfs_inode *dir_ni = ntfs_pathname_to_inode(v->vol, NULL, parent);
	if (!dir_ni) {
		int e = errno;
		ntfs_inode_close(ni);
		return (e == ENOENT) ? -ENOENT : -EIO;
	}

	ntfschar *uname = NULL;
	int ulen = ntfs_mbstoucs(filename, &uname);
	if (ulen < 0 || !uname) {
		ntfs_inode_close(dir_ni);
		ntfs_inode_close(ni);
		return -EINVAL;
	}

	/* ntfs_delete always closes ni and dir_ni (success or failure). */
	int rc = ntfs_delete(v->vol, path, ni, dir_ni, uname, (u8)ulen);
	free(uname);
	return (rc == 0) ? 0 : -EIO;
}

int ntfs_engine_rename(uint64_t vol_handle, const char *from, const char *to)
{
	NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
	if (!v || !v->vol || !from || !to) return -EINVAL;
	if (NVolReadOnly(v->vol)) return -EROFS;
	if (!from[0] || !to[0]) return -EINVAL;

	errno = 0;
	ntfs_inode *dest = ntfs_pathname_to_inode(v->vol, NULL, to);
	if (dest) {
		ntfs_inode_close(dest);
		return -EEXIST;
	}
	/* dest miss must be ENOENT; other errors are I/O. */
	if (errno != 0 && errno != ENOENT) return -EIO;

	errno = 0;
	ntfs_inode *ni = ntfs_pathname_to_inode(v->vol, NULL, from);
	if (!ni) return (errno == ENOENT) ? -ENOENT : -EIO;

	if (ni->mft_no < FILE_first_user) {
		ntfs_inode_close(ni);
		return -EPERM;
	}

	char from_parent[512], to_parent[512];
	const char *from_name = NULL;
	const char *to_name = NULL;
	split_path(from, from_parent, sizeof(from_parent), &from_name);
	split_path(to, to_parent, sizeof(to_parent), &to_name);
	if (!from_name || !*from_name || !to_name || !*to_name) {
		ntfs_inode_close(ni);
		return -EINVAL;
	}

	/* Directory cross-parent rename would leave stale '..' — refuse. */
	if (ni->mrec && (ni->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
		if (strcmp(from_parent, to_parent) != 0) {
			ntfs_inode_close(ni);
			return -EXDEV;
		}
	}

	errno = 0;
	ntfs_inode *to_dir = ntfs_pathname_to_inode(v->vol, NULL, to_parent);
	if (!to_dir) {
		int e = errno;
		ntfs_inode_close(ni);
		return (e == ENOENT) ? -ENOENT : -EIO;
	}
	{
		int deny = refuse_system_parent(to_dir);
		if (deny != 0) {
			ntfs_inode_close(to_dir);
			ntfs_inode_close(ni);
			return deny;
		}
	}

	/* Same parent: one dir inode. Two opens of root confuse link+unlink. */
	ntfs_inode *from_dir = NULL;
	int shared_parent = (strcmp(from_parent, to_parent) == 0);
	if (shared_parent) {
		from_dir = to_dir;
	} else {
		errno = 0;
		from_dir = ntfs_pathname_to_inode(v->vol, NULL, from_parent);
		if (!from_dir) {
			int e = errno;
			ntfs_inode_close(to_dir);
			ntfs_inode_close(ni);
			return (e == ENOENT) ? -ENOENT : -EIO;
		}
	}

	ntfschar *new_uname = NULL;
	int new_ulen = ntfs_mbstoucs(to_name, &new_uname);
	if (new_ulen < 0 || !new_uname) {
		if (!shared_parent) ntfs_inode_close(from_dir);
		ntfs_inode_close(to_dir);
		ntfs_inode_close(ni);
		return -EINVAL;
	}

	ntfschar *old_uname = NULL;
	int old_ulen = ntfs_mbstoucs(from_name, &old_uname);
	if (old_ulen < 0 || !old_uname) {
		free(new_uname);
		if (!shared_parent) ntfs_inode_close(from_dir);
		ntfs_inode_close(to_dir);
		ntfs_inode_close(ni);
		return -EINVAL;
	}

	if (ntfs_link(ni, to_dir, new_uname, (u8)new_ulen) != 0) {
		free(new_uname);
		free(old_uname);
		if (!shared_parent) ntfs_inode_close(from_dir);
		ntfs_inode_close(to_dir);
		ntfs_inode_close(ni);
		return -EIO;
	}

	/* ntfs_delete closes ni + from_dir. When parents match, that also
	 * closes to_dir — clear so success/rollback paths do not double-close. */
#ifdef NTFS_TEST_HOOKS
	int rc;
	if (g_ntfs_test_fail_rename_unlink) {
		/* Simulate unlink failure after successful link; keep both names. */
		ntfs_inode_close(ni);
		if (!shared_parent) ntfs_inode_close(from_dir);
		/* shared: leave to_dir open for rollback */
		rc = -1;
	} else {
		rc = ntfs_delete(v->vol, from, ni, from_dir, old_uname, (u8)old_ulen);
		if (shared_parent) to_dir = NULL; /* closed inside ntfs_delete */
	}
#else
	int rc = ntfs_delete(v->vol, from, ni, from_dir, old_uname, (u8)old_ulen);
	if (shared_parent) to_dir = NULL; /* closed inside ntfs_delete */
#endif



	free(old_uname);
	if (rc != 0) {
		/*
		 * Roll back `to` only when `from` still resolves cleanly.
		 * Ambiguous lookup (I/O) keeps both names and reports partial
		 * so callers do not delete surviving data.
		 */
		errno = 0;
#ifdef NTFS_TEST_HOOKS
		ntfs_inode *still_from = NULL;
		if (g_ntfs_test_fail_still_from_lookup) {
			errno = EIO;
		} else {
			still_from = ntfs_pathname_to_inode(v->vol, NULL, from);
		}
#else
		ntfs_inode *still_from = ntfs_pathname_to_inode(v->vol, NULL, from);
#endif
		if (!still_from) {
			int lookup_err = errno;
			free(new_uname);
			if (to_dir) ntfs_inode_close(to_dir);
			/* from already gone → keep `to` (no orphan). Ambiguous → keep both. */
			return (lookup_err == ENOENT) ? -EIO : -EUCLEAN;
		}
		ntfs_inode_close(still_from);
		errno = 0;
		ntfs_inode *linked = ntfs_pathname_to_inode(v->vol, NULL, to);
		if (!linked) {
			free(new_uname);
			if (to_dir) ntfs_inode_close(to_dir);
			return -EUCLEAN;
		}
		ntfs_inode *to_dir2 = to_dir;
		if (!to_dir2) {
			to_dir2 = ntfs_pathname_to_inode(v->vol, NULL, to_parent);
			if (!to_dir2) {
				ntfs_inode_close(linked);
				free(new_uname);
				return -EUCLEAN;
			}
		} else {
			to_dir = NULL; /* ownership moves into ntfs_delete via to_dir2 */
		}
		int roll_rc = ntfs_delete(v->vol, to, linked, to_dir2, new_uname, (u8)new_ulen);
		free(new_uname);
		if (to_dir) ntfs_inode_close(to_dir);
		return (roll_rc == 0) ? -EIO : -EUCLEAN;
	}
	free(new_uname);
	if (to_dir) ntfs_inode_close(to_dir);
	return 0;
}

int ntfs_engine_is_dirty(uint64_t vol_handle)
{
    NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
    if (!v || !v->vol) return -EINVAL;
    return (v->vol->flags & VOLUME_IS_DIRTY) ? 1 : 0;
}

int ntfs_engine_check_dirty_dev(uint64_t block_handle)
{
    ntfs_log_set_handler(ntfs_log_handler_null);
    NtfsVolumeContext *v = alloc_volume_ctx(block_handle);
    if (!v) return -EINVAL;

    v->vol = ntfs_device_mount(v->dev, NTFS_MNT_RDONLY);
    if (!v->vol) {
        ntfs_device_free(v->dev);
        free(v);
        return -EIO;
    }

    int dirty = (v->vol->flags & VOLUME_IS_DIRTY) ? 1 : 0;
    ntfs_umount(v->vol, 0);
    free(v);
    return dirty;
}

int ntfs_engine_clear_dirty(uint64_t block_handle, const char *confirmation_token)
{
    if (!confirmation_token || strcmp(confirmation_token, NTFS_CLEAR_DIRTY_CONFIRMATION) != 0) {
        return -EINVAL;
    }

    ntfs_log_set_handler(ntfs_log_handler_null);
    NtfsVolumeContext *v = alloc_volume_ctx(block_handle);
    if (!v) return -EINVAL;

    v->vol = ntfs_device_mount(v->dev, 0);
    if (!v->vol) {
        ntfs_device_free(v->dev);
        free(v);
        return -EIO;
    }

    v->vol->flags &= ~VOLUME_IS_DIRTY;
    int rc = ntfs_volume_write_flags(v->vol, v->vol->flags);
    ntfs_umount(v->vol, 0);
    free(v);
    return (rc == 0) ? 0 : -EIO;
}

int ntfs_engine_mkfs(uint64_t block_handle, const char *label, bool quick)
{
    UsbBlockOps *bops = usb_block_ops(block_handle);
    if (!bops || bops->sector_size < 512 || bops->sector_size > 4096 ||
        (bops->sector_size & (bops->sector_size - 1)) != 0)
        return -EINVAL;

    /* mkntfs reserves the final sector for the backup boot sector and then
     * requires at least 1 MiB, so reject geometry it cannot format up front. */
    if (bops->sector_count < 2 ||
        bops->sector_count > UINT64_MAX / bops->sector_size ||
        (bops->sector_count - 1) * bops->sector_size < (1u << 20))
        return -EINVAL;

    ntfs_log_set_handler(ntfs_log_handler_null);

    pthread_mutex_lock(&g_ntfs_mkfs_mu);
    NtfsVolumeContext *v = alloc_volume_ctx(block_handle);
    if (!v) {
        pthread_mutex_unlock(&g_ntfs_mkfs_mu);
        return -EINVAL;
    }

    /* mkntfs_format_device() takes ownership of the device and frees it, but
     * the device's private context lives in v, so v must outlive the call. */
    struct ntfs_device *dev = v->dev;
    v->dev = NULL;
    int rc = mkntfs_format_device(dev, label, quick ? 1 : 0);
    free(v);

    if (rc == 0 && usb_block_sync(block_handle) != 0) rc = -EIO;
    /* Upstream mkntfs ignores a failed backup boot sector write and only
     * marks the freshly created volume dirty. In the library path that is a
     * failure: a volume that cannot hold its backup boot sector must not be
     * reported as successfully formatted. */
    if (rc == 0 && ntfs_engine_check_dirty_dev(block_handle) != 0) rc = -EIO;
    pthread_mutex_unlock(&g_ntfs_mkfs_mu);
    return rc;
}
