# USB Multitool

[Русский](README.md) | **English**

Android app for direct work with USB drives: files, MBR/GPT partitions, formatting, image writing, Ventoy installation, and disk diagnostics. The app does not need root. It ships as a sideloaded APK under GPL-3.0-or-later. The UI is English and Russian, with an in-app language switch.

## Contents

- [What it is](#what-it-is)
- [Features](#features)
- [Data safety](#data-safety)
- [Requirements and compatibility](#requirements-and-compatibility)
- [Getting the APK](#getting-the-apk)
- [Limitations](#limitations)
- [Build and checks](#build-and-checks)
- [Development](#development)
- [Licenses](#licenses)
- [Related projects](#related-projects)

## What it is

The app controls a USB mass-storage device through the Android USB Host API (`UsbDeviceConnection` and Bulk-Only Transport/SCSI). In-app C engines open the filesystems through JNI. The app does not use Android mounting. The Storage Access Framework only selects files on the phone, for import, export, and restore sources.

The default resources are English. `values-ru` holds Russian. The System/Russian/English choice persists between launches.

Repository layout:

| Path | Contents |
| --- | --- |
| `usb-multitool/app/` | Kotlin/Compose app and JNI bridges |
| `usb-multitool/app/src/main/cpp/` | FAT/exFAT, NTFS, and EXT4 engines with the block-device adapter |
| `usb-multitool/scripts/` | pinned upstream fetching, host tests, and the APK alignment check |
| `usb-multitool/third_party/` | third-party sources and notices |
| `.github/workflows/android-debug.yml` | the only place for Gradle/NDK builds |

## Features

The UI has four tabs: Files, Ventoy, Partitions, Diagnostics. This section lists only the behavior in the current branch.

### Files

- The app shows one tab for each recognized partition. EFI and VTOYEFI partitions also have a tab.
- You can browse directories, create folders, upload files from the phone (SAF), save files to the phone (SAF), delete entries, and rename entries.
- Writes to an EFI or VTOYEFI partition are blocked. The unlock applies to one partition and one connection. When the disk changes, reconnects, or detaches, the app clears it. An unlocked partition can also be deleted or formatted.
- The app offers two actions for an `.iso` file on the drive. "To raw" stages the image in app storage, then writes it to the disk. "XML" links the ISO to a Windows `autounattend.xml` template.

### Ventoy

- When `/sdcard/ventoy` exists, install and update prefer that directory. Otherwise, they use the bundled Ventoy 1.1.18 assets. An external `sha256.txt` replaces the pinned expected hashes for that directory. The bundled assets match the SHA-256 values pinned in code.
- An update keeps the ISO partition. If the layout is not recognized, the update stops.
- "Download a new version and install" requests the latest GitHub release. The archive SHA-256 comes from GitHub asset metadata, and the app refuses a release without a `digest`. The code also pins the archive and member hashes for versions 1.1.05 and 1.1.18.
- Windows options have three states. For Windows 11 checks (`VTOY_WIN11_BYPASS_CHECK`), choose keep, bypass all (TPM, RAM, Secure Boot, CPU), or require all. For the account (`VTOY_WIN11_BYPASS_NRO`), choose keep, local account, or Microsoft account. An explicit choice writes `1` or `0`. Keep leaves the existing value. Ventoy needs the network unplugged for a local account.
- The XML template is minimal: it has no LabConfig or FirstLogonCommands. The app overwrites existing configuration only with explicit user consent.

### Partitions

- The app reads MBR and GPT tables. It shows free regions and a disk map.
- You can create a partition in free space with 1 MiB alignment. You can delete a partition. The app supports MBR and GPT.
- You can initialize an empty MBR or GPT table and you can replace the current scheme. The confirmation pins the current scheme and every existing partition geometry; a changed layout aborts before writing. A hybrid/ambiguous layout and a GPT recovered from its backup are refused. MBR is refused when the disk exceeds 32-bit LBA. On GPT, create can store a partition name (up to 36 UTF-16 characters) and can immediately format the new range with the chosen filesystem and volume label. MBR has no name field, so a name is refused instead of being dropped.
- You can rename the GPT name of the selected partition and the volume label of a formatted partition. Renaming a protected EFI/VTOYEFI entry is refused. Labels are written only by engines that can: FAT/FAT32 (11 characters), exFAT (11 characters), NTFS (128 characters); EXT4 labels are written only while formatting.
- A role change writes the partition type: EFI system or Linux data. The app does not restore the previous type automatically.
- The boot flag is a separate operation. GPT stores it in partition attributes, MBR in the boot byte.
- You can format the selected partition as FAT/FAT32, exFAT, NTFS, or EXT4. Real engines do the format.
- You can write an `.iso`, `.img`, `.gz`, or `.xz` from a local file. The app checks bounds and size before the write. Decompression runs on the fly. You can cancel the operation.
- When the primary GPT is damaged, the app reads the table from the backup copy. Create, delete, role change, flag change, and GPT repair are blocked until a GPT restore from a saved copy succeeds.
- A GPT write from the partition editor (create, delete, role, flag) publishes the backup structures first, then the primary. A disconnect leaves at least one complete copy with a valid CRC.
- "Restore GPT table" moves the backup to the current disk end. The app refuses a damaged primary, a hybrid layout, or partitions beyond the new limit.
- A GPT restore from a saved copy checks the size, CRC, backup position at the disk end, and primary/backup agreement.

### Diagnostics

- The self-test checks SCSI codecs, alignment, free space, GPT geometry, rescue-map serialization, Ventoy config, and filesystem guard gates. It also runs an in-memory FatFs roundtrip. With a disk attached, it probes the live device.
- The read scan reads the disk in 1 MiB chunks. It narrows bad sectors sector by sector only after a medium error. It writes nothing.
- The rescue backup writes an image of all disk sectors and a map file. The map uses the app format `usb-rescue-map 2` with sector size, sector count, device identity, and range statuses. Both files go to `Android/data/usb.multitool/files`. Unreadable sectors stay in the map.
- The GPT backup saves the critical table sectors to a separate file.
- The wipe writes zeros across the disk after confirmation.
- Image restore writes only the ranges in the map. The app re-reads each COPIED range, compares it with the image, and rewrites a mismatch. The app refuses a COPY map with NON_TRIED sectors. FAILED ranges receive image bytes, usually zeros. Sector size must match, and the map must fit the disk. The full disk geometry need not match. After sync, with full coverage and no FAILED ranges, the app verifies the whole restored region against a fresh image read.

## Data safety

> CAUTION: Test on an empty USB drive. A wrong layout, confirmation, or disk choice destroys the data on the drive. The app cannot undo a write operation.

- Each destructive operation needs a confirmation with device identity (model and serial) and capacity. The app checks partition bounds and integer overflow before each write.
- The app syncs and closes open volumes before it changes the partition table or writes raw data.
- Wipe, create/delete, create-and-format, rename, table initialization, format, raw write, GPT repair, role, and flag changes mark the first write. After it, they report `Partial`. Before the first write, the result is `Failed` or `Cancelled`. If creating a partition succeeds and the immediate format fails, the partition stays and the operation reports `Partial` with an "exists but is unformatted" result. When its own mutation fails, Ventoy install/update reports `Partial`.
- When the process stops, the app does not save the operation state. Nothing resumes automatically after reconnection.
- Do not change the rescue image before the restore finishes. The app reads it as is. The app does not promise a snapshot or change detection. An edit can produce mixed data. The final check does not prove that the source stayed unchanged.
- A rescue image stays a dense file with a map. Unreadable sectors stay visible. The app never shows a partial image as a full copy. A rescue copy needs a stable device serial.
- The app collects no telemetry. It uses the network only to check and download Ventoy releases from GitHub.

The app declares these permissions in `usb-multitool/app/src/main/AndroidManifest.xml`:

| Permission | Purpose |
| --- | --- |
| `INTERNET` | Check and download Ventoy releases |
| `POST_NOTIFICATIONS` | Show a notification for a long operation (Android 13+) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Run disk work in a foreground service |
| `WAKE_LOCK` | Keep the CPU active only during an operation |
| `MANAGE_EXTERNAL_STORAGE` | Retained in the manifest for future operations; not requested at startup. Ventoy assets use app-private storage, and import/export/backups use SAF. |

## Requirements and compatibility

- Android 9 (API 28) or later. `targetSdk` and `compileSdk` are 36.
- The APK supports `arm64-v8a` only.
- The phone must support USB Host (OTG). The drive must use USB mass storage with Bulk-Only Transport. The app does not support UAS or other transports.
- The app reads the logical sector size from the device. The code does not assume 512 bytes. For Ventoy, the app refuses 4K native disks (4096 bytes) because upstream does not support them.

The repository does not establish compatibility with specific USB drives, OTG adapters, or hubs. CI does not emulate a real USB device. Ventoy installation and ISO writing do not guarantee boot on a given PC. These checks are separate acceptance tests.

## Getting the APK

The project has no releases and no Google Play publication. GitHub Actions builds the debug APK.

1. Open the [`android-debug` workflow](../../actions/workflows/android-debug.yml).
2. Run the workflow manually with `workflow_dispatch`, or push to the `feature/android-usb-multitool` branch.
3. Download the `app-debug-apk` artifact. The `unit-test-reports` artifact holds the test reports.

The build signs the APK with a debug key for testing only. Do not distribute it. The project does not support local Gradle or NDK builds. See `usb-multitool/CONVENTIONS.md`. This README does not assert a successful final CI run or device checks.

## Limitations

- The app cannot move, shrink, or grow an occupied partition or its filesystem.
- F3, write benchmarks, and images over HTTP(S) are not planned.
- The app does not use root, FUSE, loop devices, or UAS. It does not resume an operation after a disconnect.
- CI checks the code and the host engines. It does not test real USB permissions, on-device behavior, boot from the drive, or Windows installation.
- CI checks APK and ELF alignment for 16 KB pages with a script. The project has no on-device alignment check.
- The repository has no root `LICENSE` file yet. The code declares the license in `usb-multitool/app/src/main/kotlin/usb/BuildFacts.kt`.

## Build and checks

Gradle and NDK commands run only in GitHub Actions (`.github/workflows/android-debug.yml`). The working directory is `usb-multitool`. The `verify` job runs these steps:

```bash
./scripts/fetch-third-party.sh
./scripts/run-exfat-fsck-host-test.sh
./scripts/run-ntfs-host-test.sh
./scripts/run-ext-host-test.sh
./scripts/run-usb-block-host-test.sh
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
./scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/*.apk
./gradlew :app:lintDebug --no-daemon
./gradlew :app:compileDebugKotlin --no-daemon
```

`:app:testDebugUnitTest` also runs Robolectric tests: the real `MainActivity` (POST_NOTIFICATIONS request) and the SAF boundary `ImageSource.fromContentUri`. Robolectric downloads the `android-all` jar on first run, so it needs network access; no device or emulator is required.

The separate `fatfs-host` job has no Android SDK. It builds and runs the FatFs host tests:

```bash
make -C native-tests/fatfs test
```

You can run these static checks locally: `sh -n gradlew`, `git grep` for pinned values, and `sh scripts/run-ui-host-check.sh`. The last script expects Termux paths and needs `kotlinc`. If you need upstream sources, run `scripts/fetch-third-party.sh` and the host tests deliberately. They download and build sources in `usb-multitool/third_party/`.

| What CI proves | What CI does not prove |
| --- | --- |
| Kotlin/Compose and NDK engine compilation, lint, unit tests | real USB drives and Android permissions |
| FatFs/exFAT/NTFS/EXT host tests on file images | Ventoy boot and Windows installation on a PC |
| Debug APK assembly and the 16 KB alignment check | behavior on a given phone and drive |

## Development

- `AGENTS.md` — architecture, safety rules, and commands.
- `.github/workflows/android-debug.yml` — the exact CI command order. `usb-multitool/CONVENTIONS.md` — where the project allows builds.
- `usb-multitool/third_party/README.md` — pinned engine versions and licenses.
- `usb-multitool/docs/superpowers/specs/2026-11-01-android-usb-multitool-design.md` — approved design.

### App versions

`defaultConfig` in `usb-multitool/app/build.gradle.kts` is the only source of the version (currently `versionCode = 1`, `versionName = "0.1.0"`).

- `versionCode` increases by 1 when a new installable version is released, not on every CI run: verification builds keep the version unchanged. Released `versionCode` values never repeat or decrease, otherwise Android cannot compare two installs.
- `versionName` follows MAJOR.MINOR.PATCH and never decreases; a release tag, once tags exist, is `v<versionName>`.
- The app header shows the installed `versionName` from `PackageManager`, so after a sideload it displays the version actually on the device.

The project uses Kotlin 2.2.10, Jetpack Compose (BOM 2025.12.01), AGP 8.13.2, Gradle 8.13, NDK 29.0.14206865, CMake 3.22.1, and Java 21 in CI. Tests use JUnit 4, MockK, and kotlinx-coroutines-test. Native host tests build with `make` and `gcc`.

## Licenses

Application license: GPL-3.0-or-later (`usb.BuildFacts.LICENSE`). License text: <https://www.gnu.org/licenses/gpl-3.0.html>.

Third-party components keep their own licenses. `usb-multitool/third_party/README.md` pins the versions and the links:

| Component | Use | License |
| --- | --- | --- |
| [FatFs R0.16 + patches](https://elm-chan.org/fsw/ff/) | FAT12/16/32, exFAT | BSD-style (FatFs) |
| [ntfs-3g 2026.9.28](https://github.com/tuxera/ntfs-3g) | NTFS read/write, mkntfs | GPL-2.0-or-later |
| [lwext4](https://github.com/gkostka/lwext4) | EXT2/3/4, mkfs | GPL-2.0-or-later |
| [exfatprogs 1.4.3](https://github.com/exfatprogs/exfatprogs) | exFAT fsck/repair | GPL-2.0-or-later |
| [Ventoy 1.1.18](https://github.com/ventoy/Ventoy) | boot payloads | GPL-3.0-or-later |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/), [XZ for Java](https://tukaani.org/xz/java.html) | image decompression | Apache-2.0 / public domain |

The tree includes FatFs (patched R0.16), `exfatprogs`, and part of `ntfs-3g` without `ntfsprogs`. `scripts/fetch-third-party.sh` fetches the full `ntfs-3g` tree, plus lwext4 and F3 sources from pinned URLs and revisions. When `lzip` is available, the script tries to fetch ddrescue. When `ff.c` exists, the script skips FatFs. If you distribute the APK, the GPL source-offer obligations apply.

The project is not affiliated with Ventoy, Tuxera, EtchDroid, or other projects in this document.

## Related projects

These projects were design references and documentation examples for Android USB tools. They are not forks. The project uses no code from them.

- [EtchDroid](https://github.com/EtchDroid/EtchDroid) — writes images to USB without root. It has a clear list of supported devices and images.
- [Ventoid](https://github.com/GPLaider/Ventoid) — an Android Ventoy installer. It documents OTG limits and checksum verification.
- [standard-readme](https://github.com/RichardLitt/standard-readme) — the README structure specification for this file.
- [Android USB Host](https://developer.android.com/develop/connectivity/usb/host) — official USB Host API documentation.
