# UI follow-up requirements (2026-11-22)

Recorded from user feedback. Status is updated only after code and verification.

## Units and partition creation

- [x] Partition sizes use **MiB only**. No MB option, toggle, or persisted setting.
  Reason: partition start and size are aligned to whole MiB.
- [x] Creation dialog has **no mode switch**. One range slider is always visible,
  starts at the whole aligned free region, and leaves a sub-MiB tail free.
- [x] Remove raw LBA/sector inputs, alignment buttons, info hints, duplicate
  all-free summary, and duplicate scheme confirmation.

## Partition strip

- [x] No labels inside segments, no horizontal scroll, no outer divider frame.
- [x] Segments fill the width proportionally to LBA span; legend below covers
  selected, partition, free, and unallocated.
- [x] Segments must stay interactive: partition tap selects it; free-space tap
  opens creation when the region is creatable.
- [x] Selected segment has a visible primary outline.

## Pinned selected section

- [x] The selected-partition section is below the partition list.
- [x] When scrolling, its header transitions into a pinned header only after
  the section reaches the top; it is not the permanently pinned selector.
- [x] The pinned header disappears when the selected section has scrolled past.

## Status window

- [x] The title and the compact status line stay pinned in the status dialog
  header/subheader while the journal scrolls.
- [x] Content that does not fit scrolls below the pinned header/subheader.

## Partition lists

- [x] Every partition list row shows free/size, not used/total.
- [x] Main list and the fixed picker use the same free/size line and LBA range.
- [x] Rows follow MD3 list-item structure with 48 dp minimum targets.

## Sizes in Windows layout

- [x] Partition, total, remaining, declared size, and size input labels use MiB.

## Verification

- [ ] Compile, unit tests, lint.
- [ ] Visual check on device (pinning transition, strip taps, status dialog).
- [ ] CI green after push; APK from CI only.
