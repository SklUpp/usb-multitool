/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Shared relative-LBA block contract implementation.
 * Supports both direct UsbBlockOps* handle registration and slot-based registration.
 *
 * Slot tables are guarded by a recursive mutex: JNI callbacks invoked from
 * usb_block_read/write/sync may re-enter the usb_block_* API on the same
 * thread, so a non-recursive lock would self-deadlock.
 */
/* PTHREAD_MUTEX_RECURSIVE needs _GNU_SOURCE on glibc when the build does not set it
 * (host test makefiles); the Android CMake target defines it already. */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "usb_block.h"

#include <errno.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

/* Glibc/NDK expose the enum name; Bionic header stubs on some host toolchains do
 * not, where the value is 1 on Bionic, glibc, musl and Darwin alike. */
#if defined(PTHREAD_MUTEX_RECURSIVE)
#define USB_BLOCK_RECURSIVE_MUTEX PTHREAD_MUTEX_RECURSIVE
#elif defined(PTHREAD_MUTEX_RECURSIVE_NP)
#define USB_BLOCK_RECURSIVE_MUTEX PTHREAD_MUTEX_RECURSIVE_NP
#else
#define USB_BLOCK_RECURSIVE_MUTEX 1
#endif

#define USB_BLOCK_SLOTS 64
static UsbBlockOps g_slots[USB_BLOCK_SLOTS];
static int g_slot_used[USB_BLOCK_SLOTS];

static pthread_once_t g_lock_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t g_lock;

static void init_lock(void)
{
	pthread_mutexattr_t attr;

	pthread_mutexattr_init(&attr);
	pthread_mutexattr_settype(&attr, USB_BLOCK_RECURSIVE_MUTEX);
	pthread_mutex_init(&g_lock, &attr);
	pthread_mutexattr_destroy(&attr);
}

static void lock_tables(void)
{
	pthread_once(&g_lock_once, init_lock);
	pthread_mutex_lock(&g_lock);
}

static void unlock_tables(void)
{
	pthread_mutex_unlock(&g_lock);
}

/* Caller must hold g_lock. */
static UsbBlockOps *lookup_locked(uint64_t handle)
{
	if (handle == 0) return NULL;
	if (handle <= USB_BLOCK_SLOTS) {
		if (!g_slot_used[handle - 1]) return NULL;
		return &g_slots[handle - 1];
	}
	return (UsbBlockOps *)(uintptr_t)handle;
}

uint64_t usb_block_register(UsbBlockOps *ops)
{
	return (uint64_t)(uintptr_t)ops;
}

UsbBlockOps *usb_block_ops(uint64_t handle)
{
	UsbBlockOps *ops;

	lock_tables();
	ops = lookup_locked(handle);
	unlock_tables();
	return ops;
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

	lock_tables();
	if (g_slot_used[handle - 1]) {
		unlock_tables();
		return -EBUSY;
	}
	UsbBlockOps *ops = &g_slots[handle - 1];
	ops->read = read_fn;
	ops->write = write_fn;
	ops->sync = sync_fn;
	ops->ctx = ctx;
	ops->sector_size = (uint32_t)sector_size;
	ops->sector_count = sector_count;
	g_slot_used[handle - 1] = 1;
	unlock_tables();
	return 0;
}

void usb_block_unregister(uint64_t handle)
{
	if (handle == 0 || handle > USB_BLOCK_SLOTS)
		return;
	lock_tables();
	memset(&g_slots[handle - 1], 0, sizeof(UsbBlockOps));
	g_slot_used[handle - 1] = 0;
	unlock_tables();
}

int usb_block_read(uint64_t handle, uint64_t lba, void *buf, uint32_t sectors)
{
	int rc;

	if (!buf || sectors == 0) return -EINVAL;
	lock_tables();
	UsbBlockOps *ops = lookup_locked(handle);
	if (!ops || !ops->read) {
		rc = -EINVAL;
	} else if (ops->sector_count != 0 &&
	           (lba >= ops->sector_count || sectors > ops->sector_count - lba)) {
		rc = -ERANGE;
	} else {
		rc = ops->read(ops->ctx, lba, buf, sectors);
	}
	unlock_tables();
	return rc;
}

int usb_block_write(uint64_t handle, uint64_t lba, const void *buf, uint32_t sectors)
{
	int rc;

	if (!buf || sectors == 0) return -EINVAL;
	lock_tables();
	UsbBlockOps *ops = lookup_locked(handle);
	if (!ops || !ops->write) {
		rc = -EINVAL;
	} else if (ops->sector_count != 0 &&
	           (lba >= ops->sector_count || sectors > ops->sector_count - lba)) {
		rc = -ERANGE;
	} else {
		rc = ops->write(ops->ctx, lba, buf, sectors);
	}
	unlock_tables();
	return rc;
}

int usb_block_sync(uint64_t handle)
{
	int rc;

	lock_tables();
	UsbBlockOps *ops = lookup_locked(handle);
	rc = (!ops || !ops->sync) ? -EINVAL : ops->sync(ops->ctx);
	unlock_tables();
	return rc;
}
