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
   - *Реализовано в коде*: кнопки сброса параметров (`PerformanceSettingsSection.kt:33-119`, `DiagnosticsScreen.kt:303`) для трёх настраиваемых значений (SCSI chunk size, JNI cap, Rescue checkpoint interval) со значком ↻ и восстановлением дефолтов `TransferTuning.DEFAULT_*`. Выбор и отображение общей backup-папки (`DiagnosticsScreen.kt`, `MainActivity.kt`).
   - *Независимо отрецензировано*: аудит `AuditDiagnostics` подтвердил реализацию сброса всех настраиваемых параметров; требование выбора backup-папки в исходном `New file (1).txt` отсутствует.
   - *Проверено*: тесты UI редуктора backup-папки пройдены; адресные тесты сброса производительности отсутствуют (пробел тестового покрытия при наличии кода).

5. **Разделы и Raw запись (Partitions/Raw)**:
   - *Реализовано в коде*: выбор свободной области на карте, вычисление конечного диапазона из размера источника без ручного ввода конечного LBA (`PartitionsScreen.kt`). Зонд использования ФС (`FilesystemUsageProbe.kt`, `FilesystemUsage.kt`).
   - *Независимо отрецензировано*: post-merge ревью без замечаний.
   - *Проверено*: scoped `FilesystemUsageTest`, `FilesystemUsageUiTest`, `FilesScreenModelTest` прошли, exit 0.

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
