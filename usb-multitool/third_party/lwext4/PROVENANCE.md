# lwext4 provenance

- Pin URL: https://github.com/gkostka/lwext4/archive/58bcf89a121b72d4fb66334f1693d3b30e4cb9c5.tar.gz
- Commit: `58bcf89a121b72d4fb66334f1693d3b30e4cb9c5`
- License: GPL-2.0-or-later (see upstream `LICENSE`; some files carry their own notice)
- Fetched into this working tree with user approval for local EXT engine work
- Tracked `README.md` in git is the project placeholder (CI-fetch note), not the upstream README
- Upstream README from the tarball was not kept under the tracked path (whitespace fails `git diff --check`); copy outside tree if needed: `${TMPDIR}/lwext4-upstream-README.md`
- CI continues to use `scripts/fetch-third-party.sh` when `include/ext4.h` is missing
