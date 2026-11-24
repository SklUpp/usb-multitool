# USB, разделы и образы

Документ фиксирует транспортные и записывающие контракты. Файловые системы —
в [filesystems.md](filesystems.md).

## USB Bulk-Only Transport

- Принимается только interface class `0x08`, subclass `0x06`, protocol `0x50`
  с bulk IN и bulk OUT (`device/AndroidUsbBulkChannel.kt:25-50`).
- Один `UsbDeviceConnection` на канал; `claimInterface(force=true)` выполняется
  только после процессного `UsbExclusiveClaim`.
- CBW — 31 байт, CSW — 13 байт (`device/BotConstants.kt:7-8`). CDB 1..16 байт;
  направление и длина буфера проверяются до отправки.
- Каждый `bulkTransfer` запрашивает не более 16 KiB — предел USBFS. Меньший
  возврат считается настоящим short packet; IN ZLP (`0`) завершает data phase,
  после чего читается CSW. OUT short packet дочитывается, `0` для OUT — ошибка.
- CSW проверяет signature, tag, status и residue. `PASS` требует доставленных
  байт ровно по запросу и residue 0; иначе ответ считается недостоверным и
  сессия закрывается.
- Stalls: при I/O ошибке Bulk-IN сначала `clearHaltIn`, затем читается CSW;
  reset+retry остаётся крайней мерой, второй сбой закрывает сессию; OUT I/O
  ошибка не повторяется. PHASE ERROR и недостоверный CSW → reset и close.
- READ/WRITE(10) используется при 32-битных LBA/count, иначе READ/WRITE(16).
  SCSI-план режет передачу на выровненные чанки (по умолчанию 64 KiB,
  настраиваемо 16..512 KiB).
- `SYNCHRONIZE CACHE(10)` обязателен для подтверждённой записи; timeout 30 с.
- Capacity: READ CAPACITY(10), при насыщении/ошибке — READ CAPACITY(16).
  Сектор 512..4096, степень двойки.

## Владение и fencing

- `UsbExclusiveClaim` — один lease на процесс; acquire неблокирующий,
  конкурирующий владелец получает `BusyException`.
- `AttachmentState` публикует device+generation атомарно. `AttachmentPin`
  проверяется до claim, после открытия и после чтения таблицы.
- `FilesVolumeSession` держит один `MountedFilesVolume`; epoch выделяется до
  открытия, поэтому stale close не закроет новый mount.
- Close fail-closed: ошибка close/sync оставляет lease и запрещает remount;
  UI получает `Partial`/`VolumeSyncUnconfirmed`.
- Eject: volume sync → volume close → disk sync → disk close → подтверждение
  lease; первая ошибка основная, остальные suppressed.

## MBR

- LBA 0, 512 байт, signature `0x55AA`; типы и слоты P1..P4 сохраняются.
- Protective `0xEE` — признак GPT. Запись запрещена для hybrid/ambiguous.
- Пересечения и выход за диск отвергаются. Extended-разделы не создаются.
- `commitMbr` сохраняет boot code `0..445`, перестраивает таблицу `446..509`,
  CHS = `FE FF FF`, LBA 32-битный. После записи выполняется re-read.
- Таблица до 4 primary; свободные слоты берутся первыми.

## GPT

- Header: `EFI PART`, CRC с обнулённым полем, размер 92..sector, current LBA
  совпадает с прочитанным, entry size ≥128, entry array ≤1 MiB.
- Primary и backup проверяются независимо; arrays не пересекаются с headers,
  LBA 0/1 и usable range. Backup header обязан указывать на primary LBA 1.
- Если primary повреждён, backup даёт read-only таблицу
  (`gptPrimaryDamaged`); writer/init/relocate её не перезаписывают.
- `PartitionWriter.validateGptGeometry` проверяет:
  - headers внутри диска и различны;
  - usable range не включает headers;
  - primary/backup arrays не пересекаются с headers, LBA 0, друг другом,
    usable range;
  - записанный `gptBackupEntriesLba` существует и помещается в диск.
- `commitGpt` порядок:
  1. backup entry array по **записанному** `table.gptBackupEntriesLba`;
  2. backup header;
  3. sync;
  4. primary entry array;
  5. primary header.
  Затем `disk.sync()` и сравнение обеих копий.
- Entries сохраняют слоты, unique GUID только при том же слоте/геометрии,
  attributes все биты; имя ≤36 UTF-16 units.
- Backup array нельзя предсказывать как `backupLba - entrySectors`: layout
  может быть смещён. Null/неизвестное место — отказ.
- `TableInitializer` для пустого диска пишет protective MBR с `StartingCHS=00 02 00`,
  `EndingCHS=FF FF FF`, затем backup entries, backup header, protective MBR,
  primary entries и primary header; `commitMbr` для обычных записей использует
  `FE FF FF`. Каждая фаза сопровождается sync и re-read.

## Backup/relocation

- `GptBackup` считает spans из entryCount×entrySize, а не из фиксированных 33/34
  секторов.
- Critical backup требует primary entries в LBA 2, backup header в последнем
  секторе, backup entries непосредственно перед ним; иначе backup отклоняется.
- Relocate пишет новый backup entries/header, затем primary header, sync и
  re-read; отказывается при занятых разделах за новым last usable и при
  расхождении копий.

## Выравнивание и свободное место

- Единица выравнивания — 1 МиБ; в секторах = 1 MiB / sectorSize.
- Свободный регион начинается после last usable metadata и заканчивается до
  backup metadata; create без start выравнивает вверх, с start требует
  выравнивания.
- Создание — целые МиБ. Хвост меньше 1 MiB остаётся свободным; регион меньше
  1 MiB не предлагается для создания.
- GPT free space исключает primary/secondary metadata, чтобы commit не rejected
  созданную запись.

## Raw images

- Admission в `MeasurementHandle` ограничивает число одновременных provider
  streams и close-операций; исчерпание — ошибка до открытия.
- Размер:
  - raw с известным decoded size может не измеряться;
  - gzip/xz/неизвестный размер декодируется один раз с limit = ёмкость
    диапазона, SHA-256 считается на лету;
  - SHA mismatch до записи — `SourceChangedException`, диск не тронут;
  - пусто/больше диапазона — отказ до записи.
- Запись идёт чанками 64 KiB, не читает за конец диапазона, последний неполный
  сектор дополняется нулями только внутри диапазона.
- Перед первой записью ставится `mutationAttempted`.
- После любой попытки записи: `sync`, повторное открытие source и побайтовый
  readback (с учётом zero pad). Итог: `Verified`, `Mismatch`, `Incomplete` или
  `Unverified`. Failed write request никогда не `Verified`.
- Отмена после commit → `Partial` + sync + prefix readback. Ошибка закрытия
  source понижает результат до `Partial`.
- `RawSourceChooser` ограничивает entries/depth (по умолчанию 1000/4), сортирует
  директории раньше файлов, помечает `truncated` при срезе, не скрывает
  доступных соседей после depth-пропуска.

## Restore

- Источник выбирается явно: SAF tree с ровно одним `.img` и
  `<image-stem>.map[.N]`, либо один GPT critical blob.
- Map без sector size отклоняется; compatibility с legacy 512-байтными картами
  не угадывается.
- GPT/map inspection содержит SHA-256 и повторно проверяется перед записью.
- Для rescue-образа фиксируются SAF size и last-modified; если провайдер меняет
  документ после подтверждения, restore отказывается.
- Частичный restore публикует checkpoint в выбранную SAF-папку рядом с образом
  (`<stem>.map.<generation>`, до двух поколений) и обновляет map в UI для
  продолжения. Без стабильного serial прогресс сбрасывается.
- Успешный restore очищает выбор; app-private checkpoint используется только как
  fallback и удаляется после полного успеха.

## SAF

SAF — только документы телефона: backup folder, rescue archive/restore, экспорт
trace, импорт с телефона и экспорт на телефон. USB-накопитель в SAF не виден и
не монтируется через DocumentsProvider.

- URI принимается только из выбранного tree authority; tree-scoped URI
  пересобирается, чужие authority отвергаются.
- Создание проверяет authority/ID/queryable и пустую форму; это не доказывает
  эксклюзивное владение.
- Free space возвращается только при единственном root, совпадающем с tree
  document ID; иначе `null`.
- Sequential output — `"w"` + write/flush/fsync, без seek/truncate.
- Positional image — `"rw"`, regular file, lseek/pread/pwrite probe,
  ftruncate(0)+fsync; иначе конструктор падает.
- Checkpoint: create-new → write+fsync → readback → удаление старого поколения;
  cadence rescue 256 MiB.
- SAF staging cancellation закрывает source через bounded `MeasurementHandle`,
  не блокируя cancellation thread; listener снимается в `finally`, temp
  удаляется при ошибке.
- App-private staging: `raw-*` старше 12 часов, не принадлежащие текущей
  сессии, удаляются при старте и перед проверкой свободного места; свежие и
  session-owned файлы сохраняются.
