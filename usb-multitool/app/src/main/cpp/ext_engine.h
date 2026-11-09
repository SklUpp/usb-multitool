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
 * Mount EXT2/3/4 over a registered usb_block handle via lwext4.
 * Relative LBAs only — VirtualPartition applies startLba once.
 *
 * Needs-recovery (FINCOM_RECOVER) and other unsupported incompat features
 * refuse a read-write mount. read_only=true still refuses RECOVER so we never
 * expose a dirty journal as a silent success.
 *
 * Returns opaque volume handle (>0) on success, 0 on failure.
 * On failure *out_errno (if non-NULL) receives a positive errno
 * (ENOTSUP, EBUSY for dirty, EIO, …).
 */
uint64_t ext_engine_mount(uint64_t block_handle, bool read_only, int *out_errno);

int ext_engine_close(uint64_t vol_handle);
int ext_engine_sync(uint64_t vol_handle);

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
 * Block handle must not have an open volume.
 */
int ext_engine_mkfs(uint64_t block_handle);

/* Probe: 1 if EXT magic at byte 1024, 0 if not, negative on IO error. */
int ext_engine_probe(uint64_t block_handle);

#ifdef __cplusplus
}
#endif

#endif /* USB_MULTITOOL_EXT_ENGINE_H */
