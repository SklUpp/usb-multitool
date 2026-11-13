# Repository Guidelines

## Project Overview

Android USB mass-storage multitool for file management, partitions, imaging, diagnostics and Ventoy. Sideloaded, GPL-3.0-or-later; see `usb-multitool/CONVENTIONS.md` and `usb-multitool/third_party/README.md` for build and licensing details.

## Architecture & Data Flow

- Compose screens emit `UiIntent`; `UiReducer` produces state and effects; `MainViewModel` executes effects. Keep the reducer free of Android and disk I/O.
- `DiskController` and `OperationExecutor` serialize disk operations. `DiskOperation` carries progress, cancellation and terminal state; mark `mutationAttempted` before writing so interrupted writes report `Partial`.
- `usb.operation` owns disk-operation contracts; `usb.service` owns execution and Android service lifecycle. Ventoy domain models live in `usb.ventoy`; domain code must not depend on Compose or `usb.ui`.
- `UsbDisk` handles USB BOT/SCSI. `VirtualPartition` applies the partition offset once; filesystem engines and JNI use relative LBAs. Respect device sector size and bounds.
- `PartitionVolumeSession` wraps FAT/exFAT, NTFS and EXT engines. Close and sync mounted volumes before raw or partition mutations. Preserve close errors and do not mask the original failure.
- Destructive operations bind confirmation to the target identity and geometry; revalidate before writing. Do not automatically resume after disconnect. Preserve partial rescue output and checkpoint only after sync.

## Key Directories

- `usb-multitool/app/src/main/kotlin/usb/`: `multitool` entry point, `ui`, `device`, `operation`, `partition`, `fs`, `service`, `image`, `diagnostics`, `ventoy`, `files`.
- `usb-multitool/app/src/main/cpp/`: JNI bridges, block-device adapter and native filesystem engines.
- `usb-multitool/app/src/test/`: JVM tests and fixtures; `usb-multitool/native-tests/` and `app/src/main/cpp/host/`: host-native tests.
- `usb-multitool/scripts/`: fetch, host-test and APK-alignment scripts; `third_party/`: vendored exfatprogs, upstream pins and notices, with other sources fetched by `scripts/fetch-third-party.sh`; `docs/superpowers/`: design and implementation plan.
- `.github/workflows/android-debug.yml`: CI workflow at repository root.

## Development Commands

Run app commands from `usb-multitool/`. Gradle, NDK, emulator and instrumentation commands run **only in GitHub Actions**, not on developer phones. CI runs:

```sh
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
./gradlew :app:lintDebug --no-daemon
./gradlew :app:compileDebugKotlin --no-daemon
```

Lightweight local checks include `sh -n gradlew` and `sh scripts/run-ui-host-check.sh`; `make -C native-tests/fatfs test` is permitted by `CONVENTIONS.md`. CI also runs the exFAT, NTFS, EXT and usb-block host scripts. Some scripts fetch upstream sources or write build artifacts: inspect them before running; do not fetch or install dependencies without approval.

## Code Conventions & Common Patterns

- Kotlin packages mirror `usb/<layer>`; follow existing Compose state hoisting, `UiIntent`/`UiEffect` and reducer patterns. Native code uses the existing `usb_block` callbacks and JNI boundaries.
- Use the serialized operation queue for disk work. Check cooperative cancellation and propagate transport errors; do not turn disconnects into bad-sector reports.
- Validate sizes, overflow, alignment, partition bounds and live device identity before destructive work. Preserve errors, confirmations and `Partial` states rather than suppressing failures.
- Do not assume 512-byte sectors, add partition offsets twice, or silently overwrite user data. Existing partition move, shrink and expansion are outside the current plan.
- Preserve upstream licenses and provenance. Do not commit locally fetched upstream files or generated build artifacts accidentally.

## Important Files

- `usb-multitool/app/src/main/kotlin/usb/multitool/MainActivity.kt`: Android entry, USB lifecycle and effect execution.
- `usb-multitool/app/src/main/kotlin/usb/ui/UiModels.kt` and `UiReducer.kt`: UI state and transitions.
- `usb-multitool/app/src/main/kotlin/usb/service/OperationExecutor.kt`: disk operation queue and terminal handling.
- `usb-multitool/app/src/main/kotlin/usb/partition/VirtualPartition.kt`: partition-relative block boundary.
- `usb-multitool/app/src/main/cpp/CMakeLists.txt` and `usb-multitool/app/build.gradle.kts`: native and Android build configuration.

## Runtime/Tooling Preferences

Android project uses Gradle wrapper 8.13, Kotlin/Compose and CMake/NDK; CI uses Java 21. Build targets `arm64-v8a`, min SDK 28, compile/target SDK 36. See `usb-multitool/gradle/libs.versions.toml` for pinned versions. Termux paths are environment-dependent: use `$HOME` and `$PREFIX`; do not assume `/tmp` or install software without approval.

## Testing & QA

JVM tests use JUnit 4 and coroutine test utilities; native host tests exercise file-backed images and engine boundaries. Add tests for observable safety invariants, error paths and state transitions. CI runs unit tests, host tests, debug assembly, lint and the 16 KB APK/ELF alignment check; these do not prove Android USB permissions, real-device behaviour, accessibility, PC boot or Windows installation. Record physical-device results separately.
