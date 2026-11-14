# USB Multitool: состояние и проверка

Этот файл — временные факты, результаты проверок и незавершённые работы. Требования: [консолидированный контракт](2026-11-14-consolidated-requirements.md); архитектурные инварианты: [план архитектуры](2026-11-12-architecture-refactor-plan.md) и [модель USB](2026-11-14-usb-interaction-state-model.md). Исторические сообщения и исходные пользовательские тексты сохранены в [оригиналах](2026-11-14-user-notes-originals.md).

## Текущий срез — 2026-11-14

- CI [43544218419](https://github.com/SklUpp/usb-multitool/actions/runs/43544218419) для `de20646` прошёл Rust, host FAT/exFAT/NTFS/EXT/USB, unit tests, debug APK, 16 KB alignment, lint и Kotlin compile. Это не аппаратная проверка.
- В CI входят восстановленные lifecycle и SAF, проверка protective MBR при GPT backup/restore и добавленные тесты виртуального диска, rescue и picker. Границы каждого теста не означают исчерпывающего покрытия.
- Для USB BOT/sense 52 JVM-теста `BotSessionImplTest`, `UsbDiskTest`, `UsbDiskSessionTest` прошли локально; полный CI и аппаратная проверка не заменяются этим результатом.
- Физические USB detach во время IN/OUT/sync, конкретные SAF provider faults/process death, загрузка Ventoy, установка Windows и TalkBack не проверялись.
- `third_party/lwext4` и `third_party/ntfs-3g` — локальные получаемые исходники; не переносить в коммит и не удалять без отдельной причины.
- Пользователь одобрил единый lifecycle списка `Stale / Loading / Loaded / Failed` вместо `filesPending` и session-bound SAF для выбора Ventoy-образа с телефона. Изменения объединены и прошли CI `43544218419`; проверка на устройстве остаётся открытой.
- Обнаружены и остановлены конфликтные варианты: дублирующий Compose pending для SAF и перегрузка `filesPending` для отложенного reload. Ограничения и риски доведены до пользователя.
- Одобрены передача исходного `PendingWindowsImport` в timeout координатора и упорядоченная доставка launch/cancel. Реализация объединена и входит в unit/сборку CI; проверка на устройстве не выполнена.

## Требования в работе

| Область | Состояние и границы |
|---|---|
| Files/SAF | FILE-08 multi-select, long-press и FILE-09/10 рекурсивные batch-операции и per-conflict prompts не закрыты: сейчас selection и операции скалярные. Нужны pinned batch identity, безопасное перечисление, отказ/отмена/`Partial` и SAF destination contract. Replace непустого чужого дерева не разрешён. FILE-06/11 не объявлять RW по предположению. |
| UI/status | Shell и operation mapper присутствуют; полная визуальная приёмка и completion-effects для всех token/lifecycle сценариев требуют проверки. |
| Ventoy/XML/JSON | Путь выбранного settings-файла теперь может переноситься без обрезания. Отличие выбранного от сгенерированного файла и полноэкранный редактор требуют состояния/контракта; не представлять предположение как реализованное. Сохранение неизвестных полей и backup проверять по полному сценарию. |
| Partitions/raw | Интегрированы выбор создаваемой свободной области на карте, размер раздела, различимые accessibility-описания и actual usage выбранной ФС; визуальная и CI-проверки не выполнены. GPT repair и смена схемы — разные действия. Причина первого отказа raw SAF measurement не установлена. |
| Diagnostics | Блок backup/restore и функции существуют; UI-подсказки обновлены. Общая backup-папка допустима; обязательность её запроса на первом старте пользователем не установлена. |
| Состояние/ownership | Не создавать вторых владельцев USB, mount, operation и UI. При работе с `UNIT ATTENTION`, `Partial`, sync, close и identity соблюдать модель USB. |

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

Исторический [CI 43502683843](https://github.com/SklUpp/usb-multitool/actions/runs/43502683843) для `e057bf7`: 40 из 1436 unit-тестов упали, 8 skipped. Незавершённые worktree Files из оригинала не интегрированы; нельзя считать статическое ревью доказательством поведения.

## Следующие проверки

1. Независимо проверить и интегрировать исправления 7 CI failures, не перепривязывая тесты к неправильному поведению.
2. Повторить CI на фактически отправленном SHA; проверить unit, APK, lint и 16 KB alignment отдельно.
3. Продолжить каждый ID из [исходной матрицы](2026-11-14-user-notes-originals.md#localusb-requirements-matrixmd), фиксируя evidence для текущего среза, а не записывая целый экран как выполненный по одному изменению.
4. После доступного APK проверить UI на Android в обеих темах и отдельные критические USB/SAF сценарии на тестовом оборудовании.
