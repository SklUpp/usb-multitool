# USB Multitool

Android-приложение для работы с внешними USB-накопителями без root: просмотр
диска и разделов, создание и изменение MBR/GPT, файловые операции на
FAT/exFAT/NTFS/ext4, запись raw-образов, установка Ventoy и подготовка
Windows-носителя, rescue/backup и диагностика.

## Платформа

- `minSdk 28` (Android 9), `targetSdk 36`, только `arm64-v8a`.
- Нужен USB host/OTG и разрешение Android на устройство.
- Запись идёт через USB Bulk-Only Transport (BOT) и SCSI; прямого доступа к
  `/dev/block` и root нет.
- Файловые системы монтируются собственными JNI-движками; FUSE не нужен.
- Версия приложения: `0.1.0`, лицензия GPL-3.0-or-later.

## Документы

| Файл | Содержание |
|---|---|
| [architecture.md](architecture.md) | Слои приложения, владение состоянием, USB-сессия, операции |
| [storage.md](storage.md) | BOT/SCSI, claim/fencing, разделы, GPT/MBR, raw, SAF |
| [filesystems.md](filesystems.md) | FAT/exFAT, NTFS, ext4: возможности, лимиты, ошибки |
| [ui.md](ui.md) | Экраны, MD3, полоса разделов, RangeSlider и MiB-поля |
| [build-test-release.md](build-test-release.md) | Сборка, native-движки, тесты, CI, APK |
| [modernization.md](modernization.md) | Технический долг, устаревшие API, миграции и решения по native |

## Быстрый старт

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

Полная сборка с движками и CI-эквивалент — в
[build-test-release.md](build-test-release.md). Тяжёлую сборку APK запускать в
GitHub Actions, не на телефоне.

## Границы

- Устройство должно быть смонтировано эксклюзивно; приложение не работает с
  чужими смонтированными томами и не пытается обойти Android USB permission.
- Операции, меняющие таблицу разделов или содержимое диска, требуют
  подтверждения и рассчитаны на выбранный диск; геометрия проверяется до
  записи.
- Визуальные, TalkBack/IME и физические USB/SAF/Ventoy-сценарии требуют
  проверки на устройстве.
