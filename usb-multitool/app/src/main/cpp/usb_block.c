/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Shared relative-LBA block contract implementation.
 * Supports both direct UsbBlockOps* handle registration and slot-based registration.
 */
#include "usb_block.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define USB_BLOCK_SLOTS 64
static UsbBlockOps g_slots[USB_BLOCK_SLOTS];
static int g_slot_used[USB_BLOCK_SLOTS];

uint64_t usb_block_register(UsbBlockOps *ops)
{
	return (uint64_t)(uintptr_t)ops;
}

UsbBlockOps *usb_block_ops(uint64_t handle)
{
	if (handle == 0) return NULL;
	if (handle <= USB_BLOCK_SLOTS) {
		if (!g_slot_used[handle - 1]) return NULL;
		return &g_slots[handle - 1];
	}
	return (UsbBlockOps *)(uintptr_t)handle;
}

int usb_block_register_raw(
        uint64_t handle,
        uint64_t sector_size,
        uint64_t sector_count,
        int (*read_fn)(void *ctx, uint64_t lba, void *buf, uint32_t sectors),
        int (*write_fn)(void *ctx, uint64_t lba, const void *buf, uint32_t sectors),
        int (*sync_fn)(void *ctx),
        void *ctx)
{
	if (handle == 0 || handle > USB_BLOCK_SLOTS)
		return -EINVAL;
	if (!read_fn || !write_fn || !sync_fn)
		return -EINVAL;
	if (sector_size == 0 || sector_count == 0)
		return -EINVAL;

	UsbBlockOps *ops = &g_slots[handle - 1];
	ops->read = read_fn;
	ops->write = write_fn;
	ops->sync = sync_fn;
	ops->ctx = ctx;
	ops->sector_size = (uint32_t)sector_size;
	ops->sector_count = sector_count;
	g_slot_used[handle - 1] = 1;
	return 0;
}

void usb_block_unregister(uint64_t handle)
{
	if (handle == 0 || handle > USB_BLOCK_SLOTS)
		return;
	memset(&g_slots[handle - 1], 0, sizeof(UsbBlockOps));
	g_slot_used[handle - 1] = 0;
}

int usb_block_read(uint64_t handle, uint64_t lba, void *buf, uint32_t sectors)
{
	UsbBlockOps *ops = usb_block_ops(handle);
	if (!ops || !ops->read || !buf || sectors == 0) return -EINVAL;
	if (ops->sector_count != 0 && (lba >= ops->sector_count || sectors > ops->sector_count - lba)) {
		return -ERANGE;
	}
	return ops->read(ops->ctx, lba, buf, sectors);
}

int usb_block_write(uint64_t handle, uint64_t lba, const void *buf, uint32_t sectors)
{
	UsbBlockOps *ops = usb_block_ops(handle);
	if (!ops || !ops->write || !buf || sectors == 0) return -EINVAL;
	if (ops->sector_count != 0 && (lba >= ops->sector_count || sectors > ops->sector_count - lba)) {
		return -ERANGE;
	}
	return ops->write(ops->ctx, lba, buf, sectors);
}

int usb_block_sync(uint64_t handle)
{
	UsbBlockOps *ops = usb_block_ops(handle);
	if (!ops || !ops->sync) return -EINVAL;
	return ops->sync(ops->ctx);
}
