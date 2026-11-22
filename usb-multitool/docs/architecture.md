# Архитектура

Приложение — один Android-модуль `:app` без DI-контейнера и без отдельного
backend. Вся работа с накопителем идёт через USB BOT/SCSI и собственные
JNI-движки; UI не имеет доступа к устройствам напрямую.

## Слои

| Слой | Пакет | Ответственность |
|---|---|---|
| UI | `usb.ui` | Compose-экраны, `AppUiState`, интенты, чистый reducer, переходы |
| Shell | `usb.multitool` | `MainActivity`, `MainViewModel`, USB attach/permission, probe, эффекты |
| Операции | `usb.operation`, `usb.service` | `DiskOperation`, очередь, токены, прогресс, отмена, foreground service |
| USB | `usb.device` | discovery BOT, bulk-канал, CBW/CSW, SCSI, capacity, claim |
| Файлы | `usb.files` | монтирование тома, staging, list/read/write/delete/rename/copy |
| ФС | `usb.fs` + `cpp` | FAT/exFAT, NTFS, ext4, форматирование, fsck, label |
| Разделы | `usb.partition` | MBR/GPT parse, редактор, запись, backup, выравнивание |
| Образы | `usb.image` | source detect, измерение, staging, raw write + readback |
| Ventoy | `usb.ventoy` | layout, install/update, control keys/autounattend, release |
| Rescue | `usb.diagnostics` | scan/wipe/self-test, SectorForge, backup/restore |
| SAF | `usb.saf` | документы телефона: папки, backup, checkpoint, экспорт |

`AppUiState` — единственный источник UI-истины. Экраны получают `state` и
`onIntent`, не создают сервисы и не читают USB. `MainViewModel` — единственная
точка сборки зависимостей (`MainActivity.kt:196`) и исполнения `UiEffect`.
Reducer (`usb.ui.UiReducer.reduce`) чистый: он меняет состояние и возвращает
эффекты, но не выполняет I/O.

## Запуск

- `MainActivity` — launcher и обработчик `USB_DEVICE_ATTACHED`
  (`AndroidManifest.xml:24-46`); `DiskService` — foreground service типа
  `connectedDevice`, не экспортируется.
- `MainViewModel.init` восстанавливает вкладку, язык/тему, SAF-папку и
  вызывает `DiskService.initialize`; остатки staging только показываются, не
  удаляются автоматически.
- `DiskController.get()` — process-wide singleton: `usbClaim`, очередь операций
  и `Dispatchers.IO.limitedParallelism(1)`. Один процесс — один владелец USB.
- `AppShell` рисует `Scaffold` и `NavigationBar` с вкладками Files, Ventoy,
  Partitions, Diagnostics; Settings — отдельный маршрут.

## USB-сессия

1. `MassStorageDiscovery` ищет interface class `0x08`, subclass `0x06`,
   protocol `0x50`, затем первый bulk IN/OUT.
2. `UsbExclusiveClaim` выдаёт неблокирующий процессный lease; второй владелец
   получает `BusyException`. Все открытия идут через `UsbOpenBoundary`.
3. `AndroidUsbBulkChannel` открывает `UsbDeviceConnection`, делает
   `claimInterface(force=true)` и обслуживает transfer не более 16 KiB за
   вызов.
4. `BotSessionImpl` формирует 31-байтный CBW, data phase и 13-байтный CSW;
   transfer сериализован.
5. `UsbDiskSession` проверяет TEST UNIT READY и capacity; `UsbDisk` выполняет
   READ/WRITE(10/16), `SYNCHRONIZE CACHE(10)`, retry и close.
6. `UsbOpenBoundary` проверяет `AttachmentPin` до claim, после открытия и после
   чтения таблицы. Несовпадение поколения/устройства закрывает диск.
7. `FilesVolumeSession` держит один `MountedFilesVolume`, epoch и точный pin;
   stale close не может закрыть более новый mount.

Ошибки close fail-closed: неудачное закрытие тома/диска не освобождает lease и
блокирует повторный mount до подтверждённого закрытия.

## Операции и отмена

- `OperationExecutor` выдаёт `OperationToken`, держит очередь под `Mutex` и
  выполняет одну операцию за раз. `DiskOperation` хранит состояние, прогресс,
  скорость и `cancelable`.
- Отмена кооперативная: `OperationHandle.cancel()` помечает операцию inactive;
  raw write и rescue restore проверяют это между чанками, а native mkfs
  прервать нельзя. `onInactive` — best-effort cleanup (закрыть входной поток).
- `OperationState`: Queued, Running, Completed, Cancelled, Failed, Partial.
  `Partial` — терминальное состояние, если mutation уже началась, но commit,
  close или readback не подтверждены.
- Прогресс публикуется UI с шагом 120 мс, журнал — 1 с. Поздние события
  отсекаются по attach generation и token.
- При detach: cancel очереди и active handles, mount fence, закрытие томов,
  `DiskController.onDisconnected`, presence `Disconnected`.

## Файловый стек

```
UI -> FilesOperationRunner -> FilesOperations -> MountedFilesVolume
   -> UsbFsVolume (FatFs/NtfsFs/ExtFs) -> BlockDevice -> UsbDisk -> BOT/SCSI
```

`FilesVolumeSession` сериализует mount/close/usage/files bodies. Кэш
переиспользуется только при точном pin+partition+writable. SAF используется
только для документов телефона; USB-накопитель в SAF не экспортируется.

## Безопасность состояний

- Любая мутация таблицы требует live geometry (sector size/count) и
  confirmation hash; несовпадение — отказ до записи.
- Hybrid/ambiguous/damaged layouts не изменяются; GPT, восстановленный из
  backup, доступен только для чтения/writer relocate по явному сценарию.
- EFI/ESP и `VTOYEFI` защищены от записи; override требует точного
  start+count.
- Raw write обязан пройти readback; failed write request не может быть
  `Verified`.
- UI не открывает USB и не обходит permission; все эффекты выполняет VM.

## Native-границы

`libusb_native.so` содержит адаптеры JNI и статические upstream-библиотеки:
FatFs R0.16 с патчами, ntfs-3g, lwext4, exfatprogs fsck, SectorForge bridge.
При отсутствии движка выбрасывается `EngineMissingException` с именем движка;
rescue не подменяется фиктивным результатом.

Подробности записи и ФС — в [storage.md](storage.md) и
[filesystems.md](filesystems.md).
