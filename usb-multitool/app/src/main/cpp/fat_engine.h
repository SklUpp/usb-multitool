#ifndef FAT_ENGINE_H
#define FAT_ENGINE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum FatFsKind {
	FAT_KIND_UNKNOWN = 0,
	FAT_KIND_FAT12 = 12,
	FAT_KIND_FAT16 = 16,
	FAT_KIND_FAT32 = 32,
	FAT_KIND_EXFAT = 64
} FatFsKind;

typedef enum FatMkfsType {
	FAT_MKFS_FAT = 1,
	FAT_MKFS_FAT32 = 2,
	FAT_MKFS_EXFAT = 4,
	FAT_MKFS_ANY = 7
} FatMkfsType;

typedef struct FatDirEnt {
	char name[256];
	uint8_t is_dir;
	uint64_t size;
	uint8_t attrib;
} FatDirEnt;

typedef struct FatStat {
	char name[256];
	uint8_t is_dir;
	uint64_t size;
	uint8_t attrib;
} FatStat;

/* Engine-local status outside FatFs FRESULT range. */
#define FAT_ENGINE_FILE_TOO_LARGE 100

/* Max file size accepted before create. FAT12/16/32: 4GiB-1; exFAT: effectively unlimited here. */
uint64_t fat_engine_max_file_size(FatFsKind kind);

int fat_engine_mount(uint64_t block_handle, uint32_t sector_size, uint64_t sector_count,
		     FatFsKind *out_kind, uint64_t *out_volume);
int fat_engine_umount(uint64_t volume);
int fat_engine_sync(uint64_t volume);

/*
 * Metadata-only space query: total and free bytes inside the mounted
 * filesystem (FAT cluster count x cluster size, exFAT allocation bitmap).
 * Read-only; never mounts/unmounts and never writes the media.
 * Returns FR_OK and writes both outputs, or a FatFs FRESULT on failure.
 */
int fat_engine_usage(uint64_t volume, uint64_t *out_total_bytes,
		     uint64_t *out_free_bytes);

int fat_engine_list(uint64_t volume, const char *path, FatDirEnt *out, size_t cap, size_t *out_count);
int fat_engine_stat(uint64_t volume, const char *path, FatStat *out);
int fat_engine_mkdir(uint64_t volume, const char *path);
int fat_engine_delete(uint64_t volume, const char *path);
int fat_engine_rename(uint64_t volume, const char *from, const char *to);

int fat_engine_read_file(uint64_t volume, const char *path, uint64_t offset,
			 void *buf, size_t len, size_t *out_read);
/* Rejects size > filesystem limit before creating destination. */
int fat_engine_write_file(uint64_t volume, const char *path, uint64_t offset,
			  const void *buf, size_t len, size_t *out_written,
			  uint64_t planned_total_size, int create);

int fat_engine_mkfs(uint64_t block_handle, uint32_t sector_size, uint64_t sector_count,
		    FatMkfsType type, uint32_t au_size_bytes);

/* Writes the label of an already formatted FAT/exFAT volume via f_setlabel. */
int fat_engine_set_label(uint64_t block_handle, uint32_t sector_size, uint64_t sector_count,
			 const char *label);

/* Reads the label of a formatted FAT/exFAT volume via f_getlabel (UTF-8, NUL-terminated). */
int fat_engine_get_label(uint64_t block_handle, uint32_t sector_size, uint64_t sector_count,
			 char *out, size_t cap);

FatFsKind fat_engine_probe(uint64_t block_handle, uint32_t sector_size, uint64_t sector_count);

#ifdef __cplusplus
}
#endif

#endif /* FAT_ENGINE_H */
