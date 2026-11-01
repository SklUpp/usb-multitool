/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Shared relative-LBA block contract for filesystem engines.
 * Partition start is applied exactly once by VirtualPartition / BlockDevice.
 * Engines must not add a second offset.
 *
 * Return 0 on success, negative errno on failure.
 * Bounds: return -ERANGE before any overflow or out-of-bounds access.
 */
#ifndef USB_MULTITOOL_USB_BLOCK_H
#define USB_MULTITOOL_USB_BLOCK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int usb_block_read(uint64_t handle, uint64_t lba, void *buf, uint32_t sectors);
int usb_block_write(uint64_t handle, uint64_t lba, const void *buf, uint32_t sectors);
int usb_block_sync(uint64_t handle);

/* Register a backend for handle (1..N). Host tests and JNI use this. */
int usb_block_register(
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
