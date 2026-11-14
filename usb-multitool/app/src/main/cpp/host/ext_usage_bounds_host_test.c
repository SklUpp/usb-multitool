/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Host test for EXT superblock geometry vs physical partition bounds.
 * Ensures ext_engine_usage never reports total or free bytes exceeding
 * the backing partition size when superblock block counts are corrupted.
 */
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "ext_engine.h"
#include "usb_block.h"

#define SB_OFFSET 1024u
#define SB_OFF_BLOCKS_COUNT_LO 0x04u
#define SB_OFF_FREE_BLOCKS_COUNT_LO 0x0Cu
#define SB_OFF_LOG_BLOCK_SIZE 0x18u
#define SB_OFF_FEATURES_RO_COMPAT 0x64u
#define SB_OFF_BLOCKS_COUNT_HI 0x150u
#define SB_OFF_FREE_BLOCKS_COUNT_HI 0x158u
#define EXT4_FRO_COM_METADATA_CSUM 0x0400u

typedef struct FileBlockCtx {
	int fd;
	uint32_t sector_size;
	uint64_t sector_count;
	uint64_t write_count;
} FileBlockCtx;

static int file_read(void *ctx, uint64_t lba, void *buf, uint32_t sectors)
{
	FileBlockCtx *f = (FileBlockCtx *)ctx;
	if (lba > f->sector_count || sectors > f->sector_count - lba) {
		return -1;
	}
	off_t off = (off_t)(lba * f->sector_size);
	size_t bytes = (size_t)sectors * f->sector_size;
	ssize_t n = pread(f->fd, buf, bytes, off);
	return (n == (ssize_t)bytes) ? 0 : -1;
}

static int file_write(void *ctx, uint64_t lba, const void *buf, uint32_t sectors)
{
	FileBlockCtx *f = (FileBlockCtx *)ctx;
	if (lba > f->sector_count || sectors > f->sector_count - lba) {
		return -1;
	}
	f->write_count++;
	off_t off = (off_t)(lba * f->sector_size);
	size_t bytes = (size_t)sectors * f->sector_size;
	ssize_t n = pwrite(f->fd, buf, bytes, off);
	return (n == (ssize_t)bytes) ? 0 : -1;
}

static int file_sync(void *ctx)
{
	FileBlockCtx *f = (FileBlockCtx *)ctx;
	return fsync(f->fd);
}

static uint32_t read_sb_le32(const FileBlockCtx *ctx, uint32_t sb_off)
{
	uint8_t b[4];
	assert(pread(ctx->fd, b, sizeof(b), (off_t)(SB_OFFSET + sb_off)) == (ssize_t)sizeof(b));
	return (uint32_t)b[0] | ((uint32_t)b[1] << 8) |
	       ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
}

static void write_sb_le32(const FileBlockCtx *ctx, uint32_t sb_off, uint32_t val)
{
	uint8_t b[4];
	b[0] = (uint8_t)(val & 0xFFu);
	b[1] = (uint8_t)((val >> 8) & 0xFFu);
	b[2] = (uint8_t)((val >> 16) & 0xFFu);
	b[3] = (uint8_t)((val >> 24) & 0xFFu);
	assert(pwrite(ctx->fd, b, sizeof(b), (off_t)(SB_OFFSET + sb_off)) == (ssize_t)sizeof(b));
	assert(fsync(ctx->fd) == 0);
}

static void patch_sb_counts(const FileBlockCtx *ctx, uint64_t blocks_cnt, uint64_t free_cnt)
{
	write_sb_le32(ctx, SB_OFF_BLOCKS_COUNT_LO, (uint32_t)(blocks_cnt & 0xFFFFFFFFu));
	write_sb_le32(ctx, SB_OFF_BLOCKS_COUNT_HI, (uint32_t)(blocks_cnt >> 32));
	write_sb_le32(ctx, SB_OFF_FREE_BLOCKS_COUNT_LO, (uint32_t)(free_cnt & 0xFFFFFFFFu));
	write_sb_le32(ctx, SB_OFF_FREE_BLOCKS_COUNT_HI, (uint32_t)(free_cnt >> 32));
}

static int expect_usage_rejected_ro(uint64_t block, FileBlockCtx *ctx, const char *label)
{
	uint64_t writes_before = ctx->write_count;
	int err = 0;
	uint64_t vol = ext_engine_mount(block, true, &err);
	if (ctx->write_count != writes_before) {
		printf("FAIL [%s]: RO mount wrote to device\n", label);
		if (vol != 0) (void)ext_engine_close(vol);
		return 1;
	}
	if (vol == 0) {
		printf("FAIL [%s]: expected RO mount to succeed before usage guard (err=%d)\n",
		       label, err);
		return 1;
	}
	uint64_t total = 0, free_bytes = 0;
	int rc = ext_engine_usage(vol, &total, &free_bytes);
	int close_rc = ext_engine_close(vol);
	if (ctx->write_count != writes_before) {
		printf("FAIL [%s]: RO usage/close wrote to device\n", label);
		return 1;
	}
	assert(close_rc == 0);
	if (rc != -EIO) {
		printf("FAIL [%s]: expected usage -EIO, got rc=%d total=%llu free=%llu\n",
		       label, rc, (unsigned long long)total, (unsigned long long)free_bytes);
		return 1;
	}
	printf("PASS [%s]: usage rejected invalid counts (rc=%d)\n", label, rc);
	return 0;
}

static int check_usage_within_partition_ro(uint64_t block, FileBlockCtx *ctx,
					   uint64_t part_size, const char *label)
{
	uint64_t writes_before = ctx->write_count;
	int err = 0;
	uint64_t vol = ext_engine_mount(block, true, &err);
	if (ctx->write_count != writes_before) {
		printf("FAIL [%s]: RO mount wrote to device\n", label);
		if (vol != 0) (void)ext_engine_close(vol);
		return 1;
	}
	if (vol == 0) {
		printf("PASS [%s]: oversized superblock refused at mount (err=%d)\n", label, err);
		return 0;
	}

	uint64_t total = 0, free_bytes = 0;
	int rc = ext_engine_usage(vol, &total, &free_bytes);
	int close_rc = ext_engine_close(vol);
	if (ctx->write_count != writes_before) {
		printf("FAIL [%s]: RO usage/close wrote to device\n", label);
		return 1;
	}
	assert(close_rc == 0);

	if (rc < 0) {
		printf("PASS [%s]: usage rejected oversized superblock (rc=%d)\n", label, rc);
		return 0;
	}
	if (total > part_size || free_bytes > part_size || free_bytes > total) {
		printf("FAIL [%s]: usage exceeds physical partition (%llu B): total=%llu free=%llu\n",
		       label, (unsigned long long)part_size,
		       (unsigned long long)total, (unsigned long long)free_bytes);
		return 1;
	}
	printf("PASS [%s]: usage bounded (total=%llu free=%llu <= %llu)\n",
	       label, (unsigned long long)total, (unsigned long long)free_bytes,
	       (unsigned long long)part_size);
	return 0;
}

int main(void)
{
	const char *img = "ext_usage_bounds.img";
	const uint32_t ss = 512;
	const uint64_t part_size = 32ull * 1024 * 1024;
	const uint64_t sc = part_size / ss;

	unlink(img);
	int fd = open(img, O_RDWR | O_CREAT | O_TRUNC, 0600);
	assert(fd >= 0);
	assert(ftruncate(fd, (off_t)part_size) == 0);

	FileBlockCtx ctx = {
		.fd = fd,
		.sector_size = ss,
		.sector_count = sc,
		.write_count = 0,
	};
	UsbBlockOps ops = {
		.read = file_read,
		.write = file_write,
		.sync = file_sync,
		.ctx = &ctx,
		.sector_size = ss,
		.sector_count = sc,
	};
	uint64_t block = usb_block_register(&ops);
	assert(block != 0);

	assert(ext_engine_mkfs(block, "BOUNDS") == 0);
	assert(read_sb_le32(&ctx, SB_OFF_LOG_BLOCK_SIZE) == 0); /* 1024-byte blocks */
	assert((read_sb_le32(&ctx, SB_OFF_FEATURES_RO_COMPAT) & EXT4_FRO_COM_METADATA_CSUM) == 0);

	/* Clean baseline: RO mount and usage succeed within partition bounds with 0 writes. */
	uint64_t writes_before = ctx.write_count;
	int err = 0;
	uint64_t vol = ext_engine_mount(block, true, &err);
	assert(vol != 0 && err == 0);
	uint64_t total = 0, free_bytes = 0;
	assert(ext_engine_usage(vol, &total, &free_bytes) == 0);
	assert(total > 0 && total <= part_size);
	assert(free_bytes <= total);
	assert(ext_engine_close(vol) == 0);
	assert(ctx.write_count == writes_before);

	int failures = 0;

	/* Existing arithmetic guard: 2^54 * 1024 overflows uint64_t -> must return error (-EIO). */
	patch_sb_counts(&ctx, 1ull << 54, 1ull << 53);
	failures += expect_usage_rejected_ro(block, &ctx, "u64-mul-overflow-guard");

	/* Existing free > total guard: 2048 > 1024 -> must return error (-EIO). */
	patch_sb_counts(&ctx, 1024ull, 2048ull);
	failures += expect_usage_rejected_ro(block, &ctx, "free-gt-total-guard");

	/* Corrupted 64-bit counts without overflow: 2^32 blocks (4 TiB) / 2^31 free (2 TiB) on 32 MiB. */
	patch_sb_counts(&ctx, 1ull << 32, 1ull << 31);
	failures += check_usage_within_partition_ro(block, &ctx, part_size, "oversized-hi-4TiB-on-32MiB");

	/* Corrupted 32-bit-only counts: 65536 blocks (64 MiB) on 32 MiB partition. */
	patch_sb_counts(&ctx, (part_size / 1024ull) * 2ull, (part_size / 1024ull) * 2ull);
	failures += check_usage_within_partition_ro(block, &ctx, part_size, "oversized-lo-64MiB-on-32MiB");

	usb_block_unregister(block);
	close(fd);
	unlink(img);

	if (failures != 0) {
		printf("FAIL ext_usage_bounds_host_test: %d failures\n", failures);
		return 1;
	}
	printf("PASS ext_usage_bounds_host_test\n");
	return 0;
}
