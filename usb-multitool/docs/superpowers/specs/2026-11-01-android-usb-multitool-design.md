# Android USB Multitool — Design

**Date:** 2026-11-01
**Status:** Approved for implementation planning
**License decision:** GPL-3.0-or-later for the application

## 1. Purpose

Build one Android application for direct work with USB mass-storage devices without root and without the Storage Access Framework. It provides a file manager, Ventoy installation and update, partition and raw-image operations, and disk diagnostics.

The original functional plan remains the source of required behavior. This specification corrects transport and filesystem assumptions and requires existing implementations wherever they are suitable.

## 2. Confirmed decisions

- Distribution is a sideloaded APK. Google Play publication is not a requirement.
- Android shared-storage access uses `MANAGE_EXTERNAL_STORAGE` for local images and backups. This does not grant access to private directories of other applications.
- USB access uses the Android USB Host API and explicit user permission. Root is prohibited.
- The application is a new project. It reuses code and modules; it is not a fork of EtchDroid.
- GPL-3.0-or-later is accepted, including source-distribution obligations.

## 3. Prior art and reuse

| Area | Adopted base | Boundary |
|---|---|---|
| USB BOT/SCSI | [libaums](https://github.com/magnusja/libaums), Apache-2.0 | Reuse device discovery, Bulk-Only Transport, SCSI(10), status checking, REQUEST SENSE, and STALL recovery |
| Raw transfer behavior | [EtchDroid](https://github.com/EtchDroid/EtchDroid), GPL-3.0 | Reuse proven transfer patterns only; do not inherit its UI, telemetry, or reduced device scope |
| FAT12/16/32 and exFAT | [FatFs R0.16](https://elm-chan.org/fsw/ff/) plus official patch-1 and patch-2 | Use formatting, multipartition, LFN/Unicode, exFAT, and 64-bit LBA support |
| NTFS | [libntfs-3g](https://github.com/tuxera/ntfs-3g), GPL-2.0-or-later | Use the library through a custom device backend; do not use FUSE or system mounting |
| EXT2/3/4 | [lwext4](https://github.com/gkostka/lwext4), GPL-2.0-or-later | Mandatory engine for EXT support; use the full extent-capable source and a block callback |
| Ventoy | [Ventoy native installer and utilities](https://github.com/ventoy/Ventoy), GPL-3.0-or-later | Adapt its install/update logic; do not reimplement it from shell constants |
| Damaged-media copy | [GNU ddrescue](https://www.gnu.org/software/ddrescue/), GPL-2.0-or-later | Adapt its mapfile-based rescue behavior to the sector backend |
| Compression | Android `GZIPInputStream` and an existing XZ library | Stream decompression; do not require a temporary uncompressed copy |

`java-fs` is not the write engine. Its exFAT write path throws `UnsupportedOperationException`, and its NTFS write path is a stub.

## 4. Scope

### In scope

- Discover and claim a USB mass-storage interface, then execute SCSI reads and writes.
- Enumerate MBR and GPT partitions, including the Ventoy EFI partition.
- Browse, copy, delete, and rename files through FatFs, NTFS-3G, or lwext4 according to the filesystem.
- Copy files larger than 4 GiB where the destination filesystem supports them.
- Install and update Ventoy, generate its configuration, and write Windows answer-file templates.
- Create and delete partitions in free space, change boot/ESP flags, format filesystems, and repair a GPT backup header after a smaller image write.
- Write raw `.iso`, `.img`, `.gz`, and `.xz` images from local files.
- Run read scans, wipe, bad-sector handling, backup, and restore operations.

### Out of scope

- Root, custom kernels, loop devices, FUSE mounts, and SAF-based USB access.
- USB Attached SCSI (UAS) and every non-BOT transport.
- Moving, shrinking, or expanding occupied partitions and their filesystems.
- A new NTFS, exFAT, EXT, Ventoy, or ddrescue implementation.
- Fake-capacity testing, write benchmarks, and HTTPS image URLs.
- Guaranteed BIOS/UEFI boot, Windows installation success, or bypass success on every Windows release.
- Automatic resumption after USB disconnection.
- Google Play policy approval.

## 5. Architecture

```text
Compose UI: Files | Ventoy | Partitions/Raw | Diagnostics
                    |
            OperationExecutor
      foreground service, queue, cancellation
                    |
     UsbDisk + PartitionTable + VirtualPartition
                    |
    FatFs | libntfs-3g | lwext4 | raw image | ddrescue
                    |
      BOT/SCSI(10/16), 64 KiB transfers, STALL recovery
                    |
            Android UsbDeviceConnection
```

### Modules

- `device`: permission, connection ownership, capacity, logical block size, SCSI, recovery, and disconnection.
- `partition`: MBR/GPT decode and modification, alignment, flags, free regions, protective/hybrid handling, and GPT backup relocation.
- `fs`: JNI facades. Filesystem metadata and allocation remain inside upstream engines.
- `image`: local image sources, gzip/XZ decoding, progress, and cancellation.
- `ventoy`: adapted upstream install/update flow plus JSON/XML integration.
- `diagnostics`: read scan, self-test, wipe, and rescue-map backup/restore.
- `service`: one operation queue, foreground notification, WakeLock, and persisted operation state.
- `ui`: four screens grouped into real sections (device/partition context, read-only facts, then destructive actions) with dense 8 dp spacing and hairline section dividers instead of container fills; multi-option choices are compact dropdowns that show the current value, destructive confirmations stay explicit, and no direct USB access. Short hints stay visible and longer explanations open from an info button. The operation log is an overlay without scrim or container background that keeps its own input and scroll. Theme-provided vector icons fill the navigation bar.

### Core objects

- `UsbDisk`: exclusive device, sector size, and sector count.
- `Partition`: start LBA, sector count, type, flags, and selected filesystem engine.
- `VirtualPartition`: range-checked view that adds its start LBA exactly once.
- `MountedVolume`: open filesystem and engine. It must sync and close before raw disk mutation.
- `DiskOperation`: type, bytes, progress, cancellation, result, and structured error.
- `RescueMap`: copied, failed, and non-tried ranges. A partial image remains marked partial.

## 6. Device and service rules

- Initial I/O size is 64 KiB, split only as required by sector alignment, short USB transfers, or a request boundary.
- Logical block size comes from the device. Code must not assume 512 bytes.
- `READ CAPACITY(10)` and `READ/WRITE(10)` cover addresses they can represent. `READ CAPACITY(16)` and `READ/WRITE(16)` are required when capacity or offsets exceed the 32-bit command range.
- One component owns the USB connection. Operations on one disk are strictly serial.
- The long-running worker is a `connectedDevice` foreground service, backed by `UsbManager.requestPermission()`, notification permission, and a WakeLock held only during an operation.
- Disconnect, cancellation, completion, and process teardown release the interface, connection, and WakeLock.
- A disconnect ends the operation with its last durable state. The application must not silently continue on a reconnected device.
- `claimInterface(force = true)` is allowed, but it is not proof that Android has safely unmounted a filesystem. Raw access must still be explicit and confirmed.

## 7. Functional behavior

### Files

Show one tab per recognized partition, including VTOYEFI. EFI system partitions and VTOYEFI are write-protected: file writes, deletion, and formatting are refused until the user explicitly lifts the protection. That override is a write-protection policy, not a mount mode — it never changes how a volume is opened or mounted, and it is offered only for the EFI partition of the currently attached disk on the Ventoy screen. The override is bound to one exact partition (ID and geometry) and to one attachment: a partition refresh keeps it, while a different device, a new attach generation, a detach, or changed partition geometry clears it. Copy, delete, and rename bypass Android's mounted view by using the selected engine. Creation inside free space exposes both numeric LBA/sector fields and a range slider in whole MiB, with the same alignment and bounds validation in the reducer.

A file larger than the destination filesystem or free space supports is rejected before writing begins. Rename stays inside the current directory, validates the FAT entry name, and obeys the same write gates as delete. The rename intent carries an explicit binding (device identity incl. attach generation, partition ID, start LBA, sector count); the reducer refuses a stale binding and the host re-checks device, attach generation and geometry immediately before the engine call. Context actions on `.iso` are raw-write and Windows autoinstall setup.

### Ventoy

- Install is destructive. It requires the selected upstream release's matching assets and creates the partition layout through the adapted native flow.
- Update runs only when the existing layout is recognized as compatible. It must not format or recreate the ISO partition.
- MBR and GPT have different boot gaps. GPT backup placement and reserved space must follow the upstream implementation and selected options.
- 4Kn installation is refused when unsupported by the selected Ventoy version; it is not emulated with an untested layout.
- Windows setup writes or updates `/ventoy/ventoy.json` so the selected ISO references an accessible XML template. Existing JSON and user files require an explicit overwrite choice.
- Windows options are tri-state and preserve existing config by default: null leaves the current `ventoy.json` value untouched, true writes `1`, false writes an explicit `0`. One official `VTOY_WIN11_BYPASS_CHECK` option covers the Windows 11 checks (TPM, RAM, Secure Boot, CPU) together; the UI exposes one control (keep existing / bypass all / require all) and never implies per-check control. The local-account option uses the same tri-state rule. Upstream needs the network unplugged during setup.

### Interface and language

- Sections group related controls: a heading, an optional one-line subtitle, a hairline divider, and content. Only interactive rows keep a surface fill.
- The partition map strip and the header usage strip are absent when the disk has no partitions; an empty bar was indistinguishable from a clipped control.
- The operation log overlays the active tab without a scrim or background, swallows taps on its own bounds, keeps its scroll, and retains an accessible collapse action.
- Touch targets stay at least 48 dp; dialogs cap their width and scroll in landscape.
- All authored UI text comes from string resources (English default, Russian), including statuses, errors that the app authors, hints, and destructive confirmations. The in-app language switch is persisted and applied through a localized context, so the reducer stays Android-free (`UiMessage` keys, resolved in Compose).
- Engine and OS error text and raw log lines remain unlocalized technical data on purpose. User-facing operation titles are localized; the log keeps the raw label.

### Partitions and raw writing

Table commits, formatting, GPT repair and raw writes all go through the write-tracking block device wrapper, so a failure after the first write reports Partial instead of Failed.

When the primary GPT header/entries fail validation but the backup GPT is readable, the table is shown with a warning. Create, delete, role/boot edits and GPT repair are disabled with an explanation; Restore GPT stays enabled as the recovery path. Format, wipe, raw writes and Files operations stay available; the device is not presented as whole-disk read-only.

The partition screen also edits boot metadata: the selected entry can become an EFI system partition or a data partition, and its legacy bootable flag can be set or cleared. ESP is implemented as a real on-disk type change (GPT type GUID / MBR type byte), never as a UI-only flag. Write protection is conservative and independent of the current type: a real ESP type, an EFI type byte/GUID, or the VTOYEFI name stays protected even after the type changes. The UI shows the real type for role actions; a BASIC entry is written as Linux data and the previous type is not restored automatically. Every edit requires a destructive confirmation that pins the device identity and the exact partition ID, start LBA, and sector count; the host re-checks them against the live table before writing. A protected EFI entry needs the same explicit override as file writes.

The screen shows the table, free regions, and operations. Partition creation is allowed only inside free space. Formatting is a separate user-selected action, not an implicit side effect. Occupied-partition resize and movement are absent by design.

Boot and ESP flags are separate operations. GPT tail repair relocates and checksums the backup structures after a smaller image write; it does not resize a filesystem. Hybrid layouts are checked before repair and rejected when ambiguous.

Partition-table writes reject GPT entry sizes other than 128 bytes, invalid or overlapping GPT metadata, and MBR addresses or lengths that cannot be represented in 32 bits before writing. Wipe and GPT restore report partial results if an attempted write is followed by failure, cancellation, or disconnect.

Raw writing accepts local `.iso`, `.img`, `.gz`, and `.xz` input, decompresses supported streams on the fly, reports byte progress, and checks source and destination sizes before destructive writing. A Windows ISO written raw is not presented as a completed Windows installation disk.

### Diagnostics and backup

Read scans do not write. Wipe is separately confirmed with device identity and capacity. Fake-capacity testing and write benchmarks are out of plan and have no UI entry points.

Backup and restore use the image pipeline plus a rescue map. Unreadable ranges remain visible, and a partial image is never described as a verified full copy.

A RESTORE map records the target disk identity and geometry. Restore writes only the map's sector ranges; the disk outside them is not changed. Resume requires the same disk identity (a stick without a stable serial never resumes), an equal sector size, and a map that fits the disk; full disk geometry need not match. After that admission gate, every COPIED range is read back from the disk and compared with the bytes the selected image currently provides in the same pass; it is skipped only on an exact match, otherwise it is rewritten. COPY is refused while any range is NON_TRIED; FAILED ranges are restored from image bytes, and a backup usually writes zeros there. `COPIED` is recorded only after a successful write, and checkpoints are published only after sync, so an interrupted run reports partial and never claims completed over unverified sectors. A write command that lands bytes before failing is reported as partial, not as a clean failure.

After sync, the whole restored region is read back once more and compared byte for byte with a fresh full read of the image (the same check `RawWriter` applies to raw writes). `Completed` therefore means the disk equals that second read; it is not a snapshot-consistency proof, and a source modified during the operation is not guaranteed to be detected. The source must not be changed while a restore runs. Verification is skipped when the run aborted, did not cover the map, or already has `FAILED` sectors, so those outcomes keep their original cause.

Cost: resume reads the COPIED ranges back before skipping them (one read command per status run inside the normal 1 MiB batch), and every completed run pays one extra image pass plus one disk read of the restored region. A source backed by HTTP is therefore fetched twice — once to write, once to verify.

An immutable staged snapshot would remove the second read but cannot be provided by the existing staging mechanism: `ImageStaging` ownership is session-scoped (files are not managed, resumable or deletable after process death), the checkpoint has no reference to a staged file, decoded size is unknown for SAF/content images so free space cannot be preflighted, and app-private `filesDir` capacity is unrelated to the image size. Staging restore would need an adoptable manifest plus a cleanup lifecycle owned by MainActivity/UI; it is not implemented.

## 8. Safety invariants

1. Every destructive operation requires confirmation containing device identity and capacity.
2. Filesystem volumes are synchronized and closed before partition-table or raw-disk mutation.
3. Partition bounds and integer overflow are checked before a sector reaches an engine.
4. The same partition offset is never applied twice.
5. Ventoy update refuses an unrecognized disk.
6. Existing configuration and user data are never overwritten silently.
7. Disconnect and cancellation leave a truthful partial state.
8. Partial rescue output retains its map and partial status.

## 9. Build and verification

The project is greenfield and uses Kotlin, Jetpack Compose, Android USB Host APIs, and the NDK. The first implementation pass defines the exact Gradle test, build, lint, and typecheck tasks and records them in the project conventions. Native libraries and APK packaging must meet Android's 16 KB page-size requirements; a linker flag alone is not evidence.

Implementation order:

1. SCSI disk, partition reading, and exclusive operation execution.
2. JNI filesystem mounts and real file operations.
3. Ventoy install/update and streaming raw write.
4. Diagnostics, wipe, backup, and restore. Fake-capacity testing, write benchmarks, and HTTPS image URLs are not planned.

Required verification:

- Unit tests for partition bounds, GPT checksum/relocation, operation ordering, and rescue-map transitions.
- Filesystem image tests for FAT/exFAT, NTFS, and EXT read/write behavior, including a file larger than 4 GiB where supported.
- Fault-injection tests for short USB transfer, STALL recovery, cancellation, and disconnect.
- One controlled physical USB test for files, Ventoy installation, raw write, backup, and restore.
- Native library and APK page-size verification.
- Ventoy boot and Windows installation checks on a PC are separate acceptance tests. Android tests cannot claim those results.

## 10. Documentation and source obligations

The repository includes GPL notices, upstream copyright notices, and the source or source offers required by all linked GPL components. Third-party code stays in `third_party/` and is separated from JNI adapters and application changes.

## 11. Self-review

- No placeholder requirements or deferred functional decisions.
- NTFS and EXT support are mandatory. lwext4 is the selected engine, not an optional feature that can be removed.
- No claimed behavior depends on root, SAF, UAS, or automatic reconnect.
- Destructive and non-destructive paths are explicitly separated.
- Verification distinguishes Android behavior from PC boot behavior.
