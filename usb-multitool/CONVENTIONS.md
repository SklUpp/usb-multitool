# USB Multitool Conventions

## License

Application license: `GPL-3.0-or-later` (`usb.BuildFacts.LICENSE`).

## Where builds run

Locally and in github actions

## GitHub Actions verification commands

Exact commands executed by `.github/workflows/android-debug.yml`:

```bash
bash scripts/fetch-third-party.sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug -PusbRequireEngines=true -PusbWithFatfs=true -PusbWithNtfs=true -PusbWithLwext4=true -PusbWithVentoy=true -PusbWithF3=true -PusbWithDdrescue=true
./gradlew :app:lintDebug
./gradlew :app:compileDebugKotlin
```

FatFs host image tests (job `fatfs-host`, no Android SDK):

```bash
make -C native-tests/fatfs test
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
NTFS engine and dirty-flag repair are verified on the Actions runner by:

```bash
./scripts/run-ntfs-host-test.sh
```

This builds a host binary against vendored `third_party/ntfs-3g` and the shared `usb_block_*` relative-LBA callback. No root, `/dev`, or FUSE.
