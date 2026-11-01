# Ventoy v1.1.18

Pinned upstream release: https://github.com/ventoy/Ventoy/releases/tag/v1.1.18
Target commit: `6116894ab8abdf3ef8d43411a6732d34ee19214e`
Source tarball: https://github.com/ventoy/Ventoy/archive/refs/tags/v1.1.18.tar.gz
License: GPL-3.0-or-later
Copyright (C) 2019-2026 longpanda <admin@ventoy.net>

Official release assets SHA-256 (from sha256.txt):
- `ventoy-1.1.18-linux.tar.gz`: `d86ff9de63d94c8b8f1f6b14d23c55af4b88e8207bf2893890892a6a97b1ad54`
- `boot/boot.img`: `85b4b080765738ca96620c32d8182edddbaf6a36149162946d12aa1f9802bc44`
- `boot/core.img.xz`: `97d70bf738a6e8d72fae6eb1a8993ceb4ac8bdc92570f2ff87bcd9aef3723431`
- `ventoy/ventoy.disk.img.xz`: `19174f133c4df8acda4423b1bc40ae69961773ef54e286ffd8c42fefaf6f174f`
- `ventoy/version`: `32820b5fe235bf45d17986fb1b9b336365bef8b90c678f57d795b48082b2144e` ("1.1.18")

## Vendored Sources
The following files are vendored directly from upstream `LinuxGUI/Ventoy2Disk/Core`:
- `ventoy_define.h`: Ventoy geometry, MBR/GPT structures, constants
- `ventoy_crc32.c`: Canonical Ventoy CRC32 implementation
- `ventoy_util.h`: Prototypes for MBR/GPT formatting and UUID generation
- `ventoy_util.c`: Geometry calculations (`ventoy_fill_mbr`, `ventoy_fill_gpt`, UUID generation)

The adapter engine in `app/src/main/cpp/ventoy_engine.c` connects these upstream
layout and formatting functions to the relative-LBA `usb_block_*` interface.
Installer behavior preserves partition 1 on update, refuses 4Kn and unrecognized layouts,
and requires matching release assets.
