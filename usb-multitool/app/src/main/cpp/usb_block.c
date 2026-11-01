/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Handle registry for the shared relative-LBA block contract.
 * Callers register a backend per handle. Bounds are enforced here again
 * so every engine gets -ERANGE before an out-of-range access.
 */
#include "usb_block.h"

#include <errno.h>
#include <stdint.h>
#include <string.h>

typedef struct {
    int (*read)(void *ctx, uint64_t lba, void *buf, uint32_t sectors);
    int (*write)(void *ctx, uint64_t lba, const void *buf, uint32_t sectors);
    int (*sync)(void *ctx);
    void *ctx;
    uint64_t sector_size;
    uint64_t sector_count;
    int in_use;
} usb_block_slot;

#define USB_BLOCK_SLOTS 8
static usb_block_slot g_slots[USB_BLOCK_SLOTS];

int usb_block_register(
        uint64_t handle,
        uint64_t sector_size,
        uint64_t sector_count,
        int (*read_fn)(void *, uint64_t, void *, uint32_t),
        int (*write_fn)(void *, uint64_t, const void *, uint32_t),
        int (*sync_fn)(void *),
        void *ctx) {
    if (handle == 0 || handle > USB_BLOCK_SLOTS)
        return -EINVAL;
    if (!read_fn || !write_fn || !sync_fn)
        return -EINVAL;
    if (sector_size == 0 || sector_count == 0)
        return -EINVAL;
    usb_block_slot *s = &g_slots[handle - 1];
    s->read = read_fn;
    s->write = write_fn;
    s->sync = sync_fn;
    s->ctx = ctx;
    s->sector_size = sector_size;
    s->sector_count = sector_count;
    s->in_use = 1;
    return 0;
}

void usb_block_unregister(uint64_t handle) {
    if (handle == 0 || handle > USB_BLOCK_SLOTS)
        return;
    memset(&g_slots[handle - 1], 0, sizeof(usb_block_slot));
}

static int check_bounds(const usb_block_slot *s, uint64_t lba, uint32_t sectors) {
    if (!s->in_use)
        return -EINVAL;
    if (sectors == 0)
        return -EINVAL;
    if (lba >= s->sector_count)
        return -ERANGE;
    if ((uint64_t)sectors > s->sector_count - lba)
        return -ERANGE;
    /* Overflow guard: lba + sectors must not wrap. */
    if (lba + (uint64_t)sectors < lba)
        return -ERANGE;
    return 0;
}

int usb_block_read(uint64_t handle, uint64_t lba, void *buf, uint32_t sectors) {
    if (handle == 0 || handle > USB_BLOCK_SLOTS || !buf)
        return -EINVAL;
    usb_block_slot *s = &g_slots[handle - 1];
    int rc = check_bounds(s, lba, sectors);
    if (rc)
        return rc;
    return s->read(s->ctx, lba, buf, sectors);
}

int usb_block_write(uint64_t handle, uint64_t lba, const void *buf, uint32_t sectors) {
    if (handle == 0 || handle > USB_BLOCK_SLOTS || !buf)
        return -EINVAL;
    usb_block_slot *s = &g_slots[handle - 1];
    int rc = check_bounds(s, lba, sectors);
    if (rc)
        return rc;
    return s->write(s->ctx, lba, buf, sectors);
}

int usb_block_sync(uint64_t handle) {
    if (handle == 0 || handle > USB_BLOCK_SLOTS)
        return -EINVAL;
    usb_block_slot *s = &g_slots[handle - 1];
    if (!s->in_use)
        return -EINVAL;
    return s->sync(s->ctx);
}
