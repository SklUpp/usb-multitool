# Сборка, тесты, CI и APK

Тяжёлая сборка и полный тестовый прогон выполняются в GitHub Actions.
Локальные Gradle/NDK-сборки не документируются; CI — единственный проверенный
путь сборки APK.

## Toolchain

| Компонент | Версия |
|---|---|
| Gradle wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose compiler | 2.2.10 |
| Compose BOM | 2025.12.01 |
| Java source/target | 17 (CI JDK 21) |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 28 |
| ABI | `arm64-v8a` |
| NDK | 29.0.14206865 |
| CMake | 3.22.1 |
| App | `usb.multitool 0.1.0` (`versionCode 1`) |

Native: `libusb_native.so`; статические upstream-архивы `fatfs_upstream`,
`ntfs3g_upstream`, `lwext4_upstream`, `exfatprogs_fsck`, `libsf_bridge.a`
(SectorForge). Link flag `-Wl,-z,max-page-size=16384`; Gradle передаёт
`-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`.

## Подготовка движков

`scripts/fetch-third-party.sh` — только для CI/Linux. Скрипт
скачивает pinned-версии, проверяет commit/digest и идемпотентно применяет патчи:
FatFs `ff16p1..p4`, ntfs-3g mkntfs-library, lwext4 teardown/O_EXCL, SectorForge
progress map. exfatprogs vendored 1.4.3. F3 и ddrescue скачиваются, но в
`libusb_native.so` не компилируются.

SectorForge bridge собирается Gradle-задачей `buildSfBridge`:
`cargo +1.99.1 build --locked --release --target aarch64-linux-android`.
Результат — `app/build/rust/aarch64-linux-android/release/libsf_bridge.a`.
`scripts/build-sf-bridge.sh` делает то же вручную; CI полагается на Gradle.

## Зависимости

Dependency locking объявлен (`subprojects { dependencyLocking { lockAllConfigurations() } }`),
но `gradle.lockfile`, `app/gradle.lockfile` и `gradle/verification-metadata.xml` в
репозитории отсутствуют, поэтому сейчас lock не enforced. Чтобы включить его
(нужна сеть), сгенерировать и закоммитить:

```bash
./gradlew :app:dependencies --write-locks
./gradlew --write-verification-metadata sha256 help
```

После коммита `gradle.lockfile` / `app/gradle.lockfile` /
`gradle/verification-metadata.xml` mismatch падает на CI.

Host-тесты:

```bash
make -C native-tests/fatfs test
./scripts/run-exfat-fsck-host-test.sh
./scripts/run-ntfs-host-test.sh
./scripts/run-ext-host-test.sh
./scripts/run-usb-block-host-test.sh
cargo +1.99.1 test --locked --manifest-path app/src/main/rust/sf-bridge/Cargo.toml
```

Host-тесты используют file-backed образы и fake block callbacks; реальный USB,
FUSE и root не нужны.

## CI

Workflow `.github/workflows/android-debug.yml`, ветка
`feature/android-usb-multitool` и `workflow_dispatch`, без pull_request.

- `fatfs-host`: checkout → `fetch-third-party.sh` → `make test` в
  `native-tests/fatfs`.
- `unit-tests` (без NDK/Rust/third_party): JDK 21, `setup-gradle`, кэш
  `platforms;android-36` + `build-tools;36.0.0`, затем
  `:app:testDebugUnitTest`. Отчёты загружаются только при failure.
- `verify`:
  1. fetch + Rust cache (`Cargo.lock`) + Rust 1.99.1 + `cargo test` для sf-bridge;
  2. JDK 21, `setup-gradle`, кэш NDK/CMake/SDK по `libs.versions.toml`;
  3. SDK install (platform-tools, android-36, build-tools, NDK, CMake);
  4. exFAT fsck host, NTFS host, EXT host, FAT streaming, usb_block host;
  5. `:app:assembleDebug`;
  6. `check-16kb-alignment.sh`;
  7. `:app:lintDebug`;
  8. `:app:compileDebugKotlin`.

Кэши без `restore-keys` и без секретов. Action SHA pinned. GitHub уже
аннотирует Node.js 20 deprecation: текущие v4 actions принудительно работают на
Node 24; обновление majors/SHA отслеживается в `modernization.md`.

Artifacts:
- `app-debug-apk` — debug APK (`verify`, always);
- `unit-test-reports` — JVM test reports/results (`unit-tests`, on failure).

APK не подписывается release-ключом и предназначен для тестирования, не для
распространения. Release workflow отсутствует; версия задаётся в
`app/build.gradle.kts`.

## Что покрыто тестами

- JVM (JUnit 4, MockK, coroutines-test, Robolectric): BOT/SCSI/USB session,
  claim/permission/fencing, GPT/MBR parse/edit/write/backup, partition math,
  raw source/writer/staging, FatFs/NTFS/EXT/exFAT contracts, files operations,
  SAF ownership, Ventoy layout/settings/autounattend, diagnostics/rescue,
  reducer/transitions/string parity.
- FatFs host: >256 entries, short write, sequential streaming with one open
  `FIL`, failed-chunk cleanup, format over content, 765-byte UTF-8 LFN, path
  overflow preserves neighbor, teardown/sync barriers.
- NTFS host: mount, usage bounds, dirty flag, write/read/truncate/append,
  exclusive create, sync/close, long name/parent guards.
- EXT host: mkfs → mount → CRUD, dirty refuse, failed-umount teardown, UTF-8
  names, usage bounds.
- exFAT host: corrupted image check, repair, re-check, zero writes on read-only.
- usb_block host: slot ownership `-EBUSY`, dispatch/range checks, unregister with
  concurrent reader.

## Не покрыто

- Нет `androidTest` и connected-тестов в CI.
- Не проверяются реальные USB attach/permission/RO-RW, физический BOT,
  SAF-провайдеры, Ventoy boot, Windows install.
- Нет визуального/скриншот-теста Compose, TalkBack, IME, fontScale/RTL.
- 16 KiB проверяется статически по APK/ELF, не на устройстве.
- Native mkfs не имеет cancellation callback.
- APK artifact собирается на каждый verify-run даже при падении более раннего
  шага (`if: always()`); использовать только зелёный run.
