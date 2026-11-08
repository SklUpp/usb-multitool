/* SPDX-License-Identifier: GPL-2.0-or-later */
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "ntfs_engine.h"
#include "usb_block.h"

#define HAVE_CONFIG_H 1
#include "config.h"
#include "ntfs-3g/types.h"
#include "ntfs-3g/layout.h"
#include "ntfs-3g/device.h"
#include "ntfs-3g/volume.h"
#include "ntfs-3g/attrib.h"
#include "ntfs-3g/dir.h"

typedef struct FileBlockCtx {
    int fd;
    uint32_t sector_size;
    uint64_t sector_count;
    /* Read-failure injection over an LBA range (fail_lba_to == 0 disables).
     * Used to prove that a failing directory-index read is reported instead of
     * returning a short listing. */
    uint64_t fail_lba_from;
    uint64_t fail_lba_to;
} FileBlockCtx;

static int file_read(void *ctx, uint64_t lba, void *buf, uint32_t sectors)
{
    FileBlockCtx *f = (FileBlockCtx *)ctx;
    if (f->fail_lba_to > f->fail_lba_from && lba >= f->fail_lba_from &&
        lba < f->fail_lba_to) {
        return -1;
    }
    off_t off = (off_t)(lba * f->sector_size);
    size_t bytes = (size_t)sectors * f->sector_size;
    ssize_t n = pread(f->fd, buf, bytes, off);
    return (n == (ssize_t)bytes) ? 0 : -1;
}

static int file_write(void *ctx, uint64_t lba, const void *buf, uint32_t sectors)
{
    FileBlockCtx *f = (FileBlockCtx *)ctx;
    off_t off = (off_t)(lba * f->sector_size);
    size_t bytes = (size_t)sectors * f->sector_size;
    ssize_t n = pwrite(f->fd, buf, bytes, off);
    return (n == (ssize_t)bytes) ? 0 : -1;
}

static int file_sync(void *ctx)
{
    FileBlockCtx *f = (FileBlockCtx *)ctx;
    return fsync(f->fd);
}

/* FNV-1a over the whole image, to prove a rejected write changed no bytes. */
static uint64_t hash_image(int fd, uint64_t size)
{
    uint64_t h = 1469598103934665603ULL;
    unsigned char buf[65536];
    uint64_t off = 0;
    while (off < size) {
        size_t want = sizeof(buf);
        if (size - off < want) want = (size_t)(size - off);
        ssize_t n = pread(fd, buf, want, (off_t)off);
        if (n <= 0) break;
        for (ssize_t i = 0; i < n; i++) {
            h ^= buf[i];
            h *= 1099511628211ULL;
        }
        off += (uint64_t)n;
    }
    return h;
}

/* Decompress fixture xz archive to temporary disk image */
static int unpack_fixture(const char *fixture_xz, const char *out_img)
{
    char cmd[1024];
    snprintf(cmd, sizeof(cmd), "xz -dc '%s' > '%s'", fixture_xz, out_img);
    return system(cmd) == 0 ? 0 : -1;
}

/* Type matching NtfsVolumeContext in ntfs_engine.c */
typedef struct NtfsVolumeContext {
    ntfs_volume *vol;
    struct ntfs_device *dev;
} NtfsVolumeContext;

int main(int argc, char **argv)
{
    const char *fixture_xz = (argc > 1) ? argv[1] : "usb-multitool/third_party/ntfs-3g/testdata/ntfs.img.xz";
    char tmp_img[] = "/tmp/ntfs_host_test_XXXXXX.img";
    int tmp_fd = mkstemps(tmp_img, 4);
    if (tmp_fd < 0) {
        snprintf(tmp_img, sizeof(tmp_img), "./ntfs_host_test_tmp.img");
    } else {
        close(tmp_fd);
    }

    printf("=== NTFS Host Integration Test ===\n");
    printf("Unpacking fixture: %s -> %s\n", fixture_xz, tmp_img);
    if (unpack_fixture(fixture_xz, tmp_img) != 0) {
        fprintf(stderr, "FAIL: failed to unpack fixture %s\n", fixture_xz);
        return 1;
    }

    int fd = open(tmp_img, O_RDWR);
    if (fd < 0) {
        perror("open temporary image");
        unlink(tmp_img);
        return 1;
    }

    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 1024 * 1024) {
        fprintf(stderr, "FAIL: invalid image size (%lld)\n", (long long)st.st_size);
        close(fd);
        unlink(tmp_img);
        return 1;
    }

    const uint32_t sector_size = 512;
    const uint64_t sector_count = (uint64_t)st.st_size / sector_size;

    FileBlockCtx fctx = {
        .fd = fd,
        .sector_size = sector_size,
        .sector_count = sector_count,
    };

    UsbBlockOps bops = {
        .read = file_read,
        .write = file_write,
        .sync = file_sync,
        .ctx = &fctx,
        .sector_size = sector_size,
        .sector_count = sector_count,
    };

    uint64_t bhandle = usb_block_register(&bops);
    assert(bhandle != 0);

    /* 1. Mount NTFS volume */
    uint64_t vol = ntfs_engine_mount(bhandle, false);
    if (!vol) {
        fprintf(stderr, "FAIL: ntfs_engine_mount failed\n");
        close(fd);
        unlink(tmp_img);
        return 1;
    }
    printf("PASS: mount NTFS volume over usb_block (handle=0x%llx)\n", (unsigned long long)vol);

    /* 2. Check initial clean state */
    int dirty = ntfs_engine_is_dirty(vol);
    assert(dirty == 0);
    printf("PASS: initial volume dirty flag is clean (dirty=%d)\n", dirty);

    /* 3. Write file exercising bounce buffer and sector translation */
    const char *payload = "Hello NTFS World from host test with relative-LBA bounce buffer!";
    size_t payload_len = strlen(payload);
    size_t written = 0;
    int rc = ntfs_engine_write(vol, "/test_file.txt", 0, payload, payload_len, true, &written);
    assert(rc == 0);
    assert(written == payload_len);
    printf("PASS: write file '/test_file.txt' (bytes=%zu)\n", written);

    /* 4. Sync volume */
    rc = ntfs_engine_sync(vol);
    assert(rc == 0);
    printf("PASS: sync volume\n");

    /* 5. Read back and verify */
    char read_buf[128] = {0};
    size_t read_bytes = 0;
    rc = ntfs_engine_read(vol, "/test_file.txt", 0, read_buf, sizeof(read_buf) - 1, &read_bytes);
    assert(rc == 0);
    assert(read_bytes == payload_len);
    assert(strcmp(read_buf, payload) == 0);
    printf("PASS: read back verified ('%s')\n", read_buf);

    /* 5b. create=true at offset 0 truncates; non-zero offset appends. */
    {
        const char *short_payload = "SHORT";
        written = 0;
        rc = ntfs_engine_write(vol, "/test_file.txt", 0, short_payload, strlen(short_payload),
                               true, &written);
        assert(rc == 0);
        assert(written == strlen(short_payload));
        char trunc_buf[128] = {0};
        size_t trunc_bytes = 0;
        rc = ntfs_engine_read(vol, "/test_file.txt", 0, trunc_buf, sizeof(trunc_buf) - 1,
                              &trunc_bytes);
        assert(rc == 0);
        assert(trunc_bytes == strlen(short_payload));
        assert(strcmp(trunc_buf, short_payload) == 0);

        written = 0;
        rc = ntfs_engine_write(vol, "/test_file.txt", trunc_bytes, "-TAIL", 5, true, &written);
        assert(rc == 0);
        assert(written == 5);
        memset(trunc_buf, 0, sizeof(trunc_buf));
        trunc_bytes = 0;
        rc = ntfs_engine_read(vol, "/test_file.txt", 0, trunc_buf, sizeof(trunc_buf) - 1,
                              &trunc_bytes);
        assert(rc == 0);
        assert(trunc_bytes == strlen("SHORT-TAIL"));
        assert(strcmp(trunc_buf, "SHORT-TAIL") == 0);
        printf("PASS: create=true truncates at offset 0; append at non-zero offset\n");

        /* Restore original payload for later steps that expect it. */
        written = 0;
        rc = ntfs_engine_write(vol, "/test_file.txt", 0, payload, payload_len, true, &written);
        assert(rc == 0);
        assert(written == payload_len);
    }

    /* 6. Stat file */
    NtfsStat nstat;
    memset(&nstat, 0, sizeof(nstat));
    rc = ntfs_engine_stat(vol, "/test_file.txt", &nstat);
    assert(rc == 0);
    assert(nstat.size == payload_len);
    assert(!nstat.is_directory);
    printf("PASS: stat verified (size=%llu, is_dir=%d)\n",
           (unsigned long long)nstat.size, nstat.is_directory);

    /* 7. List root directory */
    NtfsDirEntry *entries = NULL;
    size_t count = 0;
    rc = ntfs_engine_list(vol, "/", &entries, &count);
    assert(rc == 0);
    assert(count >= 1);
    bool found = false;
    for (size_t i = 0; i < count; i++) {
        if (strcmp(entries[i].name, "test_file.txt") == 0) {
            found = true;
            assert(!entries[i].is_directory);
            assert(entries[i].size == payload_len);
        }
    }
    assert(found);
    printf("PASS: list directory found test_file.txt (total %zu entries)\n", count);
    free(entries);
    entries = NULL;

    /* 7b. A maximal non-ASCII name must survive listing byte-for-byte.
     * 255 Cyrillic units = 510 UTF-8 bytes, which a 256-byte field truncates. */
    {
        char long_name[1 + 255 * 2 + 1];
        long_name[0] = '/';
        for (int i = 0; i < 255; i++) {
            long_name[1 + i * 2] = (char)0xD1;      /* U+044F in UTF-8 */
            long_name[2 + i * 2] = (char)0x8F;
        }
        long_name[1 + 255 * 2] = '\0';
        assert(strlen(long_name) == 1 + 510);

        written = 0;
        rc = ntfs_engine_write(vol, long_name, 0, "u", 1, true, &written);
        assert(rc == 0);
        assert(written == 1);

        entries = NULL;
        count = 0;
        rc = ntfs_engine_list(vol, "/", &entries, &count);
        assert(rc == 0);
        bool long_found = false;
        for (size_t i = 0; i < count; i++) {
            if (strcmp(entries[i].name, long_name + 1) == 0) long_found = true;
        }
        assert(long_found);
        free(entries);
        entries = NULL;
        printf("PASS: 510-byte UTF-8 name listed without truncation\n");
    }

    /* 7c. A directory whose index cannot be read must fail, never list short.
     * Enough long entries force the $I30 index into INDX blocks so readdir
     * has to hit the device; fail exactly that (last) read. */
    {
        const int n_files = 60;
        char name[300];
        for (int i = 0; i < n_files; i++) {
            memset(name, 0, sizeof(name));
            snprintf(name, sizeof(name), "/f%03d_", i);
            size_t cur = strlen(name);
            for (size_t j = 0; j < 180; j++) name[cur + j] = 'x';
            name[cur + 180] = '\0';
            written = 0;
            rc = ntfs_engine_write(vol, name, 0, "x", 1, true, &written);
            assert(rc == 0);
        }
        rc = ntfs_engine_sync(vol);
        assert(rc == 0);

        NtfsDirEntry *big = NULL;
        size_t big_count = 0;
        rc = ntfs_engine_list(vol, "/", &big, &big_count);
        assert(rc == 0);
        assert(big_count >= (size_t)n_files + 2);
        free(big);
        big = NULL;

        /* Fail reads of the root directory's non-resident $I30 index blocks:
         * pathname resolution and the per-entry stat reads stay intact. */
        NtfsVolumeContext *vctx_dir = (NtfsVolumeContext *)(uintptr_t)vol;
        ntfs_inode *root_ni = ntfs_pathname_to_inode(vctx_dir->vol, NULL, "/");
        assert(root_ni != NULL);
        ntfs_attr *ia = ntfs_attr_open(root_ni, AT_INDEX_ALLOCATION, NTFS_INDEX_I30, 4);
        assert(ia != NULL); /* directory entries must have spilled out of the index root */
        assert(ntfs_attr_map_runlist(ia, 0) == 0);
        LCN lcn = ntfs_rl_vcn_to_lcn(ia->rl, 0);
        assert(lcn >= 0);
        uint64_t idx_lba = (uint64_t)lcn * vctx_dir->vol->cluster_size / sector_size;
        fctx.fail_lba_from = idx_lba;
        fctx.fail_lba_to = idx_lba + (uint64_t)(ia->data_size / (s64)sector_size);
        assert(fctx.fail_lba_to > fctx.fail_lba_from);
        ntfs_attr_close(ia);
        ntfs_inode_close(root_ni);

        big = (NtfsDirEntry *)0x1;
        big_count = 99;
        rc = ntfs_engine_list(vol, "/", &big, &big_count);
        fctx.fail_lba_from = 0;
        fctx.fail_lba_to = 0;
        assert(rc == -EIO);
        assert(big == NULL);
        assert(big_count == 0);
        printf("PASS: unreadable directory index reports -EIO instead of a short listing\n");
    }

    /* 8. Mark volume dirty and close */
    NtfsVolumeContext *vctx = (NtfsVolumeContext *)(uintptr_t)vol;
    vctx->vol->flags |= VOLUME_IS_DIRTY;
    rc = ntfs_volume_write_flags(vctx->vol, vctx->vol->flags);
    assert(rc == 0);
    rc = ntfs_engine_close(vol);
    assert(rc == 0);
    printf("PASS: marked volume dirty and closed volume\n");

    /* 9. Detect dirty flag on closed device */
    dirty = ntfs_engine_check_dirty_dev(bhandle);
    assert(dirty == 1);
    printf("PASS: detected dirty flag on device (dirty=%d)\n", dirty);

    /* 10. Confirmation token enforcement */
    rc = ntfs_engine_clear_dirty(bhandle, "WRONG_TOKEN");
    assert(rc != 0);
    printf("PASS: invalid confirmation token correctly rejected\n");

    /* 11. Clear dirty flag (ntfsfix equivalent) */
    rc = ntfs_engine_clear_dirty(bhandle, "CONFIRM_NTFS_CLEAR_DIRTY");
    assert(rc == 0);
    printf("PASS: cleared dirty flag with valid confirmation\n");

    /* 12. Re-detect dirty flag: must now be 0 */
    dirty = ntfs_engine_check_dirty_dev(bhandle);
    assert(dirty == 0);
    printf("PASS: verified dirty flag on device is now clean (dirty=%d)\n", dirty);

    /* 13. Re-mount and verify data persisted across dirty clear */
    vol = ntfs_engine_mount(bhandle, false);
    assert(vol != 0);
    memset(read_buf, 0, sizeof(read_buf));
    read_bytes = 0;
    rc = ntfs_engine_read(vol, "/test_file.txt", 0, read_buf, sizeof(read_buf) - 1, &read_bytes);
    assert(rc == 0);
    assert(read_bytes == payload_len);
    assert(strcmp(read_buf, payload) == 0);
    rc = ntfs_engine_close(vol);
    assert(rc == 0);
    printf("PASS: re-mounted and verified file data integrity\n");

    /* 14. Read-only mount must refuse writes with -EROFS and touch no bytes.
     * libntfs-3g skips the device write but reports the full length as written,
     * so this can only be caught by the engine returning -EROFS itself. */
    {
        uint64_t ro = ntfs_engine_mount(bhandle, true);
        assert(ro != 0);

        uint64_t hash_before = hash_image(fd, (uint64_t)st.st_size);
        size_t ro_written = 123;
        rc = ntfs_engine_write(ro, "/test_file.txt", 0, "overwrite", 9, false, &ro_written);
        assert(rc == -EROFS);
        assert(ro_written == 0);

        ro_written = 123;
        rc = ntfs_engine_write(ro, "/ro_new_file.txt", 0, "x", 1, true, &ro_written);
        assert(rc == -EROFS);
        assert(ro_written == 0);

        /* Reads still work on a read-only mount. */
        memset(read_buf, 0, sizeof(read_buf));
        read_bytes = 0;
        rc = ntfs_engine_read(ro, "/test_file.txt", 0, read_buf, sizeof(read_buf) - 1, &read_bytes);
        assert(rc == 0);
        assert(strcmp(read_buf, payload) == 0);

        rc = ntfs_engine_close(ro);
        assert(rc == 0);

        uint64_t hash_after = hash_image(fd, (uint64_t)st.st_size);
        assert(hash_after == hash_before);
        printf("PASS: read-only mount rejected writes with -EROFS, image unchanged\n");
    }

    close(fd);
    unlink(tmp_img);
    printf("ALL NTFS INTEGRATION TESTS PASSED SUCCESSFULLY!\n");
    return 0;
}
