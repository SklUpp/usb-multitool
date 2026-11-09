/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef NTFS_ENGINE_H
#define NTFS_ENGINE_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Max UTF-8 bytes for a 255 UTF-16-unit NTFS name is 765 (3 bytes/unit);
 * the field must hold the whole thing or names are truncated mid-sequence. */
#define NTFS_DIR_ENTRY_NAME_MAX 768

typedef struct NtfsDirEntry {
    char name[NTFS_DIR_ENTRY_NAME_MAX];
    bool is_directory;
    uint64_t size;
} NtfsDirEntry;

typedef struct NtfsStat {
    uint64_t size;
    bool is_directory;
    uint32_t flags;
} NtfsStat;

/* Mount an NTFS volume over a registered usb_block handle.
 * Returns opaque volume handle (>0) on success, 0 on failure.
 */
uint64_t ntfs_engine_mount(uint64_t block_handle, bool read_only);

/* Close/unmount an NTFS volume handle. Returns 0 on success. */
int ntfs_engine_close(uint64_t vol_handle);

/* Flush dirty metadata/data to underlying block device. Returns 0 on success. */
int ntfs_engine_sync(uint64_t vol_handle);

/* Hard ceiling for a single directory listing; exceeding it is an error, never a
 * silent truncation. */
#define NTFS_LIST_MAX_ENTRIES 65536u

/* List directory entries under path (e.g. "/" or "/dir").
 * On success *out_entries points at a malloc'd array the caller must free(),
 * and *out_count holds the entry count.
 * Returns -EOVERFLOW when the directory holds more than NTFS_LIST_MAX_ENTRIES,
 * and -EIO when the directory index cannot be read or an entry name cannot be
 * converted to UTF-8. A failure never yields a short listing.
 */
int ntfs_engine_list(uint64_t vol_handle, const char *path,
                     NtfsDirEntry **out_entries, size_t *out_count);

/* Stat a file or directory. Returns 0 on success. */
int ntfs_engine_stat(uint64_t vol_handle, const char *path, NtfsStat *out_stat);

/* Read from file at byte offset.
 * *out_read is set to bytes read. Returns 0 on success.
 */
int ntfs_engine_read(uint64_t vol_handle, const char *path,
                     uint64_t offset, void *buf, size_t count,
                     size_t *out_read);

/* Write to file at byte offset.
 * If create is true and the file is missing, creates it. If create is true,
 * the file already exists, and offset is 0, truncates before writing (FAT
 * FA_CREATE_ALWAYS). Non-zero offset never truncates (append/overwrite-at-offset).
 * *out_written is set to bytes written. Returns 0 on success.
 * Returns -EROFS when the volume is mounted read-only; libntfs-3g would
 * otherwise report the full length as written without touching the device.
 */
int ntfs_engine_write(uint64_t vol_handle, const char *path,
                      uint64_t offset, const void *buf, size_t count,
                      bool create, size_t *out_written);

/* Create a directory. Returns 0 on success, -EROFS on RO mount, -EEXIST if present. */
int ntfs_engine_mkdir(uint64_t vol_handle, const char *path);

/* Delete a file or empty directory. Returns 0 on success;
 * -ENOTEMPTY when the directory still has children. */
int ntfs_engine_delete(uint64_t vol_handle, const char *path);

/* Rename/move within the volume (same-directory and cross-directory via link+unlink).
 * Fails with -EEXIST if [to] already exists.
 * On unlink failure after link: rolls back `to` when `from` still resolves;
 * if `from` is already gone keeps `to` (-EIO); ambiguous lookup/rollback keeps
 * both names and returns -EUCLEAN (partial).
 */
int ntfs_engine_rename(uint64_t vol_handle, const char *from, const char *to);

#ifdef NTFS_TEST_HOOKS
/* Host-test only: force rename unlink failure after successful link. */
void ntfs_test_set_fail_rename_unlink(int enabled);
/* Host-test only: force still_from lookup to fail as I/O (ambiguous). */
void ntfs_test_set_fail_still_from_lookup(int enabled);
#endif


/* Check if mounted volume is marked dirty.
 * Returns 1 if dirty, 0 if clean, negative on error.
 */
int ntfs_engine_is_dirty(uint64_t vol_handle);

/* Check if block device volume is marked dirty without full rw mount.
 * Returns 1 if dirty, 0 if clean, negative on error.
 */
int ntfs_engine_check_dirty_dev(uint64_t block_handle);

/*
 * Format the block device as NTFS using upstream mkntfs over usb_block.
 * No root, path, FUSE or mount table; the device must not have an open volume.
 * @quick skips the full-volume zero fill. @label may be NULL.
 * Requires at least 1 MiB after reserving the backup boot sector.
 * Returns 0 on success, otherwise a negative errno (-EINVAL for bad geometry,
 * -EIO when a write/sync fails). On failure the device may be partially written.
 */
int ntfs_engine_mkfs(uint64_t block_handle, const char *label, bool quick);

/* Clear dirty flag on NTFS volume (ntfsfix equivalent).
 * Requires confirmation token matching "CONFIRM_NTFS_CLEAR_DIRTY".
 * Underlying volume must be closed before calling.
 * Returns 0 on success, negative error code on failure.
 */
int ntfs_engine_clear_dirty(uint64_t block_handle, const char *confirmation_token);

#ifdef __cplusplus
}
#endif

#endif /* NTFS_ENGINE_H */
