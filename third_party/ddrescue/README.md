# GNU ddrescue 1.30 (pending fetch)

Pinned: https://ftp.gnu.org/gnu/ddrescue/ddrescue-1.30.tar.lz  
License: GPL-2.0-or-later

```bash
# From usb-multitool/. No digest is pinned upstream, so pass a verified one;
# without it the script skips the unpack instead of trusting the tarball.
DDRESCUE_SHA256=<64 hex> ./scripts/fetch-third-party.sh
# requires lzip; unpacks into third_party/ddrescue/
```

Bridge: `app/src/main/cpp/ddrescue_bridge.c`. Source tree intentionally absent.
