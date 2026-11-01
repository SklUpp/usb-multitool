# Ventoy v1.1.18 (pending fetch)

Pinned release: https://github.com/ventoy/Ventoy/releases/tag/v1.1.18  
Source tarball: https://github.com/ventoy/Ventoy/archive/refs/tags/v1.1.18.tar.gz  
Commit: `6116894ab8abdf3ef8d43411a6732d34ee19214e`  
License: GPL-3.0-or-later

Matching install assets must come from the same release (`ventoy-1.1.18-linux.tar.gz`
or the Android-adapted subset listed by upstream). Do not mix assets across versions.

```bash
curl -L https://github.com/ventoy/Ventoy/archive/refs/tags/v1.1.18.tar.gz \
  | tar -xz --strip-components=1 -C third_party/ventoy
```

Then enable `-DUSB_WITH_VENTOY=ON`. JNI adapter: `app/src/main/cpp/ventoy_bridge.c`.
Source tree intentionally absent from this commit.
