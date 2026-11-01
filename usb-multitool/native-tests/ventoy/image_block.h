#ifndef IMAGE_BLOCK_H
#define IMAGE_BLOCK_H

#include "usb_block.h"
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ImageBlockDev {
    uint32_t sector_size;
    uint64_t sector_count;
    uint8_t *data;
    uint64_t bytes_written;
    uint64_t write_count;
    uint64_t read_count;
    uint64_t sync_count;
    uint64_t min_written_lba;
    uint64_t max_written_lba;
    UsbBlockOps ops;
} ImageBlockDev;

uint64_t image_block_create(uint32_t sector_size, uint64_t sector_count);
void image_block_destroy(uint64_t handle);
ImageBlockDev *image_block_get(uint64_t handle);
void image_block_reset_stats(uint64_t handle);
int image_block_range_was_written(uint64_t handle, uint64_t start_lba, uint64_t count);

#ifdef __cplusplus
}
#endif

#endif /* IMAGE_BLOCK_H */
