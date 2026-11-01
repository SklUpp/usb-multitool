# Third-party engines

Upstream trees are **not** stored in this commit. Exact URLs and revisions are
pinned below. CI or a maintainer fetches them into the named directories before
enabling `USB_WITH_*` CMake options. JNI adapters live under `app/src/main/cpp/`
and must not be mixed into these trees.

## FatFs (FAT12/16/32 + exFAT)

- URL: https://elm-chan.org/fsw/ff/arc/ff16.zip
- Revision: **R0.16** (2025-07-22)
- Patches (apply in order after unpack):
  1. https://elm-chan.org/fsw/ff/patches.html — patch-1 (Unicode tables / small-volume; 2025-08-03 / 2025-09-13 notes)
  2. Same page — patch-2 for CVE-2026-6682/6687/6683 (apply to `ff.c` with patch-1)
- Unpack into: `third_party/fatfs/`
- License: FatFs license (BSD-style; see archive `LICENSE.txt`)
- Bridge: `disk_read` / `disk_write` / `disk_ioctl` → `usb_partition_*` in `fs_bridge.c`

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
- Bridge: `ext4_blockdev` callbacks → `usb_partition_read` / `usb_partition_write`

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
