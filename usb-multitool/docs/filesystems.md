# Файловые системы

Все движки работают поверх `BlockDevice` (USB BOT/SCSI) и не требуют FUSE/root.
Один том монтируется эксклюзивно; UI видит `ReadOnly`, `ReadWrite` или
`Unknown` в зависимости от движка и таблицы разделов.

## FAT / exFAT

- FatFs R0.16 с патчами; `FF_VOLUMES=4`, сектор 512..4096, exFAT включён,
  `FF_LFN_UNICODE=2` (UTF-8), `FF_MAX_LFN=255`, `FF_LFN_BUF=768`.
- 255 UTF-16 units в худшем случае — 765 UTF-8 байт; буфер 768 вмещает имя и NUL.
  JNI/struct буферы (`FatDirEnt`, `FatStat`) имеют тот же предел.
- Native volume pool переиспользует первый свободный logical drive; закрытие
  освобождает слот, поэтому повторные mount не исчерпывают `FF_VOLUMES`.
- `FatBlockCallback` получает переиспользуемый JNI scratch `byte[]` и точную
  длину; per-sector `NewByteArray` больше не создаётся.
- Операции: probe, label, space, list, stat, mkdir, delete, rename, read, write,
  sync. Write одним вызовом ≤8 MiB; planned size проверяется до open.
- Последовательные write-чанки держат один `FIL` открытым до sync/umount/смены
  файла; `f_lseek` не повторяется на каждом чанке.
- `create=true` у FAT — CREATE_ALWAYS и **truncate даже при offset != 0**;
  exclusive create при существующем файле не меняет его.
- Короткая запись/failed close возвращает ошибку; created-only файл удаляется,
  если cleanup возможен.
- Путь ограничен 1024 байтами; слишком длинный путь отвергается до FatFs, чтобы
  не задеть короткого соседа.
- Sync сначала flush dirty FatFs window, затем transport barrier; failed final
  sync оставляет sticky `barrier_pending` и повторяется следующим sync/close.
- FAT не сообщает RO-флаг драйвера: `FatFs` возвращает `Unknown`; read-only
  обеспечивается уровнем сессии/UI, но не драйвером.

## NTFS

- Read-only mount по умолчанию. RW mount запрещён для dirty volume; native mount
  также fail-closed `EBUSY`.
- Один mount на `BlockDevice`; registry/reservation не даёт открыть дважды.
- Имена: 1..255 UTF-16 units, буфер 768 байт для UTF-8. Слишком длинное имя —
  `-EINVAL`, а не обрезка.
- Parent path ограничен 511 байтами; `split_path` возвращает ошибку, а не
  молча укорачивает путь. Проверка выполняется до lookup.
- Read запрещает metadata (`MFT_no < FILE_first_user`) с `EPERM`.
- Write:
  - create только в разрешённом parent (системные metadata запрещены, root
    разрешён);
  - exclusive create при существующем — `-EEXIST`, без изменений;
  - `create=true` truncate только при offset 0; при offset != 0 truncate нет;
  - ошибка после create удаляет созданный файл;
  - failed close после create: reopen by path → delete; если удалить не удалось,
    `-EUCLEAN` с явным сообщением «файл может остаться»;
  - для существующего файла rollback не выполняется, частичные байты возможны.
- Rename: directory cross-directory запрещён (`-EXDEV`); иначе link+unlink с
  rollback destination, если source ещё резолвится.
- List ограничен 65536 entries; overflow/ошибка имени — ошибка, не короткий
  список. Synced close делает `ntfs_umount(vol,0)`; sync failure → `-EIO`.
- Label set/get с UTF-16→UTF-8 проверкой; rename label только NTFS/FAT/exFAT.

## ext2/3/4

- Mount отказывает dirty (`FINCOM_RECOVER`), on-disk `ERROR_FS/ORPHAN`,
  неподдерживаемым incompat и ro_compat — и для RO, и для RW.
- RW mount запускает journal при `HAS_JOURNAL`; ошибка старта journal
  разбирает mount и не отдаёт writable handle.
- `mkfs` создаёт ext4 **без journal**: lwext4 не replay-ит journal.
- Label >15 UTF-8 байт отвергается, не обрезается. Rename label для EXT не
  поддерживается.
- Close всегда делает teardown lwext4 до освобождения engine, даже при ошибке
  flush; ошибка возвращается.
- List ограничен 65536; overflow `-EOVERFLOW`, unreadable directory block `-EIO`,
  без короткого списка. Имена обрезаются до 767 байт, размер каталога 0.
- Write: RO → `-EROFS`; exclusive create с `O_EXCL` и удалением created-файла при
  seek/write/close failure; не-exclusive failure **без rollback** — возможны
  частичные байты.
- Access RO/RW сообщается из mount flag; close сериализован с native ops.

## Форматирование и labels

| ФС | Формат | Label |
|---|---|---|
| FAT/FAT32 | FAT32 при >2 GiB, иначе FAT12/16 | 11 символов, CP437/uppercase |
| exFAT | exFAT | 11 UTF-16 units |
| NTFS | NTFS | 128 UTF-16 units |
| ext4 | journal-less ext4 | 15 UTF-8 байт, rename запрещён |

Format — блокирующий native mkfs, без cancellation callback. Перед format
закрываются все монтирования устройства; UI обязан показать подтверждение.

## exFAT fsck

- `check` — read-only, `repair_yes=0`.
- `repair` требует токен `CONFIRM_EXFAT_REPAIR`, закрывает все volumes, затем
  запускает fsck `repair_yes=1`.
- Результаты upstream: `0` NoErrors, `1` Corrected, `4` ErrorsLeft,
  `8` OperationError.
- Host test проверяет, что read-only check не пишет сектора, repair исправляет и
  повторный check чист.

## Ошибки и границы

- `FR_NO_FILE`/`FR_NO_PATH` — отсутствие; прочие FatFs ошибки не маскируются.
- NTFS negative errno: `-ENOENT`, `-EINVAL`, `-EEXIST`, `-EIO`,
  `-EUCLEAN` (cleanup/rollback failed), `-EROFS`.
- EXT `-EROFS` для write на RO; `-EOVERFLOW` для слишком большого листинга.
- `EngineMissingException` означает, что native engine не слинкован; это не
  «пустой диск» и не повод создавать фиктивный результат.
