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
| Fake-capacity testing | [F3](https://github.com/AltraMayor/f3), GPL-3.0 | Port the real probe algorithm and replace its Linux device backend |
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
- Write raw `.iso`, `.img`, `.gz`, and `.xz` images from local files or HTTP URLs.
- Run fake-capacity, throughput, bad-sector, wipe, backup, and restore operations.

### Out of scope

- Root, custom kernels, loop devices, FUSE mounts, and SAF-based USB access.
- USB Attached SCSI (UAS) and every non-BOT transport.
- Moving, shrinking, or expanding occupied partitions and their filesystems.
- A new NTFS, exFAT, EXT, Ventoy, F3, or ddrescue implementation.
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
    FatFs | libntfs-3g | lwext4 | raw image | F3 | ddrescue
                    |
      BOT/SCSI(10/16), 64 KiB transfers, STALL recovery
                    |
            Android UsbDeviceConnection
```

### Modules

- `device`: permission, connection ownership, capacity, logical block size, SCSI, recovery, and disconnection.
- `partition`: MBR/GPT decode and modification, alignment, flags, free regions, protective/hybrid handling, and GPT backup relocation.
- `fs`: JNI facades. Filesystem metadata and allocation remain inside upstream engines.
- `image`: local and HTTP sources, gzip/XZ decoding, progress, and cancellation.
- `ventoy`: adapted upstream install/update flow plus JSON/XML integration.
- `diagnostics`: F3, throughput, read scan, wipe, and rescue-map backup/restore.
- `service`: one operation queue, foreground notification, WakeLock, and persisted operation state.
- `ui`: four screens, explicit destructive confirmations, and no direct USB access.

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

Show one tab per recognized partition, including VTOYEFI. VTOYEFI is read-only until the user explicitly enables writing. Copy, delete, and rename bypass Android's mounted view by using the selected engine.

A file larger than the destination filesystem or free space supports is rejected before writing begins. Context actions on `.iso` are raw-write and Windows autoinstall setup.

### Ventoy

- Install is destructive. It requires the selected upstream release's matching assets and creates the partition layout through the adapted native flow.
- Update runs only when the existing layout is recognized as compatible. It must not format or recreate the ISO partition.
- MBR and GPT have different boot gaps. GPT backup placement and reserved space must follow the upstream implementation and selected options.
- 4Kn installation is refused when unsupported by the selected Ventoy version; it is not emulated with an untested layout.
- Windows setup writes or updates `/ventoy/ventoy.json` so the selected ISO references an accessible XML template. Existing JSON and user files require an explicit overwrite choice.
- TPM, RAM, and local-account options are separate template options. Their effect is version-dependent and is not guaranteed.

### Partitions and raw writing

The screen shows the table, free regions, and operations. Partition creation is allowed only inside free space. Formatting is a separate user-selected action, not an implicit side effect. Occupied-partition resize and movement are absent by design.

Boot and ESP flags are separate operations. GPT tail repair relocates and checksums the backup structures after a smaller image write; it does not resize a filesystem. Hybrid layouts are checked before repair and rejected when ambiguous.

Raw writing accepts local or HTTP `.iso`, `.img`, `.gz`, and `.xz` input, decompresses supported streams on the fly, reports byte progress, and checks source and destination sizes before destructive writing. A Windows ISO written raw is not presented as a completed Windows installation disk.

### Diagnostics and backup

Read scans do not write. Fake-capacity testing, write benchmarks, and wipe are separately confirmed with device identity and capacity. Fake-capacity mode uses the real F3 algorithm, not merely writes at powers of two.

Backup and restore use the image pipeline plus a rescue map. Unreadable ranges remain visible, and a partial image is never described as a verified full copy.

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
4. Diagnostics, wipe, backup, and restore.

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
