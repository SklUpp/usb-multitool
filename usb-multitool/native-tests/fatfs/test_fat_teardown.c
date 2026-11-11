/* FAT/exFAT teardown tests: close may flush only state that a previous
 * write-path attempt left unsynced, never a clean or read-only volume; pending
 * state is flushed with the first error reported and teardown always completes.
 * Fault injection lives in image_block.c (next N write/sync calls fail). */
#include "ff.h"
#include "diskio.h"
#include "fat_engine.h"

#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

uint64_t image_block_open(const char *path, uint32_t sector_size, uint64_t sector_count, int create);
void image_block_close(uint64_t handle);
void image_block_inject(uint64_t handle, int fail_writes, int fail_syncs);
void image_block_reset_counters(uint64_t handle);
int image_block_write_count(uint64_t handle);
int image_block_sync_count(uint64_t handle);
int image_block_failed_write_lba(uint64_t handle, uint64_t *out_lba);
int image_block_wrote_lba(uint64_t handle, uint64_t lba);

#define FSINFO_FREE_COUNT_OFS 488 /* FSI_Free_Count, from the start of FSInfo sector 1 */

static void fail(const char *msg, int rc)
{
	fprintf(stderr, "FAIL: %s (rc=%d)\n", msg, rc);
	exit(1);
}

static uint64_t mkfs_image(const char *img, FatMkfsType type, uint32_t ss, uint64_t sc)
{
	uint64_t block;
	int rc;

	unlink(img);
	block = image_block_open(img, ss, sc, 1);
	if (!block) fail("image_block_open", -1);
	rc = fat_engine_mkfs(block, ss, sc, type, 0);
	if (rc != 0) fail("mkfs", rc);
	return block;
}

static uint64_t fresh_volume(const char *img, FatMkfsType type, uint32_t ss, uint64_t sc,
			     uint64_t *out_block)
{
	uint64_t block = mkfs_image(img, type, ss, sc);
	uint64_t vol = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	int rc = fat_engine_mount(block, ss, sc, &kind, &vol);
	if (rc != 0) fail("mount", rc);
	*out_block = block;
	return vol;
}

/* Force VirtFS-like "free count unknown" so f_getfree rescans and sets fsi_flag. */
static void patch_fsinfo_free_count(const char *img, uint32_t ss, uint32_t value)
{
	uint8_t b[4] = {
		(uint8_t)(value & 0xFF), (uint8_t)((value >> 8) & 0xFF),
		(uint8_t)((value >> 16) & 0xFF), (uint8_t)((value >> 24) & 0xFF),
	};
	int fd = open(img, O_RDWR);
	if (fd < 0) fail("open image for FSInfo patch", -1);
	if (lseek(fd, (off_t)ss + FSINFO_FREE_COUNT_OFS, SEEK_SET) < 0 ||
	    write(fd, b, sizeof(b)) != (ssize_t)sizeof(b)) {
		close(fd);
		fail("write FSInfo free count", -1);
	}
	close(fd);
}

static uint32_t read_fsinfo_free_count(const char *img, uint32_t ss)
{
	uint8_t b[4] = {0, 0, 0, 0};
	int fd = open(img, O_RDONLY);
	if (fd < 0) fail("open image for FSInfo read", -1);
	if (lseek(fd, (off_t)ss + FSINFO_FREE_COUNT_OFS, SEEK_SET) < 0 ||
	    read(fd, b, sizeof(b)) != (ssize_t)sizeof(b)) {
		close(fd);
		fail("read FSInfo free count", -1);
	}
	close(fd);
	return (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
}

/* Leaves the mounted volume with a dirty FatFs window: mkdir allocates a cluster
 * (dirty FAT window), its first flush write fails, and the abort path frees the
 * cluster through the same window. Returns the sector that stayed dirty. */
static uint64_t dirty_volume(uint64_t block, uint64_t vol)
{
	uint64_t lba = 0;
	int rc;

	image_block_reset_counters(block);
	image_block_inject(block, 1, 0);
	rc = fat_engine_mkdir(vol, "tdir");
	if (rc != (int)FR_DISK_ERR) fail("mkdir should fail on the injected write", rc);
	if (!image_block_failed_write_lba(block, &lba)) fail("no failed write recorded", 0);
	image_block_inject(block, 0, 0);
	image_block_reset_counters(block);
	return lba;
}

/* Read-only browse (which sets the advisory FSInfo flag) must close with no I/O
 * at all: after detach any write or CTRL_SYNC would fail and latch the session. */
static void test_readonly_browse_close_is_clean(void)
{
	const char *img = "teardown_ro.img";
	const uint32_t ss = 512;
	const uint64_t sc = (128ull * 1024 * 1024) / ss;
	uint64_t block, vol;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	uint64_t total = 0, free_bytes = 0;
	uint32_t free_count;
	int rc;

	block = mkfs_image(img, FAT_MKFS_FAT32, ss, sc);
	/* Free count unknown -> mount leaves free_clst invalid -> f_getfree rescans
	 * and sets fsi_flag, exactly the read-only browse path. */
	patch_fsinfo_free_count(img, ss, 0xFFFFFFFFu);
	rc = fat_engine_mount(block, ss, sc, &kind, &vol);
	if (rc != 0) fail("mount read-only volume", rc);
	rc = fat_engine_usage(vol, &total, &free_bytes);
	if (rc != 0) fail("usage query", rc);
	if (total == 0 || free_bytes > total) fail("usage values", rc);

	image_block_reset_counters(block);
	image_block_inject(block, 1, 1); /* any I/O would now fail */
	rc = fat_engine_umount(vol);
	if (rc != 0) fail("read-only close must not perform I/O", rc);
	if (image_block_write_count(block) != 0) fail("read-only close wrote sectors", -1);
	if (image_block_sync_count(block) != 0) fail("read-only close synced transport", -1);
	free_count = read_fsinfo_free_count(img, ss);
	if (free_count != 0xFFFFFFFFu) fail("read-only close rewrote FSInfo", (int)free_count);

	if (fat_engine_umount(vol) != (int)FR_INVALID_OBJECT) fail("second umount must be rejected", -1);
	image_block_close(block);
	unlink(img);
	printf("OK read-only close issues no I/O\n");
}

/* A successful write path already synced; closing afterwards must stay I/O-free
 * (detach after a completed upload must not fail and latch the session). */
static void test_synced_write_close_is_clean(void)
{
	const char *img = "teardown_clean.img";
	const uint32_t ss = 512;
	const uint64_t sc = (128ull * 1024 * 1024) / ss;
	uint64_t block, vol, vol2 = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	size_t wrote = 0, got = 0;
	char buf[4];
	int rc;

	vol = fresh_volume(img, FAT_MKFS_FAT32, ss, sc, &block);
	rc = fat_engine_write_file(vol, "done.txt", 0, "ok", 2, &wrote, 2, 1);
	if (rc != 0 || wrote != 2) fail("write", rc);

	image_block_reset_counters(block);
	image_block_inject(block, 1, 1);
	rc = fat_engine_umount(vol);
	if (rc != 0) fail("close after synced write must not perform I/O", rc);
	if (image_block_write_count(block) != 0) fail("synced close wrote sectors", -1);
	if (image_block_sync_count(block) != 0) fail("synced close synced transport", -1);

	rc = fat_engine_mount(block, ss, sc, &kind, &vol2);
	if (rc != 0) fail("remount after synced close", rc);
	memset(buf, 0, sizeof(buf));
	rc = fat_engine_read_file(vol2, "done.txt", 0, buf, sizeof(buf), &got);
	if (rc != 0 || got != 2 || memcmp(buf, "ok", 2) != 0) fail("data after close", rc);
	rc = fat_engine_umount(vol2);
	if (rc != 0) fail("umount remounted volume", rc);

	image_block_close(block);
	unlink(img);
	printf("OK synced close issues no I/O\n");
}

/* Pending state from a failed write is flushed by sync; the old code only
 * called the block sync and reported success while the window stayed dirty. */
static void test_sync_flushes_fatfs_window(void)
{
	const char *img = "teardown_sync.img";
	const uint32_t ss = 512;
	const uint64_t sc = (128ull * 1024 * 1024) / ss;
	uint64_t block, vol;
	uint64_t lba;
	int rc;

	vol = fresh_volume(img, FAT_MKFS_FAT32, ss, sc, &block);
	lba = dirty_volume(block, vol);

	rc = fat_engine_sync(vol);
	if (rc != 0) fail("sync after dirty window", rc);
	if (image_block_write_count(block) < 1) fail("sync issued no FatFs write", 0);
	if (!image_block_wrote_lba(block, lba)) fail("sync did not rewrite the dirty sector", 0);
	if (image_block_sync_count(block) < 1) fail("sync did not reach the transport", 0);

	rc = fat_engine_umount(vol);
	if (rc != 0) fail("umount after recovery", rc);
	if (fat_engine_sync(vol) != (int)FR_INVALID_OBJECT) fail("sync on torn-down volume", -1);

	image_block_close(block);
	unlink(img);
	printf("OK sync flushes dirty FatFs window\n");
}

/* First error is preserved and the dirty state is retryable. */
static void test_sync_failure_is_retryable(void)
{
	const char *img = "teardown_retry.img";
	const uint32_t ss = 512;
	const uint64_t sc = (128ull * 1024 * 1024) / ss;
	uint64_t block, vol;
	uint64_t lba;
	int rc;

	vol = fresh_volume(img, FAT_MKFS_FAT32, ss, sc, &block);
	lba = dirty_volume(block, vol);

	image_block_inject(block, 1, 0);
	rc = fat_engine_sync(vol);
	if (rc != (int)FR_DISK_ERR) fail("sync must report the injected write failure", rc);

	image_block_inject(block, 0, 0);
	image_block_reset_counters(block);
	rc = fat_engine_sync(vol);
	if (rc != 0) fail("sync retry after failure", rc);
	if (!image_block_wrote_lba(block, lba)) fail("retry did not rewrite the dirty sector", 0);

	rc = fat_engine_umount(vol);
	if (rc != 0) fail("umount after retry", rc);
	image_block_close(block);
	unlink(img);
	printf("OK sync failure reported, retry recovers\n");
}

/* A write path whose final CTRL_SYNC fails leaves wflag/fsi_flag cleared and
 * FA_MODIFIED dropped: the engine's sticky barrier is the only trace left. */
static void make_unconfirmed_barrier(uint64_t block, uint64_t vol)
{
	int rc;

	image_block_reset_counters(block);
	image_block_inject(block, 0, 1); /* writes land; CTRL_SYNC fails */
	rc = fat_engine_mkdir(vol, "bdir");
	if (rc != (int)FR_DISK_ERR) fail("mkdir must report the CTRL_SYNC failure", rc);
	image_block_inject(block, 0, 0);
	image_block_reset_counters(block);
}

/* sync() after a failed CTRL_SYNC must retry the barrier (no data rewrite) and
 * keep reporting failure until the transport confirms it. */
static void test_unconfirmed_barrier_retried_by_sync(void)
{
	const char *img = "teardown_barrier.img";
	const uint32_t ss = 512;
	const uint64_t sc = (128ull * 1024 * 1024) / ss;
	uint64_t block, vol;
	int rc;

	vol = fresh_volume(img, FAT_MKFS_FAT32, ss, sc, &block);
	make_unconfirmed_barrier(block, vol);

	image_block_inject(block, 0, 1);
	rc = fat_engine_sync(vol);
	if (rc != (int)FR_DISK_ERR) fail("sync must retry the unconfirmed barrier", rc);
	if (image_block_write_count(block) != 0) fail("barrier retry must not rewrite data", -1);
	if (image_block_sync_count(block) < 1) fail("sync did not reach the transport", -1);

	image_block_inject(block, 0, 0);
	image_block_reset_counters(block);
	rc = fat_engine_sync(vol);
	if (rc != 0) fail("barrier confirmation retry", rc);
	if (image_block_sync_count(block) < 1) fail("confirmed sync did not reach transport", -1);
	if (image_block_write_count(block) != 0) fail("barrier confirmation wrote data", -1);

	rc = fat_engine_umount(vol);
	if (rc != 0) fail("umount after confirmed barrier", rc);
	image_block_close(block);
	unlink(img);
	printf("OK unconfirmed CTRL_SYNC retried by sync\n");
}

/* close() must not report success while the transport barrier is unconfirmed. */
static void test_unconfirmed_barrier_retried_by_close(void)
{
	const char *img = "teardown_barrier_close.img";
	const uint32_t ss = 512;
	const uint64_t sc = (128ull * 1024 * 1024) / ss;
	uint64_t block, vol, vol2 = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	int rc;

	vol = fresh_volume(img, FAT_MKFS_FAT32, ss, sc, &block);
	make_unconfirmed_barrier(block, vol);

	image_block_inject(block, 0, 1);
	rc = fat_engine_umount(vol);
	if (rc != (int)FR_DISK_ERR) fail("close must retry and report the barrier", rc);
	if (image_block_write_count(block) != 0) fail("close barrier retry rewrote data", -1);
	if (image_block_sync_count(block) < 1) fail("close did not issue the barrier", -1);
	if (fat_engine_umount(vol) != (int)FR_INVALID_OBJECT) fail("failed close must invalidate", -1);

	image_block_inject(block, 0, 0);
	rc = fat_engine_mount(block, ss, sc, &kind, &vol2);
	if (rc != 0) fail("mount after failed close", rc);
	rc = fat_engine_umount(vol2);
	if (rc != 0) fail("umount recovered volume", rc);

	image_block_close(block);
	unlink(img);
	printf("OK unconfirmed CTRL_SYNC retried by close and reported\n");
}

/* A later close confirms the barrier: it must issue CTRL_SYNC, not skip it. */
static void test_unconfirmed_barrier_confirmed_by_close(void)
{
	const char *img = "teardown_barrier_ok.img";
	const uint32_t ss = 512;
	const uint64_t sc = (128ull * 1024 * 1024) / ss;
	uint64_t block, vol;
	int rc;

	vol = fresh_volume(img, FAT_MKFS_FAT32, ss, sc, &block);
	make_unconfirmed_barrier(block, vol);

	rc = fat_engine_umount(vol);
	if (rc != 0) fail("close must confirm the pending barrier", rc);
	if (image_block_sync_count(block) < 1) fail("close did not issue the barrier", -1);
	if (image_block_write_count(block) != 0) fail("barrier confirmation rewrote data", -1);

	image_block_close(block);
	unlink(img);
	printf("OK unconfirmed CTRL_SYNC confirmed by close\n");
}

/* Unmount flushes pending state, then invalidates the handle: no second
 * teardown, no I/O on the unbound drive, and the engine still mounts. */
static void test_umount_flushes_and_invalidates(void)
{
	const char *img = "teardown_close.img";
	const uint32_t ss = 512;
	const uint64_t sc = (128ull * 1024 * 1024) / ss;
	uint64_t block, vol, vol2 = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	uint64_t lba;
	int rc;

	vol = fresh_volume(img, FAT_MKFS_FAT32, ss, sc, &block);
	lba = dirty_volume(block, vol);

	rc = fat_engine_umount(vol);
	if (rc != 0) fail("umount must flush the dirty window", rc);
	if (!image_block_wrote_lba(block, lba)) fail("umount did not flush the dirty sector", 0);
	if (image_block_sync_count(block) < 1) fail("umount did not reach the transport", 0);

	if (fat_engine_umount(vol) != (int)FR_INVALID_OBJECT) fail("second umount must be rejected", -1);
	image_block_reset_counters(block);
	if (fat_engine_sync(vol) != (int)FR_INVALID_OBJECT) fail("sync after umount must be rejected", -1);
	if (image_block_write_count(block) != 0) fail("torn-down volume still wrote", -1);

	rc = fat_engine_mount(block, ss, sc, &kind, &vol2);
	if (rc != 0) fail("mount after umount", rc);
	rc = fat_engine_umount(vol2);
	if (rc != 0) fail("umount second volume", rc);

	image_block_close(block);
	unlink(img);
	printf("OK umount flushes and invalidates\n");
}

/* Data at risk (dirty window) must be reported if the flush cannot complete;
 * teardown still happens and the engine recovers. */
static void test_umount_failure_still_tears_down(void)
{
	const char *img = "teardown_fail.img";
	const uint32_t ss = 512;
	const uint64_t sc = (32ull * 1024 * 1024) / ss;
	uint64_t block, vol, vol2 = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	uint64_t lba;
	int writes;
	int rc;

	vol = fresh_volume(img, FAT_MKFS_FAT, ss, sc, &block);
	lba = dirty_volume(block, vol);

	/* Window writes succeed; the trailing CTRL_SYNC fails. */
	image_block_inject(block, 0, 1);
	rc = fat_engine_umount(vol);
	if (rc != (int)FR_DISK_ERR) fail("umount must report the transport failure", rc);
	if (!image_block_wrote_lba(block, lba)) fail("umount did not flush before failing", 0);

	if (fat_engine_umount(vol) != (int)FR_INVALID_OBJECT) fail("failed umount must invalidate", -1);
	writes = image_block_write_count(block);
	if (fat_engine_sync(vol) != (int)FR_INVALID_OBJECT) fail("sync after failed umount", -1);
	if (image_block_write_count(block) != writes) fail("torn-down volume still wrote", -1);

	image_block_inject(block, 0, 0);
	rc = fat_engine_mount(block, ss, sc, &kind, &vol2);
	if (rc != 0) fail("mount after failed umount", rc);
	rc = fat_engine_umount(vol2);
	if (rc != 0) fail("umount recovered volume", rc);

	image_block_close(block);
	unlink(img);
	printf("OK umount failure reported, teardown complete\n");
}

/* The flush write itself failing at close must surface (not a silent success). */
static void test_umount_write_failure_still_tears_down(void)
{
	const char *img = "teardown_wfail.img";
	const uint32_t ss = 512;
	const uint64_t sc = (128ull * 1024 * 1024) / ss;
	uint64_t block, vol, vol2 = 0;
	FatFsKind kind = FAT_KIND_UNKNOWN;
	uint64_t lba;
	int rc;

	vol = fresh_volume(img, FAT_MKFS_FAT32, ss, sc, &block);
	lba = dirty_volume(block, vol);

	image_block_inject(block, 1, 0);
	rc = fat_engine_umount(vol);
	if (rc != (int)FR_DISK_ERR) fail("umount must report the flush write failure", rc);
	if (image_block_wrote_lba(block, lba)) fail("failed flush must not report a write", -1);

	if (fat_engine_umount(vol) != (int)FR_INVALID_OBJECT) fail("failed umount must invalidate", -1);

	image_block_inject(block, 0, 0);
	rc = fat_engine_mount(block, ss, sc, &kind, &vol2);
	if (rc != 0) fail("mount after write-failed umount", rc);
	rc = fat_engine_umount(vol2);
	if (rc != 0) fail("umount recovered volume", rc);

	image_block_close(block);
	unlink(img);
	printf("OK close flush write failure reported, teardown complete\n");
}

/* sync_fs must report FSInfo sector write failure and keep fsi_flag for a
 * retry (upstream cleared the flag first and ignored the write result). */
static void test_sync_fsinfo_failure_is_reported(void)
{
	const char *img = "teardown_fsinfo.img";
	const uint32_t ss = 512;
	const uint64_t sc = (128ull * 1024 * 1024) / ss;
	uint64_t block;
	FATFS fs;
	FATFS *fsp = NULL;
	DWORD nfree = 0;
	int rc;

	block = mkfs_image(img, FAT_MKFS_FAT32, ss, sc);
	patch_fsinfo_free_count(img, ss, 0xFFFFFFFFu);
	fat_diskio_bind(0, block, ss, sc);
	rc = f_mount(&fs, "0:", 1);
	if (rc != (int)FR_OK) fail("raw mount", rc);
	rc = f_getfree("0:", &nfree, &fsp);
	if (rc != (int)FR_OK || fsp == NULL) fail("raw f_getfree", rc);

	image_block_reset_counters(block);
	image_block_inject(block, 1, 0);
	rc = f_sync_volume(&fs);
	if (rc != (int)FR_DISK_ERR) fail("FSInfo write failure must be reported", rc);

	image_block_inject(block, 0, 0);
	image_block_reset_counters(block);
	rc = f_sync_volume(&fs);
	if (rc != (int)FR_OK) fail("FSInfo retry", rc);
	if (!image_block_wrote_lba(block, 1)) fail("retry did not rewrite the FSInfo sector", 0);

	rc = f_mount(NULL, "0:", 0);
	if (rc != (int)FR_OK) fail("raw umount", rc);
	fat_diskio_unbind(0);
	image_block_close(block);
	unlink(img);
	printf("OK sync reports FSInfo write failure and retries\n");
}

int main(void)
{
	test_readonly_browse_close_is_clean();
	test_synced_write_close_is_clean();
	test_sync_flushes_fatfs_window();
	test_sync_failure_is_retryable();
	test_unconfirmed_barrier_retried_by_sync();
	test_unconfirmed_barrier_retried_by_close();
	test_unconfirmed_barrier_confirmed_by_close();
	test_umount_flushes_and_invalidates();
	test_umount_failure_still_tears_down();
	test_umount_write_failure_still_tears_down();
	test_sync_fsinfo_failure_is_reported();
	printf("all fat teardown tests passed\n");
	return 0;
}
