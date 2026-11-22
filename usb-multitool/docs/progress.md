# Состояние проекта

Документ фиксирует проверенное состояние, а не план. Архитектура и правила —
в соседних файлах; здесь только срез, CI и ограничения проверки.

## Текущий срез

- Ветка: `feature/android-usb-multitool`.
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
  поэтому файлы на USB читаются встроенным Files picker.

## Проверка

- CI [44960993460](https://github.com/SklUpp/usb-multitool/actions/runs/44960993460)
  — success: FatFs host, NTFS host, ext4 host, exFAT fsck host, usb_block
  registry, unit tests, debug APK, 16 KiB alignment, lint, Kotlin compile.
- APK: artifact `app-debug-apk` (id `14730001315`), локально
  `/data/data/com.termux/files/home/apk-779f49a/app-debug.apk`,
  `86 700 491` байт, SHA-256
  `a440af677532843a5a2a5091977b719b6bd4f07387584c89335aa5f6dae80a1b`.
  `unzip -t` и `aapt2 dump badging` — без ошибок; package `usb.multitool 0.1.0`,
  minSdk 28, target 36.
- Локально на телефоне: targeted JVM tests и FatFs host tests; APK локально не
  собирается.

## Не проверено на устройстве

- Визуальный рендер MD3, переходы, pin-заголовок, тапы по полосе разделов.
- TalkBack, IME, `fontScale 2.0`, RTL.
- Физический USB: attach/detach, реальный RO/RW, SAF, FAT/exFAT/NTFS/ext4,
  Ventoy boot, Windows setup.

## История

Старые планы, аудиты и журналы вынесены из репозитория в архив вне дерева
проекта; они не являются источником текущих контрактов. Актуальные документы —
только файлы этого каталога.
