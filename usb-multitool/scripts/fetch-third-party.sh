#!/usr/bin/env bash
# Fetch pinned upstream engines into third_party/. Intended for CI only.
# Local developer phones must not run this (large downloads).
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
mkdir -p "$TP/fatfs" "$TP/ntfs-3g" "$TP/lwext4" "$TP/ventoy" "$TP/f3" "$TP/ddrescue"

have_marker() {
  local path="$1"
  [[ -f "$path" ]]
}

echo "Fetching FatFs R0.16..."
if ! have_marker "$TP/fatfs/source/ff.c"; then
  curl -fsSL -o /tmp/ff16.zip https://elm-chan.org/fsw/ff/arc/ff16.zip
  rm -rf "$TP/fatfs"/*
  unzip -q -d "$TP/fatfs" /tmp/ff16.zip
  # Archive may unpack into a nested directory; normalize to fatfs/source/
  if [[ ! -f "$TP/fatfs/source/ff.c" ]]; then
    nested="$(find "$TP/fatfs" -type f -name ff.c | head -n1 || true)"
    if [[ -n "$nested" ]]; then
      srcdir="$(dirname "$nested")"
      mkdir -p "$TP/fatfs/source"
      cp -a "$srcdir"/. "$TP/fatfs/source/"
    fi
  fi
  have_marker "$TP/fatfs/source/ff.c" || { echo "FatFs fetch failed"; exit 1; }
fi

echo "Fetching ntfs-3g 2026.9.28..."
if ! have_marker "$TP/ntfs-3g/libntfs-3g/ntfs.c"; then
  curl -fsSL https://github.com/tuxera/ntfs-3g/archive/refs/tags/2026.9.28.tar.gz \
    | tar -xz --strip-components=1 -C "$TP/ntfs-3g"
  have_marker "$TP/ntfs-3g/libntfs-3g/ntfs.c" || { echo "ntfs-3g fetch failed"; exit 1; }
fi

echo "Fetching lwext4..."
if ! have_marker "$TP/lwext4/include/ext4.h"; then
  curl -fsSL https://github.com/gkostka/lwext4/archive/58bcf89a121b72d4fb66334f1693d3b30e4cb9c5.tar.gz \
    | tar -xz --strip-components=1 -C "$TP/lwext4"
  have_marker "$TP/lwext4/include/ext4.h" || { echo "lwext4 fetch failed"; exit 1; }
fi

echo "Fetching Ventoy v1.1.18..."
if ! have_marker "$TP/ventoy/INSTALL"; then
  # Keep README, replace with upstream tree.
  curl -fsSL https://github.com/ventoy/Ventoy/archive/refs/tags/v1.1.18.tar.gz \
    | tar -xz --strip-components=1 -C "$TP/ventoy"
  # Upstream may not ship INSTALL; accept README.md or Ventoy2Disk.sh as marker.
  if [[ ! -f "$TP/ventoy/INSTALL" ]]; then
    if [[ -f "$TP/ventoy/Ventoy2Disk.sh" ]]; then
      echo "Ventoy tree present (Ventoy2Disk.sh)" > "$TP/ventoy/INSTALL"
    elif [[ -f "$TP/ventoy/README.md" ]]; then
      # Distinguish pending README from real tree by looking for source dirs.
      if [[ -d "$TP/ventoy/SRC" || -d "$TP/ventoy/INSTALL" || -f "$TP/ventoy/Ventoy2Disk.sh" ]]; then
        echo "Ventoy tree present" > "$TP/ventoy/INSTALL"
      else
        echo "Ventoy fetch failed: no upstream markers"; exit 1
      fi
    else
      echo "Ventoy fetch failed"; exit 1
    fi
  fi
fi

echo "Fetching F3 v10.0..."
if ! have_marker "$TP/f3/f3probe.c"; then
  curl -fsSL https://github.com/AltraMayor/f3/archive/refs/tags/v10.0.tar.gz \
    | tar -xz --strip-components=1 -C "$TP/f3"
  have_marker "$TP/f3/f3probe.c" || { echo "F3 fetch failed"; exit 1; }
fi

echo "Fetching GNU ddrescue 1.30..."
if ! have_marker "$TP/ddrescue/mapfile.cc"; then
  curl -fsSL -o /tmp/ddrescue-1.30.tar.lz https://ftp.gnu.org/gnu/ddrescue/ddrescue-1.30.tar.lz
  if command -v lzip >/dev/null 2>&1; then
    lzip -dc /tmp/ddrescue-1.30.tar.lz | tar -x --strip-components=1 -C "$TP/ddrescue"
  else
    # Fallback: some images ship .tar.lz readable via tar --lzip
    tar --lzip -xf /tmp/ddrescue-1.30.tar.lz --strip-components=1 -C "$TP/ddrescue"
  fi
  have_marker "$TP/ddrescue/mapfile.cc" || { echo "ddrescue fetch failed"; exit 1; }
fi

echo "All required engines present under $TP"
