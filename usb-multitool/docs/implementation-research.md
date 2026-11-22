# Параллельный ресерч внедрения кандидатов

Дата: 2026-11-22. База: `836052f`. Статус: **только исследование и план**.
Код кандидатов не реализован. Требования —
[requirements/2026-11-22-docs-review-candidates.md](requirements/2026-11-22-docs-review-candidates.md).

Метод: пять независимых read-only lanes (SMART, f3probe, persistence,
recovery, TRIM), отдельная проверка стандартов и текущего кода. Все оценки
ниже — estimate, не измеренный diff. On-device проверки не выполнялись.

## Итог

В этой волне код пишется **только по SMART**. Остальные направления ниже
задокументированы и отложены; это не разрешение запускать их lanes.

| Кандидат | Статус | Почему |
|---|---|---|
| SMART через SAT | **В работе, единственный code lane** | read-only, минимальный риск, встраивается в существующий `UsbDisk`; зависит от моста |
| Восстановление по VBR | **Отложено** | read-only scan ценен; запись таблицы — отдельный destructive-проект |
| Ventoy persistence A | **Отложено** | file-backed ext4 adapter + `ventoy.json`; выбран вариант A, реализация позже |
| f3probe-подобный тест | **Снят** | публичного OS-unmount proof нет; destructive |
| SCSI UNMAP/TRIM | **No-go** | нет authoritative free-extent owner; probe — отдельно |
| Safe capacity preflight | **Отложено** | отдельная read-only функция, не f3 |
| NTFS/ext write session | **Отложено** | сначала benchmark + chunked host tests |

## Что уточнила проверка стандартов

- SAT `0x85` подтверждён: IDENTIFY `0xEC` и SMART READ DATA `0xB0/0xD0`
  передаются через 16-байтный CDB; успешный ATA-ответ с `CK_COND=1` может
  приходить как CHECK CONDITION с ATA Return descriptor `0x09`.
- SMART-атрибуты: 30 слотов по 12 байт с offset 2; `0x05` — reallocated
  sectors, temperature/wear — vendor-specific. Нельзя выводить «здоровье» из
  одного ID.
- `LBPME` в READ CAPACITY(16) — маска `0x40` в byte 14, но это только hint.
  UNMAP дополнительно требует VPD Block Limits `0xB0` и LBP VPD `0xB2`;
  granularity/alignment — device-reported, не фиксированные 4 КиБ.
- Ventoy `persistence` schema: `persistence: [{image, backend}]`; backend может
  быть строкой или массивом. **Фрагментированный persistence backend
  допускается**; contiguous-требование относится к ISO/boot payload.
  Поэтому `f_expand` — оптимизация, а не условие корректности.

## 1. SMART

**Вердикт: conditional go. Первый кандидат.**

Точки интеграции: `ScsiCommand.kt:14-17,62-68`, `UsbDisk.kt:127-210,454-478`,
`BotSessionImpl.kt:21-64,81-148`, `MainActivity.kt:1671-1676,6989-7054`,
`DiagnosticsScreen.kt:73-131`, `UiReducer.kt:661,978,4267`.

План:

1. Добавить только две фиксированные CDB-команды и descriptor-format REQUEST
   SENSE; никакого ATA-кода из UI.
2. `UsbDisk.readSmartTelemetry()`: IDENTIFY → sense при failed CSW → при
   успехе SMART READ DATA; запросить descriptor sense сразу, пока устройство
   не потеряло состояние.
3. Разбирать заголовок sense и descriptor `0x09` с ограничением по фактически
   доставленным байтам; не считать любой failed CSW ATA-ошибкой.
4. Модели `SmartTelemetry`/`SmartResult`; хранить raw-атрибуты, не выдумывать
   temperature/wear/health.
5. Провести через read-only operation path; отображать
   available/unsupported/unavailable/checksum warning; не запускать
   автоматически.
6. Тесты: точные CDB, sense fixtures, SAT/non-SAT fake BOT, short payload,
   cancellation, stale attachment.

Использовать/адаптировать можно `smartmontools` (`scsiata.cpp`, `ata.h`) и
`sg3_utils` (`sg_sat_identify`) — лицензии совместимы с GPL-3.0; фиксировать
ревизию и не тащить весь проект.

Оценка: **16–19 файлов, 500–850 строк** с тестами/локализацией. Риск: мост
может не поддерживать `0x85` или зависнуть на data phase; это не ошибка диска.
Обязательна проверка на SAT и non-SAT железе.

## 2. Восстановление по VBR

**Вердикт: conditional go для read-only scan; запись — отдельный проект.**

Точки: `PartitionTable.kt:65-129,171-228,360-414`,
`GptTable.kt:23-31,58-110,160-220`, `TableInitializer.kt:46-97,102-177`,
`PartitionWriter.kt:25-53,63-145,189-285`, `Diagnostics.kt:24-99`,
`DiagnosticsScreen.kt:113-127`, `MainActivity.kt:925-988`.

План:

1. Сначала проверить primary/backup GPT и внешний critical backup; не
   считать `gptPlan` API восстановления — он создаёт пустую таблицу.
2. Новый `VbrScanner` с bounded/cancellable read-only scan: MBR-подсказки,
   LBA 63/2048, известные выравнивания, затем явно запрошенный deep scan.
   Ограничения по времени/чтениям/кандидатам; при лимите — `incomplete`.
3. `RecoveryCandidate` с offset/size/type/confidence и evidence; проверка
   boot-region checksum/geometry, overlap, nested signatures.
4. `RecoveryPlanner` формирует точный payload и write ranges; GUID/name/type
   не выдумывать. MBR 4 primary; EBR не восстанавливать в первой версии.
5. Scan и write разделены: сначала read-only результат, затем явное
   destructive confirmation с fingerprint всех evidence/target sectors.
6. Тесты: LBA 63/2048/unaligned, exFAT/FAT32/NTFS, 512/4096, primary-only/
   backup-only/conflict/overlap, fault injection.

Оценка: read-only scan **12–16 prod files, 800–1 500 строк**; отдельный
reconstruction path **+500–1 000 строк** и fault fixtures. TestDisk можно
использовать как источник алгоритма/UX, но не как гарантию идентичности
оригинальной GPT.

## 3. Ventoy persistence

**Вердикт: conditional. Средний/большой проект, `f_expand` не обязателен.**

Точки: `ffconf.h:43` (`FF_USE_EXPAND=0`), `ff.c:5752-5844`, `fat_engine.c`,
`fat_jni.cpp:342-405`, `ExtFs.kt:88-94`, `VirtualPartition.kt:10-51`,
`VentoySetup.kt:1035-1057,1175-1224`, `VentoyLayout.kt:1350-1392,1632-1720`.

Ключевое уточнение: Ventoy подключает persistence backend через blocklist и
**допускает фрагментацию**. Continuous extent нужен для выбранной оптимизации,
но не для корректности. `f_expand` не заполняет файл; содержимое до mkfs
не определено, пока не перезаписано.

Два варианта adapter:

- **A. FatFs file I/O**: постоянный `FIL`, map file offsets → clusters,
  random read/write для ext4. Работает с фрагментированным файлом, но требует
  нового lifetime/lock contract и может быть медленным.
- **B. Contiguous extent**: `f_expand(opt=1)`, проверить run и границы, закрыть
  `FIL`, unmount FAT, работать через `VirtualPartition` по partition-relative
  LBAs. Быстрее и проще после проверки, но требует непрерывного свободного
  участка и жёсткого запрета FAT-доступа.

**Решение пользователя (2026-11-22): вариант A**, потому что он поддерживает
фрагментированные файлы и не требует `f_expand`/contiguous extent. `f_expand`
остаётся опциональной оптимизацией; при A `FF_USE_EXPAND` можно не включать.
В обоих случаях инициализировать все байты файла (не раскрывать старые
данные), sync, `ExtFs.mkfs`, readback label/geometry, затем remount FAT и
merge `persistence` JSON.

Оценка: **13–16 prod files, 900–1 500 строк** с тестами/JSON/UI. Нужны
fragment/contiguous fixtures, 4 GiB boundary (FAT32 vs exFAT), crash injection,
реальная Ventoy boot-проверка на выделенном носителе. `ventoy.json`:
`persistence: [{image, backend}]`, сохранять unknown fields и string/array
backend.

## 4. f3probe-подобный детектор

**Вердикт: no-go на non-root Android 28–36.** Отдельный OS-unmount research
показал: публичного API для атомарного unmount USB-тома нет; `claimInterface`
(даже `force=true`) лишь отсоединяет kernel driver и не доказывает, что vold
размонтировал ФС, сбросил page cache и не смонтирует снова. Проверка
`StorageManager`/`StorageVolume` — hint, а не proof; `/proc/mounts` и
`/sys/block` на modern Android недоступны/подвержены TOCTOU.
`IStorageManager.unmount` требует `MOUNT_UNMOUNT_FILESYSTEMS` (system/root).

Точки: `UsbExclusiveClaim.kt:4-24`, `UsbOpenBoundary.kt:122-126,166-200`,
`AndroidUsbBulkChannel.kt:190` (`claimInterface(..., true)`),
`DiskOperation.kt:6-36`, `OperationExecutor.kt`, `DiagnosticsScreen.kt`,
`AppShell.kt` confirmation.

План, если появится доказуемый system-eject/ownership:

1. Diagnostics-only, manual, typed confirmation + acknowledgement; pin
   attachment generation, identity, capacity, sector size/count, planned LBAs.
2. **До первой записи** доказать, что весь диск не смонтирован ОС и не занят
   другим процессом. `UsbExclusiveClaim` — только app-process exclusion;
   `claimInterface(force=true)` не является доказательством unmount. Если
   proof нет — fail closed.
3. Записать durable `write-may-have-started` record до первой записи; при
   crash/reboot показывать возможное повреждение, не автоматически
   восстанавливать/повторять.
4. Sampling: sector 0, last, powers of two/end-relative offset; marker с nonce,
   LBA, checksum. Писать по одному разу, sync, затем читать все; повторяемое
   cross-LBA evidence — `suspected`, иначе `unverified`. Не оценивать реальную
   ёмкость и не писать «good drive».
5. f3 tree уже есть в `third_party`, но `f3probe` bridge не собран; можно
   адаптировать идею/код f3 (GPL), но не выдавать sampling за f3probe.
6. Тесты: host fake-device с aliasing, dropped/torn writes, sync failure,
   crash states, UI gates; физический стенд — только disposable.

Оценка: **9–12 prod files + 3–5 test files, ~700–1 100 строк**, плюс
неизвестный объём platform-specific unmount/ownership. Это главный блокер.

## 5. SCSI UNMAP/TRIM

**Вердикт: no-go для free-space TRIM сейчас; только read-only probe.**

- `FilesystemSpace` даёт только total/free bytes, не extent map:
  `FilesystemUsage.kt:5-23,45-66`; FAT `f_getfree`, NTFS bitmap cache, ext
  counters — не authoritative free extents.
- Whole-partition UNMAP уничтожит данные и метаданные; это не TRIM.
- Для безопасного free-space нужен FS-specific allocation freeze,
  enumeration и revalidation; текущего owner нет.
- Capability probe: RC16 LBPME (mask `0x40`), VPD `0xB0`/`0xB2`, limits and
  granularity; malformed/unknown = not eligible. UNMAP CDB `0x42` — 10-byte
  OUT, parameter list: header 22/16, one descriptor 24 bytes; granularity от
  устройства, не 4 КиБ.
- Vendor hangs JMS567/JMS578 — unverified anecdotes; один descriptor не
  доказывает безопасность.
- Destructive path: 15–25 files / 1 000–2 000+ LOC на первую ФС, отдельный
  bench; не входит в ближайшую волну.

## Переиспользование сетевого кода

Проект — GPL-3.0-or-later; допустимы совместимые GPL-2.0+/GPL-3.0
источники. Предпочтительны небольшие адаптеры:

- SMART: smartmontools `scsiata.cpp`/`ata.h`, sg3_utils SAT helpers.
- Fake capacity: `third_party/f3` (уже скачан, но bridge не собран).
- Recovery: TestDisk scan/UX ideas; код только после проверки лицензии и
  адаптации под Kotlin/BlockDevice.
- Persistence: Ventoy `CreatePersistentImg.sh`/plugin schema; FatFs `f_expand`.
- TRIM: sg3_utils CDB/list builders; только для probe.

Фиксировать URL/commit, SHA, license notices; не делать runtime-fetch.

## Рекомендуемый порядок

1. **SMART** — отдельная ветка, read-only, быстрый полезный результат.
2. **Recovery read-only scan** — можно параллельно со SMART; запись таблицы
   не включать без отдельного fault-tested этапа.
3. **Persistence** — после выбора adapter B/A и `ventoy.json` contract.
4. **f3probe** — только после platform proof OS-unmount и стенда; иначе no-go.
5. **TRIM** — no-go, максимум read-only capability diagnostic.

## Открытые решения

- Внедрять SMART сейчас или сначала только read-only scan? Рекомендация:
  SMART первым.
- Для persistence: B (contiguous, проще, отказ при фрагментации) или A
  (фрагментация, сложнее). Рекомендация: B первой, A при продуктовой
  необходимости.
- Для recovery: выпускать read-only scan без записи? Рекомендация: да,
  reconstruction — отдельный релиз.
- f3probe: есть ли на целевых Android-устройствах доказуемый unmount всего
  USB-диска? Без этого фича не включается.
