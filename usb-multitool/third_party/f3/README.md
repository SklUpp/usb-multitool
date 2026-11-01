# F3 v10.0 (pending fetch)

Pinned: https://github.com/AltraMayor/f3/archive/refs/tags/v10.0.tar.gz  
Commit: `67b8a5d546cbe41b0072c2e62aefe165e4675b61`  
License: GPL-3.0

```bash
curl -L https://github.com/AltraMayor/f3/archive/refs/tags/v10.0.tar.gz \
  | tar -xz --strip-components=1 -C third_party/f3
```

Bridge: none. `f3_bridge.c` was removed because it returned `sectorCount - 1`
without probing; `Diagnostics.fakeCapacity` reports the missing engine instead.
