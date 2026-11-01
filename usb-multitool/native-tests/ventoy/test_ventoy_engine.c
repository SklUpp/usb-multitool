#include "ventoy_engine.h"
#include "image_block.h"
#include "ventoy_define.h"

#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void make_fake_assets(ventoy_asset_set *assets, const char *ver_tag)
{
    static uint8_t boot_buf[512];
    static uint8_t core_buf[4096];
    static uint8_t efi_buf[65536 * 512]; // Note: in real test, efi_part can be 65536 sectors

    memset(boot_buf, 0x90, sizeof(boot_buf));
    boot_buf[510] = 0x55;
    boot_buf[511] = 0xAA;

    memset(core_buf, 0xCC, sizeof(core_buf));
    core_buf[500] = 1;

    memset(efi_buf, 0x00, sizeof(efi_buf));
    // Put EFI FAT magic / signature inside
    efi_buf[0] = 0xEB;
    efi_buf[1] = 0x3C;
    efi_buf[2] = 0x90;
    memcpy(efi_buf + 3, "MSDOS5.0", 8);
    efi_buf[510] = 0x55;
    efi_buf[511] = 0xAA;
    // Put version in EFI image payload
    snprintf((char *)(efi_buf + 1024), 64, "VENTOY_VERSION=\"%s\"", ver_tag);

    assets->release = ver_tag;
    assets->boot_img = boot_buf;
    assets->boot_img_len = sizeof(boot_buf);
    assets->core_img = core_buf;
    assets->core_img_len = sizeof(core_buf);
    assets->efi_part_img = efi_buf;
    assets->efi_part_img_len = sizeof(efi_buf);
    assets->supports_4kn = 0;
}

static void test_4kn_install_refused(void)
{
    printf("Running test_4kn_install_refused...\n");
    ventoy_asset_set assets;
    make_fake_assets(&assets, "1.1.18");

    // 4Kn disk: 4096 sector size
    uint64_t handle = image_block_create(4096, 32768);
    assert(handle != 0);

    int rc = ventoy_engine_install(handle, &assets, 0, 0, 1);
    assert(rc == -ENOTSUP);

    ImageBlockDev *dev = image_block_get(handle);
    assert(dev->write_count == 0);
    assert(dev->bytes_written == 0);

    image_block_destroy(handle);
    printf("test_4kn_install_refused passed!\n");
}

static void test_4kn_update_refused(void)
{
    printf("Running test_4kn_update_refused...\n");
    ventoy_asset_set assets;
    make_fake_assets(&assets, "1.1.18");

    uint64_t handle = image_block_create(4096, 32768);
    assert(handle != 0);

    int rc = ventoy_engine_update(handle, &assets);
    assert(rc == -ENOTSUP);

    ImageBlockDev *dev = image_block_get(handle);
    assert(dev->write_count == 0);
    assert(dev->bytes_written == 0);

    image_block_destroy(handle);
    printf("test_4kn_update_refused passed!\n");
}

static void test_unrecognized_update_refused(void)
{
    printf("Running test_unrecognized_update_refused...\n");
    ventoy_asset_set assets;
    make_fake_assets(&assets, "1.1.18");

    // 128 MB disk with blank data
    uint64_t handle = image_block_create(512, 262144);
    assert(handle != 0);

    int rc = ventoy_engine_update(handle, &assets);
    assert(rc == -EINVAL);

    ImageBlockDev *dev = image_block_get(handle);
    assert(dev->write_count == 0);
    assert(dev->bytes_written == 0);

    image_block_destroy(handle);
    printf("test_unrecognized_update_refused passed!\n");
}

static void test_bounds_erange(void)
{
    printf("Running test_bounds_erange...\n");
    uint64_t handle = image_block_create(512, 100);
    assert(handle != 0);

    uint8_t buf[512];
    int rc = usb_block_read(handle, 99, buf, 1);
    assert(rc == 0);

    // Reading past bounds returns -ERANGE
    rc = usb_block_read(handle, 100, buf, 1);
    assert(rc == -ERANGE);

    rc = usb_block_read(handle, 95, buf, 10);
    assert(rc == -ERANGE);

    // Writing past bounds returns -ERANGE
    rc = usb_block_write(handle, 100, buf, 1);
    assert(rc == -ERANGE);

    // Overflow check returns -ERANGE
    rc = usb_block_read(handle, UINT64_MAX - 5, buf, 10);
    assert(rc == -ERANGE);

    image_block_destroy(handle);
    printf("test_bounds_erange passed!\n");
}

static void test_asset_validation(void)
{
    printf("Running test_asset_validation...\n");
    uint64_t handle = image_block_create(512, 262144);
    assert(handle != 0);

    ventoy_asset_set assets;
    make_fake_assets(&assets, "1.0.99"); // Mismatched version

    int rc = ventoy_engine_install(handle, &assets, 0, 0, 1);
    assert(rc == -EINVAL);

    rc = ventoy_engine_install(handle, NULL, 0, 0, 1);
    assert(rc == -EINVAL);

    image_block_destroy(handle);
    printf("test_asset_validation passed!\n");
}

static void test_mbr_install_and_probe(void)
{
    printf("Running test_mbr_install_and_probe...\n");
    ventoy_asset_set assets;
    make_fake_assets(&assets, "1.1.18");

    // 128 MB disk (262144 sectors of 512 bytes)
    uint64_t handle = image_block_create(512, 262144);
    assert(handle != 0);

    int rc = ventoy_engine_install(handle, &assets, 0, 0, 1);
    assert(rc == 0);

    ImageBlockDev *dev = image_block_get(handle);
    assert(dev->write_count > 0);
    assert(dev->sync_count > 0);

    ventoy_disk_probe probe;
    rc = ventoy_engine_probe(handle, &probe);
    assert(rc == 0);
    assert(probe.is_ventoy == 1);
    assert(probe.partition_style == MBR_PART_STYLE);
    assert(probe.part1_start_sector == 2048);
    assert(probe.part2_sector_count == 65536);
    assert((probe.part1_start_sector + probe.part1_sector_count) == probe.part2_start_sector);
    assert(probe.part2_start_sector % 8 == 0); // 4KB aligned

    // Verify LBA 0 (MBR)
    uint8_t mbr_sec[512];
    rc = usb_block_read(handle, 0, mbr_sec, 1);
    assert(rc == 0);
    assert(mbr_sec[510] == 0x55 && mbr_sec[511] == 0xAA);

    const PART_TABLE *p0 = (const PART_TABLE *)(mbr_sec + 446);
    const PART_TABLE *p1 = (const PART_TABLE *)(mbr_sec + 462);
    assert(p0->Active == 0x80);
    assert(p0->FsFlag == 0x07);
    assert(p0->StartSectorId == 2048);

    assert(p1->Active == 0x00);
    assert(p1->FsFlag == 0xEF);
    assert(p1->SectorCount == 65536);
    assert(p1->StartSectorId == probe.part2_start_sector);

    image_block_destroy(handle);
    printf("test_mbr_install_and_probe passed!\n");
}

static void test_mbr_update_preserves_part1(void)
{
    printf("Running test_mbr_update_preserves_part1...\n");
    ventoy_asset_set assets;
    make_fake_assets(&assets, "1.1.18");

    uint64_t handle = image_block_create(512, 262144);
    assert(handle != 0);

    // Initial install
    int rc = ventoy_engine_install(handle, &assets, 0, 0, 1);
    assert(rc == 0);

    ventoy_disk_probe probe1;
    rc = ventoy_engine_probe(handle, &probe1);
    assert(rc == 0 && probe1.is_ventoy == 1);

    // Write user data (canary pattern) into Partition 1: sectors 2048..2058
    uint8_t user_data[10 * 512];
    for (size_t i = 0; i < sizeof(user_data); i++) {
        user_data[i] = (uint8_t)(0xA5 ^ (i & 0xFF));
    }
    rc = usb_block_write(handle, 2048, user_data, 10);
    assert(rc == 0);

    // Reset stats to trace writes during update
    image_block_reset_stats(handle);

    // Run update with new asset set
    ventoy_asset_set assets_v2;
    make_fake_assets(&assets_v2, "1.1.18");
    rc = ventoy_engine_update(handle, &assets_v2);
    assert(rc == 0);

    // Check that user data in Partition 1 was NOT overwritten
    uint8_t check_buf[10 * 512];
    rc = usb_block_read(handle, 2048, check_buf, 10);
    assert(rc == 0);
    assert(memcmp(user_data, check_buf, sizeof(user_data)) == 0);

    // Verify probe after update
    ventoy_disk_probe probe2;
    rc = ventoy_engine_probe(handle, &probe2);
    assert(rc == 0 && probe2.is_ventoy == 1);
    assert(probe2.part1_start_sector == probe1.part1_start_sector);
    assert(probe2.part1_sector_count == probe1.part1_sector_count);
    assert(probe2.part2_start_sector == probe1.part2_start_sector);
    assert(memcmp(probe1.disk_uuid, probe2.disk_uuid, 16) == 0);
    assert(probe1.disk_signature == probe2.disk_signature);

    image_block_destroy(handle);
    printf("test_mbr_update_preserves_part1 passed!\n");
}

static void test_gpt_install_and_probe(void)
{
    printf("Running test_gpt_install_and_probe...\n");
    ventoy_asset_set assets;
    make_fake_assets(&assets, "1.1.18");

    uint64_t handle = image_block_create(512, 262144);
    assert(handle != 0);

    int rc = ventoy_engine_install(handle, &assets, 1, 0, 1);
    assert(rc == 0);

    ventoy_disk_probe probe;
    rc = ventoy_engine_probe(handle, &probe);
    assert(rc == 0);
    assert(probe.is_ventoy == 1);
    assert(probe.partition_style == GPT_PART_STYLE);
    assert(probe.part1_start_sector == 2048);
    assert(probe.part2_sector_count == 65536);
    assert(probe.part2_start_sector % 8 == 0);
    assert(probe.part2_start_sector + probe.part2_sector_count - 1 < 262144 - 34);

    // Verify protective MBR
    uint8_t mbr_sec[512];
    rc = usb_block_read(handle, 0, mbr_sec, 1);
    assert(rc == 0);
    assert(mbr_sec[510] == 0x55 && mbr_sec[511] == 0xAA);
    assert(mbr_sec[92] == 0x22);
    const PART_TABLE *p0 = (const PART_TABLE *)(mbr_sec + 446);
    assert(p0->FsFlag == 0xEE);
    assert(p0->StartSectorId == 1);

    // Verify primary GPT header
    uint8_t gpt_sec[512];
    rc = usb_block_read(handle, 1, gpt_sec, 1);
    assert(rc == 0);
    const VTOY_GPT_HDR *head = (const VTOY_GPT_HDR *)gpt_sec;
    assert(memcmp(head->Signature, "EFI PART", 8) == 0);
    assert(head->EfiStartLBA == 1);
    assert(head->EfiBackupLBA == 262143);

    // Verify backup GPT header
    uint8_t backup_sec[512];
    rc = usb_block_read(handle, 262143, backup_sec, 1);
    assert(rc == 0);
    const VTOY_GPT_HDR *bhead = (const VTOY_GPT_HDR *)backup_sec;
    assert(memcmp(bhead->Signature, "EFI PART", 8) == 0);
    assert(bhead->EfiStartLBA == 262143);
    assert(bhead->EfiBackupLBA == 1);

    image_block_destroy(handle);
    printf("test_gpt_install_and_probe passed!\n");
}

static void test_gpt_update_preserves_part1(void)
{
    printf("Running test_gpt_update_preserves_part1...\n");
    ventoy_asset_set assets;
    make_fake_assets(&assets, "1.1.18");

    uint64_t handle = image_block_create(512, 262144);
    assert(handle != 0);

    int rc = ventoy_engine_install(handle, &assets, 1, 0, 1);
    assert(rc == 0);

    // Write canary data in Partition 1
    uint8_t user_data[16 * 512];
    memset(user_data, 0x5A, sizeof(user_data));
    rc = usb_block_write(handle, 2048, user_data, 16);
    assert(rc == 0);

    // Run update
    ventoy_asset_set assets_v2;
    make_fake_assets(&assets_v2, "1.1.18");
    rc = ventoy_engine_update(handle, &assets_v2);
    assert(rc == 0);

    // Verify canary data is preserved
    uint8_t check_buf[16 * 512];
    rc = usb_block_read(handle, 2048, check_buf, 16);
    assert(rc == 0);
    assert(memcmp(user_data, check_buf, sizeof(user_data)) == 0);

    // Verify GPT validity after update
    ventoy_disk_probe probe;
    rc = ventoy_engine_probe(handle, &probe);
    assert(rc == 0 && probe.is_ventoy == 1);
    assert(probe.partition_style == GPT_PART_STYLE);

    image_block_destroy(handle);
    printf("test_gpt_update_preserves_part1 passed!\n");
}

static int dummy_io(void *ctx, uint64_t lba, void *buf, uint32_t sectors) {
    (void)ctx; (void)lba; (void)buf; (void)sectors; return 0;
}
static int dummy_sync(void *ctx) { (void)ctx; return 0; }

static void test_mbr_2tb_refused(void)
{
    printf("Running test_mbr_2tb_refused...\n");
    ventoy_asset_set assets;
    make_fake_assets(&assets, "1.1.18");

    UsbBlockOps ops = {
        .read = dummy_io,
        .write = (int (*)(void *, uint64_t, const void *, uint32_t))dummy_io,
        .sync = dummy_sync,
        .ctx = NULL,
        .sector_size = 512,
        .sector_count = 0x100000001ULL,
    };
    uint64_t handle = usb_block_register(&ops);
    assert(handle != 0);

    int rc = ventoy_engine_install(handle, &assets, 0, 0, 1);
    assert(rc == -EINVAL);

    usb_block_unregister(handle);
    printf("test_mbr_2tb_refused passed!\n");
}

int main(void)
{
    printf("========== Ventoy Engine Native Host Tests ==========\n");
    test_4kn_install_refused();
    test_4kn_update_refused();
    test_unrecognized_update_refused();
    test_bounds_erange();
    test_asset_validation();
    test_mbr_install_and_probe();
    test_mbr_update_preserves_part1();
    test_gpt_install_and_probe();
    test_gpt_update_preserves_part1();
    test_mbr_2tb_refused();
    printf("All 10 native tests PASSED successfully!\n");
    return 0;
}
