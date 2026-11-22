# Состояние проекта

Документ фиксирует проверенное состояние, а не план. Архитектура и правила —
в соседних файлах; здесь только срез, CI и ограничения проверки.

## Последняя проверка

- CI `44995728697` (`3b33d01`): `verify` и `fatfs-host` — success;
  unit tests, APK, 16 KB alignment, lint и Kotlin compile прошли.
- APK artifact `app-debug-apk` (id `14743343745`), локально
  `/data/data/com.termux/files/home/apk-3b33d01/app-debug.apk`,
  `86 858 107` байт, SHA-256
  `5f4884d8f8852ff63b59ade52cabacc4250ca86754a12578e8ef7b4d25c8d69f`.
  `unzip -t` и `aapt2 dump badging` — без ошибок; package
  `usb.multitool 0.1.0`, minSdk 28, target 36.

## Текущий срез

- Ветка: `feature/android-usb-multitool`, HEAD `3b33d01`; SMART —
  `f504ddf`, UI/MD3 и raw-write волна — `e81a6fa`, правки после ревью —
  `da484fb`, нормализация restore-путей — `bdcde20`, финальная UI-волна —
  `7cc008b..3b33d01`.
- Последняя волна: SMART diagnostics; UI-полировка (IME, сортировка Files,
  прогресс, переходы, haptics, read-only Settings/Diagnostics), общий JNI
  thread-attach helper, FAT streaming с retry close/pending unlink и
  когерентным read-after-write.
- Кандидаты в SMART/f3probe/persistence/восстановление/TRIM разобраны
  отдельно: [feature-candidates.md](feature-candidates.md); реализован только
  SMART, остальные отложены или сняты.
- Проведён параллельный ресерч внедрения пятью read-only lanes и отдельной
  проверкой стандартов: [implementation-research.md](implementation-research.md).
  Итог: SMART — первый кандидат; recovery scan — второй; persistence —
  условно (выбран file-backed adapter A); f3probe — no-go на non-root Android
  (нет OS-unmount proof); TRIM — no-go без free-extent owner.
- SMART: read-only SAT ATA PASS-THROUGH(16), только IDENTIFY DEVICE и SMART
  READ DATA; descriptor sense, проверка ATA status/error, checksum, коротких
  data phase и cancellation между командами. UI — действие в Diagnostics,
  локализованный результат и запись в operation log. Неподдерживаемый мост
  даёт `Unsupported`, а не закрытие сессии.
- UI-волна: постоянный dropdown разделов убран. Выбранный раздел закрепляется
  только после того, как его строка ушла выше вьюпорта; в покое в списке одна
  строка. Полоса разделов получила минимальную ширину 12 dp и прокрутку без
  overscroll-полос; легенда использует контрастные к теме `onSurfaceVariant`
  тона и `primary` для выбранного, а не близкие к фону container-роли.
  Из «Информации о разделе» убраны строка «Свободно» и дубль EFI RO; иконки
  редактирования одного цвета; переключатель записи EFI перенесён под тип EFI.
  Raw write по умолчанию целится в весь диск, предупреждает о перезаписи
  таблицы/загрузочных секторов и требует подтверждения; для SAF-источника
  сохраняется digest-gate, добавлен retry измерения. Self-test ограничен 10 с
  на проверку.
  Windows/Ventoy переключатели показывают «Включено/Выключено/По умолчанию»
  (upstream default — включено). В Diagnostics кнопка выбора папки вынесена
  в заголовок выбранных файлов, кнопка восстановления образа и карты видна
  всегда (disabled без пары), пути папки и файлов показываются одной
  нормализованной строкой.
- Ventoy settings: верхняя кнопка «Записать» убрана; «Применить» в редакторе
  открывает диалог со списком staged-контролов и кнопкой «Записать».
  Прочитанные из файла control-настройки показаны под «Редактор» компактным
  списком: одна строка в свёрнутом виде, все строки по кнопке справа, с
  заголовком.
- Привязка образов: в списке остаются только `.iso`; JSON/XML больше не
  выбираются. Кнопка «Сбросить выбор» удалена; снятие отметок делается
  чекбоксами, а уже привязанный образ отвязывается отдельной строкой.
  Ventoy control-ключи получили короткие описания, значения 1/0 в карточке
  показываются как «Включено/Выключено/По умолчанию». Кнопка обновления
  каталога ключей и выбор папки в Diagnostics — иконки с contentDescription;
  длинные SAF-пути ограничены тремя строками с ellipsis.
- Файлы: сортировка «папки сверху, затем A–Z без учёта регистра» уже была;
  добавлены кликабельные breadcrumbs с горизонтальной прокруткой вместо
  кнопки «Вверх» (обычный проводник и picker). Локальный фильтр по имени —
  встроенная раскрывающаяся строка на месте пути: не перекрывает иконки
  действий, открывает клавиатуру, X очищает фильтр, повторный тап по поиску
  скрывает строку. Системный Back по-прежнему поднимает на уровень выше.
- Разделы: широкая полоса (≥10% и ≥64 dp) показывает имя раздела и ФС.
  Магнитный snap при создании раздела убран, размер шагает целыми MiB.
  Постоянный selector убран; закреплённая копия выбранной строки появляется
  только после ухода оригинала выше вьюпорта. Факты и переключатели
  раздела — MD3 `ListItem`, кастомные разделители удалены; заголовки
  секций — MD3 `Card` + `ListItem`.
- AppShell: прогресс 4 dp и направленный slide вкладок уже были; для
  `screenHeightDp < 480` нижняя навигация заменена на MD3 NavigationRail
  слева, рабочая область не перекрывается. Autounattend-редактор уже
  использует FontFamily.Monospace.
- Версия приложения: `0.1.0` (`versionCode 1`), `minSdk 28`, `targetSdk 36`,
  ABI `arm64-v8a`.
- UI: Material 3, русский и английский (`values`, `values-ru`).
- Разделы: MBR/GPT, создание/удаление/изменение, выравнивание, EFI-guard,
  проверка геометрии и запись backup/primary в безопасном порядке.
- Файловые системы: FAT/exFAT, NTFS, ext4; форматирование, чтение/запись файлов,
  проверка/repair exFAT, монтирование через собственные JNI-движки.
- Образы: запись raw image по диапазону, измерение размера/дайджеста,
  bounded-close при отмене.
- Ventoy: установка/обновление, ISO-библиотека, control keys, autounattend.
- Windows: раскладка установочного носителя, импорт ISO/файлов.
- Rescue/diagnostics: SectorForge, backup/restore, checkpoint, самотест.
- SAF: выбор папки/файла и staging; SAF не видит FAT/exFAT-разделы USB,
  поэтому файлы на USB читаются встроенным Files picker. Пути restore
  нормализуются до одного вида `/Download/...` без `content://`, `//`, `.`
  и `..`.
- Ventoy: при отсутствии `/ventoy/ventoy.json` UI явно сообщает, что
  действуют встроенные значения upstream (оба Windows 11 bypass-ключа
  включены), а файл создаётся при Apply. Для всех bundled control-ключей
  добавлены короткие локализованные описания.

## Проверка

- CI [44992369032](https://github.com/SklUpp/usb-multitool/actions/runs/44992369032)
  (`bdcde20`) — **success**: fatfs-host, host exFAT/NTFS/ext4/FAT/usb_block,
  SectorForge fault tests, unit tests, debug APK, 16 KiB alignment, lint,
  Kotlin compile. Lint-fix: `ventoy_promotion_unconfirmed` теперь принимает
  оба аргумента в EN/RU.
- APK `bdcde20` из CI `44992369032`:
  `/data/data/com.termux/files/home/apk-bdcde20/app-debug.apk`,
  `86 874 011` байт, SHA-256
  `c31e4d7c5e927b1f01465af38ce819a6f12064b7ec0430c8705ded96f14de6bb`.
  `unzip -t` и `aapt2 dump badging` — без ошибок; package `usb.multitool 0.1.0`,
  minSdk 28, target 36.
- CI [44960993460](https://github.com/SklUpp/usb-multitool/actions/runs/44960993460)
  — success для предыдущего среза: FatFs host, NTFS host, ext4 host, exFAT fsck
  host, usb_block registry, unit tests, debug APK, 16 KiB alignment, lint,
  Kotlin compile.
- CI [44978454387](https://github.com/SklUpp/usb-multitool/actions/runs/44978454387)
  (`f504ddf`) — `fatfs-host` success, `verify` failed только на
  `RawWriterTest.compressedSourceIsDecodedCountedThenWrittenStreamingly`:
  глобальный бюджет measurement-close был временно занят, readback получил
  `Incomplete` и результат стал `Partial`. Исправлено изоляцией теста
  (`@Before` ждёт свежий бюджет) в `7fae2c4`; локальный `RawWriterTest` —
  BUILD SUCCESSFUL.
- CI [44979578629](https://github.com/SklUpp/usb-multitool/actions/runs/44979578629)
  (`7fae2c4`) — **success**: `fatfs-host` и `verify` (SectorForge, exFAT/NTFS/
  ext4/FAT/usb_block host tests, unit tests, APK, 16 KiB alignment, lint,
  Kotlin compile).
- APK `7fae2c4` из CI `44979578629`: `/data/data/com.termux/files/home/apk-7fae2c4/app-debug.apk`,
  `86 790 027` байт, SHA-256
  `b041471e951a00101a073dfbdadbb995db4027361986543a17d968c2d5ecce33`.
  `unzip -t` и `aapt2 dump badging` — без ошибок; package `usb.multitool 0.1.0`,
  minSdk 28, target 36.
- APK предыдущего среза: artifact `app-debug-apk` (id `14730001315`), локально
  `/data/data/com.termux/files/home/apk-779f49a/app-debug.apk`,
  `86 700 491` байт, SHA-256
  `a440af677532843a5a2a5091977b719b6bd4f07387584c89335aa5f6dae80a1b`.
  `unzip -t` и `aapt2 dump badging` — без ошибок; package `usb.multitool 0.1.0`,
  minSdk 28, target 36.
- Локально на телефоне для волны `c5e916b`:
  - `scripts/run-fat-host-test.sh` — OK, 4 МиБ streaming, no stale handle;
    добавлены read-after-write, close retry, pending unlink, exclusive recreate,
    CREATE_ALWAYS truncation и CTRL_SYNC attribution.
  - targeted JVM: 64/64, 0 failures — `FilesOperationsUploadTest` 18,
    `ShellLogBehaviorTest` 14, `PartitionRangeMathTest` 9,
    `PartitionRenameUiTest` 6, `SchemeInitUiTest` 8,
    `PartitionRepairUiTest` 9. Compile — BUILD SUCCESSFUL.
  - APK локально не собирается; on-device UI/USB не проверялись.
- Независимое ревью волны `a73d18e..HEAD` (три read-only reviewer):
  подтверждены и исправлены SAF size mismatch, queued Files Back,
  Diagnostics-operation origin при rotation, FAT pending unlink в close/switch/
  unmount. Финальные вердикты — без подтверждённых дефектов; для трёх редких
  pending-unlink веток добавление отдельных host-инъекций остаётся
  рекомендацией, не блокером.
- SMART-волна: targeted JVM 92/92, 0 failures — SMART parser/transport,
  `UsbDiskTest`, `FilesOperationsUploadTest`, `ShellLogBehaviorTest`,
  `PartitionRangeMathTest`; Kotlin compile — BUILD SUCCESSFUL. Независимое
  ревью SMART (SCSI/parser, UI/state, tests/safety) подтвердило ошибку
  checksum-фикстуры, BOT recovery при REQUEST SENSE, DF status и
  cancellation-разрыв; все подтверждённые пункты исправлены и перепроверены.

## Не проверено на устройстве

- Визуальный рендер MD3, переходы, pin-заголовок, тапы по полосе разделов.
- TalkBack, IME, `fontScale 2.0`, RTL.
- Физический USB: attach/detach, реальный RO/RW, SAF, FAT/exFAT/NTFS/ext4,
  Ventoy boot, Windows setup.

## История

Старые планы, аудиты и журналы вынесены из репозитория в архив вне дерева
проекта; они не являются источником текущих контрактов. Актуальные документы —
только файлы этого каталога.
