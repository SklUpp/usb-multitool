#include "image_block.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>

static int dev_read(void *ctx, uint64_t lba, void *buf, uint32_t sectors)
{
    ImageBlockDev *dev = (ImageBlockDev *)ctx;
    if (lba + sectors > dev->sector_count) return -ERANGE;
    size_t offset = (size_t)lba * dev->sector_size;
    size_t bytes = (size_t)sectors * dev->sector_size;
    memcpy(buf, dev->data + offset, bytes);
    dev->read_count++;
    return 0;
}

static int dev_write(void *ctx, uint64_t lba, const void *buf, uint32_t sectors)
{
    ImageBlockDev *dev = (ImageBlockDev *)ctx;
    if (lba + sectors > dev->sector_count) return -ERANGE;
    size_t offset = (size_t)lba * dev->sector_size;
    size_t bytes = (size_t)sectors * dev->sector_size;
    memcpy(dev->data + offset, buf, bytes);
    dev->bytes_written += bytes;
    dev->write_count++;
    if (lba < dev->min_written_lba) dev->min_written_lba = lba;
    if (lba + sectors > dev->max_written_lba) dev->max_written_lba = lba + sectors;
    return 0;
}

static int dev_sync(void *ctx)
{
    ImageBlockDev *dev = (ImageBlockDev *)ctx;
    dev->sync_count++;
    return 0;
}

uint64_t image_block_create(uint32_t sector_size, uint64_t sector_count)
{
    ImageBlockDev *dev = (ImageBlockDev *)calloc(1, sizeof(ImageBlockDev));
    if (!dev) return 0;

    dev->sector_size = sector_size;
    dev->sector_count = sector_count;
    size_t total_bytes = (size_t)sector_size * (size_t)sector_count;
    dev->data = (uint8_t *)calloc(1, total_bytes);
    if (!dev->data) {
        free(dev);
        return 0;
    }

    dev->min_written_lba = UINT64_MAX;
    dev->max_written_lba = 0;

    dev->ops.read = dev_read;
    dev->ops.write = dev_write;
    dev->ops.sync = dev_sync;
    dev->ops.ctx = dev;
    dev->ops.sector_size = sector_size;
    dev->ops.sector_count = sector_count;

    return usb_block_register(&dev->ops);
}

void image_block_destroy(uint64_t handle)
{
    UsbBlockOps *ops = usb_block_ops(handle);
    if (!ops) return;
    ImageBlockDev *dev = (ImageBlockDev *)ops->ctx;
    if (!dev) return;
    free(dev->data);
    free(dev);
}

ImageBlockDev *image_block_get(uint64_t handle)
{
    UsbBlockOps *ops = usb_block_ops(handle);
    if (!ops) return NULL;
    return (ImageBlockDev *)ops->ctx;
}

void image_block_reset_stats(uint64_t handle)
{
    ImageBlockDev *dev = image_block_get(handle);
    if (!dev) return;
    dev->bytes_written = 0;
    dev->write_count = 0;
    dev->read_count = 0;
    dev->sync_count = 0;
    dev->min_written_lba = UINT64_MAX;
    dev->max_written_lba = 0;
}

int image_block_range_was_written(uint64_t handle, uint64_t start_lba, uint64_t count)
{
    ImageBlockDev *dev = image_block_get(handle);
    if (!dev || dev->write_count == 0) return 0;
    uint64_t end_lba = start_lba + count;
    if (dev->min_written_lba >= end_lba || dev->max_written_lba <= start_lba) {
        return 0;
    }
    return 1;
}
