# USB Multitool: состояние и проверка

Этот файл — временные факты, результаты проверок и незавершённые работы. Требования: [консолидированный контракт](2026-11-14-consolidated-requirements.md); архитектурные инварианты: [план архитектуры](2026-11-12-architecture-refactor-plan.md) и [модель USB](2026-11-14-usb-interaction-state-model.md). Исторические сообщения и исходные пользовательские тексты сохранены в [оригиналах](2026-11-14-user-notes-originals.md).

## Текущий срез — 2026-11-14

- Последний подтверждённый полный CI: запуск [43590020532](https://github.com/SklUpp/usb-multitool/actions/runs/43590020532) для `b42154c` (ранее [43545606806](https://github.com/SklUpp/usb-multitool/actions/runs/43545606806) для `06f59ce`). Текущее рабочее дерево `feature/android-usb-multitool` содержит незакоммиченные изменения и полным CI на текущем срезе ещё не проверено.
- Статус изменений строго разделяется на три уровня: **реализовано в коде** (source implemented), **независимо отрецензировано** (independently reviewed) и **проверено** (verified).

### Разделение статуса компонентов

1. **Файловые операции и SAF (Files/SAF)**:
   - *Реализовано в коде*: мультивыбор (multi-select), long-press выбор файлов, пакетное скачивание и удаление, постраничные запросы per-conflict prompt (с блокировкой перезаписи непустых чужих каталогов): `FilesScreen.kt`, `FilesOperations.kt`. В `SafBackupFolder.kt` устранено рекурсивное удаление отвергнутых URI и SAF-архивов; при ошибках возвращаются пути сохранённых документов; no-op ownership abstraction удалена.
   - *Независимо отрецензировано*: `MergeDiskAccess` (18 файлов, 0 конфликтов, clean post-merge review); параметры `device` конструкторов EXT/NTFS и тестовые фикстуры проверены. Статическое ревью `FixSafOwnership` пройдено (подтверждено прекращение удаления отвергнутых документов; зафиксированы риски невалидных провайдеров, не гарантирующих свежесть URI при `queryDocument`, и конкурентных писателей в архивные директории).
   - *Проверено*: scoped unit-тесты `FilesVolumeSessionTest` прошли локально (`BUILD SUCCESSFUL`, exit 0). `SafBackupFolderOwnershipTest` и `SafAvailableBytesTest` **только скомпилированы** (`:app:compileDebugUnitTestKotlin` exit 0); выполнение тестов SAF и полный прогон отложены до CI из-за ограничений Robolectric/native Conscrypt на Termux/Bionic.

2. **Нативные файловые системы (EXT/NTFS host evidence)**:
   - *Реализовано в коде*: tracked O_EXCL патч для `lwext4` (`scripts/patches/lwext4-o-excl-existing.patch`) с восстановленным upstream guard; нативный host-раннер `app/src/main/cpp/host/ext_host_test.c` и `ntfs_host_test.c`.
   - *Независимо отрецензировано*: оба патча и host-тесты независимо проверены.
   - *Проверено*:
     - **Чистый EXT host**: скомпилирован и выполнен на чистой изолированной временной копии pinned upstream `lwext4` (commit `58bcf89`) с обоими tracked-патчами; подтверждено корректное создание с O_EXCL и сохранение существующего файла, exit 0.
     - **Чистый NTFS host**: выполнен на чистой временной копии pinned upstream `ntfs-3g` с tracked `mkntfs` патчем и временным (temp-only) изменением жёстко заданного пути `/tmp` на `$PREFIX/tmp` Termux в тестовой копии (не отдельный tracked-патч); успешно пройдены все 46 проверок, exit 0.

3. **Сценарии Windows XML и Ventoy JSON**:
   - *Реализовано в коде*: полноэкранный редактор autounattend XML, кодек UTF-8/UTF-16 с сохранением BOM (`XmlText.kt`), экранирование путей (`XmlPath.kt`), генератор и импорт ответов, создание резервной копии перед заменой XML/JSON (`AutounattendSection.kt`, `VentoySetup.kt`, `VentoySettingsDocument.kt`).
   - *Независимо отрецензировано*: post-merge ревью в рамках `MergeDiskAccess`.
   - *Проверено*: scoped JVM unit-тесты `XmlPathTest`, `XmlTextTest` и `AutounattendRawWriteUiTest` прошли, exit 0. Сквозное сохранение произвольных структур XML/JSON и резервное копирование при множественных коллизиях требуют проверки в CI.

4. **Диагностика и сброс параметров (Diagnostics)**:
   - *Реализовано в коде*: кнопки сброса параметров (`PerformanceSettingsSection.kt`, единственное место — экран «Настройки»; в «Обслуживании» дубль удалён 2026-11-18) для трёх настраиваемых значений (SCSI chunk size, JNI cap, Rescue checkpoint interval) со значком ↻ и восстановлением дефолтов `TransferTuning.DEFAULT_*`. Выбор и отображение общей backup-папки (`DiagnosticsScreen.kt`, `MainActivity.kt`).
   - *Независимо отрецензировано*: аудит `AuditDiagnostics` подтвердил реализацию сброса всех настраиваемых параметров; требование выбора backup-папки в исходном `New file (1).txt` отсутствует.
   - *Проверено*: тесты UI редуктора backup-папки пройдены; адресные тесты сброса производительности отсутствуют (пробел тестового покрытия при наличии кода).

5. **Разделы и Raw запись (Partitions/Raw)**:
   - *Реализовано в коде*: выбор свободной области на карте, вычисление конечного диапазона из размера источника без ручного ввода конечного LBA (`PartitionsScreen.kt`). Зонд использования ФС (`FilesystemUsageProbe.kt`, `FilesystemUsage.kt`).
   - *Независимо отрецензировано*: post-merge ревью без замечаний.
   - *Проверено*: scoped `FilesystemUsageTest`, `FilesystemUsageUiTest`, `FilesScreenModelTest` прошли, exit 0.

### UI-срез аудита (HEAD `cce0d6d`, 2026-11-18)

- *Реализовано в коде*: у пикера Files постоянная шапка с названием задачи и X (отмена по-прежнему проходит через reducer и его guard для running/confirmation); путь пикера — одна моноширинная строка со `StartEllipsis`; удаление нескольких файлов подтверждается плюральной строкой; путь/up в браузере — 48 dp и кликабельный узел с ролью кнопки; длинное «расширение» ограничено и получает `Ellipsis`; в «Разметке» заголовок больше не сжимается trailing-контролами (они в body), `blockedHint` отображается, строки/полоса объединены `selectableGroup`, выбранный элемент выделен заливкой без дополнительной рамки; карточка копирования в пикере и статус операции показывают процент, скорость и ETA одновременно, если измерены; подсказка Switch переносится полностью; поле XML-редактора имеет доступное имя; валидация Ventoy JSON локализована через типизированные `VentoyControlCatalog.Issue` с сохранением ключа/лимита/значения.
- *Тесты*: добавлен `VentoyControlIssueTest`; расширен `ShellPanelBehaviorTest` (видимость пикера при пропаже носителя, состав строки прогресса). Локально прошли `:app:compileDebugKotlin`, `FilesPickUiTest`, `ShellPanelBehaviorTest`, `VentoyReleaseUiTest`, `VentoyControlIssueTest` (батч зелёный).
- *Независимо отрецензировано*: ревью диффа без блокеров; единственное замечание (a11y-имя кнопок EFI/bootable скрывало состояние) исправлено через `contentDescription` + `stateDescription`.
- *Не проверено*: TalkBack, физический USB и полный CI; список разделов по-прежнему показывает размер и LBA (требование №7), сырой выбор источника остаётся двумя кнопками.
- *Сверка `ui2-acceptance.md` по исходникам*: disabled-токены приведены к `onSurface` 0.12/0.38 (плитки «Обслуживания», upload FAB, значения строк раздела), у раздела убрано двойное указание выбора (остаётся заливка `secondaryContainer`), `Badge` ограничен 160 dp с `Ellipsis`, `animateContentSize` добавлен строкам редактора ventoy.json и динамическим баннерам, очистка журнала использует официальный `delete_sweep`. Намеренные отличия: eject в меню «Ещё» (пункт 18), свёрнутый статус без скорости, header журнала — собственный Row с 48 dp и `heading` вместо `SmallTopAppBar`.

### Критический UI-срез по новому ТЗ (HEAD `995dd3b`, 2026-11-19)

- *Реализовано в коде*: выбор действия MBR и выбор файловой системы — `ModalBottomSheet` с полноширинными `ListItem` (кнопки «Отмена» нет, свайп/фон закрывают; все guard'ы `repairAvailable`/`changeBlocked`, `matches(latestState)` и порядок intent'ов сохранены); 4-я вкладка — «Сервис»/«Service»; удалён `StatusLogPanel` (плавающая пилюля и нижняя плашка), статус идёт через `Snackbar` с `statusSequence`, а компактная строка операции в `bottomBar` показывает название, процент · скорость · ETA, причину завершения и вердикт readback (не «тикает» по snackbar на каждом 120-мс обновлении, перенос статуса не поднимает sequence); нижний отступ 16 dp у всех скроллов.
- *Тесты*: `ShellPanelBehaviorTest` (повторный `Status` даёт новую эмиссию, очистка не меняет sequence, тики Running не бампают), `ShellLogBehaviorTest`, `ConfirmationTest`, `FilesListFreshnessTest`, `OperationExecutorTest`, `DiskOperationThroughputTest` — зелёные локально. Полный локальный прогон: 1672 passed, 84 `UnsatisfiedLinkError` — известное ограничение Robolectric на Termux/Bionic, покрывается CI.
- *Независимо отрецензировано*: два круга; P1 (snackbar на каждом тике прогресса) и P3 (перенос sequence при detach) исправлены, финальный вердикт — correct.
- *Не проверено*: TalkBack, рендер, физический USB; кастомный header журнала вместо `SmallTopAppBar`.

### Полировка UI, вторая волна (HEAD `651ae8e`, 2026-11-19)

- *Реализовано в коде*: диалоги подтверждения получили заголовок по операции (ремонт GPT, формат, стирание, смена типа, восстановление GPT), контейнер чекбокса `surfaceContainerHigh` 12 dp с `toggleable(Role.Checkbox)`, деструктивные подтверждения — `errorContainer`/`onErrorContainer`, ремонт GPT — `primary`; устройство показывается как `Метка (3.73 GiB)`, серийный номер отдельной моноширинной строкой (сопоставление по-прежнему по `identity`). Шторки справки рендерятся блоками с отступом 8 dp и `**жирными**` терминами; `partitions_info_help_text` переписан в четыре блока без изменения фактов. «Сервис»: подзаголовок `SCSI, GPT, ФС`, декоративная иконка запуска на обеих карточках, один `Info` в заголовке резервных копий, `FilledTonalButton` для выбора папки, однострочные `Создать/Восстановить/Сохранить/Восстановить`. «Разделы»: короткие значения состояния записи, свободное место `3.70 / 3.70 GiB` моноширинно, подписи под узкими колонками карты секторов убраны (легенда осталась), бейджи записи нейтральные. Ventoy: `Переустановить` при совпадении версий и `Выбрать XML…`. Files: при единственном разделе селектор без стрелки и не кликабелен. Тактильный `LongPress` — один раз на терминальный токен операции.
- *Тесты*: `DeviceIdentityTest` (displayName и пара ёмкостей), плюс зелёные `ConfirmationTest`, `StringResourceParityTest`, `ShellPanelBehaviorTest`, `ShellLogBehaviorTest`, `FilesPickUiTest`, `PartitionRenameUiTest`, `PartitionRepairUiTest`, `SchemeInitUiTest`, `VentoyReleaseUiTest`, `VentoySettingsUiTest`, `AutounattendRawWriteUiTest`, `AutounattendReloadUiTest`.
- *Независимо отрецензировано*: найдена нечитаемая пара `onError` на `errorContainer` (≈1.3:1), исправлена на `onErrorContainer`; остальное подтверждено.
- *Не проверено*: TalkBack, рендер, крупный шрифт, физический USB.

### Открытые неизвестные (Outstanding Unknowns)

1. **Причина первого отказа измерения SAF raw-образа (`RAW-01`, `RAW-03–RAW-07`)**: UI блокирует запись при нулевом/недоступном размере, но коренная причина отказа системного провайдера SAF при первом измерении источника с телефона не выяснена.
2. **Фактическое состояние RO/RW смонтированных томов (`FILE-06`, `FILE-11`)**: в UI отображаются флаги драйвера и политики, но нет подтверждения на реальном оборудовании, что поддерживаемые ФС переходят в честный `ReadWrite`, а не остаются в fallback `"—"`.
3. **Отказ измерения свободного места Files (`FILE-03`, `FILE-12`)**: fallback «недоступно» реализован, но причина отказа зонда на реальных смонтированных файловых системах устройства не подтверждена исправленной.
4. **Обязательность первого запроса backup-папки (`DGN-04`)**: в `New file (1).txt` требование отсутствует; статус обязательности первого запроса при старте остаётся открытым проектным решением.
5. **Гарантии свежести SAF и гонки в каталогах**: `queryDocument` не удостоверяет отсутствие подмены ID невалидным провайдером; рекурсивное удаление остановлено, но гонка конкурентного писателя в созданный архив остаётся открытым граничным риском.
6. **Физические сбои и системный lifecycle**: поведение при физическом извлечении USB во время IN/OUT/sync, смерть процесса во время операций, загрузка Ventoy, установка Windows и TalkBack остаются непроверенными на оборудовании.

### Архитектурный переход и кэш SAF

- **Порядок декомпозиции**: декомпозиция архитектуры согласована строго **после** успешного прохождения полного CI текущим деревом.
- **Единый владелец USB**: декомпозиция выполняется по проверенному плану из 7 последовательных срезов с сохранением строго **одного общепроцессного владения USB** (`UsbExclusiveClaim`) и единого `FilesVolumeSession` замка/epoch fence. Запрещено создание параллельных очередей или второго владельца USB.
- **Одобренный кэш SAF**: пользователь утвердил стратегию кэширования SAF (полная запись во внутреннее app-private временное хранилище с последующей передачей в целевой SAF-документ; при ошибке передачи после мутации — честный `Partial`). Реализация кэша строго зафиксирована **после** завершения архитектурной декомпозиции; никакой код кэша не должен предшествовать границе декомпозиции.

### Decomposition closure review — HEAD `f85e5e7`

Independent review compared reviewer acceptance obligations with actual tests. Missing deterministic coverage was limited to:

- body failure plus volume-close failure through `FilesOperationRunner` and `OperationExecutor`: `app/src/test/kotlin/usb/files/FilesOperationRunnerTest.kt:68-115`; primary body error is preserved as the wrapped cause, close error is suppressed and exposed, next mount is refused by the close latch;
- append-only close diagnostic after a newer operation begins: `app/src/test/kotlin/usb/ui/OperationTransitionsTest.kt:89-105`; diagnostic changes only `logLines` and leaves current operation/status/effects unchanged. Old terminal completion/reload behavior is already covered by `FilesListFreshnessTest.kt:876-923` and token fencing by `OperationTransitionsTest.kt:49-87`.

Existing close/terminal obligations already covered: `UsbExclusiveClaimTest.kt:98-131`; `FilesVolumeSessionTest.kt:53-67,101-390,410-631,699-778,839-878,1001-1188,1296-1404,1458-1579`; `FilesOperationPolicyTest.kt:98-163`; `OperationExecutorTest.kt:90-249,528-695,821-1129`; `OperationTransitionsTest.kt:22-105`; `FilesListFreshnessTest.kt:876-923`. No duplicate close test or SAF-cache test added.

Residual coupling is intentional: `MainActivity.kt:5897-6019` composes runner/executor/close diagnostics/terminal callbacks; `MainActivity.kt:679-783` owns operation-generation fencing and effect dispatch. `FilesVolumeSession.kt:36-67` remains sole mount/claim/latch owner; `OperationExecutor.kt:297-405` remains sole queue/terminal classifier. Further extraction would add proxy layers, not remove ownership coupling.

Exact CI decision: CI **43641196718**, SHA **`f85e5e7`**, green (per review input). Workflow contract is `.github/workflows/android-debug.yml:21-110`: FatFs, locked Rust, exFAT/NTFS/EXT/USB-block hosts, unit tests, debug APK, 16 KB alignment, lint, Kotlin compile. Current assignment ran no build/test. Physical USB/SAF checks remain unperformed. Third-party/data preserved. SAF cache remains deferred until decomposition closure.

## Требования в работе

| Область | Реализовано в коде | Независимо проверено | Доказательства и пробелы верификации |
|---|---|---|---|
| Files/SAF | Мультивыбор, long-press, batch download/delete, per-conflict prompt (`FilesScreen.kt`, `FilesOperations.kt`). Отказ от удаления отвергнутых URI (`SafBackupFolder.kt`). | `MergeDiskAccess` post-merge ревью clean; статический анализ `FixSafOwnership` одобрен. | Scoped `FilesVolumeSessionTest` exit 0. SAF-тесты **только скомпилированы** (`:app:compileDebugUnitTestKotlin` exit 0), выполнение ждёт CI. Полный CI на live tree не пройден. |
| UI/status | Shell и operation progress mapper; разделение прогресса и отмены. | Post-merge ревью без замечаний. | Scoped unit-тесты mapper пройдены. Проверка completion effects для всех token/lifecycle сценариев на устройстве не проводилась. |
| Ventoy/XML/JSON | Raw XML editor, UTF-8/UTF-16 BOM codec, JSON merge с сохранением секций, backup перед записью. | Post-merge ревью без замечаний. | Scoped JVM-тесты кодека и путей exit 0. Сквозное сохранение форматирования JSON и коллизии повторных backup не подтверждены. |
| Partitions/raw | Свободные области, размер источника определяет конец raw-записи, зонд использования ФС. | Post-merge ревью без замечаний. | Scoped тесты usage exit 0. Причина первого отказа raw SAF measurement не установлена. |
| Diagnostics | Кнопки ↻ сброса 3 изменяемых параметров; выбор общей папки резервных копий. | Аудит требований: сброс параметров закрыт; backup folder отсутствует в оригинале. | Тесты редуктора backup folder есть. Тесты персистентности сброса параметров производительности отсутствуют. |
| Состояние/ownership | Единый `UsbExclusiveClaim`, `FilesVolumeSession` session lock и epoch-fence. | План декомпозиции (7 срезов) одобрен без дублирования владельцев. | Нативный EXT/NTFS host evidence exit 0 на чистых pinned upstream. Декомпозиция и кэш SAF выполняются после зелёного CI. |
## История интегрированных срезов

Ниже — исторические claims и проверки на конкретных ревизиях, не доказательство текущего HEAD.

| Commit | Срез и доказательство на тот момент |
|---|---|
| `aabd137` | Files picker transitions; host UI JUnit 354, host check и CI unit/APK/lint/Kotlin/16 KB прошли. |
| `787ae35` | Inspection ticket ownership; 9 scoped host-тестов и [CI 43105775045](https://github.com/SklUpp/usb-multitool/actions/runs/43105775045) прошли. |
| `cbb266f` | ExtCloseGuard, MBR geometry, gzip size, bulk reset, FAT teardown, app version и Robolectric; записаны 11/11 нативных host-тестов. |
| `5a83a00` | Files path policy: проверка имён и allowlist ошибок. |
| `8893e1d` | FAT bounds и защита arithmetic. |
| `9ddf0de`, `f53c303` | Выделены 36 reducer transitions; историческое ревью зафиксировало сравнение 108 ветвей. |
| `e8186df` | JNI I/O limits, EXT writable и NTFS sync; удалены старые мосты. |
| `e84fc21` | Process-wide USB claim, атомарный attachment snapshot и backup picker pin. |
| `af7ee39` | Redacted trace log и SAF export. |
| `adf0f5f` | Ventoy sync перед полным readback Part2. |
| `f53c303` | App-private Ventoy storage, caps, предупреждения, скрытие пароля, устранение дублей sync. |
| `cea754b` | SHA-256 и pinned commit в загрузке third-party. |
| `8a03e6f`, `e829fb5`, `d239975` | Интеграция shell status, повторного Raw measurement и сохранения XML по прежним записям. |
| `1b0b9f4` | Ventoy release, partition UI, global settings, Windows picker, JNI snapshot bounds и token/status по прежним записям; тогда runtime не проверялся. |
| `035b6f9` | USB BOT/sense recovery и документы; CI 43514783120 остановился на компиляции. |
| `4f19d47` | Восстановлены ресурсы и скобка `VentoyLayout`, консолидированы оригиналы; CI 43519995761 дошёл до unit tests и обнаружил 7 ошибок. |
| `b42154c` | Зафиксированы параметры `device` и фикстуры; полный [CI 43590020532](https://github.com/SklUpp/usb-multitool/actions/runs/43590020532) прошёл. Последующие изменения live tree им не проверены. |

Исторический [CI 43502683843](https://github.com/SklUpp/usb-multitool/actions/runs/43502683843) для `e057bf7`: 40 из 1436 unit-тестов упали, 8 skipped. Незавершённые worktree Files из оригинала не интегрированы; нельзя считать статическое ревью доказательством поведения.

## Следующие проверки

1. Запустить и подтвердить полный CI текущего дерева (включая компиляцию Kotlin, unit-тесты, SAF тесты под Robolectric, lint и 16 KB alignment).
2. Сверить все требования с `New file (1).txt` и зафиксировать evidence на текущем срезе.
3. После успешного CI приступить к последовательной декомпозиции архитектуры (срезы 1–7) с сохранением строго одного процесса-владельца USB (`UsbExclusiveClaim`).
4. Реализовать одобренный двухфазный кэш SAF строго после завершения архитектурной декомпозиции.
5. Провести проверку UI на реальном Android-устройстве в обеих темах и физические сценарии USB/SAF.

## Срез 2026-11-19 — `c7c5f03`

Независимое ревью (две волны, три круга правок, итоговый вердикт `correct`) и [CI 44527671005](https://github.com/SklUpp/usb-multitool/actions/runs/44527671005) — success: verify и fatfs-host. APK: `~/tmp/usb-multitool-apk-44527671005/app-debug-apk/app-debug.apk`, SHA-256 `0d0a7de7d603ab575a41dd16b03aad8471536d1789ec4ebd6869c9cf4c1f79f7`, 54 225 139 байт.

- Файлы: строки появляются сразу (удалён fade-in `animateItem`), переходы каталогов без горизонтального слайда с `SizeTransform(clip = true)`, чип пишет только `RO`/`RW` на одной нейтральной подложке (RW — `primary`, RO — `onSurfaceVariant`; `primaryContainer` и `tertiaryContainer` в палитрах совпадают), тип ФС на базовой линии имени тома, вторая строка — только свободное место, путь одной строкой с целью 48 dp.
- Оболочка: полоса операции над заголовком открывает `ModalBottomSheet` с прогрессом, ETA, вердиктом readback и отменой; журнал накрывает заголовок и вкладки, но не полосу; убран дублирующий верхний инсет `TopAppBar`.
- Разделы: полоска-карта больше не свайпается; под ней слайдер прокрутки (48 dp цель, 24 dp видимая полоса), панорама через `graphicsLayer` в узле, который сообщает ширину вьюпорта, иначе фон карточки вылезал за отступ; маркер удерживается в кадре, привязка старта к началу и концу любого раздела — 16 dp.
- Иконки: `Glyph.File/Iso/Copy/Refresh/Stop/More/Play` рендерятся дуотон-векторами (`ic_file`, `ic_file_iso`, `ic_copy`, `ic_refresh`, `ic_stop`, `ic_more`, `ic_play`), остальные остались ручными Canvas-путями.
- Отличия и ограничения: полоса операции остаётся тонкой (~32 dp) без `minimumInteractiveComponentSize` — приоритет требования «тонкой полоской», цель нажатия меньше 48 dp; при fontScale 2.0 полоса растёт по высоте, разделитель виден. Пара системных уведомлений «накопитель извлечён/подключён» при force-claim остаётся системной и не подавляется; наше приложение показывает не больше одного уведомления (`usb.disk`, id 42) и только для записей. Жесты карты, слайдер, дуотон в тёмной теме, TalkBack и крупный шрифт на устройстве не проверены.

## Срез 2026-11-20 — `eba7224`

Независимое ревью волны 3 (четыре findings, повторный вердикт `correct`) и CI [44594054632](https://github.com/SklUpp/usb-multitool/actions/runs/44594054632) — success.
APK: `~/tmp/usb-multitool-apk-44594054632/app-debug-apk/app-debug.apk`, SHA-256 `86aad77d7175c9f6d17d4dbbc2d019cf6379ce65a4be2b903bff55a225e91ad9`, 54 258 951 байт; копия `~/usb-multitool-eba7224-debug.apk`.

- Ventoy: кнопка всегда «Обновить», подтверждение обещает сохранение раздела данных и ISO (неатомарность оговорена), установка осталась единственным деструктивным действием в `errorContainer`; импорт `ventoy.json` с телефона починен — READ-only persistable grant вместо падавшего WRITE-запроса, приём без проверки данных раздела, перепроверка identity/поколения/раздела/хеша при записи.
- Оболочка: постоянная строка статуса под заголовком (видна на всех вкладках и при журнале, «Готово» без операции), детали — центральный диалог, номинативные названия операций, журнал `время · уровень · текст`.
- Файлы: янтарный RO и `primary` RW на общей нейтральной подложке, рядом только `RO`/`RW`; путь, «Назад», «Обновить» и «Создать папку» в одной строке 48 dp; меню `surfaceContainerHigh` с тенью, недоступные пункты приглушаются только текстом; дуотон там, где есть соответствие.
- Разделы: счётчик удалён, «Используется», `занято/максимум` и `сектор X–Y (N)` в строке, слова о RO-политике убраны при сохранённом guard записи EFI; VTOYEFI показывает производные EFI/загрузочный и не переключается; удаление — полноширинная кнопка внизу, подтверждение с именем и размером; путь образа — `OutlinedTextField` с меткой.
- Настройки: слайдеры со значением, справка блоками с tonal-кнопкой, выбор образа XML — главное действие блока.
- Документация: требования разбиты по блокам (`docs/superpowers/specs/2026-11-19-wave3-*.md`), единый индекс `docs/README.md`, решения второго ревью зафиксированы.
- Ограничения: проверки на устройстве (SAF-импорт, TalkBack, fontScale 2.0, тёмная тема, реальная Ventoy-флешка) не выполнены; красный в журнале только для ошибок, успех — `primary` (зелёного токена в палитре нет).

## Срез 2026-11-20 (раунд 3) — `2663398`

Независимое ревью `Round3Review` (четыре круга: два P1/P2 по статусу и карте, два a11y-фикса) — итоговый вердикт `correct`. CI [44607299140](https://github.com/SklUpp/usb-multitool/actions/runs/44607299140) — success (verify, fatfs-host).
APK: `~/tmp/usb-multitool-apk-44607299140/app-debug-apk/app-debug.apk`, SHA-256 `e09d686599075e42f5c4d037058404fee45553dcfaaa6b46f0ecf578a4aa9182`, 54 275 267 байт; копия `~/usb-multitool-2663398-debug.apk`.

- Разделы: строка и закреплённая карточка — `LBA X–Y (N)` без слова «сектор» и без порядкового номера; привязка к подтверждённым началам/концам — 24 dp, ограничение идёт по известному размеру образа (`ceiling = if (sectorCount == null) disk else maxStart`), типизированное поле LBA по-прежнему не ограничивается; у выбранного раздела отступы сверху и по бокам.
- Полоска-карта: причина бага «каждый свайп — привязка с начала и вид у левого края» — `remember(contentPx)` пересоздавал состояние панорамы при переизмерении; теперь состояние стабильно, диапазон панорамы сжимается один раз через `LaunchedEffect(maxPanPx)`, перетаскивание маркера идёт в координатах контента, начальный вид — с начала полоски, маркер в кадре.
- Оболочка: блок статуса — MD3-поверхность (`surfaceContainerLow` в покое, `surfaceContainerHigh` при работе), поля 12 dp по бокам и 10 dp по вертикали, шеврон, `LinearProgressIndicator` только при работе, тап открывает центральный диалог; на «Настройках» и при открытом журнале блок скрыт.
- Snackbar статусов удалён полностью. Одиночные `statusMessage` без операции (ошибки загрузки ссылок Windows, кандидатов ISO, привязок Autounattend, отказы действий) показывает тот же блок нейтральным тоном; для TalkBack — `semantics(mergeDescendants = true)` с `liveRegion = Polite`, иначе анонсируемый узел был без текста (текст лежит на дочерних `Text`).
- Заголовок: снят cap 56 dp, возвращено значение Material 3 по умолчанию 64 dp (56 dp обрезало двухстрочный заголовок при fontScale ≥ 1.4).
- Ограничения: остаточное поджатие двухстрочного заголовка в 64 dp при fontScale 2.0 было и до раунда; проверки на устройстве (жесты карты, TalkBack, крупный шрифт, тёмная тема, реальная Ventoy-флешка) не выполнены. Удаление `~/tmp/usb-multitool-review` сломало git-альтернативы старого рабочего дерева; диф спасён в `~/wave3-round3.patch`, репозиторий переклонирован в `~/usb-multitool-repo`, сломанное дерево сохранено как `~/usb-multitool-repo-broken`.

## Срез 2026-11-20 (волна 4) — `a3f0178`

CI [44619949905](https://github.com/SklUpp/usb-multitool/actions/runs/44619949905) — success (verify, fatfs-host). APK: `~/usb-multitool-a3f0178-debug.apk`, SHA-256 `80e2e81306196e254de412e9d76d60c22f8e97678c2d8d1164c1d8b5efee2965`, 54 292 471 байт.

- Статус: блок всегда кликабелен (шеврон, `Статус: …`), компактный прогресс внутри блока; окно «Статус» открывается и без операции, X убран, снизу «Отмена»/«Закрыть»; технический моно-лог в стиле журнала показывает текущую операцию и состояние устройства, сбрасывается на новый токен, переживает тики и блипы подключения, не перетекает между флешками; прокручивается вместе с фактами при fontScale 2.0.
- «Настройки»: убран двойной верхний инсет собственной панели.
- Окно форматирования: радиокнопки вместо четырёх одинаковых иконок документа.
- Ventoy: после установки/обновления источник настроек перепривязывается к живому разделу и перезагружается; при обновлении таблицы читается путь по умолчанию без пинов; геометрия раздела 1 — ключ перезагрузки каталога. Файл не выдумывается. Опрос логов — 1 с.
- Ограничения: полный локальный `testDebugUnitTest` на Termux падает на `conscrypt` (`libpthread.so.0`), это ограничение платформы; адресные suite и CI — зелёные.
