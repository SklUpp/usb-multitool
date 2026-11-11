#!/usr/bin/env bash
# Fetch pinned upstream engines into third_party/. Intended for CI only.
# Local developer phones must not run this (large downloads).
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
mkdir -p "$TP/fatfs" "$TP/ntfs-3g" "$TP/lwext4" "$TP/f3" "$TP/ddrescue"

have_marker() {
  local path="$1"
  [[ -f "$path" ]]
}

echo "Fetching FatFs R0.16..."
if ! have_marker "$TP/fatfs/source/ff.c"; then
  curl -fsSL -o /tmp/ff16.zip https://elm-chan.org/fsw/ff/arc/ff16.zip
  # Preserve CVE patches + README under fatfs/; only replace source tree.
  patches_backup="$(mktemp -d)"
  if [[ -d "$TP/fatfs/patches" ]]; then
    cp -a "$TP/fatfs/patches" "$patches_backup/"
  fi
  for keep in README.md LICENSE.txt NOTICE; do
    [[ -f "$TP/fatfs/$keep" ]] && cp -a "$TP/fatfs/$keep" "$patches_backup/"
  done
  rm -rf "$TP/fatfs/source"
  mkdir -p "$TP/fatfs"
  unzip -q -d "$TP/fatfs" /tmp/ff16.zip
  # Archive may unpack into a nested directory; normalize to fatfs/source/
  if [[ ! -f "$TP/fatfs/source/ff.c" ]]; then
    nested="$(find "$TP/fatfs" -type f -name ff.c | head -n1 || true)"
    if [[ -n "$nested" ]]; then
      srcdir="$(dirname "$nested")"
      mkdir -p "$TP/fatfs/source"
      cp -a "$srcdir"/. "$TP/fatfs/source/"
      # Drop nested unpack dir if it is not source/
      if [[ "$(dirname "$srcdir")" == "$TP/fatfs" && "$srcdir" != "$TP/fatfs/source" ]]; then
        rm -rf "$srcdir"
      fi
    fi
  fi
  # Restore patches/docs then apply patch-1/2 (CVE-2026-6682/6687/6683).
  if [[ -d "$patches_backup/patches" ]]; then
    rm -rf "$TP/fatfs/patches"
    cp -a "$patches_backup/patches" "$TP/fatfs/"
  fi
  for keep in README.md LICENSE.txt NOTICE; do
    [[ -f "$patches_backup/$keep" && ! -f "$TP/fatfs/$keep" ]] && cp -a "$patches_backup/$keep" "$TP/fatfs/"
  done
  rm -rf "$patches_backup"
  if [[ -f "$TP/fatfs/patches/ff16p1.diff" ]]; then
    # Diff headers may use alternate names; force target ff.c.
    patch -p0 -d "$TP/fatfs/source" --batch ff.c < "$TP/fatfs/patches/ff16p1.diff"
  else
    echo "FatFs patch-1 missing at $TP/fatfs/patches/ff16p1.diff" >&2
    exit 1
  fi
  if [[ -f "$TP/fatfs/patches/ff16p2.diff" ]]; then
    patch -p0 -d "$TP/fatfs/source" --batch ff.c < "$TP/fatfs/patches/ff16p2.diff"
  else
    echo "FatFs patch-2 missing at $TP/fatfs/patches/ff16p2.diff" >&2
    exit 1
  fi
  # Project patch: f_sync_volume() so FAT teardown can flush the FatFs window
  # and FSInfo/exFAT VBR before f_mount(NULL) unregisters the volume.
  FF_SYNC_PATCH="$TP/fatfs/patches/ff16p3-sync-volume.diff"
  if ! grep -q "f_sync_volume" "$TP/fatfs/source/ff.c" 2>/dev/null; then
    if [[ ! -f "$FF_SYNC_PATCH" ]]; then
      echo "FatFs volume-sync patch missing at $FF_SYNC_PATCH" >&2
      exit 1
    fi
    patch -p0 -d "$TP/fatfs/source" --batch < "$FF_SYNC_PATCH"
    grep -q "f_sync_volume" "$TP/fatfs/source/ff.c" || {
      echo "FatFs volume-sync patch failed" >&2
      exit 1
    }
  fi
  # Project patch: report FSInfo/PercInUse write failures from sync_fs and keep
  # fsi_flag set until the sector is on the medium, so a flush can be retried.
  FF_SYNC_FS_PATCH="$TP/fatfs/patches/ff16p4-sync-fs-errors.diff"
  if ! grep -q "Keep fsi_flag so the flush can be retried" "$TP/fatfs/source/ff.c" 2>/dev/null; then
    if [[ ! -f "$FF_SYNC_FS_PATCH" ]]; then
      echo "FatFs sync_fs error patch missing at $FF_SYNC_FS_PATCH" >&2
      exit 1
    fi
    patch -p0 -d "$TP/fatfs/source" --batch < "$FF_SYNC_FS_PATCH"
    grep -q "Keep fsi_flag so the flush can be retried" "$TP/fatfs/source/ff.c" || {
      echo "FatFs sync_fs error patch failed" >&2
      exit 1
    }
  fi
  have_marker "$TP/fatfs/source/ff.c" || { echo "FatFs fetch failed"; exit 1; }
fi

echo "Fetching ntfs-3g 2026.9.28..."
if ! have_marker "$TP/ntfs-3g/libntfs-3g/volume.c" || ! have_marker "$TP/ntfs-3g/ntfsprogs/mkntfs.c"; then
  curl -fsSL https://github.com/tuxera/ntfs-3g/archive/refs/tags/2026.9.28.tar.gz \
    | tar -xz --strip-components=1 -C "$TP/ntfs-3g"
  have_marker "$TP/ntfs-3g/libntfs-3g/volume.c" && have_marker "$TP/ntfs-3g/ntfsprogs/mkntfs.c" || { echo "ntfs-3g fetch failed"; exit 1; }
fi

# mkntfs is a CLI program upstream. This patch adds a device-injection entry
# point so the app links the real formatter without a path, root or FUSE.
NTFS_MKNTFS_PATCH="$ROOT/scripts/patches/ntfs-3g-mkntfs-library.patch"
if ! grep -q "mkntfs_format_device" "$TP/ntfs-3g/ntfsprogs/mkntfs.c"; then
  patch -p1 -d "$TP/ntfs-3g" --batch < "$NTFS_MKNTFS_PATCH"
fi

echo "Fetching lwext4..."
LWEXT4_UMOUNT_PATCH="$ROOT/scripts/patches/lwext4-umount-teardown-on-fini-fail.patch"
if ! have_marker "$TP/lwext4/include/ext4.h"; then
  curl -fsSL https://github.com/gkostka/lwext4/archive/58bcf89a121b72d4fb66334f1693d3b30e4cb9c5.tar.gz \
    | tar -xz --strip-components=1 -C "$TP/lwext4"
  have_marker "$TP/lwext4/include/ext4.h" || { echo "lwext4 fetch failed"; exit 1; }
fi
# Idempotent: skip when already applied (teardown_r marker in umount).
if ! grep -q "teardown_r = EOK" "$TP/lwext4/src/ext4.c" 2>/dev/null; then
  if [[ ! -f "$LWEXT4_UMOUNT_PATCH" ]]; then
    echo "lwext4 umount patch missing at $LWEXT4_UMOUNT_PATCH" >&2
    exit 1
  fi
  patch -p1 -d "$TP/lwext4" --batch < "$LWEXT4_UMOUNT_PATCH"
  grep -q "teardown_r = EOK" "$TP/lwext4/src/ext4.c" || {
    echo "lwext4 umount teardown patch failed" >&2
    exit 1
  }
fi

echo "Fetching F3 v10.0..."
if ! have_marker "$TP/f3/src/f3probe.c"; then
  curl -fsSL https://github.com/AltraMayor/f3/archive/refs/tags/v10.0.tar.gz \
    | tar -xz --strip-components=1 -C "$TP/f3"
  have_marker "$TP/f3/src/f3probe.c" || { echo "F3 fetch failed"; exit 1; }
fi

echo "Fetching GNU ddrescue 1.30..."
if ! have_marker "$TP/ddrescue/mapfile.cc"; then
  if ! command -v lzip >/dev/null 2>&1; then
    echo "lzip not installed; skipping optional ddrescue fetch (no local package install)" >&2
  else
    curl -fsSL -o /tmp/ddrescue-1.30.tar.lz https://ftp.gnu.org/gnu/ddrescue/ddrescue-1.30.tar.lz
    lzip -dc /tmp/ddrescue-1.30.tar.lz | tar -x --strip-components=1 -C "$TP/ddrescue"
    have_marker "$TP/ddrescue/mapfile.cc" || { echo "ddrescue fetch failed"; exit 1; }
  fi
fi

echo "All required engines present under $TP"
