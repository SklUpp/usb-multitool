# Third-party engines

Upstream trees are **not** stored in this commit. Exact URLs and revisions are
pinned below. CI runs `scripts/fetch-third-party.sh` before enabling
`USB_WITH_*` and `-DUSB_REQUIRE_ENGINES=ON`. Local builds keep engines OFF and
must not invent filesystem/Ventoy/F3/ddrescue success. `exfatprogs` is the one
exception: it is vendored under `third_party/exfatprogs/` with its upstream tag and
commit recorded in `UPSTREAM.md`.

## FatFs (FAT12/16/32 + exFAT)

- URL: https://elm-chan.org/fsw/ff/arc/ff16.zip
- Revision: **R0.16** (2025-07-22)
- Patches (apply in order after unpack):
  1. https://elm-chan.org/fsw/ff/patches.html — patch-1 (Unicode tables / small-volume; 2025-08-03 / 2025-09-13 notes)
  2. Same page — patch-2 for CVE-2026-6682/6687/6683 (apply to `ff.c` with patch-1)
  3. Local patch (via `scripts/fetch-third-party.sh`): `third_party/fatfs/patches/ff16p3-sync-volume.diff` — adds `f_sync_volume(FATFS*)`, a public wrapper over the static `sync_fs`, so the engine can flush the cached window, FAT32 FSInfo / exFAT PercInUse and `CTRL_SYNC` before `f_mount(NULL, path, 0)`. Upstream `f_mount(NULL, …)` only drops the volume object and never flushes.
  4. Local patch: `third_party/fatfs/patches/ff16p4-sync-fs-errors.diff` — `sync_fs` now treats a failed FSInfo sector write or exFAT VBR read/write as `FR_DISK_ERR` and clears `fsi_flag` only after the sector reached the medium. Upstream cleared the flag first and ignored the write result, so a later flush reported success for metadata that never landed and could not retry.
- Unpack into: `third_party/fatfs/`
- License: FatFs license (BSD-style; see archive `LICENSE.txt`)
- Bridge: `fat_engine.c` / `fat_jni.cpp` — `disk_read` / `disk_write` / `disk_ioctl` go through the `usb_block.c` handle

## ntfs-3g

- URL: https://github.com/tuxera/ntfs-3g/archive/refs/tags/2026.9.28.tar.gz
- Alternate: https://www.tuxera.com/opensource/ntfs-3g_ntfsprogs-2026.9.28.tgz
- Revision / tag: **2026.9.28**
- Commit: `7f0f841fc52cf719106c5c93bafe465004e36816`
- Unpack into: `third_party/ntfs-3g/`
- License: GPL-2.0-or-later
- Bridge: custom device backend via `pread`/`pwrite` on the partition IO object; no FUSE

## lwext4

- URL: https://github.com/gkostka/lwext4/archive/58bcf89a121b72d4fb66334f1693d3b30e4cb9c5.tar.gz
- Revision: commit **58bcf89a121b72d4fb66334f1693d3b30e4cb9c5** (master tip recorded 2022-09-22)
- Tag fallback: `v1.0.0` (`890484c72dbb321535127625c3686e51f1f424b9`) if the tip moves
- Unpack into: `third_party/lwext4/`
- License: GPL-2.0-or-later
- Bridge: `ext_engine.c` — `ext4_blockdev` callbacks → `usb_block_read` / `usb_block_write`
- Local patch (via `scripts/fetch-third-party.sh`): `scripts/patches/lwext4-umount-teardown-on-fini-fail.patch` — `ext4_umount` always tears down in-memory mountpoint/bcache when `ext4_fs_fini` fails so free of the blockdev cannot UAF on remount.

## exfatprogs (exFAT fsck only)

- Vendored in-tree: `third_party/exfatprogs/` (tag `1.4.3`, commit `a37d3273`)
- URL: https://github.com/exfatprogs/exfatprogs/archive/refs/tags/1.4.3.tar.gz
- License: GPL-2.0-or-later (compatible with this app's GPL-3.0-or-later)
- Bridge: `fsck/exfat_fsck.c` driven through `exfat_fsck_jni.cpp`; mkfs comes from FatFs

## Fetch checklist

```bash
# From usb-multitool/
mkdir -p third_party/fatfs third_party/ntfs-3g third_party/lwext4
curl -L -o /tmp/ff16.zip https://elm-chan.org/fsw/ff/arc/ff16.zip
unzip -d third_party/fatfs /tmp/ff16.zip
curl -L https://github.com/tuxera/ntfs-3g/archive/refs/tags/2026.9.28.tar.gz \
  | tar -xz --strip-components=1 -C third_party/ntfs-3g
curl -L https://github.com/gkostka/lwext4/archive/58bcf89a121b72d4fb66334f1693d3b30e4cb9c5.tar.gz \
  | tar -xz --strip-components=1 -C third_party/lwext4
```

Then enable `-DUSB_WITH_FATFS=ON -DUSB_WITH_NTFS=ON -DUSB_WITH_LWEXT4=ON` in the
app `externalNativeBuild` CMake arguments.

## Ventoy

- Ventoy 1.1.18 boot payloads are bundled in `app/src/main/assets/ventoy/`;
  their SHA-256 values are pinned in `VentoyAssets.kt`. Upstream: GPL-3.0-or-later,
  source https://github.com/ventoy/Ventoy/tree/v1.1.18.
- Download-and-install resolves the latest GitHub release and checks the archive
  SHA-256 against GitHub release metadata over HTTPS. New releases do not have an
  independently pinned digest; bundled 1.1.18 retains pinned member hashes.
- URL: https://github.com/ventoy/Ventoy/releases

## F3

- See `f3/README.md`. The tree is fetched by `scripts/fetch-third-party.sh`, but no
  `f3probe` bridge is compiled: capacity probing needs destructive write patterns
  with a signed confirmation, so the diagnostics screen offers only a read scan and
  a ddrescue-style rescue image.

## GNU ddrescue

- See `ddrescue/README.md`
- URL: https://ftp.gnu.org/gnu/ddrescue/ddrescue-1.30.tar.lz
- Revision: **1.30**
- Bridge: none compiled. The rescue map is maintained in Kotlin (`RescueMap`) and
  checkpointed by the app; no native ddrescue code is linked.
