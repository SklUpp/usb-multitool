#ifndef VENTOY_ENGINE_H
#define VENTOY_ENGINE_H

#include <stdint.h>
#include <stddef.h>
#include "usb_block.h"
#include "ventoy_define.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VENTOY_SECTOR_SIZE          512
#define VENTOY_EFI_PART_SECTORS     65536
#define VENTOY_PART1_START_SECTOR   2048

/* Upstream release metadata */
#define VENTOY_PINNED_RELEASE       "1.1.18"
#define VENTOY_PINNED_COMMIT        "6116894ab8abdf3ef8d43411a6732d34ee19214e"

typedef struct ventoy_asset_set {
    const char *release;            /* Must match VENTOY_PINNED_RELEASE */
    const uint8_t *boot_img;        /* 512 bytes (MBR boot code) */
    size_t boot_img_len;
    const uint8_t *core_img;        /* legacy GRUB stage1 (core.img raw, up to 1MB) */
    size_t core_img_len;
    const uint8_t *efi_part_img;    /* VTOYEFI partition raw image (32MB, 65536 sectors) */
    size_t efi_part_img_len;
    int supports_4kn;               /* 0 for v1.1.18 */
} ventoy_asset_set;

typedef struct ventoy_disk_probe {
    int is_ventoy;                  /* 1 if valid Ventoy layout recognized, 0 otherwise */
    int partition_style;            /* MBR_PART_STYLE (0) or GPT_PART_STYLE (1) */
    uint64_t part1_start_sector;    /* 2048 */
    uint64_t part1_sector_count;
    uint64_t part2_start_sector;
    uint64_t part2_sector_count;    /* 65536 */
    uint64_t preserved_space;
    uint8_t disk_uuid[16];
    uint32_t disk_signature;
    uint8_t rsvdata[4096];
    char ventoy_version[32];
} ventoy_disk_probe;

/* Validates that assets match required release and size contracts */
int ventoy_engine_validate_assets(const ventoy_asset_set *assets);

/* Probes a block device for an existing Ventoy layout without modifying it */
int ventoy_engine_probe(uint64_t block_handle, ventoy_disk_probe *probe);

/* Installs Ventoy onto a block device (destructive) */
int ventoy_engine_install(uint64_t block_handle,
                          const ventoy_asset_set *assets,
                          int gpt,
                          uint64_t reserve_bytes,
                          int align4k);

/* Updates Ventoy on a block device (preserves partition 1) */
int ventoy_engine_update(uint64_t block_handle,
                         const ventoy_asset_set *assets);

#ifdef __cplusplus
}
#endif

#endif /* VENTOY_ENGINE_H */
