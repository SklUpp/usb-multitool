#include "usb_block.h"

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef struct ImageCtx {
	int fd;
	uint32_t sector_size;
	uint64_t sector_count;
	UsbBlockOps ops;
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
