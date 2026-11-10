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
	/* Read-failure injection over an LBA range (fail_lba_to == 0 disables). */
	uint64_t fail_lba_from;
	uint64_t fail_lba_to;
} FileBlockCtx;

static int file_read(void *ctx, uint64_t lba, void *buf, uint32_t sectors)
{
	FileBlockCtx *f = (FileBlockCtx *)ctx;
	if (f->fail_lba_to > f->fail_lba_from && lba >= f->fail_lba_from &&
	    lba < f->fail_lba_to) {
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

static int file_sync_fail(void *ctx)
{
	(void)ctx;
	errno = EIO;
	return -1;
}

/* Sector containing the first occurrence of marker, or UINT64_MAX. */
static uint64_t find_marker_lba(FileBlockCtx *ctx, const char *marker)
{
	size_t mlen = strlen(marker);
	uint8_t buf[65536 + 256];
	uint64_t total = ctx->sector_count * ctx->sector_size;
	uint64_t off = 0;
	if (mlen == 0) return UINT64_MAX;
	while (off < total) {
		size_t want = sizeof(buf);
		if (total - off < want) want = (size_t)(total - off);
		ssize_t n = pread(ctx->fd, buf, want, (off_t)off);
		if (n <= 0) break;
		for (ssize_t i = 0; i + (ssize_t)mlen <= n; i++) {
			if (memcmp(buf + i, marker, mlen) == 0)
				return (off + (uint64_t)i) / ctx->sector_size;
		}
		if ((size_t)n <= mlen) break;
		off += (uint64_t)n - (mlen - 1);
	}
	return UINT64_MAX;
}

static void check_utf16_to_utf8(void)
{
	char out[64];
	const uint16_t ascii[] = {'A', 'B'};
	assert(ext_utf16_to_utf8(ascii, 2, out, sizeof(out)) == 2);
	assert(memcmp(out, "AB", 2) == 0);

	const uint16_t latin1[] = {0x00E9}; /* é, two bytes in standard UTF-8 */
	assert(ext_utf16_to_utf8(latin1, 1, out, sizeof(out)) == 2);
	assert(memcmp(out, "\xC3\xA9", 2) == 0);

	const uint16_t euro[] = {0x20AC}; /* €, three bytes */
	assert(ext_utf16_to_utf8(euro, 1, out, sizeof(out)) == 3);
	assert(memcmp(out, "\xE2\x82\xAC", 3) == 0);

	const uint16_t emoji[] = {0xD83D, 0xDE00}; /* U+1F600, one 4-byte sequence */
	assert(ext_utf16_to_utf8(emoji, 2, out, sizeof(out)) == 4);
	assert(memcmp(out, "\xF0\x9F\x98\x80", 4) == 0);

	/* Three supplementary characters are 12 bytes, so they fit the 15-byte label. */
	const uint16_t three[] = {0xD83D, 0xDE00, 0xD83D, 0xDE00, 0xD83D, 0xDE00};
	assert(ext_utf16_to_utf8(three, 6, out, sizeof(out)) == 12);

	assert(ext_utf16_to_utf8(emoji, 1, out, sizeof(out)) == -1); /* unpaired high */
	const uint16_t low[] = {0xDE00};
	assert(ext_utf16_to_utf8(low, 1, out, sizeof(out)) == -1); /* unpaired low */
	assert(ext_utf16_to_utf8(emoji, 2, out, 4) == -1);         /* 4 bytes plus NUL overflow */
	assert(ext_utf16_to_utf8(ascii, 0, out, sizeof(out)) == 0);
	assert(out[0] == '\0');
}

int main(void)
{
	const char *img = "ext_host.img";
	const uint32_t ss = 512;
	const uint64_t sc = (32ull * 1024 * 1024) / ss; /* 32 MiB */
	FileBlockCtx ctx;
	check_utf16_to_utf8();
	unlink(img);
	uint64_t block = open_image(img, ss, sc, &ctx);

	assert(ext_engine_probe(block) == 0);
	assert(ext_engine_mkfs(block, "LABEL-IS-TOO-LONG") == -ENAMETOOLONG);
	/* Standard UTF-8 boundaries agree with the Kotlin 15-byte ceiling. */
	assert(ext_engine_mkfs(block, "A\xF0\x9F\x98\x80\xF0\x9F\x98\x80\xF0\x9F\x98\x80") == 0);
	assert(ext_engine_mkfs(block, "\xF0\x9F\x98\x80\xF0\x9F\x98\x80\xF0\x9F\x98\x80\xF0\x9F\x98\x80") ==
	       -ENAMETOOLONG);
	assert(ext_engine_mkfs(block, "HOSTEXT") == 0);
	assert(ext_engine_probe(block) == 1);
	printf("PASS: mkfs + probe\n");

	int err = 0;
	uint64_t vol = ext_engine_mount(block, false, &err);
	assert(vol != 0);
	assert(err == 0);

	/* Metadata-only space query: sane values on a freshly formatted volume. */
	uint64_t total_bytes = 0, free_bytes = 0;
	assert(ext_engine_usage(vol, &total_bytes, &free_bytes) == 0);
	assert(total_bytes > 0 && total_bytes <= sc * ss);
	assert(free_bytes > 0 && free_bytes <= total_bytes);
	printf("PASS: usage total=%llu free=%llu\n",
	       (unsigned long long)total_bytes, (unsigned long long)free_bytes);

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

	/* Allocation is reflected in the free count; the query never grows. */
	{
		uint64_t total_after = 0, free_after = 0;
		assert(ext_engine_usage(vol, &total_after, &free_after) == 0);
		assert(total_after == total_bytes);
		assert(free_after <= free_bytes);
		free_bytes = free_after;
	}

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
	/* Read-only mount answers the same query without any journal/write traffic. */
	assert(ext_engine_usage(vol, &total_bytes, &free_bytes) == 0);
	assert(total_bytes > 0 && total_bytes <= sc * ss);
	assert(free_bytes <= total_bytes);
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
	assert(ext_engine_mkfs(block, "HOSTEXT") == 0);

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
	assert(ext_engine_mkfs(block, "HOSTEXT") == 0);
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

	/* mkfs must report a failed flush instead of claiming success. */
	block = open_image(img, ss, sc, &ctx);
	g_ops.sync = file_sync_fail;
	assert(ext_engine_mkfs(block, "HOSTEXT") == -EIO);
	g_ops.sync = file_sync;
	usb_block_unregister(block);
	close(ctx.fd);
	unlink(img);
	printf("PASS: mkfs reports sync failure (-EIO)\n");

	/* A directory block that cannot be read must fail the listing, never
	 * return a short listing that hides the remaining entries. */
	block = open_image(img, ss, sc, &ctx);
	assert(ext_engine_mkfs(block, "HOSTEXT") == 0);
	err = 0;
	vol = ext_engine_mount(block, false, &err);
	assert(vol != 0);
	{
		char nm[260];
		for (int i = 0; i < 30; i++) {
			memset(nm, 0, sizeof(nm));
			snprintf(nm, sizeof(nm), "/entry_%02d_", i);
			size_t cur = strlen(nm);
			for (size_t j = 0; j < 180; j++) nm[cur + j] = 'q';
			if (i == 29) memcpy(nm + cur + 170, "ZZMARKZZ", 8);
			nm[cur + 180] = '\0';
			size_t w = 0;
			assert(ext_engine_write(vol, nm, 0, "x", 1, true, &w) == 0);
			assert(w == 1);
		}
	}
	assert(ext_engine_sync(vol) == 0);
	assert(ext_engine_close(vol) == 0);

	uint64_t marker_lba = find_marker_lba(&ctx, "ZZMARKZZ");
	assert(marker_lba != UINT64_MAX);

	{
		uint8_t sb[4096];
		uint64_t sb_lba = 1024 / ss;
		uint32_t sb_off = 1024 % ss;
		assert(pread(ctx.fd, sb, ss, (off_t)sb_lba * ss) == (ssize_t)ss);
		uint32_t log_bs = *(uint32_t *)(sb + sb_off + 0x18);
		uint64_t block_bytes = 1024ull << log_bs;
		uint64_t block_sectors = block_bytes / ss;
		assert(block_sectors > 0 && (block_sectors & (block_sectors - 1)) == 0);

		err = 0;
		vol = ext_engine_mount(block, true, &err);
		assert(vol != 0 && err == 0);
		ctx.fail_lba_from = marker_lba & ~(block_sectors - 1);
		ctx.fail_lba_to = ctx.fail_lba_from + block_sectors;
		ExtDirEntry *le = (ExtDirEntry *)0x1;
		size_t lc = 99;
		assert(ext_engine_list(vol, "/", &le, &lc) == -EIO);
		assert(le == NULL);
		assert(lc == 0);
		ctx.fail_lba_from = 0;
		ctx.fail_lba_to = 0;
		assert(ext_engine_list(vol, "/", &le, &lc) == 0);
		assert(lc >= 2);
		free(le);
		assert(ext_engine_close(vol) == 0);
	}
	usb_block_unregister(block);
	close(ctx.fd);
	unlink(img);
	printf("PASS: unreadable directory block reports -EIO, not a short listing\n");

	/* Different sector sizes: mkfs must select a compatible ext4 block size,
	 * and the result must mount and round-trip data. */
	{
		static const uint32_t geom_ss[] = {512, 1024, 2048, 4096};
		for (size_t g = 0; g < sizeof(geom_ss) / sizeof(geom_ss[0]); g++) {
			uint32_t gss = geom_ss[g];
			uint64_t gsc = (8ull * 1024 * 1024) / gss;
			FileBlockCtx gctx;
			uint64_t gb = open_image(img, gss, gsc, &gctx);
			assert(ext_engine_mkfs(gb, "SECTORED") == 0);
			assert(ext_engine_probe(gb) == 1);

			err = 0;
			uint64_t gv = ext_engine_mount(gb, false, &err);
			assert(gv != 0 && err == 0);
			const char *gp = "ext-geom";
			size_t gw = 0;
			assert(ext_engine_write(gv, "/geom.txt", 0, gp, strlen(gp), true,
						&gw) == 0);
			assert(gw == strlen(gp));
			assert(ext_engine_sync(gv) == 0);
			assert(ext_engine_close(gv) == 0);

			err = 0;
			gv = ext_engine_mount(gb, true, &err);
			assert(gv != 0 && err == 0);
			char gr[32] = {0};
			size_t grl = 0;
			assert(ext_engine_read(gv, "/geom.txt", 0, gr, sizeof(gr) - 1,
					       &grl) == 0);
			assert(grl == strlen(gp));
			assert(strcmp(gr, gp) == 0);
			assert(ext_engine_close(gv) == 0);

			usb_block_unregister(gb);
			close(gctx.fd);
			unlink(img);
			printf("PASS: ext mkfs/mount/read/write with %u-byte sectors\n",
			       (unsigned)gss);
		}
	}

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
