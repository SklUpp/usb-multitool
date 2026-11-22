# UI и взаимодействие

Один Compose-shell, Material 3, тёмная/светлая тема, строки в `values` и
`values-ru` с обязательной parity-проверкой.

## Каркас

- `AppShell` — `Scaffold` + `NavigationBar`; вкладки Files, Ventoy, Partitions,
  Diagnostics. Settings открывается отдельным маршрутом из заголовка.
- `AppUiState` — единственный state holder; экраны stateless:
  `(state, onIntent, modifier)`.
- Переключение вкладок блокируется, пока операция Queued/Running или открыто
  подтверждение.
- Постоянный status block под заголовком: одна строка `Status: операция —
  состояние` и тонкий progress track; тап открывает компактный dialog с журналом.
  Отмена — в этом dialog; отдельная кнопка в status block не дублируется.

## Отмена длинных операций

`CancelOperationButton` — общий компонент (`AppShell.kt`). Он виден только при
Queued/Running и `cancelable`, отменяет точный `OperationToken`.

Размещён:
- в постоянном status block (доступен на всех вкладках);
- рядом с установкой/обновлением Ventoy;
- рядом с проверками Diagnostics и raw write;
- рядом с partition list и Files actions.

ISO-import в Ventoy сохраняет свой progress cancel. Native mkfs/mount не
прерывается: кнопка не показывается, если операция объявила себя
non-cancelable.

## Partitions: список и закреплённый selector

- Selector раздела — **один** общий `PartitionDropdown` (clickable dropdown),
  а не отдельный статический header.
- Он находится внутри блока Layout, непосредственно над списком разделов;
  отдельного selector над Layout больше нет.
- При скролле вниз selector закрепляется у верха viewport, пока список ещё
  виден. Закреплённая копия — тот же dropdown и остаётся раскрываемым; это не
  дублирующий текстовый header.
- Список показывает реальные разделы и free regions; free-region tap открывает
  создание, partition tap выбирает раздел.
- Ширина полосы — полная; подписи не накладываются; высота интерактивной зоны
  ≥48 dp. Selected — primary outline. Легенда и список — доступная замена
  полосе.

## Создание раздела

Диалог намеренно короткий:

1. free region (если их несколько);
2. RangeSlider;
3. поля `Начало, МиБ` и `Конец, МиБ`;
4. одна строка `Размер: N МиБ`;
5. подтверждение.

Старый preview-bar с повторяющимися `free/new/free`, caption `start/end/size`
и дублирующие подписи удалены. Ошибки alignment/range остаются supporting text
только у соответствующего поля.

Правила:

- границы — целые MiB; create пишет целые МиБ, sub-MiB хвост остаётся свободным;
- slider и поля обновляются из одной exact-геометрии
  (`startOffsetSectors/alignSectors`, `endOffsetSectors/alignSectors`), а не из
  округлённого Float;
- двигается только тот thumb, который изменил пользователь; второй сохраняет
  точную границу;
- магниты: обе границы free region, 25/50/75%, стандартные размеры, все
  выровненные GiB-границы и размеры существующих разделов относительно
  противоположной границы;
- поле, не совпадающее с отправляемой геометрией, блокирует create;
- нулевая длина, `end ≤ start`, выход за free region и end > available
  блокируются; slider скрывается при невалидном end;
- подтверждение отправляет LBA/start/count из полей, а reducer повторно
  проверяет alignment/free-space/table validity.

## Files

- `PartitionDropdown` — тот же селектор, что в Partitions.
- Папка: path, Refresh, Create folder; один lazy list; selection row:
  Download/Rename/Delete/Clear; row bottom sheet.
- Копирование показывает progress и cancel; ошибки/прогресс также в shell status.
- Read-only badge основан на measured policy: EFI guard или driver mount facts;
  неизвестное состояние не рисует badge.
- SAF picker используется для телефона; USB-файлы — через встроенный Files picker
  (SAF не видит FAT/exFAT USB).

## Восстановление (Diagnostics)

Один нормализованный сценарий для rescue-образа и GPT-бэкапа:

1. `Выбрать папку` / `Выбрать файл` — только выбор источника, без destructive
   диалога.
2. Проверка: пара `.img` + `<stem>.map[.N]`, bounded map decode, GPT
   header/CRC/geometry. Ошибка/неизвестный sector size блокируют restore.
3. Явная кнопка `Восстановить образ` / `Восстановить GPT`; reducer повторно
   проверяет kind/partial COPY/fit/geometry.
4. Confirmation фиксирует identity, capacity, attach generation, URI и
   inspection; для map/GPT дополнительно SHA-256, для образа — size и
   last-modified метаданные SAF.
5. Execution заново проверяет generation/capacity, digest map/GPT и метаданные
   образа, затем пишет. Partial resume: checkpoint публикуется в выбранную
   SAF-папку как `<stem>.map.<generation>` и подставляется в state как новый map.
6. Без USB serial restore разрешён, но прежний прогресс отбрасывается и все
   сектора перезаписываются; с serial — resume по map.

Отмена доступна через status dialog; restore не запускается автоматически после
выбора файла.

## Ventoy / Windows

- Ventoy: install/update, ISO library, control keys, autounattend, release
  download; destructive actions подтверждаются.
- Windows: раскладка установочного носителя, импорт ISO/файлов, autounattend;
  длительные действия используют общий operation/status/cancel.
- Никаких dot-separated meta-строк (`·`, `•`) в UI. Несколько фактов
  разделяются запятой, отдельными Text-строками или заголовками.

## Strings и форматирование

- Все пользовательские строки — `R.string`; `StringResourceParityTest` проверяет
  одинаковый набор ключей в `values` и `values-ru`.
- MiB — двоичные (1024²); отдельного MB/MiB toggle нет.
- LBA/сектора подписываются явно; единицы не смешиваются в одном числе.
- Длинные значения используют monospace/tnum только там, где сравниваются
  колонки, без декоративных разделителей.

## Доступность

- Интерактивные зоны ≥48 dp; dropdown имеет content description и раскрывается
  кликом/TalkBack.
- Полоса разделов имеет текстовую легенду/список как fallback.
- Status использует live region только для running/standalone; journal не
  превращается в live region.
- Проверка TalkBack, IME, fontScale 2.0, RTL и переходов pin-селектора требует
  устройства; см. [progress.md](progress.md).
