/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "ntfs_engine.h"
#include "usb_block.h"

#define HAVE_CONFIG_H 1
#include "config.h"
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

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#define NTFS_CLEAR_DIRTY_CONFIRMATION "CONFIRM_NTFS_CLEAR_DIRTY"

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
    (void)dev;
    (void)flags;
    return 0;
}

static int dev_op_close(struct ntfs_device *dev)
{
    (void)dev;
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
    NtfsDirEntry *entries;
    size_t max_entries;
    size_t count;
    ntfs_volume *vol;
} ListContext;

static int list_filldir(void *dirent, const ntfschar *name, const int name_len,
                        const int name_type, const s64 pos, const MFT_REF mref,
                        const unsigned dt_type)
{
    (void)pos;
    ListContext *ctx = (ListContext *)dirent;
    if (ctx->count >= ctx->max_entries) return -1;
    if (name_type == FILE_NAME_DOS) return 0;

    /* Skip "." and ".." */
    if (name_len == 1 && name[0] == cpu_to_le16('.')) return 0;
    if (name_len == 2 && name[0] == cpu_to_le16('.') && name[1] == cpu_to_le16('.')) return 0;

    char *mbs = NULL;
    int mbs_len = ntfs_ucstombs(name, name_len, &mbs, 0);
    if (mbs_len <= 0 || !mbs) return 0;

    NtfsDirEntry *e = &ctx->entries[ctx->count];
    memset(e, 0, sizeof(*e));
    strncpy(e->name, mbs, sizeof(e->name) - 1);
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
                     NtfsDirEntry *out_entries, size_t max_entries,
                     size_t *out_count)
{
    NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
    if (!v || !v->vol || !path || !out_count) return -EINVAL;

    *out_count = 0;
    ntfs_inode *dir_ni = ntfs_pathname_to_inode(v->vol, NULL, path);
    if (!dir_ni) return -ENOENT;

    if (!(dir_ni->mrec && (dir_ni->mrec->flags & MFT_RECORD_IS_DIRECTORY))) {
        ntfs_inode_close(dir_ni);
        return -ENOTDIR;
    }

    ListContext ctx = {
        .entries = out_entries,
        .max_entries = max_entries,
        .count = 0,
        .vol = v->vol,
    };

    s64 pos = 0;
    ntfs_readdir(dir_ni, &pos, &ctx, list_filldir);
    ntfs_inode_close(dir_ni);

    *out_count = ctx.count;
    return 0;
}

int ntfs_engine_stat(uint64_t vol_handle, const char *path, NtfsStat *out_stat)
{
    NtfsVolumeContext *v = (NtfsVolumeContext *)(uintptr_t)vol_handle;
    if (!v || !v->vol || !path || !out_stat) return -EINVAL;

    ntfs_inode *ni = ntfs_pathname_to_inode(v->vol, NULL, path);
    if (!ni) return -ENOENT;

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
    ntfs_inode *ni = ntfs_pathname_to_inode(v->vol, NULL, path);
    if (!ni) return -ENOENT;

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
    if (!v || !v->vol || !path || !buf || !out_written) return -EINVAL;

    *out_written = 0;
    ntfs_inode *ni = ntfs_pathname_to_inode(v->vol, NULL, path);
    if (!ni && create) {
        char parent[512];
        const char *filename = NULL;
        split_path(path, parent, sizeof(parent), &filename);

        ntfs_inode *dir_ni = ntfs_pathname_to_inode(v->vol, NULL, parent);
        if (!dir_ni) return -ENOENT;

        ntfschar *uname = NULL;
        int ulen = ntfs_mbstoucs(filename, &uname);
        if (ulen < 0 || !uname) {
            ntfs_inode_close(dir_ni);
            return -EINVAL;
        }

        ni = ntfs_create(dir_ni, 0, uname, (u8)ulen, S_IFREG);
        free(uname);
        ntfs_inode_close(dir_ni);
    }

    if (!ni) return -ENOENT;

    ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    if (!na) {
        ntfs_inode_close(ni);
        return -EIO;
    }

    s64 written = ntfs_attr_pwrite(na, (s64)offset, (s64)count, buf);
    ntfs_attr_close(na);
    ntfs_inode_close(ni);

    if (written < 0) return -EIO;
    *out_written = (size_t)written;
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
