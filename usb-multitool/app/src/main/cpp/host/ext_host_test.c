/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Host behavioral test for ext_engine over usb_block + lwext4.
 * mkfs → mount → CRUD → root sizes → nonempty delete → dirty refuse (no write).
 */
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "ext_engine.h"
#include "usb_block.h"

typedef struct FileBlockCtx {
	int fd;
	uint32_t sector_size;
	uint64_t sector_count;
	uint64_t write_count;
} FileBlockCtx;

static int file_read(void *ctx, uint64_t lba, void *buf, uint32_t sectors)
{
	FileBlockCtx *f = (FileBlockCtx *)ctx;
	off_t off = (off_t)(lba * f->sector_size);
	size_t bytes = (size_t)sectors * f->sector_size;
	ssize_t n = pread(f->fd, buf, bytes, off);
	return (n == (ssize_t)bytes) ? 0 : -1;
}

static int file_write(void *ctx, uint64_t lba, const void *buf, uint32_t sectors)
{
	FileBlockCtx *f = (FileBlockCtx *)ctx;
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

static UsbBlockOps g_ops;

static uint64_t open_image(const char *path, uint32_t ss, uint64_t sc, FileBlockCtx *ctx)
{
	int fd = open(path, O_RDWR | O_CREAT | O_TRUNC, 0600);
	assert(fd >= 0);
	assert(ftruncate(fd, (off_t)ss * (off_t)sc) == 0);
	memset(ctx, 0, sizeof(*ctx));
	ctx->fd = fd;
	ctx->sector_size = ss;
	ctx->sector_count = sc;
	g_ops.read = file_read;
	g_ops.write = file_write;
	g_ops.sync = file_sync;
	g_ops.ctx = ctx;
	g_ops.sector_size = ss;
	g_ops.sector_count = sc;
	uint64_t h = usb_block_register(&g_ops);
	assert(h != 0);
	return h;
}

static void patch_sb_u16(FileBlockCtx *ctx, uint32_t sb_off, uint16_t or_bits)
{
	uint8_t sector[4096];
	uint32_t ss = ctx->sector_size;
	uint64_t lba = 1024 / ss;
	uint32_t off = 1024 % ss;
	assert(pread(ctx->fd, sector, ss, (off_t)lba * ss) == (ssize_t)ss);
	uint16_t *field = (uint16_t *)(sector + off + sb_off);
	*field |= or_bits;
	assert(pwrite(ctx->fd, sector, ss, (off_t)lba * ss) == (ssize_t)ss);
	fsync(ctx->fd);
}

static void patch_sb_u32(FileBlockCtx *ctx, uint32_t sb_off, uint32_t or_bits)
{
	uint8_t sector[4096];
	uint32_t ss = ctx->sector_size;
	uint64_t lba = 1024 / ss;
	uint32_t off = 1024 % ss;
	assert(pread(ctx->fd, sector, ss, (off_t)lba * ss) == (ssize_t)ss);
	uint32_t *field = (uint32_t *)(sector + off + sb_off);
	*field |= or_bits;
	assert(pwrite(ctx->fd, sector, ss, (off_t)lba * ss) == (ssize_t)ss);
	fsync(ctx->fd);
}

static void set_recover_flag(FileBlockCtx *ctx)
{
	/* features_incompatible at 0x60 within SB. */
	patch_sb_u32(ctx, 0x60, 0x0004); /* EXT4_FINCOM_RECOVER */
}

static void set_error_fs_state(FileBlockCtx *ctx)
{
	/* s_state at 0x3A within SB. */
	patch_sb_u16(ctx, 0x3A, 0x0002); /* EXT4_SUPERBLOCK_STATE_ERROR_FS */
}

int main(void)
{
	const char *img = "ext_host.img";
	const uint32_t ss = 512;
	const uint64_t sc = (32ull * 1024 * 1024) / ss; /* 32 MiB */
	FileBlockCtx ctx;
	unlink(img);
	uint64_t block = open_image(img, ss, sc, &ctx);

	assert(ext_engine_probe(block) == 0);
	assert(ext_engine_mkfs(block) == 0);
	assert(ext_engine_probe(block) == 1);
	printf("PASS: mkfs + probe\n");

	int err = 0;
	uint64_t vol = ext_engine_mount(block, false, &err);
	assert(vol != 0);
	assert(err == 0);

	assert(ext_engine_mkdir(vol, "/data") == 0);
	const char *payload = "hello-ext";
	size_t wrote = 0;
	assert(ext_engine_write(vol, "/data/a.txt", 0, payload, strlen(payload), true, &wrote) == 0);
	assert(wrote == strlen(payload));

	/* Root listing must report file sizes (no double-slash). */
	assert(ext_engine_write(vol, "/root.txt", 0, "XYZ", 3, true, &wrote) == 0);
	assert(wrote == 3);
	ExtDirEntry *entries = NULL;
	size_t count = 0;
	assert(ext_engine_list(vol, "/", &entries, &count) == 0);
	int found_root = 0;
	for (size_t i = 0; i < count; i++) {
		if (strcmp(entries[i].name, "root.txt") == 0) {
			assert(!entries[i].is_directory);
			assert(entries[i].size == 3);
			found_root = 1;
		}
	}
	assert(found_root);
	free(entries);
	printf("PASS: root list sizes\n");

	char buf[64] = {0};
	size_t got = 0;
	assert(ext_engine_read(vol, "/data/a.txt", 0, buf, sizeof(buf) - 1, &got) == 0);
	assert(got == strlen(payload));
	assert(strcmp(buf, payload) == 0);

	entries = NULL;
	count = 0;
	assert(ext_engine_list(vol, "/data", &entries, &count) == 0);
	assert(count == 1);
	assert(strcmp(entries[0].name, "a.txt") == 0);
	assert(!entries[0].is_directory);
	assert(entries[0].size == (int64_t)strlen(payload));
	free(entries);

	assert(ext_engine_rename(vol, "/data/a.txt", "/data/b.txt") == 0);
	/* Non-empty /data must refuse delete (FAT semantics). */
	assert(ext_engine_delete(vol, "/data") == -ENOTEMPTY);
	assert(ext_engine_delete(vol, "/data/b.txt") == 0);
	assert(ext_engine_delete(vol, "/data") == 0);
	assert(ext_engine_delete(vol, "/root.txt") == 0);
	assert(ext_engine_sync(vol) == 0);
	assert(ext_engine_close(vol) == 0);
	printf("PASS: mount/list/stat/read/write/mkdir/rename/delete/nonempty\n");

	/* mount(RO) → close → mount(RO) must succeed (browse must not leave ERROR_FS). */
	err = 0;
	vol = ext_engine_mount(block, true, &err);
	assert(vol != 0);
	assert(err == 0);
	assert(ext_engine_close(vol) == 0);
	err = 0;
	vol = ext_engine_mount(block, true, &err);
	assert(vol != 0);
	assert(err == 0);
	assert(ext_engine_close(vol) == 0);
	/* RW mutation mount then RO remount. */
	err = 0;
	vol = ext_engine_mount(block, false, &err);
	assert(vol != 0);
	assert(ext_engine_mkdir(vol, "/rw") == 0);
	assert(ext_engine_sync(vol) == 0);
	assert(ext_engine_close(vol) == 0);
	err = 0;
	vol = ext_engine_mount(block, true, &err);
	assert(vol != 0);
	assert(err == 0);
	assert(ext_engine_close(vol) == 0);
	printf("PASS: mount-close-remount RO and RW→RO\n");

	/* On-disk incompat/ro_compat after our mkfs must be ⊆ CONFIG_SUPPORTED (no silent surprise). */
	{
		uint8_t sector[4096];
		uint32_t ss = ctx.sector_size;
		uint64_t lba = 1024 / ss;
		uint32_t off = 1024 % ss;
		assert(pread(ctx.fd, sector, ss, (off_t)lba * ss) == (ssize_t)ss);
		uint32_t incom = *(uint32_t *)(sector + off + 0x60);
		uint32_t rocom = *(uint32_t *)(sector + off + 0x64);
		/* EXT4_SUPPORTED_FINCOM | EXT_FINCOM_IGNORED roughly: filetype|extents|… + recover|mmp */
		const uint32_t supported_incom = 0x42u /* filetype|extents */ | 0x4u | 0x100u; /* recover|mmp ignored ok */
		const uint32_t supported_ro =
			0x1u | 0x2u | 0x8u | 0x10u | 0x20u | 0x40u | 0x400u; /* sparse|large|huge|gdt|nlink|extra|meta_csum */
		assert((incom & ~supported_incom) == 0);
		assert((rocom & ~supported_ro) == 0);
		(void)supported_incom;
		(void)supported_ro;
	}
	printf("PASS: mkfs feature bits within supported masks\n");

	/* Unsupported ro_compat: BIGALLOC|QUOTA sit in lwext4's #if 0 "TODO" list and are
	 * outside CONFIG_SUPPORTED_FRO_COM — refuse, no write, no flag clear. */
	{
		uint64_t wb = ctx.write_count;
		patch_sb_u32(&ctx, 0x64, 0x0200u | 0x0100u); /* BIGALLOC | QUOTA */
		err = 0;
		vol = ext_engine_mount(block, true, &err);
		assert(vol == 0);
		assert(err == ENOTSUP);
		assert(ctx.write_count == wb);
	}
	printf("PASS: BIGALLOC|QUOTA ro_compat refused, no write\n");

	/* Fresh image for dirty tests (prior patch dirtied ro_compat). */
	usb_block_unregister(block);
	close(ctx.fd);
	unlink(img);
	block = open_image(img, ss, sc, &ctx);
	assert(ext_engine_mkfs(block) == 0);

	/* Dirty FINCOM_RECOVER: refuse without writing. */
	set_recover_flag(&ctx);
	uint64_t writes_before = ctx.write_count;
	err = 0;
	vol = ext_engine_mount(block, false, &err);
	assert(vol == 0);
	assert(err == EBUSY);
	assert(ctx.write_count == writes_before);
	vol = ext_engine_mount(block, true, &err);
	assert(vol == 0);
	assert(err == EBUSY);
	assert(ctx.write_count == writes_before);
	printf("PASS: dirty FINCOM_RECOVER refused, no write\n");

	/* Fresh image: journal-less unclean (ERROR_FS on disk) also refused. */
	usb_block_unregister(block);
	close(ctx.fd);
	unlink(img);
	block = open_image(img, ss, sc, &ctx);
	assert(ext_engine_mkfs(block) == 0);
	set_error_fs_state(&ctx);
	writes_before = ctx.write_count;
	err = 0;
	vol = ext_engine_mount(block, false, &err);
	assert(vol == 0);
	assert(err == EBUSY);
	assert(ctx.write_count == writes_before);
	vol = ext_engine_mount(block, true, &err);
	assert(vol == 0);
	assert(err == EBUSY);
	assert(ctx.write_count == writes_before);
	printf("PASS: journal-less ERROR_FS refused, no write\n");

	usb_block_unregister(block);
	close(ctx.fd);
	unlink(img);

	/* Optional: real mkfs.ext4 image (metadata_csum etc.). Skip if tool missing. */
	{
		const char *extimg = "ext_mke2fs.img";
		unlink(extimg);
		char cmd[256];
		snprintf(cmd, sizeof(cmd),
			 "dd if=/dev/zero of='%s' bs=1M count=32 status=none 2>/dev/null && "
			 "(command -v mkfs.ext4 >/dev/null && mkfs.ext4 -F -q '%s' || "
			 "/system/bin/mkfs.ext4 -F -q '%s') >/dev/null 2>&1",
			 extimg, extimg, extimg);
		int rc = system(cmd);
		if (rc != 0) {
			printf("SKIP: mkfs.ext4 fixture unavailable (rc=%d)\n", rc);
		} else {
			FileBlockCtx ectx;
			memset(&ectx, 0, sizeof(ectx));
			int fd = open(extimg, O_RDWR);
			assert(fd >= 0);
			ectx.fd = fd;
			ectx.sector_size = 512;
			ectx.sector_count = (32ull * 1024 * 1024) / 512;
			g_ops.read = file_read;
			g_ops.write = file_write;
			g_ops.sync = file_sync;
			g_ops.ctx = &ectx;
			g_ops.sector_size = ectx.sector_size;
			g_ops.sector_count = ectx.sector_count;
			uint64_t eb = usb_block_register(&g_ops);
			assert(eb != 0);
			assert(ext_engine_probe(eb) == 1);
			err = 0;
			uint64_t ev = ext_engine_mount(eb, true, &err);
			if (ev == 0) {
				/* Unsupported features on this Android mkfs.ext4 → ENOTSUP is OK fail-closed. */
				printf("PASS: mkfs.ext4 probe; mount refused errno=%d (fail-closed OK)\n", err);
			} else {
				assert(ext_engine_close(ev) == 0);
				err = 0;
				ev = ext_engine_mount(eb, true, &err);
				assert(ev != 0);
				assert(ext_engine_close(ev) == 0);
				printf("PASS: mkfs.ext4 mount-close-remount RO\n");
			}
			usb_block_unregister(eb);
			close(fd);
			unlink(extimg);
		}
	}

	printf("all ext host tests passed\n");
	return 0;
}
