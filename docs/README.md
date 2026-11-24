# USB Multitool

Android-приложение для работы с внешними USB-накопителями без root: просмотр
диска и разделов, создание и изменение MBR/GPT, файловые операции на
FAT/exFAT/NTFS/ext4, запись raw-образов, установка Ventoy и подготовка
Windows-носителя, rescue/backup и диагностика.

## Документы

| Файл | Содержание |
|---|---|
| [architecture.md](architecture.md) | Слои приложения, владение состоянием, USB-сессия, операции |
| [storage.md](storage.md) | BOT/SCSI, claim/fencing, разделы, GPT/MBR, raw, SAF |
| [filesystems.md](filesystems.md) | FAT/exFAT, NTFS, ext4: возможности, лимиты, ошибки |
| [ui.md](ui.md) | Экраны, MD3, полоса разделов, RangeSlider и MiB-поля |
| [build-test-release.md](build-test-release.md) | Сборка, native-движки, тесты, CI, APK |
| [modernization.md](modernization.md) | Технический долг, устаревшие API, миграции и решения по native |
