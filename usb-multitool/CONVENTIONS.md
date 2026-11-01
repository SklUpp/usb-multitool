# USB Multitool Conventions

## License

Application license: `GPL-3.0-or-later` (`usb.BuildFacts.LICENSE`).

## Where builds run

Gradle, NDK, emulator, and instrumentation commands run **only in GitHub Actions**.

Local checks are file, script, and static validation only. Do not run `./gradlew` with any task on developer phones.

## GitHub Actions verification commands

Exact commands executed by `.github/workflows/android-debug.yml`:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:compileDebugKotlin
```

## Local static checks

```bash
sh -n gradlew
git grep -n "compileSdk = 36" -- app/build.gradle.kts
git grep -n "connectedDevice" -- app/src/main/AndroidManifest.xml
git grep -n "actions/setup-java" -- .github/workflows/android-debug.yml
```

## Tooling pins

- Gradle wrapper: 8.13 (`gradle/wrapper/gradle-wrapper.properties`)
- AGP / Kotlin / NDK versions: `gradle/libs.versions.toml`
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 28`

## Host native checks (CI)

exFAT fsck repair path is verified on the Actions runner (not the phone) by:

```bash
./scripts/run-exfat-fsck-host-test.sh
```

This builds a host binary against vendored `third_party/exfatprogs` and the shared `usb_block_*` relative-LBA callback. No root, `/dev`, or FUSE.
