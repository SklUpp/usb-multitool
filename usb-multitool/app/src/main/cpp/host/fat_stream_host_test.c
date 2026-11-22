/* Host regression: sequential FAT chunks must stay in one open FIL and clean up
 * through sync/delete/unmount. The image is 64 MiB so FAT32 mkfs is valid. */
#include "fat_engine.h"
#include "usb_block.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define SECTOR_SIZE 512u
#define SECTOR_COUNT 131072u
#define FILE_SIZE (4u * 1024u * 1024u)
#define CHUNK_SIZE (64u * 1024u)

static uint8_t *g_image;
static int g_fail_write_at = -1;

static int img_read(void *ctx, uint64_t lba, void *buf, uint32_t sectors)
{
	(void)ctx;
	if (lba + sectors > SECTOR_COUNT) return -1;
	memcpy(buf, g_image + lba * SECTOR_SIZE, (size_t)sectors * SECTOR_SIZE);
	return 0;
}

static int img_write(void *ctx, uint64_t lba, const void *buf, uint32_t sectors)
{
	(void)ctx;
	if (g_fail_write_at == 0) {
		g_fail_write_at = -1;
		return -1;
	}
	if (g_fail_write_at > 0) g_fail_write_at--;
	if (lba + sectors > SECTOR_COUNT) return -1;
	memcpy(g_image + lba * SECTOR_SIZE, buf, (size_t)sectors * SECTOR_SIZE);
	return 0;
}

static int img_sync(void *ctx)
{
	(void)ctx;
	return 0;
}

static int fail(const char *what, int rc)
{
	fprintf(stderr, "FAIL %s rc=%d\n", what, rc);
	return 1;
}

static int write_chunks(uint64_t vol, const char *path, const uint8_t *chunk, size_t total)
{
	uint64_t offset = 0;
	while (offset < total) {
		size_t part = total - offset < CHUNK_SIZE ? total - offset : CHUNK_SIZE;
		size_t written = 0;
		int rc = fat_engine_write_file(vol, path, offset, chunk, part, &written, total,
					       offset == 0 ? 2 : 0);
		if (rc != 0) return rc;
		if (written != part) return FAT_ENGINE_CLEANUP_FAILED;
		offset += part;
	}
	return 0;
}

static int check_tail(uint64_t vol, const char *path, const uint8_t *chunk)
{
	uint8_t readback[CHUNK_SIZE];
	size_t read = 0;
	int rc = fat_engine_read_file(vol, path, FILE_SIZE - CHUNK_SIZE, readback,
				      sizeof(readback), &read);
	if (rc != 0) return rc;
	if (read != sizeof(readback) || memcmp(readback, chunk, sizeof(readback)) != 0) {
		return FAT_ENGINE_CLEANUP_FAILED;
	}
	return 0;
}

int main(void)
{
	UsbBlockOps ops = {
		.read = img_read,
		.write = img_write,
		.sync = img_sync,
		.ctx = NULL,
		.sector_size = SECTOR_SIZE,
		.sector_count = SECTOR_COUNT,
	};
	uint8_t chunk[CHUNK_SIZE];
	uint64_t vol = 0;
	uint64_t block = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	FatStat st;
	size_t written = 0;
	int rc;

	g_image = calloc(SECTOR_COUNT, SECTOR_SIZE);
	if (!g_image) return fail("alloc", 0);
	for (size_t i = 0; i < sizeof(chunk); i++) chunk[i] = (uint8_t)(i * 31u);

	block = usb_block_register(&ops);
	rc = fat_engine_mkfs(block, SECTOR_SIZE, SECTOR_COUNT, FAT_MKFS_FAT32, 0);
	if (rc != 0) return fail("mkfs", rc);
	rc = fat_engine_mount(block, SECTOR_SIZE, SECTOR_COUNT, &kind, &vol);
	if (rc != 0) return fail("mount", rc);
	if (kind != FAT_KIND_FAT32) return fail("kind", kind);

	rc = write_chunks(vol, "/stream.bin", chunk, FILE_SIZE);
	if (rc != 0) return fail("chunks", rc);
	rc = fat_engine_sync(vol);
	if (rc != 0) return fail("sync", rc);
	rc = fat_engine_stat(vol, "/stream.bin", &st);
	if (rc != 0) return fail("stat", rc);
	if (st.size != FILE_SIZE) return fail("size", (int)st.size);
	rc = check_tail(vol, "/stream.bin", chunk);
	if (rc != 0) return fail("tail", rc);

	/* A structure-changing op must close the stream before touching the file. */
	rc = fat_engine_write_file(vol, "/open.bin", 0, chunk, CHUNK_SIZE, &written,
				   CHUNK_SIZE, 2);
	if (rc != 0) return fail("open create", rc);
	rc = fat_engine_delete(vol, "/open.bin");
	if (rc != 0) return fail("delete open", rc);
	rc = fat_engine_stat(vol, "/open.bin", &st);
	if (rc == 0) return fail("deleted still present", 0);

	/* sync mid-file closes; the next chunk reopens at the exact offset. */
	rc = fat_engine_write_file(vol, "/mid.bin", 0, chunk, CHUNK_SIZE, &written,
				   CHUNK_SIZE * 2, 2);
	if (rc != 0) return fail("mid create", rc);
	rc = fat_engine_sync(vol);
	if (rc != 0) return fail("mid sync", rc);
	rc = fat_engine_write_file(vol, "/mid.bin", CHUNK_SIZE, chunk, CHUNK_SIZE, &written,
				   CHUNK_SIZE * 2, 0);
	if (rc != 0) return fail("mid append", rc);
	rc = fat_engine_sync(vol);
	if (rc != 0) return fail("mid append sync", rc);
	rc = fat_engine_stat(vol, "/mid.bin", &st);
	if (rc != 0 || st.size != CHUNK_SIZE * 2) return fail("mid size", rc);

	/* A failed chunk aborts the open FIL and removes an exclusive-created file. */
	rc = fat_engine_write_file(vol, "/fail.bin", 0, chunk, CHUNK_SIZE, &written,
				   CHUNK_SIZE * 2, 2);
	if (rc != 0) return fail("fail create", rc);
	g_fail_write_at = 0;
	rc = fat_engine_write_file(vol, "/fail.bin", CHUNK_SIZE, chunk, CHUNK_SIZE, &written,
				   CHUNK_SIZE * 2, 0);
	if (rc == 0) return fail("fail chunk accepted", 0);
	rc = fat_engine_stat(vol, "/fail.bin", &st);
	if (rc == 0) return fail("failed file still present", 0);
	rc = write_chunks(vol, "/recover.bin", chunk, CHUNK_SIZE);
	if (rc != 0) return fail("recover", rc);
	rc = fat_engine_sync(vol);
	if (rc != 0) return fail("recover sync", rc);

	/* unmount alone must close the writer, not discard the last chunk. */
	rc = fat_engine_write_file(vol, "/nosync.bin", 0, chunk, CHUNK_SIZE, &written,
				   CHUNK_SIZE, 2);
	if (rc != 0) return fail("nosync create", rc);
	rc = fat_engine_umount(vol);
	if (rc != 0) return fail("umount", rc);

	/* Remount proves the data and directory entries reached the medium. */
	vol = 0;
	rc = fat_engine_mount(block, SECTOR_SIZE, SECTOR_COUNT, &kind, &vol);
	if (rc != 0) return fail("remount", rc);
	rc = fat_engine_stat(vol, "/stream.bin", &st);
	if (rc != 0 || st.size != FILE_SIZE) return fail("remount size", rc);
	rc = fat_engine_stat(vol, "/nosync.bin", &st);
	if (rc != 0 || st.size != CHUNK_SIZE) return fail("unmount close size", rc);
	rc = check_tail(vol, "/stream.bin", chunk);
	if (rc != 0) return fail("remount tail", rc);
	rc = fat_engine_umount(vol);
	if (rc != 0) return fail("final umount", rc);

	printf("OK FAT streaming: %u bytes, no stale open handle\n", FILE_SIZE);
	free(g_image);
	return 0;
}
