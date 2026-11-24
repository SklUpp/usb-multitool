# Модернизация и совместимость

Аудит: 2026-11-25. Базовая линия: Kotlin 2.2.10, AGP 8.13.2, Gradle 8.13,
Compose BOM 2025.12.01, `compileSdk`/`targetSdk` 36, `minSdk` 28.

Документ фиксирует **некритичный** технический долг и миграции. Ошибки, которые
могут привести к падению, потере данных или неработоспособности на актуальных
версиях Android, исправляются сразу, а не откладываются сюда.

## Исправлено сразу

| Область | Файл | Что было |
|---|---|---|
| SAF/file URI | `ImageSource.kt` (`openRaw`) | `uri.path!!` падал с `NullPointerException` на opaque `file:` URI; теперь явный `IOException` |
| Coroutines | `ProbePublication.kt` | `catch (Throwable)` проглатывал `CancellationException`, и `publish()` мог выполняться после отмены; теперь отмена пробрасывается после `close()` |
| Android 15+ UI | `MainActivity.kt` (`configureSystemBars`) | иконки системных баров всегда были светлыми; в светлой теме они сливались с фоном. Теперь контраст зависит от `darkTheme` и обновляется через `SideEffect` |
| USB permission | `UsbAttachController.kt` | `PendingIntent` без `FLAG_UPDATE_CURRENT` мог вернуть старый token после смерти процесса; добавлено обновление extras, `FLAG_MUTABLE` сохранён |
| Exported Activity | `UsbAttachController.kt` (`probeAttachedDevice`) | битый `Parcelable` из внешнего explicit intent мог уронить `onStart`; разбор обёрнут в `runCatching` |

## Очередь правок

Приоритеты: **P1** — сделать до следующего крупного релиза; **P2** — при
следующем обновлении toolchain; **P3** — гигиена/размер APK.

| # | Приоритет | Область | Пути | Проблема | Официальная замена |
|---|---|---|---|---|---|
| 1 | P1 | Windows-import SAF | `MainActivity.kt:5329-5335, 5463-5468, 5506-5511`; `WindowsImportLaunchQueue.kt:20-24, 56-85`; `WindowsImportPickerCoordinator.kt:105-115` | Последний путь на `startActivityForResult`/`onActivityResult` с собственным аллокатором request code. Диапазон `0x5700..0xFFFE` исчерпывается навсегда, а его непересечение с AndroidX опирается на приватную реализацию `ActivityResultRegistry` | `registerForActivityResult(ActivityResultContracts.OpenDocument())` + один pending-slot. Удалить `requestCode`, `WindowsImportRequestCodes`, `take(requestCode)`, saved `STATE_REQUEST`; тесты перевести на `ActivityResultRegistry` |
| 2 | P1 | Persisted URI grants | `MainActivity.kt:5087-5136, 5188-5194, 5369-5372, 5533-5560`; `SafBackupCoordinator.kt:60-69, 607-611` | Каждый выбор/смена папки делает `takePersistableUriPermission`, но `releasePersistableUriPermission` нигде нет. Старые grant'ы копятся; при лимите системы новые `take` могут отклоняться, а `runCatching` это скрывает | Освобождать заменённые grant'ы после завершения операций, которые ещё держат URI; проверять `getPersistedUriPermissions()` и показывать ошибку `take` |
| 3 | P1 | SAF на main thread | `MainActivity.kt:1398-1403, 5141-5157`; `SafBackupCoordinator.kt:60-69` | `contentInfo()`, `documentDisplayName()` и `DocumentsContract`-запросы выполняются из callback picker'а на UI-потоке. Медленный/облачный provider даёт jank или ANR | Метаданные и grant-bookkeeping — в `Dispatchers.IO`, результат — отдельным `UiIntent` на Main |
| 4 | P1 | Ventoy download | `MainActivity.kt:212, 3690-3712`; `VentoyDownloader.kt:556, 595-610` | Долгий HTTPS-download (до 512 MiB) живёт в Activity-scope без foreground service. На Android 15 фоновая сеть вне lifecycle может быть оборвана; задача не переживает смерть процесса | `lifecycleScope`/`repeatOnLifecycle(STARTED)` для UI-bound загрузки либо `WorkManager`/`dataSync` foreground service для продолжения в фоне |
| 5 | P1 | System bars | `MainActivity.kt:5697-5710` | `statusBarColor`/`navigationBarColor` deprecated в API 35 и не работают в enforced edge-to-edge. Контраст иконок уже исправлен, но сами цвета — мёртвый код | `enableEdgeToEdge`/`SystemBarStyle` из activity 1.17+ и фон под `WindowInsets`; обновлять appearance при смене темы |
| 6 | P1 | Kotlin DSL | `app/build.gradle.kts:109-111` | `kotlinOptions` deprecated с Kotlin 2.0; блокирует переход на Kotlin 2.3/AGP 9 | `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` вне `android {}` |
| 7 | P2 | Version lookup | `VentoyUpstreamCache.kt:14`; `AppShell.kt:783` | `getPackageInfo(String, Int)` deprecated на API 33+ | `PackageInfoFlags.of(0)` с legacy fallback ниже 33 |
| 8 | P3 | Документация | `README.md:104`, `README.en.md:102`, `MainActivity.kt:5897` | Утверждается, что `MANAGE_EXTERNAL_STORAGE` объявлен в манифесте; фактически его нет (это правильно) | Удалить строку/комментарий; не добавлять разрешение |
| 9 | P3 | Build hygiene | `build.gradle.kts:7-10`; `gradle/libs.versions.toml:4-6, 37-38` | `dependencyLocking` без lockfile ничего не блокирует; SDK-алиасы не используются; `tukaani-xz` дублирует `xz` | Либо `--write-locks` и commit lockfile, либо убрать блок; удалить неиспользуемые aliases |
| 10 | P3 | Dead code | `FilesOperations.kt:22-23` | Устаревший `typealias BatchDownloadCursor` без ссылок | Удалить typealias; фасад `FilesOperations` оставить, пока есть вызовы |
| 11 | P3 | Receiver lifecycle | `MainActivity.kt` receiver comment; `MainActivity.kt:5856, 5869-5879` | Комментарий обещает unregister между `onStop/onStart`, но `UsbAttachController.stop()` вызывается только из `onDestroy`; Android 14 кэширует broadcast'ы | Исправить комментарий; при необходимости регистрировать/снимать receiver в `onStart`/`onStop` и перепроверять live-устройства |
| 12 | P3 | Icons | `gradle/libs.versions.toml:27`; `FilesScreen.kt`, `PartitionsScreen.kt`, `VentoyScreen.kt` | `material-icons-extended` больше не рекомендуется и заметно увеличивает APK | Перейти на нужные vector drawables/Material Symbols |
| 13 | P3 | CI actions | `.github/workflows/android-debug.yml` | `actions/setup-java@v4` больше не обновляется; `checkout/cache/setup-java/upload-artifact` работают на принудительном Node 24 из-за Node 20 deprecation | Обновить majors actions и проверить, что pinned SHAs обновлены осознанно |

## Перенос в native: решение

| Кандидат | Решение | Причина |
|---|---|---|
| XZ | **Не переносить сейчас** | Java `XZCompressorInputStream` уже ограничен 64 MiB (`ImageSource.kt`, вызов `(raw, true, 65_536)`). liblzma не уменьшит общий RSS: словарь всё равно нужен, только окажется вне ART heap. JNI-декодер усложнит SAF/`ContentResolver`, отмену и подсчёт байт; liblzma — новая поверхность памяти для недоверенных образов. Вернуться только если профиль покажет, что decode > I/O и нужна поддержка словарей >64 MiB |
| GZIP | **Не переносить** | `java.util.zip.GZIPInputStream` на Android уже использует native zlib через `Inflater` |
| exFAT buffers | **Уже сделано** | `exfat_fsck_jni.cpp` держит один persistent direct `ByteBuffer` на handle (`kScratchBytes`, `HandleRec.scratch_bb`), большие запросы режет на чанки; `allocateDirect` вызывается только при open |
| Wipe | **Не переносить** | `Diagnostics.wipe()` уже пишет по 64 KiB одним переиспользуемым буфером. Узкое место — USB/флеш-контроллер, а не `memset`; native-цикл всё равно должен вернуться в Java USB stack за записью |

### План портирования, если понадобится

Порядок работ и границы, чтобы перенос не обошёл подтверждения, отмену и учёт прогресса.

#### 1. XZ: единственный кандидат с реальным выигрышем по CPU

- Точка входа: `ImageSource.wrap()`/`openRaw()`, формат `ImageFormat.XZ`.
- Реализация: C API `liblzma` (`lzma_stream_decoder`, `lzma_code`, `lzma_end`) в существующем CMake-таргете `usb_native`; Rust `xz2` не даёт выигрыша, потому что всё равно связывает liblzma и добавляет второй мост.
- JNI-контракт: `nativeOpenXz(source, memLimitKb) -> handle`, `nativeReadXz(handle, directBuffer) -> int`, `nativeCloseXz(handle)`. Один persistent direct buffer 64 KiB на handle; без аллокаций на чтение.
- Источник: для `File`/`ParcelFileDescriptor` передавать fd; для `ContentResolver`/HTTP без fd — Java read-callback либо оставить Java-путь. Нельзя требовать seekable fd от SAF.
- Лимит: `lzma_stream_decoder(..., memlimit, ...)`; `LZMA_MEM_ERROR` маппить в `IOException`, а не в native abort. Это не отменяет общий RSS: словарь всё равно занимает память, просто вне ART heap.
- Отмена/закрытие: `close()` из другого потока вызывает `lzma_end`; `nativeReadXz` проверяет cancel-флаг, иначе отмена повиснет до конца блока.
- Совместимость: сохранить текущий контракт `InputStream`/`CountedInputStream`, `decodedSize`, `snapshot`, `skip` и подсчёт SHA-256 на стороне Kotlin.
- Приёмка: benchmark на реальном ARM64-телефоне (decode time, wall time, PSS/native heap), корпус битых XZ, словарь >64 MiB, concatenated/truncated streams. Переключать default только если decode > I/O и лимит памяти доказан.

#### 2. exFAT: перенос уже не нужен

- Сейчас `HandleRec` хранит `scratch_bb` (global ref) и `scratch_ptr`; `java_transfer` режет запрос на `scratch_bytes` и вызывает Java read/write без `allocateDirect` на сектор.
- Замена direct ByteBuffer на `malloc` не даёт выигрыша: обе области вне Java heap; меняется только владелец и lifetime.
- Если трогать, то только для устранения конкретного профиля (например, JNI GlobalRef pressure), с тестом на close/GC и без изменения FS-контракта.

#### 3. Wipe: native-цикл не оправдан

- `Diagnostics.wipe()` уже использует один `ByteBuffer` и `TransferPlan.MAX_CHUNK`; накладные — USB Bulk-Only/SCSI и FTL, не `memset`.
- Native wipe должен был бы вызывать тот же `usb_block_write`, но при этом сохранить `DestructiveConfirmation`, `DiskOperation` token/progress/cancel и телеметрию. Иначе появляется второй путь записи в обход защиты — это не optimization, а security regression.
- Допустимая локальная оптимизация без native: переиспользуемый direct zero-buffer и/или chunk size, подтверждённый SCSI/USB стеком.

#### Условия запуска портирования

1. Профиль показывает, что декодирование/нуление — узкое место, а не USB I/O.
2. Для XZ есть требование поддерживать словари >64 MiB либо decode заметно быстрее Java.
3. Реализация идёт за feature flag с Java fallback; default не меняется до benchmark + fuzz + release-проверки.
4. Любой native-путь записи обязан проходить через существующие confirmation/operation guards, а не дублировать их.

## Проверено: менять не нужно

- `ActivityResultContracts.CreateDocument(String)` — актуальная перегрузка; без параметра — deprecated.
- `OpenDocument`, `OpenDocumentTree`, `OpenMultipleDocuments`, `RequestPermission` — актуальны.
- `takePersistableUriPermission` присутствует на всех сохраняемых URI; проблема в освобождении, не в получении.
- Нет `READ/WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `Environment.getExternalStorageDirectory`.
- `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` и `FLAG_MUTABLE` для USB `PendingIntent` корректны для Android 14.
- Foreground service `connectedDevice` и `startForeground` с типом на API 34+ корректны.
- Compose compiler plugin подключён; `composeOptions`/`kotlinCompilerExtensionVersion` отсутствуют.
- 16 KB page-size флаги и CI-проверка присутствуют.

## Источники

- Activity Result: https://developer.android.com/training/basics/intents/result
- SAF permissions: https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions
- Edge-to-edge: https://developer.android.com/develop/ui/views/layout/edge-to-edge
- Android 15 background network: https://developer.android.com/about/versions/15/behavior-changes-all#background-network-access-restrictions
- Kotlin compiler options: https://kotlinlang.org/docs/gradle-compiler-options.html
- Kotlin/AGP compatibility: https://kotlinlang.org/docs/gradle-configure-project.html
- Dependency locking: https://docs.gradle.org/8.13/userguide/dependency_locking.html
