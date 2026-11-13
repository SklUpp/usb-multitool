# План архитектуры и декомпозиции

Основа: аудит `c666d50`, `docs/superpowers/specs/2026-11-01-android-usb-multitool-design.md` § Architecture review and deferred decomposition. План исполняется последовательными проверяемыми изменениями; новые границы вводятся только при реальном снижении связности.

## Проблемы и инварианты

- `measureFilesystemUsage` работает вне очереди диска; уже запущенная проверка может смонтировать файловую систему между закрытием тома и открытием USB другой операцией. Одновременный claim и повреждение не воспроизведены; окно подтверждено кодом.
- `currentAttachmentPin()` читает устройство и поколение раздельно; переход подключения может дать несогласованную пару. Фактический ущерб не доказан.
- ViewModel совмещает жизненные циклы attachment, файловой сессии, inspection, операций и staging. Reducer объединяет переходы разных сценариев.
- Обязательны единое владение USB, identity и attach generation, точная геометрия, epoch-fence, неподтверждённый close latch, destructive confirmation, sync/close, cancellation, readback и честные `Partial`/`Failed`/`Completed`. `OperationExecutor` сохраняет владение очередью и итоговым вердиктом.
- `FilesVolumeSession` уже содержит pin и epoch-fence; `UiEffect` уже типизирован. Не внедрять повторно. Latch не сбрасывается по одному detach без доказательства безопасности native-движков.

## Порядок

1. Зафиксировать детерминированными тестами гонки attachment snapshot и параллельного открытия USB; устранить их общей границей владения, включая usage probe, filesystem и direct-disk операции. Сохранить порядок закрытия и обработку ошибок. Независимо проверить до интеграции.
2. Выделить владельца inspection-ticket и его жизненный цикл, только если это не создаёт второго владельца USB. SAF cancellation не снимает USB guard до фактического завершения и закрытия; поздние callback и process death не пересекают сессии.
3. По одному отделять сценарии picker и SAF backup от Activity/ViewModel: Android lifecycle и launcher остаются с соответствующим владельцем; reducer сохраняет единую точку переходов. Не смешивать инспекцию импорта с инспекцией restore.
4. Выделять domain handlers и чистые переходы reducer по отдельности, когда это улучшает локальность и тестируемость. Разные политики close/cancel/Partial не сводить к универсальному submit-wrapper.
5. Остановиться на естественных границах: не создавать однопроходные прокси и классы ради размера файлов; не переносить ownership staging, подтверждений или операций без проверяемой необходимости.

## Проверка

Каждый срез: тесты наблюдаемого поведения и гонок, независимое ревью, соответствующие локальные лёгкие host-проверки; Android build/unit/lint и 16 KB alignment в CI. Для опасных путей нужны физические USB detach/claim, ошибки файловых движков и провайдера SAF; CI не заменяет их. Не утверждать проверку Ventoy boot, Windows installation или TalkBack без отдельного испытания.

Статическая валидация в CI: существующий `lintDebug` сохранить; оценить `lintRelease` и включение Kotlin warnings-as-errors после проверки текущих предупреждений. Для собственного C/C++ рассмотреть предупреждения компилятора и Clang Static Analyzer с реальными NDK compile commands, не включая весь upstream в обязательный gate. Для зависимостей проверить Gradle verification и контроль SHA скачиваемых исходников; сейчас `fetch-third-party.sh` не сверяет документированные контрольные суммы. Новые инструменты требуют отдельного согласия; CodeQL для Kotlin требует сборки, `build-mode: none` его не анализирует. Рекомендации пока не выполнялись.

Порядок внедрения: (1) в CI зафиксировать фактические lint и compiler warnings, не вводя пустой baseline и глобальный `-Werror` вслепую; (2) проверять собственные JNI/engine translation units с NDK-флагами из `compile_commands.json`, отделив upstream от first-party; (3) проверить SHA архивов и достоверность Gradle verification metadata по доверенному источнику; (4) оценить отдельный CodeQL manual-build для Kotlin и C/C++. Сгенерированные из непроверенной загрузки контрольные суммы не устанавливают её подлинность. SARIF, доступность clang-tidy в NDK r29 и работоспособность Android CodeQL build ещё не проверены.

Верификация UI/XML: строки собственных статусов частично обходят локализацию; зажатие функций parser security подтверждено лишь как обработка исключений, а эксплуатация UTF-32 и внутренней entity expansion зависит от конкретного парсера. Не утверждать подтверждённый XXE без воспроизведения. События трассировки не должны включать пароль unattended XML, содержимое файлов, токены и произвольные URI.

Производительность: измерений нет. Подтверждены 64 KiB SCSI-команды, повторные sync в delete/rename/upload и частые checkpoint при restore; SAF backup уже использует интервал 256 MiB. Любая оптимизация должна сохранить ошибки sync, `Partial`, отмену и порядок данных/карты. Увеличение SCSI-команды требует оценки таймаута и задержки отмены; увеличение интервала checkpoint увеличивает потерю прогресса. Прогресс UI уже опрашивается каждые 120 мс, поэтому частые `report()` сами по себе не означают миллион перерисовок. Readback и live revalidation не кэшировать.

## Отдельные открытые вопросы

При не-транспортной ошибке повторного probe могут остаться старые разделы с `tableValid=false`. При удалении или форматировании обработчик повторно читает таблицу и сверяет геометрию: совпавшая цель может быть обработана, несовпадение приводит к отказу до записи. Старый список остаётся проблемой достоверности UI; физическая проверка не проводилась.

FAT/exFAT JNI не передаёт результат unmount. `f_mount(NULL, ...)` снимает регистрацию тома и не вызывает `sync_fs`; обычные изменения файловой системы имеют собственные пути синхронизации. Ветка исправления teardown не принята: ревью выявило, что безусловный flush при закрытии может записывать метаданные read-only просмотра и защёлкивать latch после отключения. Фактическая потеря данных не воспроизведена; требуется сохранить защиту writable-операций без записи при read-only просмотре.

Независимая проверка EXT/NTFS: `ExtVolume.close()` не синхронизирован; два параллельных вызова одного объекта вне файловой сессии могут передать освобождённый native handle повторно. Штатная `FilesVolumeSession` сериализует этот путь; исправление защиты границы API готовится. `NtfsVolume.close()` уже синхронизирован.

Прямой повторный mount EXT/NTFS одного `BlockDevice` может оставить первый объект вне карты открытых томов; штатная файловая сессия закрывает прежний mount до нового. Два разных `VirtualPartition` могут представлять один диапазон и при этом иметь разные object-identity. Защиту этой публичной границы оценивать отдельно от доказанной штатной достижимости.

JNI EXT/NTFS выделяет буфер по запрошенной длине без явного потолка; чрезмерный прямой запрос может вызвать аварийную нативную аллокацию. Штатная загрузка файлов читает чанками до 1 MiB. Непроверенные утверждения о некорректном UTF-8 и результате NTFS sync остаются гипотезами, а не доказанной потерей данных.

При ошибке `bulkOnlyReset()` `UsbDisk` может остаться открытым: последующие sync/read внутри той же операции повторно обращаются к неисправному каналу. Независимая проверка не обнаружила ложного `Completed` или длительной утечки: финальный `close` освобождает соединение. Исправление должно закрывать сессию при неудачном reset и сохранять причину ошибки; сгруппировать с изменениями USB ownership после завершения аудита.

Хранилище: загружаемые компоненты Ventoy и staging должны находиться в app-private storage. Автоматическое использование `/sdcard/ventoy` требуется убрать; внешнее хранилище используется для явно выбранных пользователем backup, образов, файлов, GPT backup и конфигураций через системные picker. Выбранные пользователем данные и их разрешения не удалять при переходе.

В текущем HEAD внешний `/sdcard/ventoy` имеет приоритет над APK, а его `sha256.txt` хранится рядом с образами и не является независимым источником доверия. Для latest без закреплённой версии digest берётся из GitHub API, а хеши отдельных файлов вычисляются из скачанного содержимого; это не независимый pin. XZ-разжатие не ограничено по выходному размеру. Исправления объединить с переносом хранения Ventoy и проверить до записи на USB.

Ventoy: независимая проверка показала, что VTOYEFI сверяется только по первому и последнему сектору payload, причём чтение происходит до `disk.sync()`. Ошибка в середине 32 MiB может остаться незамеченной. В группе исправлений Ventoy предусмотреть sync до полной проверки записанного диапазона и `Partial` при ошибке; чтение после sync не гарантирует честность аппаратного кэша без проверки устройства.

Ожидающее действие backup folder picker должно быть привязано к исходному подключению: смена устройства во время системного диалога не должна незаметно направлять backup на другой носитель. Исправление и его lifecycle находятся на независимом ревью.

Политика восстановления после latch требует отдельного решения. SectorForge и ранее начатые UI/Raw/Ventoy ветки интегрировать только после совместного ревью и проверок, не смешивая с механическим рефакторингом.

## Выполненные срезы

- `aabd137`: переходы файлового picker вынесены из `UiReducer` в `FilesPickTransitions`; host UI JUnit — 354 теста, host check — PASS. CI для этого commit прошёл unit, сборку debug APK, lint, Kotlin и проверку 16 KB alignment.
- `787ae35`: инспекция получила ticket-bound владельца задач; независимое ревью и scoped host-прогон девяти тестов прошли. CI [43105775045](https://github.com/SklUpp/usb-multitool/actions/runs/43105775045) прошёл host, unit, сборку debug APK, lint, Kotlin и проверку 16 KB alignment. USB-guard сохраняется до окончания операции и закрытия диска.
- `cbb266f`: интегрированы `ExtCloseGuard`, `FixMbrGeometry`, `FixGzipSize`, `FixBulkReset`, `FatTeardownFix` (11/11 нативных тестов пройдены на хосте), `AppVersionUi` (6 файлов) и `RobolectricLifecycle` (Robolectric 4.17, 3 теста POST_NOTIFICATIONS + SAF `ContentUriImageSourceTest`).
- `5a83a00`: `FixFilesPolicy` (ревью **correct**) — запрет `\`, NUL, `.`/`..` в `FilesOperationPolicy.requireChildName`, точный allowlist в `isMissingDirectoryListError`, удалён мёртвый `isAbsentPathError`.
- `8893e1d`: `FixFatBounds` — защита от отрицательных `offset`/`length` и переполнения `offset + size` в `FatVolume` и `resolvePlannedTotal`.
- `9ddf0de` + `f53c303`: `DecomposeReducer` — вынесены 36 чистых переходов в `PartitionTransitions.kt`, `RawTransitions.kt`, `RescueBackupTransitions.kt`, `VentoyTransitions.kt` (`UiReducer.kt` 4843 → 3972 строк); ревью `ReviewBulkReset` подтвердило 100% эквивалентность всех 108 ветвей.
- `e8186df`: `FixNativeJni` (ревью **correct**) — `requireIoRange` (8 MiB FAT/EXT, 16 MiB NTFS), `ensureWritable()` в `ExtVolume`, проброс `-EIO` из `ntfs_engine_sync`, удалены мёртвые `fs_bridge.c`, `ddrescue_bridge.c` и `DdrescueNative`.
- `e84fc21`: объединены `UsbOwnershipFix` (процессный `UsbExclusiveClaim`, атомарный `AttachmentState<UsbDevice>`, `openRawDisk(revalidate)` на всех 19 путях) и `DecomposeBackupFlow` (`BackupFolderLaunch.kt` с каноническим trimmed serial и проверкой pin на открываемом snapshot).
- `af7ee39`: `TraceLogExport` — кольцевой лог `AppTraceLogger` (`trace.log`/`trace.log.1` 2×512 KiB в `filesDir/trace`, `redactTrace` для паролей/токенов/Bearer/query) и экспорт в SAF с экрана журнала.
- `adf0f5f`: `FixVentoyReadback` (ревью **correct, 0 findings**) — `disk.sync()` перенесён до всех readback-проверок в `VentoyLayout.kt`, `requirePart2Written` проверяет весь диапазон Part2 (65536 секторов) чанками по 64 KiB.
- `f53c303`: `VentoyStorageFix` (ревью **correct**) — перенос ассетов/staging Ventoy в app-private storage, удаление стартового запроса `MANAGE_EXTERNAL_STORAGE`, XZ-кап 64 MiB и gzip-кап 512 MiB, диалог предупреждения загрузки релиза, маскировка пароля Autounattend с иконкой Eye/EyeOff и SHA-256 в плане, удаление дублей `sync`, пакетная очистка 2×17 KiB и слайдеры производительности в `DiagnosticsScreen`.
- `cea754b`: `FixFetchSha256` (ревью `ReviewNativeJni` + рецепт объединения цепочки `ff16p1..p4`) — проверка SHA-256 и commit-pin в `scripts/fetch-third-party.sh`.

## Повторный аудит всего проекта — 2026-11-13

Срез: live `62336d6` и отдельно незавершённые worktree. Два независимых read-only скаута: `agent://AuditFilesArchitecture` (Files/SAF, USB, native и Rust) и `agent://AuditUiArchitecture` (Android, UI, Ventoy, тесты и CI). Никакие сборки и runtime-сценарии в этом аудите не запускались. Разделы выше фиксируют историческое состояние на момент прошлой проверки и не являются утверждением о текущем HEAD: в частности, ранее отмеченные EXT double-close, USB reset, FAT teardown, Ventoy app-private storage, SHA pins и полный readback позднее исправлялись; статус реализации указан в списке выполненных срезов и в состоянии проекта.

### Приоритеты и границы

1. **Android orchestration.** `MainViewModel` по-прежнему связывает USB attachment, Files, SAF, Ventoy, Raw, backup, progress и lifecycle (`app/src/main/kotlin/usb/multitool/MainActivity.kt:171-5665`). Это повышает цену проверки изменений и риск междоменного взаимодействия. Уже есть `AttachmentState`, `InspectionOwnership`, `DiskController`, `FilesVolumeSession` и typed intents/effects; не создавать их дубликаты. `WindowsImportPickerCoordinator` интегрирован в `c96810e` после статического независимого ревью; registration launcher и разрешения остаются в Activity. Сборка и runtime ещё не проверены.
2. **Reducer и оболочка.** `UiReducer.kt` всё ещё связывает несколько доменов и общую lifecycle-политику; `AppShell.kt` смешивает shell/dialogs и Android side effects. Сохранять единый верхнеуровневый контракт и выделять только целостные transitions или screen-independent shell с сохранением accessibility и lifecycle, не делить по размеру.
3. **Files и SAF.** Незавершённая реализация conflict/resume не интегрирована. Контракт должен связывать согласие с точным SAF parent/name/document ID и type, отделять созданный каталог от родителя, повторно проверять attachment/geometry в queued операции и сохранять факт мутации до provider вызова. Отмена/ошибка после мутации — честный `Partial`; удаление чужого дерева при directory Replace не разрешено. `FilesVolumeSession` остаётся единым владельцем mount/lock/epoch/claim/close latch.
4. **Операции и статус.** Незавершённая token-ветка не принята: вытеснение отображаемой операции может подавить обязательные completion effects прежней операции. Разделить владение отображением, адресную отмену и выполнение обязательных completion effects; token не заменяет attachment generation. Измерение использования файловой системы выполняется вне общей очереди (`MainActivity.kt:2622-2694`); возможное пересечение с raw-операцией требует проверки на уровне shared claim/очереди и теста, физическая порча не доказана. Binding должен использовать единый immutable snapshot устройства и геометрии.
5. **SectorForge/JNI.** `rust/sf-bridge/src/lib.rs:157-190` формирует полный snapshot карты; `app/src/main/cpp/sf_bridge_jni.cpp:127-147` выделяет JNI-массив. Фрагментация может увеличить объём памяти; размер `count * 3` должен проверяться до преобразования в `jsize`. Ограничение без явного incomplete/failure запрещено: карта checkpoint не должна выглядеть полной после усечения. Также проанализировать единое владение run/BridgeCtx при конкурентных `execute/blocks/free`; штатный Kotlin caller последователен, production race не воспроизведён. Проверить attach/detach JNI threads и checked arithmetic для Rust callback seek; это граничные риски, а не доказанная потеря данных.
6. **SAF/native boundaries.** Обработка неудачи persistable URI grant должна различать transient и persisted право, не объявляя неподтверждённое постоянным. Direct FAT block close и повторный native mount вне `FilesVolumeSession` — риски публичной границы, не доказанные сбои штатного UI. SAF positional capability уже проверяется fail-closed; очистка допустима только для точно принадлежащих операции URI. Не добавлять глобальный registry или удаление, не доказав их семантику с close latch.
7. **Build/test.** Текущий CI содержит native host/Rust/unit, APK, lint и 16 KB alignment; это не проверка физического USB, SAF provider faults/process death, Ventoy boot, Windows Setup или TalkBack. Rust artifact/CMake provenance необходимо сохранять fail-closed. Нужны поведенческие проверки orchestration, ошибки провайдера, изменения attachment и ограничений JNI, не тесты текста исходников.

### Порядок

Завершить и независимо проверить Files; безопасно интегрировать проверенный Windows picker; устранить противоречие token completion ownership; затем оценивать изолированные срезы SectorForge/JNI, shared USB scheduling, оставшиеся доменные handlers и shell/reducer. Каждый срез сохраняет sync/close, read-back, cancellation, `Partial` и process-wide fail-closed latch. Любая новая зависимость или системная установка требует отдельного согласования. Утверждение «декомпозиция завершена» возможно только после повторного аудита, когда оставшиеся разделения не дают измеримой локальности или безопасности.

### Предыдущие решения и источники

SAF prior art: [SimpleStorage](https://github.com/anggrayudi/SimpleStorage) — Kotlin recursive copy/conflicts, но его sealed `StorageFile` не принимает наш `FilesVolume` напрямую; [FSAF](https://github.com/K1rakishou/Fuck-Storage-Access-Framework) предоставляет custom manager seam, совместимость exact-ID/Partial/USB ownership не доказана; [libaums](https://github.com/magnusja/libaums) заявляет FAT32 и не заменяет все имеющиеся файловые системы. Поиск не подтверждает готовую безопасную замену всего backend; зависимости не добавлялись.
