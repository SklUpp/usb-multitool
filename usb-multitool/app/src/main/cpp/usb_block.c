#include "usb_block.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>

uint64_t usb_block_register(UsbBlockOps *ops)
{
	return (uint64_t)(uintptr_t)ops;
}

UsbBlockOps *usb_block_ops(uint64_t handle)
{
	return (UsbBlockOps *)(uintptr_t)handle;
}

int usb_block_read(uint64_t handle, uint64_t lba, void *buf, uint32_t sectors)
{
	UsbBlockOps *ops = usb_block_ops(handle);
	if (!ops || !ops->read || !buf || sectors == 0) return -EINVAL;
	if (lba + sectors < lba) return -ERANGE;
	if (ops->sector_count != 0 && lba + sectors > ops->sector_count) return -ERANGE;
	return ops->read(ops->ctx, lba, buf, sectors);
}

int usb_block_write(uint64_t handle, uint64_t lba, const void *buf, uint32_t sectors)
{
	UsbBlockOps *ops = usb_block_ops(handle);
	if (!ops || !ops->write || !buf || sectors == 0) return -EINVAL;
	if (lba + sectors < lba) return -ERANGE;
	if (ops->sector_count != 0 && lba + sectors > ops->sector_count) return -ERANGE;
	return ops->write(ops->ctx, lba, buf, sectors);
}

int usb_block_sync(uint64_t handle)
{
	UsbBlockOps *ops = usb_block_ops(handle);
	if (!ops || !ops->sync) return -EINVAL;
	return ops->sync(ops->ctx);
}
