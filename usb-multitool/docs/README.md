# Документация проекта

Единое место: все проектные документы лежат в `docs/superpowers/specs/` плоским
списком, имя файла начинается с даты. Отдельных вложенных каталогов для волн
нет — новая волна добавляет файлы с той же схемой имени.

## Требования

| Файл | Содержание |
|---|---|
| [2026-11-14-consolidated-requirements.md](superpowers/specs/2026-11-14-consolidated-requirements.md) | Свод первоначальных требований и согласованных решений |
| [2026-11-14-user-notes-originals.md](superpowers/specs/2026-11-14-user-notes-originals.md) | Исходные заметки пользователя без правок |
| [2026-11-19-wave3-README.md](superpowers/specs/2026-11-19-wave3-README.md) | Волна 3: индекс по блокам и правила Material 3 |
| [2026-11-19-wave3-ventoy.md](superpowers/specs/2026-11-19-wave3-ventoy.md) | Ventoy: «Обновить», кнопка-иконка, SAF настроек |
| [2026-11-19-wave3-files.md](superpowers/specs/2026-11-19-wave3-files.md) | Файлы: иконки, строка пути, RO/RW, меню, отступ |
| [2026-11-19-wave3-shell-status.md](superpowers/specs/2026-11-19-wave3-shell-status.md) | Статус, центральные детали, формат логов |
| [2026-11-19-wave3-partitions.md](superpowers/specs/2026-11-19-wave3-partitions.md) | Разделы: счётчик, «Используется», строка раздела |
| [2026-11-19-wave3-round2-review-decisions.md](superpowers/specs/2026-11-19-wave3-round2-review-decisions.md) | Второе ревью: что применено, что отклонено и почему |

## Устройство и архитектура

| Файл | Содержание |
|---|---|
| [2026-11-01-android-usb-multitool-design.md](superpowers/specs/2026-11-01-android-usb-multitool-design.md) | Общий дизайн приложения |
| [2026-11-14-usb-interaction-state-model.md](superpowers/specs/2026-11-14-usb-interaction-state-model.md) | Модель состояний USB-взаимодействия |
| [2026-11-12-architecture-refactor-plan.md](superpowers/specs/2026-11-12-architecture-refactor-plan.md) | План архитектурной декомпозиции |

## История и доказательства

| Файл | Содержание |
|---|---|
| [2026-11-14-project-progress.md](superpowers/specs/2026-11-14-project-progress.md) | Таблица срезов, CI-запуски, APK и ограничения проверок |

## Правила ведения

- Документ описывает требование или проверенное состояние, а не намерение:
  незавершённое помечается как «не проверено» или «в работе».
- Одна волна требований — один набор файлов с общим префиксом даты и слова
  `waveN`; правки старых волн не переписывают историю, а добавляют новый файл.
- Ссылки между документами относительные, имена файлов не переименовываются
  без обновления индекса.
