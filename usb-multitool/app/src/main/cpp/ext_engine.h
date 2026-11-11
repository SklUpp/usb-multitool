/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef USB_MULTITOOL_EXT_ENGINE_H
#define USB_MULTITOOL_EXT_ENGINE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EXT_DIR_ENTRY_NAME_MAX 768
#define EXT_LIST_MAX_ENTRIES 65536u

typedef struct ExtDirEntry {
	char name[EXT_DIR_ENTRY_NAME_MAX];
	bool is_directory;
	uint64_t size;
} ExtDirEntry;

typedef struct ExtStat {
	uint64_t size;
	bool is_directory;
} ExtStat;

/*
 * Convert a UTF-16 label to standard UTF-8 (supplementary code points as one 4-byte
 * sequence, unlike Java's modified UTF-8 / CESU-8). Returns the byte count written
 * without the terminator, or -1 for an unpaired surrogate or too small an output buffer.
 * Used for the ext4 volume label, which lwext4 copies verbatim into the superblock.
 */
int ext_utf16_to_utf8(const uint16_t *in, size_t in_len, char *out, size_t out_cap);

/*
 * Mount EXT2/3/4 over a registered usb_block handle via lwext4.
 * Relative LBAs only — VirtualPartition applies startLba once.
 *
 * Needs-recovery (FINCOM_RECOVER) and other unsupported incompat features
 * refuse mount (RO and RW). read_only=true still refuses RECOVER so we never
 * expose a dirty journal as a silent success.
 *
 * RW mount runs ext4_journal_start after ext4_mount. No-op when the volume has
 * no HAS_JOURNAL feature. If start fails, mount is torn down and refused —
 * never return a writable handle without a live journal.
 *
 * Returns opaque volume handle (>0) on success, 0 on failure.
 * On failure *out_errno (if non-NULL) receives a positive errno
 * (ENOTSUP, EBUSY for dirty, EIO, …).
 */
uint64_t ext_engine_mount(uint64_t block_handle, bool read_only, int *out_errno);

/* Always frees the volume. On umount/sync error still tears down the lwext4
 * mountpoint before free (no stale s_mp / dangling bdev). Returns 0 or negative errno. */
int ext_engine_close(uint64_t vol_handle);
int ext_engine_sync(uint64_t vol_handle);

/*
 * Metadata-only space query from the mounted superblock/group descriptors:
 * total and free bytes = blocks_count/free_blocks_count x block_size.
 * Read-only; no allocation, no journal traffic. Returns 0 or a negative errno.
 */
int ext_engine_usage(uint64_t vol_handle, uint64_t *out_total_bytes,
		     uint64_t *out_free_bytes);

int ext_engine_list(uint64_t vol_handle, const char *path,
		    ExtDirEntry **out_entries, size_t *out_count);
int ext_engine_stat(uint64_t vol_handle, const char *path, ExtStat *out_stat);
int ext_engine_read(uint64_t vol_handle, const char *path,
		    uint64_t offset, void *buf, size_t count, size_t *out_read);
int ext_engine_write(uint64_t vol_handle, const char *path,
		     uint64_t offset, const void *buf, size_t count,
		     bool create, size_t *out_written);
int ext_engine_mkdir(uint64_t vol_handle, const char *path);
int ext_engine_delete(uint64_t vol_handle, const char *path);
int ext_engine_rename(uint64_t vol_handle, const char *from, const char *to);

/*
 * Format partition as EXT4 without a journal (avoids FINCOM_RECOVER on first
 * unclean unmount under lwext4's non-replaying journal).
 * Block handle must not have an open volume. [label] is the 16-byte superblock
 * volume name (NULL/empty = no label); longer labels are rejected, not truncated.
 */
int ext_engine_mkfs(uint64_t block_handle, const char *label);

/* Probe: 1 if EXT magic at byte 1024, 0 if not, negative on IO error. */
int ext_engine_probe(uint64_t block_handle);

#ifdef __cplusplus
}
#endif

#endif /* USB_MULTITOOL_EXT_ENGINE_H */
