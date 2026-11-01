#include "fat_engine.h"
#include "usb_block.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

uint64_t image_block_open(const char *path, uint32_t sector_size, uint64_t sector_count, int create);
void image_block_close(uint64_t handle);

static void fail(const char *msg, int rc)
{
	fprintf(stderr, "FAIL: %s (rc=%d)\n", msg, rc);
	exit(1);
}

static void exercise(const char *img, FatMkfsType type, FatFsKind expect_min, FatFsKind expect_max,
		     uint32_t ss, uint64_t sc)
{
	uint64_t block, vol = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	FatDirEnt ents[32];
	FatStat st;
	size_t n = 0, got = 0, wrote = 0;
	char buf[64];
	int rc;

	unlink(img);
	block = image_block_open(img, ss, sc, 1);
	if (!block) fail("image_block_open", -1);

	rc = fat_engine_mkfs(block, ss, sc, type);
	if (rc != 0) fail("mkfs", rc);

	rc = fat_engine_mount(block, ss, sc, &kind, &vol);
	if (rc != 0) fail("mount", rc);
	if (kind < expect_min || kind > expect_max) {
		fprintf(stderr, "FAIL: kind %d not in [%d,%d]\n", (int)kind, (int)expect_min, (int)expect_max);
		exit(1);
	}

	rc = fat_engine_mkdir(vol, "dir1");
	if (rc != 0) fail("mkdir", rc);

	rc = fat_engine_write_file(vol, "dir1/hello.txt", 0, "hello", 5, &wrote, 5, 1);
	if (rc != 0 || wrote != 5) fail("write", rc);

	rc = fat_engine_stat(vol, "dir1/hello.txt", &st);
	if (rc != 0 || st.size != 5 || st.is_dir) fail("stat", rc);

	memset(buf, 0, sizeof(buf));
	rc = fat_engine_read_file(vol, "dir1/hello.txt", 0, buf, sizeof(buf), &got);
	if (rc != 0 || got != 5 || memcmp(buf, "hello", 5) != 0) fail("read", rc);

	rc = fat_engine_rename(vol, "dir1/hello.txt", "dir1/hi.txt");
	if (rc != 0) fail("rename", rc);

	rc = fat_engine_list(vol, "dir1", ents, 32, &n);
	if (rc != 0 || n < 1) fail("list", rc);

	rc = fat_engine_delete(vol, "dir1/hi.txt");
	if (rc != 0) fail("delete", rc);

	rc = fat_engine_sync(vol);
	if (rc != 0) fail("sync", rc);

	/* Classic FAT rejects > 4 GiB before create */
	if (kind == FAT_KIND_FAT12 || kind == FAT_KIND_FAT16 || kind == FAT_KIND_FAT32) {
		uint64_t too_big = fat_engine_max_file_size(kind) + 1;
		wrote = 0;
		rc = fat_engine_write_file(vol, "huge.bin", 0, "x", 1, &wrote, too_big, 1);
		if (rc == 0) fail("expected size reject", rc);
		rc = fat_engine_stat(vol, "huge.bin", &st);
		if (rc == 0) fail("huge.bin should not exist", rc);
	}

	rc = fat_engine_umount(vol);
	if (rc != 0) fail("umount", rc);
	image_block_close(block);
	unlink(img);
	printf("OK %s kind=%d\n", img, (int)kind);
}

int main(void)
{
	const uint32_t ss = 512;
	/* FM_FAT on ~32 MiB → FAT12 or FAT16 */
	exercise("fat16.img", FAT_MKFS_FAT, FAT_KIND_FAT12, FAT_KIND_FAT16, ss, (32ull * 1024 * 1024) / ss);
	/* FAT32: ~128 MiB */
	exercise("fat32.img", FAT_MKFS_FAT32, FAT_KIND_FAT32, FAT_KIND_FAT32, ss, (128ull * 1024 * 1024) / ss);
	/* exFAT: ~64 MiB */
	exercise("exfat.img", FAT_MKFS_EXFAT, FAT_KIND_EXFAT, FAT_KIND_EXFAT, ss, (64ull * 1024 * 1024) / ss);
	printf("all fat image tests passed\n");
	return 0;
}
