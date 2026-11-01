#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/*
 * Filesystem JNI boundary.
 *
 * Upstream engines (FatFs, ntfs-3g, lwext4) are vendored under third_party/
 * and wired through the callbacks below. Until those trees are fetched into
 * CI, this bridge exposes the mount/probe ABI and keeps the shared library
 * linkable. Disk I/O callbacks are registered from Kotlin via the partition
 * handle; engines never see absolute LBAs.
 */

enum usb_fs_id {
    USB_FS_FAT = 0,
    USB_FS_EXFAT = 1,
    USB_FS_NTFS = 2,
    USB_FS_EXT = 3,
};

typedef struct usb_partition_io {
    int64_t start_lba;
    int64_t sector_count;
    int32_t sector_size;
    /* Engine-facing callbacks (filled when engines are linked). */
    int (*disk_read)(void *user, uint64_t relative_lba, void *buf, uint32_t count);
    int (*disk_write)(void *user, uint64_t relative_lba, const void *buf, uint32_t count);
    void *user;
} usb_partition_io;

typedef struct usb_fs_handle {
    enum usb_fs_id id;
    usb_partition_io io;
    int open;
} usb_fs_handle;

/* FatFs disk_read/disk_write, ntfs-3g pread/pwrite, lwext4 block_dev hooks
 * all reduce to relative LBA + count against this partition IO object. */
int usb_partition_read(usb_partition_io *io, uint64_t relative_lba, void *buf, uint32_t sectors) {
    if (io == NULL || relative_lba + sectors > (uint64_t)io->sector_count) {
        return -1;
    }
    if (io->disk_read == NULL) {
        memset(buf, 0, (size_t)sectors * (size_t)io->sector_size);
        return 0;
    }
    return io->disk_read(io->user, relative_lba, buf, sectors);
}

int usb_partition_write(usb_partition_io *io, uint64_t relative_lba, const void *buf, uint32_t sectors) {
    if (io == NULL || relative_lba + sectors > (uint64_t)io->sector_count) {
        return -1;
    }
    if (io->disk_write == NULL) {
        return -1;
    }
    return io->disk_write(io->user, relative_lba, buf, sectors);
}

JNIEXPORT jlong JNICALL
Java_usb_fs_FilesystemNative_nativeMount(JNIEnv *env, jclass clazz,
                                         jint idOrdinal, jlong startLba,
                                         jlong sectorCount, jint sectorSize) {
    (void)env;
    (void)clazz;
    if (sectorSize <= 0 || sectorCount <= 0) {
        return 0;
    }
    usb_fs_handle *handle = (usb_fs_handle *)calloc(1, sizeof(usb_fs_handle));
    if (handle == NULL) {
        return 0;
    }
    handle->id = (enum usb_fs_id)idOrdinal;
    handle->io.start_lba = startLba;
    handle->io.sector_count = sectorCount;
    handle->io.sector_size = sectorSize;
    handle->open = 1;
    return (jlong)(intptr_t)handle;
}

JNIEXPORT void JNICALL
Java_usb_fs_FilesystemNative_nativeSync(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    usb_fs_handle *h = (usb_fs_handle *)(intptr_t)handle;
    if (h == NULL || !h->open) {
        return;
    }
    /* Upstream sync: f_sync / ntfsmount flush / ext4_cache_flush. */
}

JNIEXPORT void JNICALL
Java_usb_fs_FilesystemNative_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    usb_fs_handle *h = (usb_fs_handle *)(intptr_t)handle;
    if (h == NULL) {
        return;
    }
    h->open = 0;
    free(h);
}

JNIEXPORT jint JNICALL
Java_usb_fs_FilesystemNative_nativeProbe(JNIEnv *env, jclass clazz,
                                         jlong startLba, jlong sectorCount,
                                         jint sectorSize) {
    (void)env;
    (void)clazz;
    (void)startLba;
    (void)sectorCount;
    (void)sectorSize;
    /* Probe order is owned by Kotlin Filesystem.probe; native returns -1 here. */
    return -1;
}
