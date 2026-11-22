/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Shared relative-LBA block contract for filesystem engines.
 * Partition start is applied exactly once by VirtualPartition / BlockDevice.
 * Engines must not add a second offset.
 */
#ifndef USB_MULTITOOL_USB_BLOCK_H
#define USB_MULTITOOL_USB_BLOCK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Return 0 on success, negative errno on failure. */
int usb_block_read(uint64_t handle, uint64_t lba, void *buf, uint32_t sectors);
int usb_block_write(uint64_t handle, uint64_t lba, const void *buf, uint32_t sectors);
int usb_block_sync(uint64_t handle);

/* Ops table behind a handle (FatFs, NTFS, Ventoy, host tests). */
typedef struct UsbBlockOps {
	int (*read)(void *ctx, uint64_t lba, void *buf, uint32_t sectors);
	int (*write)(void *ctx, uint64_t lba, const void *buf, uint32_t sectors);
	int (*sync)(void *ctx);
	void *ctx;
	uint32_t sector_size;
	uint64_t sector_count;
} UsbBlockOps;

uint64_t usb_block_register(UsbBlockOps *ops);
UsbBlockOps *usb_block_ops(uint64_t handle);

/*
 * Slot-based register for exFAT fsck (handles 1..64).
 *
 * Thread-safe: the slot tables and callback dispatch are guarded by a
 * recursive mutex, so callbacks may call back into usb_block_read/write/sync
 * on the same thread. Registering a handle that is already in use returns
 * -EBUSY; call usb_block_unregister() first. Unregistering does not wait for
 * an in-flight callback on another thread beyond the call already dispatched,
 * so the owner must not free the callback context while a transfer is running.
 */
int usb_block_register_raw(
        uint64_t handle,
        uint64_t sector_size,
        uint64_t sector_count,
        int (*read_fn)(void *ctx, uint64_t lba, void *buf, uint32_t sectors),
        int (*write_fn)(void *ctx, uint64_t lba, const void *buf, uint32_t sectors),
        int (*sync_fn)(void *ctx),
        void *ctx);

void usb_block_unregister(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif /* USB_MULTITOOL_USB_BLOCK_H */
