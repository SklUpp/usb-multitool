#include "usb_block.h"

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define IMAGE_WRITE_LOG 32

typedef struct ImageCtx {
	int fd;
	uint32_t sector_size;
	uint64_t sector_count;
	UsbBlockOps ops;
	int fail_writes;                 /* remaining injected write failures */
	int fail_syncs;                  /* remaining injected sync failures */
	int writes;                      /* write calls attempted */
	int syncs;                       /* sync calls attempted */
	int write_log_n;                 /* successfully written LBAs since reset */
	uint64_t write_log[IMAGE_WRITE_LOG];
	int failed_write_valid;
	uint64_t failed_write_lba;
} ImageCtx;

static int image_read(void *ctx, uint64_t lba, void *buf, uint32_t sectors)
{
	ImageCtx *img = ctx;
	size_t bytes = (size_t)sectors * img->sector_size;
	off_t off = (off_t)(lba * img->sector_size);
	uint8_t *p = buf;
	size_t got = 0;
	if (lseek(img->fd, off, SEEK_SET) < 0) return -1;
	while (got < bytes) {
		ssize_t n = read(img->fd, p + got, bytes - got);
		if (n < 0) return -1;
		if (n == 0) {
			memset(p + got, 0, bytes - got);
			break;
		}
		got += (size_t)n;
	}
	return 0;
}

static int image_write(void *ctx, uint64_t lba, const void *buf, uint32_t sectors)
{
	ImageCtx *img = ctx;
	size_t bytes = (size_t)sectors * img->sector_size;
	off_t off = (off_t)(lba * img->sector_size);
	const uint8_t *p = buf;
	size_t put = 0;
	img->writes++;
	if (img->fail_writes > 0) {
		img->fail_writes--;
		img->failed_write_lba = lba;
		img->failed_write_valid = 1;
		return -1;
	}
	if (img->write_log_n < IMAGE_WRITE_LOG) img->write_log[img->write_log_n++] = lba;
	if (lseek(img->fd, off, SEEK_SET) < 0) return -1;
	while (put < bytes) {
		ssize_t n = write(img->fd, p + put, bytes - put);
		if (n <= 0) return -1;
		put += (size_t)n;
	}
	return 0;
}

static int image_sync(void *ctx)
{
	ImageCtx *img = ctx;
	img->syncs++;
	if (img->fail_syncs > 0) {
		img->fail_syncs--;
		return -1;
	}
	return fsync(img->fd) == 0 ? 0 : -1;
}

uint64_t image_block_open(const char *path, uint32_t sector_size, uint64_t sector_count, int create)
{
	ImageCtx *img;
	int flags = O_RDWR;
	int fd;
	if (create) flags |= O_CREAT | O_TRUNC;
	fd = open(path, flags, 0644);
	if (fd < 0) return 0;
	if (create) {
		off_t sz = (off_t)sector_size * (off_t)sector_count;
		if (ftruncate(fd, sz) != 0) {
			close(fd);
			return 0;
		}
	}
	img = calloc(1, sizeof(*img));
	if (!img) {
		close(fd);
		return 0;
	}
	img->fd = fd;
	img->sector_size = sector_size;
	img->sector_count = sector_count;
	img->ops.read = image_read;
	img->ops.write = image_write;
	img->ops.sync = image_sync;
	img->ops.ctx = img;
	img->ops.sector_size = sector_size;
	img->ops.sector_count = sector_count;
	return usb_block_register(&img->ops);
}

void image_block_close(uint64_t handle)
{
	UsbBlockOps *ops = usb_block_ops(handle);
	ImageCtx *img;
	if (!ops) return;
	img = ops->ctx;
	if (!img) return;
	close(img->fd);
	free(img);
}

/* Test-only fault injection and observation for sync/teardown tests. */
static ImageCtx *image_ctx(uint64_t handle)
{
	UsbBlockOps *ops = usb_block_ops(handle);
	return ops ? (ImageCtx *)ops->ctx : NULL;
}

/* Fail the next fail_writes write calls and fail_syncs sync calls with -1. */
void image_block_inject(uint64_t handle, int fail_writes, int fail_syncs)
{
	ImageCtx *img = image_ctx(handle);
	if (!img) return;
	img->fail_writes = fail_writes;
	img->fail_syncs = fail_syncs;
}

void image_block_reset_counters(uint64_t handle)
{
	ImageCtx *img = image_ctx(handle);
	if (!img) return;
	img->writes = 0;
	img->syncs = 0;
	img->write_log_n = 0;
	img->failed_write_valid = 0;
}

int image_block_write_count(uint64_t handle)
{
	ImageCtx *img = image_ctx(handle);
	return img ? img->writes : -1;
}

int image_block_sync_count(uint64_t handle)
{
	ImageCtx *img = image_ctx(handle);
	return img ? img->syncs : -1;
}

/* 1 and *out_lba when the last injected write failure targeted *out_lba. */
int image_block_failed_write_lba(uint64_t handle, uint64_t *out_lba)
{
	ImageCtx *img = image_ctx(handle);
	if (!img || !out_lba || !img->failed_write_valid) return 0;
	*out_lba = img->failed_write_lba;
	return 1;
}

/* 1 when a successful write covered lba since the last reset. */
int image_block_wrote_lba(uint64_t handle, uint64_t lba)
{
	ImageCtx *img = image_ctx(handle);
	int i;
	if (!img) return 0;
	for (i = 0; i < img->write_log_n; i++) {
		if (img->write_log[i] == lba) return 1;
	}
	return 0;
}
