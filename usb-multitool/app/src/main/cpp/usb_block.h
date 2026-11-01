/* Shared relative-LBA block backend.
 * VirtualPartition (or image file) adds the partition start offset exactly once.
 * LBAs are relative to the supplied disk/partition handle.
 * Out-of-bounds or overflow access returns -ERANGE before I/O.
 */
#ifndef USB_BLOCK_H
#define USB_BLOCK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Return 0 on success, -ERANGE on bounds/overflow, negative errno on error. */
int usb_block_read(uint64_t handle, uint64_t lba, void *buf, uint32_t sectors);
int usb_block_write(uint64_t handle, uint64_t lba, const void *buf, uint32_t sectors);
int usb_block_sync(uint64_t handle);

typedef struct UsbBlockOps {
	int (*read)(void *ctx, uint64_t lba, void *buf, uint32_t sectors);
	int (*write)(void *ctx, uint64_t lba, const void *buf, uint32_t sectors);
	int (*sync)(void *ctx);
	void *ctx;
	uint32_t sector_size;
	uint64_t sector_count;
} UsbBlockOps;

uint64_t usb_block_register(UsbBlockOps *ops);
UsbBlockOps *usb_block_ops(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif /* USB_BLOCK_H */
