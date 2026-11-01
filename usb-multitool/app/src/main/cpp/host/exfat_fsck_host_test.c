/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Host test: load a small corrupt exFAT image (upstream bs_bad_csum fixture),
 * detect the error via relative-LBA block callbacks, repair, re-check, and
 * read the boot OEM name back. No /dev, no FUSE, no root.
 */
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "usb_block.h"

#include "exfat_ondisk.h"
#include "libexfat.h"
#include "exfat_fsck_lib.h"

typedef struct {
    int fd;
    uint64_t sector_size;
    uint64_t sector_count;
    uint64_t write_count;
} file_backend;

static int file_read(void *ctx, uint64_t lba, void *buf, uint32_t sectors) {
    file_backend *b = ctx;
    size_t bytes = (size_t)sectors * (size_t)b->sector_size;
    off_t off = (off_t)(lba * b->sector_size);
    uint8_t *p = buf;
    while (bytes > 0) {
        ssize_t n = pread(b->fd, p, bytes, off);
        if (n < 0)
            return -errno;
        if (n == 0)
            return -EIO;
        p += n;
        off += n;
        bytes -= (size_t)n;
    }
    return 0;
}

static int file_write(void *ctx, uint64_t lba, const void *buf, uint32_t sectors) {
    file_backend *b = ctx;
    size_t bytes = (size_t)sectors * (size_t)b->sector_size;
    off_t off = (off_t)(lba * b->sector_size);
    const uint8_t *p = buf;
    b->write_count += sectors;
    while (bytes > 0) {
        ssize_t n = pwrite(b->fd, p, bytes, off);
        if (n < 0)
            return -errno;
        if (n == 0)
            return -EIO;
        p += n;
        off += n;
        bytes -= (size_t)n;
    }
    return 0;
}

static int file_sync(void *ctx) {
    file_backend *b = ctx;
    return fsync(b->fd) == 0 ? 0 : -errno;
}

static const struct exfat_block_ops kOps = {
    usb_block_read,
    usb_block_write,
    usb_block_sync,
};

static int extract_fixture(const char *archive, const char *out_path) {
    char cmd[768];
    snprintf(cmd, sizeof(cmd),
             "xz -dc '%s' | tar -xOf - exfat.img > '%s'",
             archive, out_path);
    return system(cmd) == 0 ? 0 : -1;
}

static int open_image(const char *path, file_backend *b) {
    struct stat st;
    int fd = open(path, O_RDWR);
    if (fd < 0)
        return -errno;
    if (fstat(fd, &st) != 0) {
        int e = -errno;
        close(fd);
        return e;
    }
    if (st.st_size < 512) {
        close(fd);
        return -EINVAL;
    }
    b->fd = fd;
    b->sector_size = 512;
    b->sector_count = (uint64_t)st.st_size / b->sector_size;
    b->write_count = 0;
    return 0;
}

static int run_fsck(uint64_t handle, const file_backend *b, int repair) {
    struct exfat_blk_dev bd;
    int rc = exfat_setup_block_dev(&bd, handle, &kOps,
                                   (unsigned int)b->sector_size,
                                   b->sector_count);
    int exit_code;
    if (rc < 0) {
        fprintf(stderr, "setup_block_dev failed: %d\n", rc);
        return 99;
    }
    exit_code = exfat_fsck_on_blk_dev(&bd, repair);
    exfat_deinit_blk_dev_info(&bd);
    return exit_code;
}

static int read_oem(uint64_t handle, char out[9]) {
    unsigned char sector[512];
    struct pbr *bs = (struct pbr *)sector;
    int rc = usb_block_read(handle, 0, sector, 1);
    if (rc != 0)
        return rc;
    memcpy(out, bs->bpb.oem_name, 8);
    out[8] = '\0';
    return 0;
}

int main(int argc, char **argv) {
    const char *archive = argc > 1
        ? argv[1]
        : "third_party/exfatprogs/testdata/exfat.img.tar.xz";
    char image_buf[256];
    const char *tmpdir = getenv("TMPDIR");
    if (!tmpdir || !tmpdir[0])
        tmpdir = "/tmp";
    snprintf(image_buf, sizeof(image_buf), "%s/exfat-host-test.img", tmpdir);
    const char *image = image_buf;
    file_backend backend;
    uint64_t handle = 1;
    int rc;
    int check_corrupt;
    int repair_rc;
    int check_clean;
    char oem[9];
    unsigned char buf[512];
    uint64_t writes_before_check;

    if (extract_fixture(archive, image) != 0) {
        fprintf(stderr, "failed to extract %s\n", archive);
        return 1;
    }
    rc = open_image(image, &backend);
    if (rc != 0) {
        fprintf(stderr, "open_image: %d\n", rc);
        return 1;
    }

    rc = usb_block_register(handle, backend.sector_size, backend.sector_count,
                            file_read, file_write, file_sync, &backend);
    if (rc != 0) {
        fprintf(stderr, "usb_block_register: %d\n", rc);
        close(backend.fd);
        return 1;
    }

    /* Partition bounds enforced inside callback. */
    rc = usb_block_read(handle, backend.sector_count, buf, 1);
    if (rc != -ERANGE) {
        fprintf(stderr, "expected -ERANGE on OOB read, got %d\n", rc);
        usb_block_unregister(handle);
        close(backend.fd);
        return 1;
    }
    printf("bounds -ERANGE ok (sectors=%llu)\n",
           (unsigned long long)backend.sector_count);

    writes_before_check = backend.write_count;
    check_corrupt = run_fsck(handle, &backend, 0);
    printf("check(corrupt) exit=%d writes=%llu\n", check_corrupt,
           (unsigned long long)(backend.write_count - writes_before_check));
    if (check_corrupt == EXFAT_FSCK_EXIT_NO_ERRORS) {
        fprintf(stderr, "expected corruption detection\n");
        usb_block_unregister(handle);
        close(backend.fd);
        return 1;
    }
    if (backend.write_count != writes_before_check) {
        fprintf(stderr, "read-only check wrote %llu sectors\n",
                (unsigned long long)(backend.write_count - writes_before_check));
        usb_block_unregister(handle);
        close(backend.fd);
        return 1;
    }

    repair_rc = run_fsck(handle, &backend, 1);
    printf("repair exit=%d\n", repair_rc);
    if (repair_rc != EXFAT_FSCK_EXIT_NO_ERRORS &&
        repair_rc != EXFAT_FSCK_EXIT_CORRECTED) {
        fprintf(stderr, "repair failed: %d\n", repair_rc);
        usb_block_unregister(handle);
        close(backend.fd);
        return 1;
    }

    writes_before_check = backend.write_count;
    check_clean = run_fsck(handle, &backend, 0);
    printf("check(repaired) exit=%d writes=%llu\n", check_clean,
           (unsigned long long)(backend.write_count - writes_before_check));
    if (check_clean != EXFAT_FSCK_EXIT_NO_ERRORS) {
        fprintf(stderr, "re-check failed: %d\n", check_clean);
        usb_block_unregister(handle);
        close(backend.fd);
        return 1;
    }
    if (backend.write_count != writes_before_check) {
        fprintf(stderr, "read-only re-check wrote %llu sectors\n",
                (unsigned long long)(backend.write_count - writes_before_check));
        usb_block_unregister(handle);
        close(backend.fd);
        return 1;
    }

    if (read_oem(handle, oem) != 0) {
        fprintf(stderr, "read OEM via usb_block_read failed\n");
        usb_block_unregister(handle);
        close(backend.fd);
        return 1;
    }
    printf("oem='%s'\n", oem);
    if (strncmp(oem, "EXFAT", 5) != 0) {
        fprintf(stderr, "unexpected OEM name\n");
        usb_block_unregister(handle);
        close(backend.fd);
        return 1;
    }

    usb_block_unregister(handle);
    close(backend.fd);
    unlink(image);
    printf("PASS exfat_fsck_host_test\n");
    return 0;
}
