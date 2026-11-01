#include "ventoy_engine.h"
#include "ventoy_define.h"
#include "ventoy_util.h"
#include "usb_block.h"

#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void ventoy_syslog(int level, const char *Fmt, ...)
{
    (void)level;
    (void)Fmt;
}

static int write_buffer_chunks(uint64_t handle, uint64_t start_lba, const uint8_t *buf, uint64_t sectors)
{
    const uint32_t chunk_sectors = 128; // 64 KiB chunks
    uint64_t rem = sectors;
    uint64_t cur_lba = start_lba;
    const uint8_t *p = buf;

    while (rem > 0) {
        uint32_t count = (rem > chunk_sectors) ? chunk_sectors : (uint32_t)rem;
        int rc = usb_block_write(handle, cur_lba, p, count);
        if (rc != 0) return rc;
        cur_lba += count;
        p += (size_t)count * VENTOY_SECTOR_SIZE;
        rem -= count;
    }
    return 0;
}

static int zero_sector_range(uint64_t handle, uint64_t start_lba, uint64_t sectors)
{
    uint8_t zero_buf[64 * VENTOY_SECTOR_SIZE];
    memset(zero_buf, 0, sizeof(zero_buf));
    uint64_t rem = sectors;
    uint64_t cur_lba = start_lba;

    while (rem > 0) {
        uint32_t count = (rem > 64) ? 64 : (uint32_t)rem;
        int rc = usb_block_write(handle, cur_lba, zero_buf, count);
        if (rc != 0) return rc;
        cur_lba += count;
        rem -= count;
    }
    return 0;
}

static int fill_backup_gpt_head(VTOY_GPT_INFO *info, VTOY_GPT_HDR *head)
{
    uint64_t lba = info->Head.EfiStartLBA;
    uint64_t backup_lba = info->Head.EfiBackupLBA;

    memcpy(head, &info->Head, sizeof(VTOY_GPT_HDR));
    head->EfiStartLBA = backup_lba;
    head->EfiBackupLBA = lba;
    head->PartTblStartLBA = backup_lba + 1 - 33;
    head->Crc = 0;
    head->Crc = ventoy_crc32(head, head->Length);
    return 0;
}

int ventoy_engine_validate_assets(const ventoy_asset_set *assets)
{
    if (!assets) return -EINVAL;
    if (!assets->release || strcmp(assets->release, VENTOY_PINNED_RELEASE) != 0) {
        return -EINVAL;
    }
    if (!assets->boot_img || assets->boot_img_len < 446) {
        return -EINVAL;
    }
    if (!assets->core_img || assets->core_img_len == 0) {
        return -EINVAL;
    }
    if (!assets->efi_part_img || assets->efi_part_img_len == 0) {
        return -EINVAL;
    }
    return 0;
}

int ventoy_engine_probe(uint64_t block_handle, ventoy_disk_probe *probe)
{
    if (!probe) return -EINVAL;
    memset(probe, 0, sizeof(*probe));

    UsbBlockOps *ops = usb_block_ops(block_handle);
    if (!ops) return -EINVAL;

    if (ops->sector_size == 4096) {
        return -ENOTSUP;
    }
    if (ops->sector_size != VENTOY_SECTOR_SIZE) {
        return -ENOTSUP;
    }

    if (ops->sector_count < (VENTOY_PART1_START_SECTOR + VENTOY_EFI_PART_SECTORS + 2048)) {
        return 0;
    }

    uint8_t mbr_sector[VENTOY_SECTOR_SIZE];
    int rc = usb_block_read(block_handle, 0, mbr_sector, 1);
    if (rc != 0) return rc;

    if (mbr_sector[510] != 0x55 || mbr_sector[511] != 0xAA) {
        return 0;
    }

    memcpy(probe->disk_uuid, mbr_sector + 0x180, 16);
    memcpy(&probe->disk_signature, mbr_sector + 0x1B8, 4);

    const PART_TABLE *part0 = (const PART_TABLE *)(mbr_sector + 446);
    const PART_TABLE *part1 = (const PART_TABLE *)(mbr_sector + 462);

    if (part0->FsFlag == 0xEE) {
        // GPT Layout Probe
        uint8_t gpt_header_sector[VENTOY_SECTOR_SIZE];
        rc = usb_block_read(block_handle, 1, gpt_header_sector, 1);
        if (rc != 0) return rc;

        const VTOY_GPT_HDR *head = (const VTOY_GPT_HDR *)gpt_header_sector;
        if (memcmp(head->Signature, "EFI PART", 8) != 0) {
            return 0;
        }

        VTOY_GPT_PART_TBL part_tbl[128];
        rc = usb_block_read(block_handle, 2, part_tbl, 32);
        if (rc != 0) return rc;

        uint32_t calc_crc = ventoy_crc32(part_tbl, sizeof(part_tbl));
        if (calc_crc != head->PartTblCrc) {
            return 0;
        }

        uint64_t p1_start = part_tbl[0].StartLBA;
        uint64_t p1_last = part_tbl[0].LastLBA;
        uint64_t p2_start = part_tbl[1].StartLBA;
        uint64_t p2_last = part_tbl[1].LastLBA;

        if (p1_start != VENTOY_PART1_START_SECTOR || p2_start != (p1_last + 1)) {
            return 0;
        }
        if ((p2_last - p2_start + 1) != VENTOY_EFI_PART_SECTORS) {
            return 0;
        }

        char name2[36] = {0};
        for (int i = 0; i < 35; i++) {
            name2[i] = (char)part_tbl[1].Name[i];
        }
        if (strcmp(name2, "VTOYEFI") != 0) {
            return 0;
        }

        probe->is_ventoy = 1;
        probe->partition_style = GPT_PART_STYLE;
        probe->part1_start_sector = p1_start;
        probe->part1_sector_count = p1_last - p1_start + 1;
        probe->part2_start_sector = p2_start;
        probe->part2_sector_count = VENTOY_EFI_PART_SECTORS;
        if (ops->sector_count > (p2_start + VENTOY_EFI_PART_SECTORS + 33)) {
            probe->preserved_space = (ops->sector_count - (p2_start + VENTOY_EFI_PART_SECTORS + 33)) * VENTOY_SECTOR_SIZE;
        }
    } else {
        // MBR Layout Probe
        uint64_t p1_start = part0->StartSectorId;
        uint64_t p1_count = part0->SectorCount;
        uint64_t p2_start = part1->StartSectorId;
        uint64_t p2_count = part1->SectorCount;

        if (p1_start != VENTOY_PART1_START_SECTOR || (p1_start + p1_count) != p2_start) {
            return 0;
        }
        if (p2_count != VENTOY_EFI_PART_SECTORS || part1->FsFlag != 0xEF) {
            return 0;
        }

        probe->is_ventoy = 1;
        probe->partition_style = MBR_PART_STYLE;
        probe->part1_start_sector = p1_start;
        probe->part1_sector_count = p1_count;
        probe->part2_start_sector = p2_start;
        probe->part2_sector_count = p2_count;
        if (ops->sector_count > (p2_start + p2_count)) {
            probe->preserved_space = (ops->sector_count - (p2_start + p2_count)) * VENTOY_SECTOR_SIZE;
        }
    }

    if (probe->is_ventoy) {
        usb_block_read(block_handle, 2040, probe->rsvdata, 8);
        strncpy(probe->ventoy_version, VENTOY_PINNED_RELEASE, sizeof(probe->ventoy_version) - 1);
    }
    return 0;
}

int ventoy_engine_install(uint64_t block_handle,
                          const ventoy_asset_set *assets,
                          int gpt,
                          uint64_t reserve_bytes,
                          int align4k)
{
    int rc = ventoy_engine_validate_assets(assets);
    if (rc != 0) return rc;

    UsbBlockOps *ops = usb_block_ops(block_handle);
    if (!ops) return -EINVAL;

    if (ops->sector_size == 4096) {
        return -ENOTSUP;
    }
    if (ops->sector_size != VENTOY_SECTOR_SIZE) {
        return -ENOTSUP;
    }

    uint64_t disk_sectors = ops->sector_count;
    uint64_t min_needed = VENTOY_PART1_START_SECTOR + VENTOY_EFI_PART_SECTORS + 2048;
    if (disk_sectors < min_needed) {
        return -ENOSPC;
    }

    memcpy(g_mbr_template, assets->boot_img, (assets->boot_img_len >= 512) ? 512 : assets->boot_img_len);

    uint64_t part2_start = 0;
    VTOY_GPT_INFO gpt_info;
    MBR_HEAD mbr;

    if (gpt) {
        memset(&gpt_info, 0, sizeof(gpt_info));
        ventoy_fill_gpt(disk_sectors * VENTOY_SECTOR_SIZE, reserve_bytes, align4k, &gpt_info);
        part2_start = gpt_info.PartTbl[1].StartLBA;
    } else {
        memset(&mbr, 0, sizeof(mbr));
        ventoy_fill_mbr(disk_sectors * VENTOY_SECTOR_SIZE, reserve_bytes, align4k, &mbr);
        part2_start = mbr.PartTbl[1].StartSectorId;
    }

    // Step 1: Clean partition table areas
    rc = zero_sector_range(block_handle, 0, 34);
    if (rc != 0) return rc;

    if (gpt) {
        rc = zero_sector_range(block_handle, disk_sectors - 34, 34);
        if (rc != 0) return rc;
    }

    // Step 2: Write VTOYEFI partition image at part2_start (65536 sectors)
    uint64_t efi_sectors = assets->efi_part_img_len / VENTOY_SECTOR_SIZE;
    if (efi_sectors > VENTOY_EFI_PART_SECTORS) {
        efi_sectors = VENTOY_EFI_PART_SECTORS;
    }
    rc = write_buffer_chunks(block_handle, part2_start, assets->efi_part_img, efi_sectors);
    if (rc != 0) return rc;

    // Step 3: Write legacy GRUB stage1 (core.img)
    if (gpt) {
        uint8_t *core_buf = (uint8_t *)calloc(2014, VENTOY_SECTOR_SIZE);
        if (!core_buf) return -ENOMEM;
        size_t copy_len = (assets->core_img_len > 2014 * VENTOY_SECTOR_SIZE) ?
                          2014 * VENTOY_SECTOR_SIZE : assets->core_img_len;
        memcpy(core_buf, assets->core_img, copy_len);
        core_buf[500] = 35; // Update blocklist
        rc = usb_block_write(block_handle, 34, core_buf, 2014);
        free(core_buf);
        if (rc != 0) return rc;

        // Step 4: Write GPT partition structures
        VTOY_GPT_HDR backup_head;
        fill_backup_gpt_head(&gpt_info, &backup_head);

        rc = usb_block_write(block_handle, disk_sectors - 33, gpt_info.PartTbl, 32);
        if (rc != 0) return rc;

        rc = usb_block_write(block_handle, disk_sectors - 1, &backup_head, 1);
        if (rc != 0) return rc;

        rc = usb_block_write(block_handle, 2, gpt_info.PartTbl, 32);
        if (rc != 0) return rc;

        rc = usb_block_write(block_handle, 1, &gpt_info.Head, 1);
        if (rc != 0) return rc;

        rc = usb_block_write(block_handle, 0, &gpt_info.MBR, 1);
        if (rc != 0) return rc;
    } else {
        uint8_t *core_buf = (uint8_t *)calloc(2047, VENTOY_SECTOR_SIZE);
        if (!core_buf) return -ENOMEM;
        size_t copy_len = (assets->core_img_len > 2047 * VENTOY_SECTOR_SIZE) ?
                          2047 * VENTOY_SECTOR_SIZE : assets->core_img_len;
        memcpy(core_buf, assets->core_img, copy_len);
        rc = usb_block_write(block_handle, 1, core_buf, 2047);
        free(core_buf);
        if (rc != 0) return rc;

        // Write MBR
        rc = usb_block_write(block_handle, 0, &mbr, 1);
        if (rc != 0) return rc;
    }

    return usb_block_sync(block_handle);
}

int ventoy_engine_update(uint64_t block_handle,
                         const ventoy_asset_set *assets)
{
    int rc = ventoy_engine_validate_assets(assets);
    if (rc != 0) return rc;

    UsbBlockOps *ops = usb_block_ops(block_handle);
    if (!ops) return -EINVAL;

    if (ops->sector_size == 4096) {
        return -ENOTSUP;
    }
    if (ops->sector_size != VENTOY_SECTOR_SIZE) {
        return -ENOTSUP;
    }

    ventoy_disk_probe probe;
    rc = ventoy_engine_probe(block_handle, &probe);
    if (rc != 0) return rc;
    if (!probe.is_ventoy) {
        // Refuse unrecognized layouts without writing a single byte
        return -EINVAL;
    }

    uint64_t part2_start = probe.part2_start_sector;
    uint64_t disk_sectors = ops->sector_count;

    // Preserve partition 1: sectors from 2048 to part2_start - 1 are NEVER touched!

    // Step 1: Update Part 2 (VTOYEFI)
    uint64_t efi_sectors = assets->efi_part_img_len / VENTOY_SECTOR_SIZE;
    if (efi_sectors > VENTOY_EFI_PART_SECTORS) {
        efi_sectors = VENTOY_EFI_PART_SECTORS;
    }
    rc = write_buffer_chunks(block_handle, part2_start, assets->efi_part_img, efi_sectors);
    if (rc != 0) return rc;

    // Step 2: Update legacy GRUB stage1 (core.img)
    if (probe.partition_style == GPT_PART_STYLE) {
        uint8_t *core_buf = (uint8_t *)calloc(2014, VENTOY_SECTOR_SIZE);
        if (!core_buf) return -ENOMEM;
        size_t copy_len = (assets->core_img_len > 2014 * VENTOY_SECTOR_SIZE) ?
                          2014 * VENTOY_SECTOR_SIZE : assets->core_img_len;
        memcpy(core_buf, assets->core_img, copy_len);
        core_buf[500] = 35; // Update blocklist
        rc = usb_block_write(block_handle, 34, core_buf, 2014);
        free(core_buf);
        if (rc != 0) return rc;
    } else {
        uint8_t *core_buf = (uint8_t *)calloc(2047, VENTOY_SECTOR_SIZE);
        if (!core_buf) return -ENOMEM;
        size_t copy_len = (assets->core_img_len > 2047 * VENTOY_SECTOR_SIZE) ?
                          2047 * VENTOY_SECTOR_SIZE : assets->core_img_len;
        memcpy(core_buf, assets->core_img, copy_len);
        rc = usb_block_write(block_handle, 1, core_buf, 2047);
        free(core_buf);
        if (rc != 0) return rc;
    }

    // Step 3: Restore reserved data at LBA 2040
    rc = usb_block_write(block_handle, 2040, probe.rsvdata, 8);
    if (rc != 0) return rc;

    // Step 4: Update MBR boot code (first 440 bytes), preserving original UUID and signature
    uint8_t current_mbr[VENTOY_SECTOR_SIZE];
    rc = usb_block_read(block_handle, 0, current_mbr, 1);
    if (rc != 0) return rc;

    size_t boot_code_len = (assets->boot_img_len >= 440) ? 440 : assets->boot_img_len;
    memcpy(current_mbr, assets->boot_img, boot_code_len);
    memcpy(current_mbr + 0x180, probe.disk_uuid, 16);
    memcpy(current_mbr + 0x1B8, &probe.disk_signature, 4);

    if (probe.partition_style == MBR_PART_STYLE) {
        current_mbr[446] = 0x80; // Ensure partition 1 active flag enabled
    } else {
        current_mbr[92] = 0x22;
    }

    rc = usb_block_write(block_handle, 0, current_mbr, 1);
    if (rc != 0) return rc;

    // Step 5: If GPT, update EFI partition attribute and recompute CRCs
    if (probe.partition_style == GPT_PART_STYLE) {
        VTOY_GPT_INFO gpt_info;
        memset(&gpt_info, 0, sizeof(gpt_info));

        rc = usb_block_read(block_handle, 0, &gpt_info.MBR, 1);
        if (rc != 0) return rc;
        rc = usb_block_read(block_handle, 1, &gpt_info.Head, 1);
        if (rc != 0) return rc;
        rc = usb_block_read(block_handle, 2, gpt_info.PartTbl, 32);
        if (rc != 0) return rc;

        gpt_info.PartTbl[1].Attr = 0x8000000000000000ULL;
        gpt_info.Head.PartTblCrc = ventoy_crc32(gpt_info.PartTbl, sizeof(gpt_info.PartTbl));
        gpt_info.Head.Crc = 0;
        gpt_info.Head.Crc = ventoy_crc32(&gpt_info.Head, gpt_info.Head.Length);

        VTOY_GPT_HDR backup_head;
        fill_backup_gpt_head(&gpt_info, &backup_head);

        rc = usb_block_write(block_handle, disk_sectors - 33, gpt_info.PartTbl, 32);
        if (rc != 0) return rc;
        rc = usb_block_write(block_handle, disk_sectors - 1, &backup_head, 1);
        if (rc != 0) return rc;
        rc = usb_block_write(block_handle, 2, gpt_info.PartTbl, 32);
        if (rc != 0) return rc;
        rc = usb_block_write(block_handle, 1, &gpt_info.Head, 1);
        if (rc != 0) return rc;
    }

    return usb_block_sync(block_handle);
}
