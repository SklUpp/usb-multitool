#!/usr/bin/env bash
# Fail unless the APK and every packaged native library are 16 KB page ready.
# Follows Android's documented checks:
#   - zip entry alignment for uncompressed .so files: zipalign -c -P 16 4
#   - ELF LOAD p_align >= 2**14
#   - GNU_RELRO 16 KB rule: no GNU_RELRO, end aligned to 0x4000, or GNU_RELRO
#     ends exactly where its containing LOAD segment ends.
# Uses only the already installed Android SDK build-tools and NDK; nothing is
# downloaded and no Gradle task is invoked.
# Usage: scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/*.apk
set -euo pipefail

PAGE_SIZE=16384
PAGE_SIZE_KB=16
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  echo "::error::ANDROID_HOME or ANDROID_SDK_ROOT must be set" >&2
  exit 1
fi
if [ "$#" -eq 0 ]; then
  echo "usage: $0 <apk> [<apk>...]" >&2
  exit 1
fi

newest() {
  local matches
  matches=$(compgen -G "$1" 2>/dev/null || true)
  [ -n "$matches" ] || return 1
  sort -V <<<"$matches" | tail -n 1
}

zipalign=$(newest "$SDK/build-tools/*/zipalign") || {
  echo "::error::zipalign not found under $SDK/build-tools" >&2
  exit 1
}

readelf=
for pat in \
    "${ANDROID_NDK_HOME:+$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin/llvm-readelf}" \
    "${ANDROID_NDK_ROOT:+$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/*/bin/llvm-readelf}" \
    "$SDK/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf" \
    "$SDK/ndk-bundle/toolchains/llvm/prebuilt/*/bin/llvm-readelf"; do
  [ -n "$pat" ] || continue
  if readelf=$(newest "$pat"); then
    break
  fi
  readelf=
done
if [ -z "$readelf" ]; then
  echo "::error::llvm-readelf not found; install the NDK via sdkmanager" >&2
  exit 1
fi

echo "zipalign:     $zipalign"
echo "llvm-readelf: $readelf"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
rc=0

for apk in "$@"; do
  if [ ! -f "$apk" ]; then
    echo "::error::APK not found: $apk" >&2
    rc=1
    continue
  fi
  name=$(basename "$apk")
  echo "== $name: zip entry alignment (zipalign -c -P $PAGE_SIZE_KB 4)"
  if ! "$zipalign" -v -c -P "$PAGE_SIZE_KB" 4 "$apk"; then
    echo "::error::$name: zipalign -c -P 16 failed; uncompressed native libraries are not 16 KB aligned" >&2
    rc=1
  fi

  out="$tmp/$name-$RANDOM"
  mkdir -p "$out"
  if ! unzip -q -o "$apk" 'lib/*/*.so' -d "$out"; then
    echo "::error::$name: no lib/*/*.so entries found in the APK" >&2
    rc=1
    continue
  fi

  found=0
  while IFS= read -r lib; do
    found=$((found + 1))
    rel=${lib#"$out/"}

    # Capture both streams and the exit status first: a failing llvm-readelf
    # must be reported, not abort the loop through `set -o pipefail`.
    if ! headers=$("$readelf" -Wl "$lib" 2>&1); then
      printf '::error::%s: llvm-readelf failed\n' "$rel" >&2
      printf '%s\n' "$headers" >&2
      rc=1
      continue
    fi

    loads=()
    while IFS= read -r line; do
      loads+=("$line")
    done < <(awk '$1 == "LOAD" { flags = ""; for (i = 7; i < NF; i++) flags = flags $i; print $3, $6, $NF, flags }' <<<"$headers")
    relro_line=$(awk '$1 == "GNU_RELRO" { print $3, $6 }' <<<"$headers" | head -n 1)

    if [ "${#loads[@]}" -eq 0 ]; then
      echo "::error::$rel: no ELF LOAD segments (not a readable ELF?)" >&2
      rc=1
      continue
    fi

    bad=0
    align_list=
    for line in "${loads[@]}"; do
      read -r lv lm la lf <<<"$line"
      align_list="$align_list $la"
      case "$la" in
        0x*) ;;
        *)
          echo "::error::$rel: unexpected LOAD p_align '$la'" >&2
          rc=1
          bad=1
          continue
          ;;
      esac
      if ((la < PAGE_SIZE)); then
        echo "::error::$rel: LOAD p_align $la is below 0x4000 (16 KB)" >&2
        rc=1
        bad=1
      fi
    done

    relro_state="no GNU_RELRO"
    if [ -n "$relro_line" ]; then
      read -r rv rm <<<"$relro_line"
      rend=$((rv + rm))
      if ((rend % PAGE_SIZE == 0)); then
        relro_state="GNU_RELRO end $(printf '0x%x' "$rend") 16 KB aligned"
      else
        covered=0
        for line in "${loads[@]}"; do
          read -r lv lm la lf <<<"$line"
          if ((lv <= rv && rv < lv + lm && lv + lm == rend)); then
            covered=1
          fi
        done
        if ((covered == 1)); then
          relro_state="GNU_RELRO end $(printf '0x%x' "$rend") closes its LOAD segment"
        else
          echo "::error::$rel: GNU_RELRO end $(printf '0x%x' "$rend") is not 16 KB aligned and writable data follows it in the same LOAD segment" >&2
          rc=1
          bad=1
          relro_state="GNU_RELRO UNALIGNED"
        fi
      fi
    fi

    if [ "$bad" -eq 0 ]; then
      echo "ok: $rel (LOAD p_align:$align_list; $relro_state)"
    fi
  done < <(find "$out/lib" -type f -name '*.so' -print | sort)

  if [ "$found" -eq 0 ]; then
    echo "::error::$name: no .so files extracted from lib/" >&2
    rc=1
  fi
done

if [ "$rc" -ne 0 ]; then
  echo "16 KB page-size alignment check FAILED" >&2
  exit 1
fi
echo "16 KB page-size alignment check passed"
