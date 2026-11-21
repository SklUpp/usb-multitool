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

## Slider and MiB fields

- [x] Slider snaps only the thumb the user moved; the other boundary stays exact.
- [x] Magnets include both free-region boundaries, 25/50/75%, standard sizes,
  every disk-aligned GiB boundary, and start/end points that preserve a standard
  or existing partition size relative to the opposite boundary.
- [x] `Начало, МиБ` and `Конец, МиБ` are synchronized with the slider, captions,
  and submitted LBA/count; a text edit that does not match the submitted
  geometry blocks creation.
- [x] Zero-length ranges and ends past the free region are rejected.
- [x] Slider and preview use exact offset-sector math, not rounded Float MiB.

## USB and JNI review fixes

- [x] Bulk IN requests stay within the 16 KiB USBFS buffer, so kernel-split
  completions are not mistaken for short packets; standalone IN ZLPs end the
  data phase before CSW.
- [x] Native threads attached with `AttachCurrentThread` detach at thread exit;
  JVM-managed threads are never detached.
- [x] FatFs block callbacks reuse one JNI scratch array and pass an explicit
  byte length, avoiding per-sector `NewByteArray` allocation.
- [x] FatFs volume slots are recycled; repeated mounts cannot exhaust `FF_VOLUMES`.
- [x] GPT backup entries use the recorded `gptBackupEntriesLba`; raw writes use
  the measured size; SAF cancellation uses bounded single-owner close; NTFS name
  and parent bounds plus close-failure rollback are enforced; FAT LFN buffers
  hold the 765-byte worst case.

## Verification

- [x] Compile, unit tests, lint. CI run `44955094683` is green.
- [x] Targeted JVM tests pass for GPT, raw writer/source chooser, SAF staging,
  FatFs callback/JNI signature, BOT ZLP, slider math, and string parity.
- [x] FatFs host tests pass, including a 765-byte/255-unit UTF-8 name round-trip.
- [ ] NTFS host test and full CI for the review-fix commit are pending.
- [ ] Visual check on device (pinning transition, strip taps, status dialog).
- [ ] CI green for the review-fix commit; APK is built only by CI.
