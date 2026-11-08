#include "fat_engine.h"
#include "usb_block.h"
#include "ff.h"
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

	/* Root directory stat must succeed (synthetic for bare "/"). */
	rc = fat_engine_stat(vol, "/", &st);
	if (rc != 0 || !st.is_dir || st.size != 0) fail("root stat", rc);
	rc = fat_engine_stat(vol, "", &st);
	if (rc != 0 || !st.is_dir) fail("empty-path root stat", rc);

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

	/* Classic FAT rejects > 4 GiB before create with distinct code. */
	if (kind == FAT_KIND_FAT12 || kind == FAT_KIND_FAT16 || kind == FAT_KIND_FAT32) {
		uint64_t too_big = fat_engine_max_file_size(kind) + 1;
		wrote = 0;
		rc = fat_engine_write_file(vol, "huge.bin", 0, "x", 1, &wrote, too_big, 1);
		if (rc != FAT_ENGINE_FILE_TOO_LARGE) {
			fprintf(stderr, "FAIL: expected FILE_TOO_LARGE got %d\n", rc);
			exit(1);
		}
		if (wrote != 0) fail("too-large wrote bytes", (int)wrote);
		rc = fat_engine_stat(vol, "huge.bin", &st);
		if (rc == 0) fail("huge.bin should not exist", rc);

		/* Genuine FR_INVALID_PARAMETER must not map as too-large. */
		rc = fat_engine_write_file(vol, NULL, 0, "x", 1, &wrote, 1, 1);
		if (rc != (int)19 /* FR_INVALID_PARAMETER */) {
			fprintf(stderr, "FAIL: null path expected INVALID_PARAMETER got %d\n", rc);
			exit(1);
		}
	}

	rc = fat_engine_umount(vol);
	if (rc != 0) fail("umount", rc);
	image_block_close(block);
	unlink(img);
	printf("OK %s kind=%d ss=%u\n", img, (int)kind, (unsigned)ss);
}

/* Directory listing must return >256 entries without silent truncation. */
static void test_list_over_256(void)
{
	const char *img = "list256.img";
	const uint32_t ss = 512;
	const uint64_t sc = (64ull * 1024 * 1024) / ss;
	uint64_t block, vol = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	FatDirEnt *ents = NULL;
	size_t n = 0, wrote = 0;
	char name[32];
	int rc;
	const int want = 300;

	unlink(img);
	block = image_block_open(img, ss, sc, 1);
	if (!block) fail("list256 open", -1);
	rc = fat_engine_mkfs(block, ss, sc, FAT_MKFS_FAT32);
	if (rc != 0) fail("list256 mkfs", rc);
	rc = fat_engine_mount(block, ss, sc, &kind, &vol);
	if (rc != 0) fail("list256 mount", rc);

	for (int i = 0; i < want; i++) {
		snprintf(name, sizeof(name), "f%04d.txt", i);
		wrote = 0;
		rc = fat_engine_write_file(vol, name, 0, "x", 1, &wrote, 1, 1);
		if (rc != 0 || wrote != 1) fail("list256 write", rc);
	}

	/* Probe count with null buffer (cap=0). */
	n = 0;
	rc = fat_engine_list(vol, "/", NULL, 0, &n);
	if (rc != 0) fail("list256 probe", rc);
	if (n < (size_t)want) {
		fprintf(stderr, "FAIL: list probe count %zu < %d\n", n, want);
		exit(1);
	}

	ents = calloc(n, sizeof(*ents));
	if (!ents) fail("list256 calloc", -1);
	rc = fat_engine_list(vol, "/", ents, n, &n);
	if (rc != 0) fail("list256 full", rc);
	if (n < (size_t)want) {
		fprintf(stderr, "FAIL: list full count %zu < %d\n", n, want);
		exit(1);
	}
	free(ents);

	fat_engine_umount(vol);
	image_block_close(block);
	unlink(img);
	printf("OK list >256 entries (%d)\n", want);
}

/* Fill a tiny volume until a write truncates; short write must error. */
static void test_short_write(void)
{
	const char *img = "short.img";
	/* ~1.5 MiB FAT12/16 — small enough to fill quickly. */
	const uint32_t ss = 512;
	const uint64_t sc = (1536ull * 1024) / ss;
	uint64_t block, vol = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	size_t wrote = 0;
	char chunk[4096];
	char name[32];
	int rc;
	int filled = 0;

	memset(chunk, 'A', sizeof(chunk));
	unlink(img);
	block = image_block_open(img, ss, sc, 1);
	if (!block) fail("short open", -1);
	rc = fat_engine_mkfs(block, ss, sc, FAT_MKFS_FAT);
	if (rc != 0) fail("short mkfs", rc);
	rc = fat_engine_mount(block, ss, sc, &kind, &vol);
	if (rc != 0) fail("short mount", rc);

	for (int i = 0; i < 512; i++) {
		snprintf(name, sizeof(name), "b%03d.bin", i);
		wrote = 0;
		rc = fat_engine_write_file(vol, name, 0, chunk, sizeof(chunk), &wrote, sizeof(chunk), 1);
		if (rc != 0) {
			/* Expect disk-full style failure (FR_DISK_ERR=1 or FR_DENIED=7). */
			if (wrote > 0 && wrote < sizeof(chunk) && rc != 1) {
				fprintf(stderr, "FAIL: short write rc=%d wrote=%zu\n", rc, wrote);
				exit(1);
			}
			if (rc == 0) fail("short write returned OK", rc);
			filled = 1;
			break;
		}
		if (wrote != sizeof(chunk)) {
			if (rc == 0) fail("short write returned OK with partial", (int)wrote);
			filled = 1;
			break;
		}
	}
	if (!filled) fail("could not fill volume for short-write test", 0);

	fat_engine_umount(vol);
	image_block_close(block);
	unlink(img);
	printf("OK short-write surfaced as error\n");
}

/* FORMAT_PARTITION over a partition that already holds a filesystem: format
 * must produce a mountable FAT volume with no stale entries, and the result
 * must accept a write/read round trip. */
static void test_format_over_existing_content(void)
{
	const char *img = "reformat.img";
	const uint32_t ss = 512;
	const uint64_t sc = (32ull * 1024 * 1024) / ss;
	uint64_t block, vol = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	FatStat st;
	size_t wrote = 0, got = 0;
	char buf[32];
	int rc;

	unlink(img);
	block = image_block_open(img, ss, sc, 1);
	if (!block) fail("reformat open", -1);
	rc = fat_engine_mkfs(block, ss, sc, FAT_MKFS_FAT);
	if (rc != 0) fail("reformat initial mkfs", rc);
	rc = fat_engine_mount(block, ss, sc, &kind, &vol);
	if (rc != 0) fail("reformat initial mount", rc);
	rc = fat_engine_write_file(vol, "stale.txt", 0, "old", 3, &wrote, 3, 1);
	if (rc != 0 || wrote != 3) fail("reformat stale write", rc);
	rc = fat_engine_umount(vol);
	if (rc != 0) fail("reformat initial umount", rc);
	image_block_close(block);

	/* Re-open without create: format over the existing content in place. */
	block = image_block_open(img, ss, sc, 0);
	if (!block) fail("reformat reopen", -1);
	rc = fat_engine_mkfs(block, ss, sc, FAT_MKFS_FAT); /* Formatter: FilesystemId.FAT */
	if (rc != 0) fail("reformat mkfs", rc);
	rc = fat_engine_mount(block, ss, sc, &kind, &vol);
	if (rc != 0) fail("reformat mount", rc);
	if (kind != FAT_KIND_FAT12 && kind != FAT_KIND_FAT16) {
		fprintf(stderr, "FAIL: reformat produced kind %d\n", (int)kind);
		exit(1);
	}
	if (fat_engine_stat(vol, "stale.txt", &st) == 0) fail("stale.txt survived format", 0);

	rc = fat_engine_write_file(vol, "hello.txt", 0, "hello", 5, &wrote, 5, 1);
	if (rc != 0 || wrote != 5) fail("reformat write", rc);
	memset(buf, 0, sizeof(buf));
	rc = fat_engine_read_file(vol, "hello.txt", 0, buf, sizeof(buf), &got);
	if (rc != 0 || got != 5 || memcmp(buf, "hello", 5) != 0) fail("reformat read", rc);

	rc = fat_engine_umount(vol);
	if (rc != 0) fail("reformat umount", rc);
	image_block_close(block);
	unlink(img);
	printf("OK format over existing content (kind=%d)\n", (int)kind);
}
/* Over-long path must refuse before any FatFs op so a truncated name cannot
 * create/delete a shorter neighbor that already exists. */
static void test_path_overflow_preserves_neighbor(void)
{
	const char *img = "pathovf.img";
	const uint32_t ss = 512;
	const uint64_t sc = (32ull * 1024 * 1024) / ss;
	uint64_t block, vol = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	FatStat st;
	size_t wrote = 0, got = 0;
	char neighbor[] = "keep.txt";
	char long_path[600];
	char buf[16];
	int rc;
	size_t i;

	unlink(img);
	block = image_block_open(img, ss, sc, 1);
	if (!block) fail("pathovf open", -1);
	/* Same geometry as exercise(fat16.img): FAT32 aborts (FR_MKFS_ABORTED=14) on 32 MiB. */
	rc = fat_engine_mkfs(block, ss, sc, FAT_MKFS_FAT);
	if (rc != 0) fail("pathovf mkfs", rc);
	rc = fat_engine_mount(block, ss, sc, &kind, &vol);
	if (rc != 0) fail("pathovf mount", rc);

	rc = fat_engine_write_file(vol, neighbor, 0, "SAFE", 4, &wrote, 4, 1);
	if (rc != 0 || wrote != 4) fail("pathovf neighbor write", rc);

	/* "0:/" + path must exceed 512. Build a single-component path of 'A's. */
	long_path[0] = '/';
	for (i = 1; i < sizeof(long_path) - 1; i++) long_path[i] = 'A';
	long_path[sizeof(long_path) - 1] = '\0';

	wrote = 0;
	rc = fat_engine_write_file(vol, long_path, 0, "X", 1, &wrote, 1, 1);
	if (rc != (int)FR_INVALID_NAME) {
		fprintf(stderr, "FAIL: pathovf write expected INVALID_NAME got %d\n", rc);
		exit(1);
	}
	if (wrote != 0) fail("pathovf write leaked bytes", (int)wrote);

	rc = fat_engine_delete(vol, long_path);
	if (rc != (int)FR_INVALID_NAME) {
		fprintf(stderr, "FAIL: pathovf delete expected INVALID_NAME got %d\n", rc);
		exit(1);
	}

	rc = fat_engine_stat(vol, neighbor, &st);
	if (rc != 0 || st.size != 4) fail("pathovf neighbor missing after overflow", rc);
	memset(buf, 0, sizeof(buf));
	rc = fat_engine_read_file(vol, neighbor, 0, buf, sizeof(buf), &got);
	if (rc != 0 || got != 4 || memcmp(buf, "SAFE", 4) != 0) fail("pathovf neighbor corrupted", rc);

	rc = fat_engine_umount(vol);
	if (rc != 0) fail("pathovf umount", rc);
	image_block_close(block);
	unlink(img);
	printf("OK path overflow preserves neighbor\n");
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
	/* 4096-byte sector coverage (exFAT accepts smaller 4K volumes than FAT32). */
	exercise("exfat_4k.img", FAT_MKFS_EXFAT, FAT_KIND_EXFAT, FAT_KIND_EXFAT, 4096,
		 (64ull * 1024 * 1024) / 4096);

	test_list_over_256();
	test_short_write();
	test_format_over_existing_content();
	test_path_overflow_preserves_neighbor();

	printf("all fat image tests passed\n");
	return 0;
}
