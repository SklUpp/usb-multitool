#!/usr/bin/env bash
# Fetch pinned upstream engines into third_party/. Intended for CI only.
# Local developer phones must not run this (large downloads).
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
mkdir -p "$TP/fatfs" "$TP/ntfs-3g" "$TP/lwext4" "$TP/f3" "$TP/ddrescue" "$TP/sectorforge"

have_marker() {
  local path="$1"
  [[ -f "$path" ]]
}

# Integrity pins. Each one is documented next to the upstream notice:
#   fatfs       third_party/fatfs/NOTICE (archive + official diffs + project patches)
#   ntfs-3g     third_party/README.md   (tag commit)
#   lwext4      third_party/README.md   (commit pin + v1.0.0 tag fallback)
#   f3          third_party/f3/README.md (v10.0 tag commit)
# Archives fetched through git are verified by comparing `git rev-parse HEAD`
# with the pinned commit, so a moved tag or branch is refused instead of used.
FATFS_ZIP_SHA256=99f7dc1f7e095356e4a9e3dbe29959090d8b948afe2bbc5441e52fdf4b85449e
FATFS_PATCH1_SHA256=7996ddc3135f3d534a8153f7d75d587030e5c8551b003c3b7ee823ffb7fc64bf
FATFS_PATCH2_SHA256=5cd39f1fc299f0f1dec9fa1ce544dc0bc048b2f6eb3b8161476ed20f9cf1e290
FATFS_PATCH3_SHA256=8e9d62a06e10d476859430a89f64f0601faa338c06b357287f570b69a69d91d0
FATFS_PATCH4_SHA256=c2af87c6d455d5253fb204417719227d49fb667c8a9f53252c8e0357b0ad52a8
NTFS3G_COMMIT=7f0f841fc52cf719106c5c93bafe465004e36816
LWEXT4_COMMIT=58bcf89a121b72d4fb66334f1693d3b30e4cb9c5
LWEXT4_TAG=v1.0.0
LWEXT4_TAG_COMMIT=890484c72dbb321535127625c3686e51f1f424b9
F3_COMMIT=67b8a5d546cbe41b0072c2e62aefe165e4675b61
SF_COMMIT=b292a299c18013706e81d670657ced6c43f200ca
SF_PROGRESS_PATCH_SHA256=0c3dccd3d62551605bb7dbbb333f8482f63033de3f3bc31b2b1fd335f739c203
# Upstream publishes only a GPG signature for this tarball, so there is no
# digest to pin here. The fetch is skipped (not silently trusted) until a
# verified digest is supplied: DDRESCUE_SHA256=<64 hex> scripts/fetch-third-party.sh
DDRESCUE_SHA256="${DDRESCUE_SHA256:-}"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

require_sha256() {
  local file="$1" expected="$2" name="$3" actual
  actual="$(sha256_of "$file")"
  if [[ "$actual" != "$expected" ]]; then
    echo "$name SHA-256 mismatch: got $actual, pinned $expected; refusing" >&2
    exit 1
  fi
}

# Fetch a pinned ref and unpack exactly the pinned commit. Fails rather than
# accepting whatever the remote tip happens to be.
fetch_pinned_git() {
  local name="$1" url="$2" ref="$3" commit="$4" dest="$5" work head
  work="$(mktemp -d)"
  git init -q "$work"
  git -C "$work" remote add origin "$url"
  if ! GIT_TERMINAL_PROMPT=0 git -C "$work" fetch -q --depth 1 origin "$ref"; then
    rm -rf "$work"
    return 1
  fi
  git -C "$work" checkout -q --detach FETCH_HEAD || { rm -rf "$work"; return 1; }
  head="$(git -C "$work" rev-parse HEAD)" || { rm -rf "$work"; return 1; }
  if [[ "$head" != "$commit" ]]; then
    echo "$name: $ref resolves to $head, pinned $commit; refusing" >&2
    rm -rf "$work"
    exit 1
  fi
  mkdir -p "$dest" || { rm -rf "$work"; return 1; }
  if ! git -C "$work" archive --format=tar HEAD | tar -x -C "$dest"; then
    rm -rf "$work"
    return 1
  fi
  rm -rf "$work"
}

# Idempotent patch application: a reverse dry-run proves the change is already
# in the tree (a marker file only proves that some tree exists). `--batch` is
# deliberately absent from that check: with it, patch ignores -R and reports
# success on an unpatched file. `-i` plus `</dev/null` keeps prompts off the
# tty, and the patch path must be absolute because `-d` chdirs first.
apply_patch_idempotent() {
  local patch_file="$1" dir="$2"
  shift 2
  if patch -d "$dir" --dry-run --reverse -i "$patch_file" "$@" </dev/null >/dev/null 2>&1; then
    echo "patch already applied: ${patch_file##*/}"
    return 0
  fi
  if ! patch -d "$dir" --dry-run --forward --batch -i "$patch_file" "$@" </dev/null >/dev/null; then
    echo "patch does not apply cleanly: $patch_file" >&2
    exit 1
  fi
  patch -d "$dir" --forward --batch -i "$patch_file" "$@" </dev/null >/dev/null
  if ! patch -d "$dir" --dry-run --reverse -i "$patch_file" "$@" </dev/null >/dev/null 2>&1; then
    echo "patch did not apply cleanly: $patch_file" >&2
    exit 1
  fi
}

command -v git >/dev/null 2>&1 || { echo "git required to fetch pinned engines" >&2; exit 1; }

echo "Fetching FatFs R0.16..."
if ! have_marker "$TP/fatfs/source/ff.c"; then
  ff_zip="$(mktemp)"
  curl -fsSL -o "$ff_zip" https://elm-chan.org/fsw/ff/arc/ff16.zip
  require_sha256 "$ff_zip" "$FATFS_ZIP_SHA256" "ff16.zip"
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
  unzip -q -d "$TP/fatfs" "$ff_zip"
  rm -f "$ff_zip"
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
  have_marker "$TP/fatfs/source/ff.c" || { echo "FatFs fetch failed"; exit 1; }
fi

# Applied outside the fetch gate: a tree fetched earlier must still receive
# these patches, and the reverse dry-run keeps a re-run idempotent.
FATFS_PATCH1="$TP/fatfs/patches/ff16p1.diff"
FATFS_PATCH2="$TP/fatfs/patches/ff16p2.diff"
FATFS_PATCH3="$TP/fatfs/patches/ff16p3-sync-volume.diff"
FATFS_PATCH4="$TP/fatfs/patches/ff16p4-sync-fs-errors.diff"
for fatfs_patch in "$FATFS_PATCH1" "$FATFS_PATCH2" "$FATFS_PATCH3" "$FATFS_PATCH4"; do
  [[ -f "$fatfs_patch" ]] || { echo "FatFs patch missing at $fatfs_patch" >&2; exit 1; }
done
require_sha256 "$FATFS_PATCH1" "$FATFS_PATCH1_SHA256" "ff16p1.diff"
require_sha256 "$FATFS_PATCH2" "$FATFS_PATCH2_SHA256" "ff16p2.diff"
require_sha256 "$FATFS_PATCH3" "$FATFS_PATCH3_SHA256" "ff16p3-sync-volume.diff"
require_sha256 "$FATFS_PATCH4" "$FATFS_PATCH4_SHA256" "ff16p4-sync-fs-errors.diff"
# p1+p2 are an ordered pair: p2 rewrites the banner and macro block that p1
# touched, so p1 no longer reverses once p2 is in. p2 still reverses on a
# p1..p4 tree, so its reverse probe covers the pair.
if patch -d "$TP/fatfs/source" --dry-run --reverse -i "$FATFS_PATCH2" -p0 ff.c </dev/null >/dev/null 2>&1; then
  echo "FatFS upstream patches already applied (p1+p2)"
else
  # Diff headers may use alternate names; force target ff.c.
  apply_patch_idempotent "$FATFS_PATCH1" "$TP/fatfs/source" -p0 ff.c
  apply_patch_idempotent "$FATFS_PATCH2" "$TP/fatfs/source" -p0 ff.c
fi
# Project patches: f_sync_volume() for volume teardown, and sync_fs() reporting
# FSInfo/PercInUse write failures so a flush can be retried. Each reverses on
# its own; their headers name ff.c/ff.h, so no target file is passed.
apply_patch_idempotent "$FATFS_PATCH3" "$TP/fatfs/source" -p0
apply_patch_idempotent "$FATFS_PATCH4" "$TP/fatfs/source" -p0

echo "Fetching ntfs-3g 2026.9.28..."
if ! have_marker "$TP/ntfs-3g/libntfs-3g/volume.c" || ! have_marker "$TP/ntfs-3g/ntfsprogs/mkntfs.c"; then
  fetch_pinned_git ntfs-3g https://github.com/tuxera/ntfs-3g.git 2026.9.28 \
    "$NTFS3G_COMMIT" "$TP/ntfs-3g" || { echo "ntfs-3g fetch failed"; exit 1; }
  have_marker "$TP/ntfs-3g/libntfs-3g/volume.c" && have_marker "$TP/ntfs-3g/ntfsprogs/mkntfs.c" || { echo "ntfs-3g fetch failed"; exit 1; }
fi

# mkntfs is a CLI program upstream. This patch adds a device-injection entry
# point so the app links the real formatter without a path, root or FUSE.
NTFS_MKNTFS_PATCH="$ROOT/scripts/patches/ntfs-3g-mkntfs-library.patch"
[[ -f "$NTFS_MKNTFS_PATCH" ]] || { echo "ntfs-3g mkntfs patch missing at $NTFS_MKNTFS_PATCH" >&2; exit 1; }
apply_patch_idempotent "$NTFS_MKNTFS_PATCH" "$TP/ntfs-3g" -p1

echo "Fetching lwext4..."
LWEXT4_UMOUNT_PATCH="$ROOT/scripts/patches/lwext4-umount-teardown-on-fini-fail.patch"
LWEXT4_O_EXCL_PATCH="$ROOT/scripts/patches/lwext4-o-excl-existing.patch"
if ! have_marker "$TP/lwext4/include/ext4.h"; then
  # Commit pin first; the documented v1.0.0 tag is the only fallback, and it
  # carries its own commit pin.
  fetch_pinned_git lwext4 https://github.com/gkostka/lwext4.git "$LWEXT4_COMMIT" \
      "$LWEXT4_COMMIT" "$TP/lwext4" ||
    fetch_pinned_git lwext4 https://github.com/gkostka/lwext4.git "$LWEXT4_TAG" \
      "$LWEXT4_TAG_COMMIT" "$TP/lwext4" ||
    { echo "lwext4 fetch failed"; exit 1; }
  have_marker "$TP/lwext4/include/ext4.h" || { echo "lwext4 fetch failed"; exit 1; }
fi
[[ -f "$LWEXT4_UMOUNT_PATCH" ]] || { echo "lwext4 umount patch missing at $LWEXT4_UMOUNT_PATCH" >&2; exit 1; }
[[ -f "$LWEXT4_O_EXCL_PATCH" ]] || { echo "lwext4 O_EXCL patch missing at $LWEXT4_O_EXCL_PATCH" >&2; exit 1; }
apply_patch_idempotent "$LWEXT4_UMOUNT_PATCH" "$TP/lwext4" -p1
apply_patch_idempotent "$LWEXT4_O_EXCL_PATCH" "$TP/lwext4" -p1

echo "Fetching F3 v10.0..."
if ! have_marker "$TP/f3/src/f3probe.c"; then
  fetch_pinned_git f3 https://github.com/AltraMayor/f3.git v10.0 "$F3_COMMIT" "$TP/f3" \
    || { echo "F3 fetch failed"; exit 1; }
  have_marker "$TP/f3/src/f3probe.c" || { echo "F3 fetch failed"; exit 1; }
fi

echo "Fetching SectorForge sf-core (pinned b292a299)..."
if ! have_marker "$TP/sectorforge/crates/sf-core/src/imager.rs"; then
  fetch_pinned_git sectorforge https://github.com/terraseraph/sectorforge \
    "$SF_COMMIT" "$SF_COMMIT" "$TP/sectorforge" \
    || { echo "SectorForge fetch failed" >&2; exit 1; }
fi
have_marker "$TP/sectorforge/crates/sf-core/src/imager.rs" \
  || { echo "SectorForge fetch failed" >&2; exit 1; }

# Applied outside the fetch gate so existing trees receive the pinned patch too.
SF_PROGRESS_PATCH="$ROOT/scripts/patches/sectorforge-progress-map.patch"
[[ -f "$SF_PROGRESS_PATCH" ]] \
  || { echo "SectorForge progress patch missing at $SF_PROGRESS_PATCH" >&2; exit 1; }
require_sha256 "$SF_PROGRESS_PATCH" "$SF_PROGRESS_PATCH_SHA256" "sectorforge-progress-map.patch"
apply_patch_idempotent "$SF_PROGRESS_PATCH" "$TP/sectorforge" -p1

echo "Fetching GNU ddrescue 1.30..."
if ! have_marker "$TP/ddrescue/mapfile.cc"; then
  if ! command -v lzip >/dev/null 2>&1; then
    echo "lzip not installed; skipping optional ddrescue fetch (no local package install)" >&2
  elif [[ -z "$DDRESCUE_SHA256" ]]; then
    echo "ddrescue 1.30 has no pinned SHA-256 (upstream ships only a GPG signature);" >&2
    echo "verify it, then re-run as: DDRESCUE_SHA256=<64 hex> $0" >&2
    echo "skipping the unpack instead of trusting an unverified tarball" >&2
  else
    dr_lz="$(mktemp)"
    curl -fsSL -o "$dr_lz" https://ftp.gnu.org/gnu/ddrescue/ddrescue-1.30.tar.lz
    require_sha256 "$dr_lz" "$DDRESCUE_SHA256" "ddrescue-1.30.tar.lz"
    lzip -dc "$dr_lz" | tar -x --strip-components=1 -C "$TP/ddrescue"
    rm -f "$dr_lz"
    have_marker "$TP/ddrescue/mapfile.cc" || { echo "ddrescue fetch failed"; exit 1; }
  fi
fi

echo "All required engines present under $TP"
