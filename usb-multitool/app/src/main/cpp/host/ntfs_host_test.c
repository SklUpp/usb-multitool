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
    /* Write-failure injection over an LBA range (fail_write_lba_to == 0 disables). */
    uint64_t fail_write_lba_from;
    uint64_t fail_write_lba_to;
    /* Sync-failure injection: consume one failure per count. Used to prove that label
     * writeback errors are reported instead of returning success. */
    int fail_sync;
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
    if (f->fail_write_lba_to > f->fail_write_lba_from &&
        lba >= f->fail_write_lba_from && lba < f->fail_write_lba_to) {
        errno = EIO;
        return -1;
    }
    off_t off = (off_t)(lba * f->sector_size);
    size_t bytes = (size_t)sectors * f->sector_size;
    ssize_t n = pwrite(f->fd, buf, bytes, off);
    return (n == (ssize_t)bytes) ? 0 : -1;
}

static int file_sync(void *ctx)
{
    FileBlockCtx *f = (FileBlockCtx *)ctx;
    if (f->fail_sync > 0) {
        f->fail_sync--;
        errno = EIO;
        return -1;
    }
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

/* Must match NtfsVolumeContext layout in ntfs_engine.c (vol first). */
typedef struct NtfsVolumeContext {
    ntfs_volume *vol;
    struct ntfs_device *dev;
    /* trailing fields unused by host tests; keep size ≥ engine for cast safety */
    char opaque[64];
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

    /* 1b. Metadata-only usage: sane values, no allocation/write. */
    uint64_t total_bytes = 0;
    uint64_t free_bytes = 0;
    int usage_rc = ntfs_engine_usage(vol, &total_bytes, &free_bytes);
    assert(usage_rc == 0);
    assert(total_bytes > 0);
    assert(free_bytes <= total_bytes);
    printf("PASS: usage total=%llu free=%llu\n",
           (unsigned long long)total_bytes, (unsigned long long)free_bytes);

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
    written = 0;
    rc = ntfs_engine_write_exclusive(vol, "/test_file.txt", 0, "x", 1, true, true, &written);
    assert(rc == -EEXIST);
    assert(written == 0);
    printf("PASS: NTFS exclusive create rejects existing file\n");
    printf("PASS: write existing file remains intact after exclusive create rejection (bytes=%zu)\n", written);

    /* Allocation must not increase free space; totals stay stable. */
    {
        uint64_t total_after = 0;
        uint64_t free_after = 0;
        usage_rc = ntfs_engine_usage(vol, &total_after, &free_after);
        assert(usage_rc == 0);
        assert(total_after == total_bytes);
        assert(free_after <= free_bytes);
    }

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

    /* Engine must refuse RW mount while dirty (fail-closed). */
    {
        uint64_t dirty_rw = ntfs_engine_mount(bhandle, false);
        assert(dirty_rw == 0);
        printf("PASS: RW mount refused while volume dirty\n");
    }

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


    /* --- mkdir / delete / rename / metadata guards (fixture only) --- */
    {
        uint64_t vol = ntfs_engine_mount(bhandle, false);
        assert(vol != 0);

        int rc = ntfs_engine_mkdir(vol, "/host_mut_dir");
        assert(rc == 0);
        rc = ntfs_engine_mkdir(vol, "/host_mut_dir");
        assert(rc == -EEXIST);

        size_t wrote = 0;
        rc = ntfs_engine_write(vol, "/host_mut_dir/file.txt", 0, "abc", 3, true, &wrote);
        assert(rc == 0 && wrote == 3);

        rc = ntfs_engine_delete(vol, "/host_mut_dir");
        assert(rc == -ENOTEMPTY);
        printf("PASS: nonempty directory delete returns -ENOTEMPTY\n");

        rc = ntfs_engine_delete(vol, "/host_mut_dir/file.txt");
        assert(rc == 0);
        rc = ntfs_engine_delete(vol, "/host_mut_dir");
        assert(rc == 0);
        printf("PASS: empty directory delete succeeds\n");

        wrote = 0;
        rc = ntfs_engine_write(vol, "/rename_src.txt", 0, "xy", 2, true, &wrote);
        assert(rc == 0);
        rc = ntfs_engine_rename(vol, "/rename_src.txt", "/rename_dst.txt");
        assert(rc == 0);
        NtfsStat st;
        rc = ntfs_engine_stat(vol, "/rename_src.txt", &st);
        assert(rc == -ENOENT);
        rc = ntfs_engine_stat(vol, "/rename_dst.txt", &st);
        assert(rc == 0 && st.size == 2);
        printf("PASS: file rename moves path\n");

        wrote = 0;
        rc = ntfs_engine_write(vol, "/rename_exists.txt", 0, "z", 1, true, &wrote);
        assert(rc == 0);
        rc = ntfs_engine_rename(vol, "/rename_dst.txt", "/rename_exists.txt");
        assert(rc == -EEXIST);
        printf("PASS: rename onto existing path returns -EEXIST\n");

        rc = ntfs_engine_mkdir(vol, "/dir_a");
        assert(rc == 0);
        rc = ntfs_engine_mkdir(vol, "/dir_b");
        assert(rc == 0);
        rc = ntfs_engine_rename(vol, "/dir_a", "/dir_b/dir_a");
        assert(rc == -EXDEV);
        printf("PASS: cross-directory directory rename refused (-EXDEV)\n");

        rc = ntfs_engine_delete(vol, "/$MFT");
        assert(rc == -EPERM);
        rc = ntfs_engine_rename(vol, "/$MFT", "/$MFT_renamed");
        assert(rc == -EPERM);
        printf("PASS: $MFT delete/rename refused (-EPERM)\n");

        wrote = 0;
        rc = ntfs_engine_write(vol, "/$MFT", 0, "X", 1, true, &wrote);
        assert(rc == -EPERM);
        printf("PASS: $MFT write refused (-EPERM)\n");

        /* Parent system dirs refuse create/mkdir/rename-into. Root stays allowed. */
        wrote = 0;
        rc = ntfs_engine_write(vol, "/$Extend/host_forbid.txt", 0, "X", 1, true, &wrote);
        assert(rc == -EPERM);
        rc = ntfs_engine_mkdir(vol, "/$Extend/host_forbid_dir");
        assert(rc == -EPERM);
        wrote = 0;
        rc = ntfs_engine_write(vol, "/parent_guard_src.txt", 0, "P", 1, true, &wrote);
        assert(rc == 0);
        rc = ntfs_engine_rename(vol, "/parent_guard_src.txt", "/$Extend/parent_guard_dst.txt");
        assert(rc == -EPERM);
        {
            NtfsStat stg;
            assert(ntfs_engine_stat(vol, "/parent_guard_src.txt", &stg) == 0);
        }
        printf("PASS: $Extend parent create/mkdir/rename refused; root still writable\n");

        /* Same-parent directory rename must succeed. */
        rc = ntfs_engine_mkdir(vol, "/same_parent_a");
        assert(rc == 0);
        rc = ntfs_engine_rename(vol, "/same_parent_a", "/same_parent_b");
        assert(rc == 0);
        {
            NtfsStat st2;
            assert(ntfs_engine_stat(vol, "/same_parent_a", &st2) == -ENOENT);
            assert(ntfs_engine_stat(vol, "/same_parent_b", &st2) == 0);
        }
        printf("PASS: same-parent directory rename\n");

        /*
         * Rename rollback: unlink-fail after link rolls back `to` when `from`
         * still resolves. Ambiguous still_from lookup keeps both + -EUCLEAN.
         */
#ifdef NTFS_TEST_HOOKS
        wrote = 0;
        rc = ntfs_engine_write(vol, "/rb_src.txt", 0, "R", 1, true, &wrote);
        assert(rc == 0 && wrote == 1);
        ntfs_test_set_fail_rename_unlink(1);
        rc = ntfs_engine_rename(vol, "/rb_src.txt", "/rb_dst.txt");
        ntfs_test_set_fail_rename_unlink(0);
        assert(rc == -EIO);
        {
            NtfsStat st3;
            assert(ntfs_engine_stat(vol, "/rb_src.txt", &st3) == 0);
            assert(ntfs_engine_stat(vol, "/rb_dst.txt", &st3) == -ENOENT);
        }
        printf("PASS: rename unlink-fail rolls back destination\n");

        wrote = 0;
        rc = ntfs_engine_write(vol, "/partial_src.txt", 0, "Q", 1, true, &wrote);
        assert(rc == 0 && wrote == 1);
        ntfs_test_set_fail_rename_unlink(1);
        ntfs_test_set_fail_still_from_lookup(1);
        rc = ntfs_engine_rename(vol, "/partial_src.txt", "/partial_dst.txt");
        ntfs_test_set_fail_still_from_lookup(0);
        ntfs_test_set_fail_rename_unlink(0);
        assert(rc == -EUCLEAN);
        {
            NtfsStat st4;
            assert(ntfs_engine_stat(vol, "/partial_src.txt", &st4) == 0);
            assert(ntfs_engine_stat(vol, "/partial_dst.txt", &st4) == 0);
        }
        printf("PASS: ambiguous rename keeps both names (-EUCLEAN)\n");
#else
        wrote = 0;
        rc = ntfs_engine_write(vol, "/keep_src.txt", 0, "K", 1, true, &wrote);
        assert(rc == 0 && wrote == 1);
        rc = ntfs_engine_rename(vol, "/keep_src.txt", "/keep_dst.txt");
        assert(rc == 0);
        {
            NtfsStat st3;
            assert(ntfs_engine_stat(vol, "/keep_src.txt", &st3) == -ENOENT);
            assert(ntfs_engine_stat(vol, "/keep_dst.txt", &st3) == 0 && st3.size == 1);
        }
        printf("PASS: rename leaves surviving destination name\n");
#endif


        rc = ntfs_engine_close(vol);
        assert(rc == 0);
    }

    close(fd);
    unlink(tmp_img);

    /* --- upstream mkntfs over usb_block: format → mount → read/write → remount --- */
    {
        char fmt_img[] = "/tmp/ntfs_mkfs_XXXXXX.img";
        int fmt_fd = mkstemps(fmt_img, 4);
        assert(fmt_fd >= 0);

        const uint32_t fss = 512;
        const uint64_t fsc = (32ull * 1024 * 1024) / fss;
        assert(ftruncate(fmt_fd, (off_t)fsc * fss) == 0);

        FileBlockCtx fctx_fmt = {
            .fd = fmt_fd,
            .sector_size = fss,
            .sector_count = fsc,
        };
        UsbBlockOps fmt_ops = {
            .read = file_read,
            .write = file_write,
            .sync = file_sync,
            .ctx = &fctx_fmt,
            .sector_size = fss,
            .sector_count = fsc,
        };
        uint64_t fmt_block = usb_block_register(&fmt_ops);
        assert(fmt_block != 0);

        /* Geometry smaller than mkntfs' 1 MiB minimum is refused up front. */
        char tiny_img[] = "/tmp/ntfs_tiny_XXXXXX.img";
        int tiny_fd = mkstemps(tiny_img, 4);
        assert(tiny_fd >= 0);
        assert(ftruncate(tiny_fd, 8 * 1024) == 0);
        FileBlockCtx tiny_ctx = {
            .fd = tiny_fd,
            .sector_size = fss,
            .sector_count = 16,
        };
        UsbBlockOps tiny_ops = {
            .read = file_read,
            .write = file_write,
            .sync = file_sync,
            .ctx = &tiny_ctx,
            .sector_size = fss,
            .sector_count = 16,
        };
        uint64_t tiny_block = usb_block_register(&tiny_ops);
        assert(ntfs_engine_mkfs(tiny_block, "TINY", true) == -EINVAL);
        usb_block_unregister(tiny_block);
        close(tiny_fd);
        unlink(tiny_img);
        printf("PASS: mkfs rejects geometry below the NTFS minimum\n");

        /* Upstream mkntfs only marks the volume dirty when the backup boot
         * sector write fails and still returns success. Engine must fail
         * closed instead of reporting a formatted volume. */
        char bad_img[] = "/tmp/ntfs_backupfail_XXXXXX.img";
        int bad_fd = mkstemps(bad_img, 4);
        assert(bad_fd >= 0);
        assert(ftruncate(bad_fd, (off_t)fsc * fss) == 0);
        FileBlockCtx bad_ctx = {
            .fd = bad_fd,
            .sector_size = fss,
            .sector_count = fsc,
            .fail_write_lba_from = fsc - 1,
            .fail_write_lba_to = fsc,
        };
        UsbBlockOps bad_ops = {
            .read = file_read,
            .write = file_write,
            .sync = file_sync,
            .ctx = &bad_ctx,
            .sector_size = fss,
            .sector_count = fsc,
        };
        uint64_t bad_block = usb_block_register(&bad_ops);
        assert(bad_block != 0);
        assert(ntfs_engine_mkfs(bad_block, "BACKUPFAIL", true) == -EIO);
        usb_block_unregister(bad_block);
        close(bad_fd);
        unlink(bad_img);
        printf("PASS: failed backup boot sector is reported as mkfs failure\n");

        /* Full (non-quick) format exercises mkntfs' zero-fill path too. */
        int mrc = ntfs_engine_mkfs(fmt_block, "HOSTMKFS", false);
        assert(mrc == 0);

        uint64_t mv = ntfs_engine_mount(fmt_block, false);
        assert(mv != 0);
        assert(ntfs_engine_is_dirty(mv) == 0);

        const char *mkfs_payload = "mkfs round trip over usb_block";
        size_t mkfs_len = strlen(mkfs_payload);
        size_t mkfs_written = 0;
        rc = ntfs_engine_write(mv, "/mkfs.txt", 0, mkfs_payload, mkfs_len, true,
                               &mkfs_written);
        assert(rc == 0);
        assert(mkfs_written == mkfs_len);
        assert(ntfs_engine_sync(mv) == 0);
        assert(ntfs_engine_close(mv) == 0);
        printf("PASS: mkntfs formatted volume mounts rw and accepts a write\n");

        mv = ntfs_engine_mount(fmt_block, true);
        assert(mv != 0);
        char mkfs_read[64] = {0};
        size_t mkfs_read_len = 0;
        rc = ntfs_engine_read(mv, "/mkfs.txt", 0, mkfs_read, sizeof(mkfs_read) - 1,
                              &mkfs_read_len);
        assert(rc == 0);
        assert(mkfs_read_len == mkfs_len);
        assert(strcmp(mkfs_read, mkfs_payload) == 0);
        assert(ntfs_engine_close(mv) == 0);
        printf("PASS: remounted formatted volume reads data back\n");

        /* Second mkntfs in the same process: formatter globals must be reset
         * (allocation list head, bad-block count) or this reuses freed memory. */
        assert(ntfs_engine_mkfs(fmt_block, "SECOND", true) == 0);
        mv = ntfs_engine_mount(fmt_block, true);
        assert(mv != 0);
        NtfsStat root_stat;
        assert(ntfs_engine_stat(mv, "/", &root_stat) == 0);
        assert(root_stat.is_directory);
        assert(ntfs_engine_close(mv) == 0);
        printf("PASS: repeated mkntfs succeeds and produces a mountable volume\n");

        usb_block_unregister(fmt_block);
        close(fmt_fd);
        unlink(fmt_img);
    }

    /* Different sector sizes and volume sizes: mkntfs must take geometry from
     * the block callbacks, and each result must mount and round-trip data. */
    {
        static const uint32_t geom_ss[] = {512, 1024, 2048, 4096};
        static const uint64_t geom_mib[] = {2, 4, 8, 16};
        for (size_t g = 0; g < sizeof(geom_ss) / sizeof(geom_ss[0]); g++) {
            char gimg[] = "/tmp/ntfs_geom_XXXXXX.img";
            int gfd = mkstemps(gimg, 4);
            assert(gfd >= 0);
            uint32_t gss = geom_ss[g];
            uint64_t gsc = (geom_mib[g] << 20) / gss;
            assert(ftruncate(gfd, (off_t)gsc * gss) == 0);

            FileBlockCtx gctx = {
                .fd = gfd,
                .sector_size = gss,
                .sector_count = gsc,
            };
            UsbBlockOps gops = {
                .read = file_read,
                .write = file_write,
                .sync = file_sync,
                .ctx = &gctx,
                .sector_size = gss,
                .sector_count = gsc,
            };
            uint64_t gblock = usb_block_register(&gops);
            assert(gblock != 0);
            assert(ntfs_engine_mkfs(gblock, "GEOM", true) == 0);

            uint64_t gvol = ntfs_engine_mount(gblock, false);
            assert(gvol != 0);
            assert(ntfs_engine_is_dirty(gvol) == 0);
            char payload[32];
            int plen = snprintf(payload, sizeof(payload), "geom-%u", (unsigned)gss);
            assert(plen > 0 && (size_t)plen < sizeof(payload));
            size_t gw = 0;
            assert(ntfs_engine_write(gvol, "/geometry.txt", 0, payload, (size_t)plen,
                                     true, &gw) == 0);
            assert(gw == (size_t)plen);
            assert(ntfs_engine_sync(gvol) == 0);
            assert(ntfs_engine_close(gvol) == 0);

            gvol = ntfs_engine_mount(gblock, true);
            assert(gvol != 0);
            char rb[64] = {0};
            size_t rl = 0;
            assert(ntfs_engine_read(gvol, "/geometry.txt", 0, rb, sizeof(rb) - 1,
                                    &rl) == 0);
            assert(rl == (size_t)plen);
            assert(strcmp(rb, payload) == 0);
            assert(ntfs_engine_close(gvol) == 0);

            usb_block_unregister(gblock);
            close(gfd);
            unlink(gimg);
            printf("PASS: mkfs/mount/read/write with %u-byte sectors, %llu MiB\n",
                   (unsigned)gss, (unsigned long long)geom_mib[g]);
        }
    }

    /* Volume label setter: round-trip, and writeback (sync) failures must be reported. */
    {
        char limg[] = "/tmp/ntfs_label_XXXXXX.img";
        int lfd = mkstemps(limg, 4);
        assert(lfd >= 0);
        uint64_t lsc = (8ULL << 20) / 512u;
        assert(ftruncate(lfd, (off_t)lsc * 512u) == 0);

        FileBlockCtx lctx = {
            .fd = lfd,
            .sector_size = 512u,
            .sector_count = lsc,
        };
        UsbBlockOps lops = {
            .read = file_read,
            .write = file_write,
            .sync = file_sync,
            .ctx = &lctx,
            .sector_size = 512u,
            .sector_count = lsc,
        };
        uint64_t lblock = usb_block_register(&lops);
        assert(lblock != 0);
        assert(ntfs_engine_mkfs(lblock, "INIT", true) == 0);

        char llabel[64];
        assert(ntfs_engine_set_label(lblock, "NEWLABEL") == 0);
        memset(llabel, 0, sizeof(llabel));
        assert(ntfs_engine_get_label(lblock, llabel, sizeof(llabel)) == 0);
        assert(strcmp(llabel, "NEWLABEL") == 0);
        printf("PASS: NTFS label set/get\n");

        /* $Volume metadata is written during sync/unmount; a failed flush must surface. */
        lctx.fail_sync = 1;
        assert(ntfs_engine_set_label(lblock, "NOFLUSH") != 0);
        printf("PASS: NTFS label writeback error is reported\n");

        usb_block_unregister(lblock);
        close(lfd);
        unlink(limg);
    }

    /* JNI label boundary: Java UTF-16 is converted to standard UTF-8 here. Modified UTF-8
     * (what GetStringUTFChars returns) encodes the emoji as surrogate sequences that
     * ntfs_mbstoucs rejects, so the conversion must produce the real 4-byte sequence and
     * refuse unpaired surrogates/NULs before any mount happens. */
    {
        char u8[32];
        const uint16_t emoji[] = { 'C', 0xD83D, 0xDE00, 'X' };
        assert(ntfs_label_from_utf16(emoji, 4, u8, sizeof(u8)) == 0);
        assert(strcmp(u8, "C\xF0\x9F\x98\x80X") == 0);
        assert(ntfs_label_from_utf16(emoji + 1, 1, u8, sizeof(u8)) == -EINVAL);
        assert(ntfs_label_from_utf16((const uint16_t[]){ 'a', 0, 'b' }, 3, u8, sizeof(u8)) == -EINVAL);
        assert(ntfs_label_from_utf16((const uint16_t[]){ 0xDC00, 'a' }, 2, u8, sizeof(u8)) == -EINVAL);
        assert(ntfs_label_from_utf16((const uint16_t[]){ 'a' }, 1, u8, 1) == -ERANGE);
        printf("PASS: NTFS label UTF-16 to standard UTF-8\n");
    }

    printf("ALL NTFS INTEGRATION TESTS PASSED SUCCESSFULLY!\n");
    return 0;
}
