/*-----------------------------------------------------------------------*/
/* FatFs low-level disk I/O → usb_block_* (relative LBA)                 */
/* Project glue; not part of upstream FatFs.                             */
/*-----------------------------------------------------------------------*/

#include "ff.h"
#include "diskio.h"

#include <stdint.h>
#include <string.h>

#include "usb_block.h"

#ifndef FF_VOLUMES
#error FF_VOLUMES required
#endif

typedef struct {
	uint64_t handle;
	uint32_t sector_size;
	uint64_t sector_count;
	BYTE status; /* STA_* bits */
} FatDrive;

static FatDrive drives[FF_VOLUMES];

void fat_diskio_bind(BYTE pdrv, uint64_t handle, uint32_t sector_size, uint64_t sector_count)
{
	if (pdrv >= FF_VOLUMES) return;
	drives[pdrv].handle = handle;
	drives[pdrv].sector_size = sector_size;
	drives[pdrv].sector_count = sector_count;
	drives[pdrv].status = (sector_size == 0 || sector_count == 0) ? STA_NOINIT : 0;
}

void fat_diskio_unbind(BYTE pdrv)
{
	if (pdrv >= FF_VOLUMES) return;
	memset(&drives[pdrv], 0, sizeof(drives[pdrv]));
	drives[pdrv].status = STA_NOINIT;
}

DSTATUS disk_status(BYTE pdrv)
{
	if (pdrv >= FF_VOLUMES) return STA_NOINIT;
	return drives[pdrv].status;
}

DSTATUS disk_initialize(BYTE pdrv)
{
	if (pdrv >= FF_VOLUMES) return STA_NOINIT;
	if (drives[pdrv].sector_size == 0 || drives[pdrv].sector_count == 0) {
		drives[pdrv].status = STA_NOINIT;
		return STA_NOINIT;
	}
	drives[pdrv].status = 0;
	return 0;
}

DRESULT disk_read(BYTE pdrv, BYTE *buff, LBA_t sector, UINT count)
{
	FatDrive *d;
	if (pdrv >= FF_VOLUMES || !buff || count == 0) return RES_PARERR;
	d = &drives[pdrv];
	if (d->status & STA_NOINIT) return RES_NOTRDY;
	if ((uint64_t)sector + count > d->sector_count) return RES_PARERR;
	/* Relative LBA only — VirtualPartition owns absolute offset. */
	if (usb_block_read(d->handle, (uint64_t)sector, buff, (uint32_t)count) != 0) {
		return RES_ERROR;
	}
	return RES_OK;
}

DRESULT disk_write(BYTE pdrv, const BYTE *buff, LBA_t sector, UINT count)
{
	FatDrive *d;
	if (pdrv >= FF_VOLUMES || !buff || count == 0) return RES_PARERR;
	d = &drives[pdrv];
	if (d->status & STA_NOINIT) return RES_NOTRDY;
	if (d->status & STA_PROTECT) return RES_WRPRT;
	if ((uint64_t)sector + count > d->sector_count) return RES_PARERR;
	if (usb_block_write(d->handle, (uint64_t)sector, buff, (uint32_t)count) != 0) {
		return RES_ERROR;
	}
	return RES_OK;
}

DRESULT disk_ioctl(BYTE pdrv, BYTE cmd, void *buff)
{
	FatDrive *d;
	if (pdrv >= FF_VOLUMES) return RES_PARERR;
	d = &drives[pdrv];
	if (d->status & STA_NOINIT) return RES_NOTRDY;

	switch (cmd) {
	case CTRL_SYNC:
		return usb_block_sync(d->handle) == 0 ? RES_OK : RES_ERROR;

	case GET_SECTOR_COUNT:
		if (!buff) return RES_PARERR;
		*(LBA_t *)buff = (LBA_t)d->sector_count;
		return RES_OK;

	case GET_SECTOR_SIZE:
		if (!buff) return RES_PARERR;
		*(WORD *)buff = (WORD)d->sector_size;
		return RES_OK;

	case GET_BLOCK_SIZE:
		if (!buff) return RES_PARERR;
		*(DWORD *)buff = 1; /* erase block unknown; 1 sector */
		return RES_OK;

	default:
		return RES_PARERR;
	}
}

DWORD get_fattime(void)
{
	/* 2025-07-22 00:00:00 — fixed stamp when RTC unavailable. */
	return ((DWORD)(2025 - 1980) << 25)
		| ((DWORD)7 << 21)
		| ((DWORD)22 << 16);
}
