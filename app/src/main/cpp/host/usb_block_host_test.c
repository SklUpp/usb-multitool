/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Host checks for the shared usb_block_* registry: slot ownership (-EBUSY),
 * dispatch, range checks, and register/unregister under a concurrent reader.
 */
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "usb_block.h"

#define SECTOR_SIZE 512u
#define SECTOR_COUNT 8u
#define STORE_BYTES ((size_t)SECTOR_SIZE * SECTOR_COUNT)

static uint8_t g_slot_store[STORE_BYTES];
static uint8_t g_other_store[STORE_BYTES];
static int g_sync_calls;
static volatile int g_stop;

static int slot_read(void *ctx, uint64_t lba, void *buf, uint32_t sectors)
{
	uint8_t *store = (uint8_t *)ctx;
	if (lba > SECTOR_COUNT || sectors > SECTOR_COUNT - lba) return -ERANGE;
	memcpy(buf, store + (size_t)lba * SECTOR_SIZE, (size_t)sectors * SECTOR_SIZE);
	return 0;
}

static int slot_write(void *ctx, uint64_t lba, const void *buf, uint32_t sectors)
{
	uint8_t *store = (uint8_t *)ctx;
	if (lba > SECTOR_COUNT || sectors > SECTOR_COUNT - lba) return -ERANGE;
	memcpy(store + (size_t)lba * SECTOR_SIZE, buf, (size_t)sectors * SECTOR_SIZE);
	return 0;
}

static int slot_sync(void *ctx)
{
	(void)ctx;
	g_sync_calls++;
	return 0;
}

static void check_slot_register(void)
{
	uint8_t buf[SECTOR_SIZE];
	uint8_t pattern[SECTOR_SIZE];

	assert(usb_block_register_raw(1, SECTOR_SIZE, SECTOR_COUNT, slot_read,
	                              slot_write, slot_sync,
	                              g_slot_store) == 0);
	/* Second registration of the same slot must be refused, not aliased. */
	assert(usb_block_register_raw(1, SECTOR_SIZE, SECTOR_COUNT, slot_read,
	                              slot_write, slot_sync,
	                              g_other_store) == -EBUSY);
	/* The refused registration must not have replaced the owner's ctx. */
	memset(pattern, 0x5A, sizeof(pattern));
	assert(usb_block_write(1, 2, pattern, 1) == 0);
	assert(g_slot_store[2 * SECTOR_SIZE + 7] == 0x5A);
	assert(g_other_store[2 * SECTOR_SIZE + 7] == 0);

	memset(buf, 0, sizeof(buf));
	assert(usb_block_read(1, 2, buf, 1) == 0);
	assert(memcmp(buf, pattern, sizeof(buf)) == 0);

	assert(usb_block_sync(1) == 0);
	assert(g_sync_calls == 1);

	assert(usb_block_read(1, SECTOR_COUNT, buf, 1) == -ERANGE);
	assert(usb_block_read(1, SECTOR_COUNT - 1, buf, 2) == -ERANGE);
	assert(usb_block_write(1, SECTOR_COUNT, buf, 1) == -ERANGE);
	assert(usb_block_read(1, 0, NULL, 1) == -EINVAL);
	assert(usb_block_read(1, 0, buf, 0) == -EINVAL);
	/* Unregistered slot handle: handle 2 is never registered here. */
	assert(usb_block_read(2, 0, buf, 1) == -EINVAL);

	usb_block_unregister(1);
	assert(usb_block_ops(1) == NULL);
	assert(usb_block_read(1, 0, buf, 1) == -EINVAL);
	assert(usb_block_sync(1) == -EINVAL);

	/* The slot is reusable after unregister. */
	assert(usb_block_register_raw(1, SECTOR_SIZE, SECTOR_COUNT, slot_read,
	                              slot_write, slot_sync,
	                              g_other_store) == 0);
	memset(buf, 0x11, sizeof(buf));
	assert(usb_block_write(1, 0, buf, 1) == 0);
	assert(g_other_store[0] == 0x11);
	usb_block_unregister(1);
}

static void check_invalid_args(void)
{
	assert(usb_block_register_raw(0, SECTOR_SIZE, SECTOR_COUNT, slot_read,
	                              slot_write, slot_sync, g_slot_store) == -EINVAL);
	assert(usb_block_register_raw(65, SECTOR_SIZE, SECTOR_COUNT, slot_read,
	                              slot_write, slot_sync, g_slot_store) == -EINVAL);
	assert(usb_block_register_raw(UINT64_MAX, SECTOR_SIZE, SECTOR_COUNT, slot_read,
	                              slot_write, slot_sync, g_slot_store) == -EINVAL);
	assert(usb_block_register_raw(1, 0, SECTOR_COUNT, slot_read,
	                              slot_write, slot_sync, g_slot_store) == -EINVAL);
	assert(usb_block_register_raw(1, SECTOR_SIZE, SECTOR_COUNT, NULL,
	                              slot_write, slot_sync, g_slot_store) == -EINVAL);
	usb_block_unregister(0);
	usb_block_unregister(65);
}

static void check_pointer_mode(void)
{
	uint8_t buf[SECTOR_SIZE];
	UsbBlockOps ops;
	uint64_t handle;

	assert(usb_block_register(NULL) == 0);
	assert(usb_block_ops(0) == NULL);
	assert(usb_block_read(0, 0, buf, 1) == -EINVAL);

	memset(&ops, 0, sizeof(ops));
	ops.read = slot_read;
	ops.write = slot_write;
	ops.sync = slot_sync;
	ops.ctx = g_slot_store;
	ops.sector_size = SECTOR_SIZE;
	ops.sector_count = SECTOR_COUNT;
	handle = usb_block_register(&ops);
	assert(handle != 0);
	assert(usb_block_ops(handle) == &ops);
	memset(buf, 0x23, sizeof(buf));
	assert(usb_block_write(handle, 5, buf, 1) == 0);
	assert(usb_block_read(handle, 5, buf, 1) == 0);
	assert(buf[0] == 0x23);
}

static void *reader_main(void *arg)
{
	uint8_t buf[SECTOR_SIZE];
	(void)arg;
	while (!g_stop) {
		int rc = usb_block_read(7, 0, buf, 1);
		assert(rc == 0 || rc == -EINVAL);
	}
	return NULL;
}

static void check_concurrent_reader(void)
{
	pthread_t reader;
	int i;

	assert(usb_block_register_raw(7, SECTOR_SIZE, SECTOR_COUNT, slot_read,
	                              slot_write, slot_sync, g_slot_store) == 0);
	g_stop = 0;
	assert(pthread_create(&reader, NULL, reader_main, NULL) == 0);
	for (i = 0; i < 2000; i++) {
		usb_block_unregister(7);
		assert(usb_block_register_raw(7, SECTOR_SIZE, SECTOR_COUNT,
		                              slot_read, slot_write, slot_sync,
		                              g_slot_store) == 0);
	}
	g_stop = 1;
	assert(pthread_join(reader, NULL) == 0);
	usb_block_unregister(7);
}

int main(void)
{
	check_slot_register();
	check_invalid_args();
	check_pointer_mode();
	check_concurrent_reader();
	printf("usb_block_host_test: OK\n");
	return 0;
}
