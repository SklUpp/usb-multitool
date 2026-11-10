# Android USB Multitool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a sideloaded GPL-3.0 Android USB mass-storage multitool with direct file management, Ventoy, partitions, raw imaging, and diagnostics.

**Architecture:** One exclusive `UsbDisk` feeds a range-checked `VirtualPartition`; filesystem engines, raw imaging, Ventoy, and diagnostics all use that boundary. One `OperationExecutor` serializes disk work inside a `connectedDevice` foreground service.

**Tech Stack:** Kotlin, Jetpack Compose, Android USB Host API, NDK/CMake, JUnit 4, MockK, kotlinx-coroutines-test, FatFs R0.16, libntfs-3g, lwext4, Ventoy, F3, GNU ddrescue, Apache Commons Compress XZ.

## Global Constraints

- Application license: GPL-3.0-or-later.
- No root, custom kernel, loop device, FUSE mount, SAF USB access, or UAS.
- Distribution is sideloaded; Google Play approval is not a goal.
- `MANAGE_EXTERNAL_STORAGE` is required for local shared-storage images and backups.
- USB permission uses `UsbManager.requestPermission()`.
- Initial transfer size is 64 KiB.
- Device logical block size is authoritative; never assume 512 bytes.
- SCSI(16) is mandatory above the 32-bit LBA range.
- One owner and one serial operation queue per disk.
- Foreground service type is `connectedDevice`.
- WakeLock exists only while an operation runs.
- Disconnect never auto-resumes.
- Filesystems sync and close before raw disk mutation.
- Partition offset is applied exactly once.
- Destructive confirmation includes device identity and capacity.
- Existing user data and JSON are never overwritten silently.
- Partial rescue output remains marked partial.
- No occupied partition move, shrink, or expand.
- No new NTFS, exFAT, EXT, Ventoy, F3, or ddrescue engine.
- Android tests must not claim PC boot or Windows installation success.
- Third-party source stays under `third_party/`.
- Native libraries and APK packaging must pass 16 KB page-size verification.

---

### Task 1: Project Skeleton and GitHub Actions Verification

**Files:**
- Create: `usb-multitool/settings.gradle.kts`
- Create: `usb-multitool/build.gradle.kts`
- Create: `usb-multitool/gradle/libs.versions.toml`
- Create: `usb-multitool/gradlew`
- Create: `usb-multitool/gradle/wrapper/gradle-wrapper.properties`
- Create: `usb-multitool/gradle/wrapper/gradle-wrapper.jar`
- Create: `usb-multitool/app/build.gradle.kts`
- Create: `usb-multitool/app/src/main/AndroidManifest.xml`
- Create: `usb-multitool/.github/workflows/android-debug.yml`
- Create: `usb-multitool/CONVENTIONS.md`
- Test: `usb-multitool/app/src/test/kotlin/usb/SkeletonTest.kt`

**Interfaces:**
- Consumes: approved design specification.
- Produces: GitHub Actions commands `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`, `./gradlew :app:lintDebug`, and `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 1: Write the failing test**

```kotlin
package usb

import org.junit.Assert.assertEquals
import org.junit.Test

class SkeletonTest {
    @Test fun licenseIsGpl3() {
        assertEquals("GPL-3.0-or-later", BuildFacts.LICENSE)
    }
}
```

- [ ] **Step 2: Run the lightweight local check**

Run: `sh -n gradlew || true`
Expected: FAIL because `gradlew` does not exist yet. Do not invoke Gradle.

- [ ] **Step 3: Write minimal implementation**

Create the Android Gradle project and committed wrapper using `compileSdk = 36`, `targetSdk = 36`, and `minSdk = 28`. Add Kotlin, Compose, JUnit, MockK, coroutines-test, and NDK configuration. Declare USB host, notifications, foreground connected device, WakeLock, and all-files access in the manifest. Put `const val LICENSE = "GPL-3.0-or-later"` in `BuildFacts`.

Create `.github/workflows/android-debug.yml` on `ubuntu-latest`. It sets up Java 21, enables KVM for an Android emulator, installs the SDK packages required by the Gradle files, and runs:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:compileDebugKotlin
```

Upload the debug APK and test reports. In `CONVENTIONS.md`, state that Gradle, NDK, emulator, and instrumentation commands run only in GitHub Actions. Local checks are file, script, and static validation only.

- [ ] **Step 4: Verify without a phone build**

Run:

```bash
sh -n gradlew
git grep -n "compileSdk = 36" -- app/build.gradle.kts
git grep -n "connectedDevice" -- app/src/main/AndroidManifest.xml
git grep -n "actions/setup-java" -- .github/workflows/android-debug.yml
```

Expected: shell syntax passes and all three strings are present.

- [ ] **Step 5: Commit**

```bash
git add usb-multitool
git commit -m "build: create Android project and CI verification"
```

After commit, the controller starts the workflow with `gh workflow run` or an authenticated push and records its run URL. A CI failure returns to this task and does not authorize a local Gradle fallback.

### Task 2: Block Geometry and SCSI Command Selection

**Files:**
- Create: `usb-multitool/app/src/main/kotlin/usb/device/BlockGeometry.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/device/ScsiCommand.kt`
- Test: `usb-multitool/app/src/test/kotlin/usb/device/ScsiCommandTest.kt`

**Interfaces:**
- Consumes: sector size and sector count.
- Produces: `BlockGeometry(sectorSize: Int, sectorCount: ULong)`, `ScsiCommand.select(lba: ULong, blocks: Int): ScsiWidth`, and `TransferPlan.plan(bytes: Long, sectorSize: Int): List<Int>`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun largeLbaUsesSixteenByteCommand() {
    val width = ScsiCommand.select(lba = 1UL shl 32, blocks = 1)
    assertEquals(ScsiWidth.CDB16, width)
}

@Test fun transferPlanStartsAt64KiBAndAlignsRemainder() {
    val parts = TransferPlan.plan(bytes = 70L * 1024L, sectorSize = 4096)
    assertEquals(listOf(64 * 1024, 6 * 1024), parts)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.device.ScsiCommandTest`
Expected: FAIL with unresolved `ScsiCommand` and `TransferPlan`.

- [ ] **Step 3: Write minimal implementation**

Use CDB10 only when LBA and block count fit its fields; otherwise use CDB16. Reject non-positive or non-power-of-two sector sizes outside 512..4096. Split transfers into 64 KiB chunks and one aligned final chunk.

- [ ] **Step 4: Run test to verify it passes**

Run the same Gradle command.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/device app/src/test/kotlin/usb/device
git commit -m "feat: define SCSI width and transfer planning"
```

### Task 3: Exclusive Disk Session and Fault Recovery

**Files:**
- Create: `usb-multitool/app/src/main/kotlin/usb/device/UsbDisk.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/device/BotTransport.kt`
- Test: `usb-multitool/app/src/test/kotlin/usb/device/UsbDiskTest.kt`

**Interfaces:**
- Consumes: `BlockGeometry`, `TransferPlan`, and a `BlockTransport` test double.
- Produces: `UsbDisk.read(lba: ULong, destination: ByteBuffer)`, `UsbDisk.write(lba: ULong, source: ByteBuffer)`, `DiskException.Disconnected`, and `Recovery.bulkOnlyReset()`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun stallRetriesOnceThenReads() {
    val transport = FakeTransport(stallsBeforeSuccess = 1)
    val disk = UsbDisk(transport, BlockGeometry(512, 100UL))
    val out = ByteBuffer.allocate(512)
    disk.read(4UL, out)
    assertEquals(1, transport.resetCount)
    assertEquals(512, out.position())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.device.UsbDiskTest`
Expected: FAIL with unresolved disk types.

- [ ] **Step 3: Write minimal implementation**

Implement BOT command, data, and status phases over the transport boundary. On STALL, clear endpoint halt, read sense, run Bulk-Only Mass Storage Reset once, and retry once. A second failure or a transport disconnect throws `DiskException` and marks the session closed.

- [ ] **Step 4: Run test to verify it passes**

Run the same Gradle command.
Expected: PASS, including short-read, disconnect, and no-second-reset tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/device app/src/test/kotlin/usb/device
git commit -m "feat: recover stalled BOT transfers once"
```

### Task 4: Partition Bounds and Virtual Views

**Files:**
- Create: `usb-multitool/app/src/main/kotlin/usb/partition/VirtualPartition.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/partition/PartitionTable.kt`
- Test: `usb-multitool/app/src/test/kotlin/usb/partition/VirtualPartitionTest.kt`

**Interfaces:**
- Consumes: `UsbDisk.read/write`.
- Produces: `VirtualPartition(startLba: ULong, sectorCount: ULong, disk: UsbDisk)`, `Partition`, and `FreeRegion`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun offsetIsAppliedOnceAndBoundsRejectOverflow() {
    val disk = RecordingDisk(sectorCount = 10UL)
    val part = VirtualPartition(2UL, 3UL, disk)
    part.read(1UL, ByteBuffer.allocate(512))
    assertEquals(listOf(3UL), disk.reads)
    assertFailsWith<IndexOutOfBoundsException> {
        part.read(3UL, ByteBuffer.allocate(512))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.partition.VirtualPartitionTest`
Expected: FAIL with unresolved partition types.

- [ ] **Step 3: Write minimal implementation**

Check LBA, count, addition, and disk bounds before delegation. Store the absolute start only in `VirtualPartition`; no engine receives a pre-adjusted LBA.

- [ ] **Step 4: Run test to verify it passes**

Run the same Gradle command.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/partition app/src/test/kotlin/usb/partition
git commit -m "feat: enforce one-time partition offsets"
```

### Task 5: MBR and GPT Parsing

**Files:**
- Create: `usb-multitool/app/src/main/kotlin/usb/partition/MbrTable.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/partition/GptTable.kt`
- Test: `usb-multitool/app/src/test/kotlin/usb/partition/PartitionTableTest.kt`
- Test fixture: `usb-multitool/app/src/test/resources/partition/mbr-gpt.bin`

**Interfaces:**
- Consumes: `UsbDisk.read`.
- Produces: `PartitionTable.read(disk: UsbDisk): List<Partition>`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun gptProtectiveMbrDoesNotHideEntries() {
    val disk = DiskFixture.load("partition/mbr-gpt.bin")
    val partitions = PartitionTable.read(disk)
    assertEquals(listOf("EFI", "DATA"), partitions.map { it.name })
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.partition.PartitionTableTest`
Expected: FAIL with unresolved parser.

- [ ] **Step 3: Write minimal implementation**

Parse MBR and primary GPT, verify signature, entry size, CRC32, and bounds. Return every usable entry, including VTOYEFI. Reject overlapping entries.

- [ ] **Step 4: Run test to verify it passes**

Run the same Gradle command.
Expected: PASS for MBR, GPT, empty, overlap, and bad-CRC fixtures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/partition app/src/test/kotlin/usb/partition app/src/test/resources/partition
git commit -m "feat: parse MBR and GPT partition tables"
```

### Task 6: Operation Queue and Foreground Ownership

**Files:**
- Create: `usb-multitool/app/src/main/kotlin/usb/service/OperationExecutor.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/service/DiskService.kt`
- Modify: `usb-multitool/app/src/main/AndroidManifest.xml`
- Test: `usb-multitool/app/src/test/kotlin/usb/service/OperationExecutorTest.kt`

**Interfaces:**
- Consumes: `UsbDisk` and suspending disk operations.
- Produces: `OperationExecutor.submit(operation: DiskOperation)`, `DiskOperation`, and `OperationState` values `Queued`, `Running`, `Completed`, `Cancelled`, `Failed`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun secondOperationWaitsAndDisconnectStopsBoth() = runTest {
    val executor = OperationExecutor(this)
    val first = executor.submit(blockingOperation())
    val second = executor.submit(markerOperation())
    executor.onDisconnected()
    assertEquals(OperationState.Failed(DiskException.Disconnected()), first.await())
    assertEquals(OperationState.Failed(DiskException.Disconnected()), second.await())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.service.OperationExecutorTest`
Expected: FAIL with unresolved executor.

- [ ] **Step 3: Write minimal implementation**

Use one single-thread dispatcher. Start the foreground service on the first queued operation and stop it when idle. Acquire WakeLock immediately before execution and release it in `finally`. Cancellation is cooperative through `DiskOperation.isActive()`.

- [ ] **Step 4: Run test to verify it passes**

Run the same Gradle command.
Expected: PASS for ordering, cancellation, disconnect, and WakeLock release.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/service app/src/test/kotlin/usb/service app/src/main/AndroidManifest.xml
git commit -m "feat: serialize disk operations in one service"
```

### Task 7: Native Filesystem Boundary

**Files:**
- Create: `usb-multitool/app/src/main/cpp/CMakeLists.txt`
- Create: `usb-multitool/app/src/main/cpp/fs_bridge.c`
- Create: `usb-multitool/app/src/main/kotlin/usb/fs/Filesystem.kt`
- Create: `usb-multitool/third_party/README.md`
- Test: `usb-multitool/app/src/test/kotlin/usb/fs/FilesystemContractTest.kt`

**Interfaces:**
- Consumes: `VirtualPartition`.
- Produces: ~~`Filesystem.mount` / `MountedVolume` / `VolumeFile`~~ (superseded by `PartitionVolumeSession` / `MountedFilesVolume`); `FilesystemId` values `FAT`, `EXFAT`, `NTFS`, `EXT`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun mountSelectsEngineFromProbe() {
    val volume = Filesystem.mount(FatImage.partition())
    assertEquals(FilesystemId.FAT, volume.id)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.fs.FilesystemContractTest`
Expected: FAIL with unresolved filesystem facade.

- [ ] **Step 3: Write minimal implementation**

Vendor exact upstream revisions into `third_party/fatfs`, `third_party/ntfs-3g`, and `third_party/lwext4`, with notices and update URLs. Bridge `disk_read`, `disk_write`, NTFS `pread/pwrite`, and lwext4 block callbacks to one JNI partition boundary. Probe in FAT, exFAT, NTFS, EXT order and expose one Kotlin volume API.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest :app:externalNativeBuildDebug`
Expected: contract PASS and native build exit 0.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp app/src/main/kotlin/usb/fs app/src/test/kotlin/usb/fs third_party
git commit -m "feat: bridge FAT NTFS and EXT engines"
```

### Task 8: Real File Operations and Large-File Guard

**Files:**
- Create: `usb-multitool/app/src/main/kotlin/usb/fs/FileOperations.kt`
- Test: `usb-multitool/app/src/androidTest/kotlin/usb/fs/FilesystemImageTest.kt`
- Test fixture: `usb-multitool/app/src/androidTest/assets/fs/`

**Interfaces:**
- Consumes: `MountedVolume`.
- Produces: `FileOperations.list/copy/delete/rename`.

- [ ] **Step 1: Write the failing instrumentation test**

```kotlin
@Test fun fat32RejectsFileAboveFourGiBBeforeWrite() {
    val volume = mountAsset("fs/fat32.img")
    val source = FakeFile(size = 4L * 1024L * 1024L * 1024L + 1L)
    assertFailsWith<FileTooLargeException> {
        FileOperations.copy(source, volume.root)
    }
    assertEquals(0, volume.root.count())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=usb.fs.FilesystemImageTest`
Expected: FAIL until image fixtures and copy guard exist.

- [ ] **Step 3: Write minimal implementation**

Implement list, copy, delete, and rename against each engine. Check destination maximum file size and free space before creating a destination file. Preserve source data on failure.

- [ ] **Step 4: Run test to verify it passes**

Run the instrumentation command.
Expected: PASS for FAT32, exFAT, NTFS, and EXT, including a file larger than 4 GiB on capable filesystems.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/fs app/src/androidTest
git commit -m "test: verify real filesystem file operations"
```

### Task 9: Partition Mutation and Formatting

**Files:**
- Create: `usb-multitool/app/src/main/kotlin/usb/partition/PartitionEditor.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/fs/Formatter.kt`
- Test: `usb-multitool/app/src/test/kotlin/usb/partition/PartitionEditorTest.kt`

**Interfaces:**
- Consumes: `PartitionTable`, `MountedVolume`, and native format entry points.
- Produces: `PartitionEditor.create/delete/setFlags/repairGptTail` and `Formatter.format(partition: VirtualPartition, id: FilesystemId)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun createUsesFreeSpaceWithoutMovingNeighbors() {
    val before = fixture("between-partitions")
    val after = PartitionEditor.create(before, size = 2048UL, type = PartitionType.BASIC)
    assertEquals(before.partitions, after.partitions.take(2))
    assertEquals(2048UL, after.partitions.last().sectorCount)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.partition.PartitionEditorTest`
Expected: FAIL with unresolved editor.

- [ ] **Step 3: Write minimal implementation**

Create only inside a free region and align to 1 MiB. Delete by table entry only. Flags change boot/ESP independently. GPT repair rewrites the backup header and entries at the current disk end, recalculates CRC32, and refuses hybrid ambiguity. Formatting closes volumes first and calls the selected upstream formatter.

- [ ] **Step 4: Run test to verify it passes**

Run the same Gradle command.
Expected: PASS for MBR, GPT, no-free-space, flags, hybrid refusal, and tail repair.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/partition app/src/main/kotlin/usb/fs app/src/test/kotlin/usb/partition
git commit -m "feat: edit free space and repair GPT tail"
```

### Task 10: Image Sources and Streaming Raw Write

**Files:**
- Create: `usb-multitool/app/src/main/kotlin/usb/image/ImageSource.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/image/RawWriter.kt`
- Test: `usb-multitool/app/src/test/kotlin/usb/image/RawWriterTest.kt`

**Interfaces:**
- Consumes: `UsbDisk`, `OperationExecutor`, and Java/XZ streams.
- Produces: `ImageSource.open(uri: Uri): CountedInputStream` and `RawWriter.write(source: ImageSource, disk: UsbDisk)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun compressedSourceWritesDecodedBytesAndStopsAtCapacity() {
    val source = ImageSource.fromBytes(gzip(ByteArray(6000) { 7 }))
    val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
    assertFailsWith<ImageTooLargeException> { RawWriter.write(source, disk) }
    assertEquals(0, disk.bytesWritten)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.image.RawWriterTest`
Expected: FAIL with unresolved image types.

- [ ] **Step 3: Write minimal implementation**

Support file and HTTP URLs, `.iso`, `.img`, `.gz`, and `.xz`. Decode compressed input before size acceptance when its decoded size is known; otherwise enforce the disk limit before each write and commit no sector after rejection. Report bytes through `DiskOperation`.

- [ ] **Step 4: Run test to verify it passes**

Run the same Gradle command.
Expected: PASS for all four formats, HTTP cancellation, and exact final partial sector.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/image app/src/test/kotlin/usb/image
git commit -m "feat: stream local and remote disk images"
```

### Task 11: Ventoy Install, Update, and Windows Setup

**Files:**
- Create: `usb-multitool/app/src/main/kotlin/usb/ventoy/VentoyLayout.kt`
- Create: `usb-multitool/app/src/main/cpp/ventoy_bridge.c`
- Create: `usb-multitool/third_party/ventoy/README.md`
- Test: `usb-multitool/app/src/test/kotlin/usb/ventoy/VentoyLayoutTest.kt`

**Interfaces:**
- Consumes: `UsbDisk`, `PartitionTable`, `MountedVolume`, and upstream Ventoy assets.
- Produces: `VentoyInstaller.install/update` and `WindowsSetup.write(isoName: String, options: SetupOptions, overwrite: Boolean)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun updateRejectsUnknownDiskWithoutWriting() {
    val disk = RecordingDisk.fromAsset("ventoy/plain-fat.img")
    assertFailsWith<IncompatibleVentoyException> {
        VentoyInstaller.update(disk, assets)
    }
    assertEquals(0, disk.bytesWritten)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.ventoy.VentoyLayoutTest`
Expected: FAIL with unresolved installer.

- [ ] **Step 3: Write minimal implementation**

Vendor one exact Ventoy release and its matching assets. Adapt upstream native install/update code to `UsbDisk`; do not copy layout constants into a replacement installer. Preserve MBR/GPT differences, reserved space, and 4Kn refusal. Update writes JSON and XML only after explicit overwrite permission.

- [ ] **Step 4: Run test to verify it passes**

Run the same Gradle command.
Expected: PASS for install, compatible update, unrecognized refusal, JSON merge, and overwrite protection.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/ventoy app/src/main/cpp/ventoy_bridge.c app/src/test/kotlin/usb/ventoy third_party/ventoy
git commit -m "feat: adapt upstream Ventoy install and update"
```

### Task 12: Diagnostics, Wipe, and Rescue Maps

**Files:**
- Create: `usb-multitool/app/src/main/kotlin/usb/diagnostics/RescueMap.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/diagnostics/Diagnostics.kt`
- Create: `usb-multitool/app/src/main/cpp/f3_bridge.c`
- Create: `usb-multitool/app/src/main/cpp/ddrescue_bridge.c`
- Test: `usb-multitool/app/src/test/kotlin/usb/diagnostics/RescueMapTest.kt`

**Interfaces:**
- Consumes: `UsbDisk`, `ImageSource`, and upstream F3/ddrescue code.
- Produces: `RescueMap`, `Diagnostics.fakeCapacity/readScan/writeBenchmark/wipe`, and `Rescue.copy/restore`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun failedRangeRemainsPartialAfterResume() {
    val map = RescueMap.whole(100UL)
    map.markFailed(10UL, 2UL)
    map.markCopied(0UL, 10UL)
    assertTrue(map.partial)
    assertEquals(listOf(10UL until 12UL), map.failed)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.diagnostics.RescueMapTest`
Expected: FAIL with unresolved rescue types.

- [ ] **Step 3: Write minimal implementation**

Port F3probe behavior and ddrescue map transitions without rewriting their algorithms. Read scan never writes. Wipe writes zeros only after confirmation. Backup and restore persist the map beside partial output; a RESTORE checkpoint resumes only on the same disk identity and geometry, and every COPIED range is read back from the disk and compared with the selected image before it is skipped. Legacy maps stay readable but never resume without a stable destination identity.

- [ ] **Step 4: Run test to verify it passes**

Run the same Gradle command.
Expected: PASS for non-tried, copied, failed, verified resume, stale-content rewrite, replaced-disk rewrite, mutable-source verify failure, verification-read failure, and partial-output states.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/diagnostics app/src/main/cpp app/src/test/kotlin/usb/diagnostics third_party
git commit -m "feat: add diagnostics and resumable rescue maps"
```

### Task 13: Four-Screen UI and Destructive Confirmation

**Files:**
- Create: `usb-multitool/app/src/main/kotlin/usb/ui/AppShell.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/ui/FilesScreen.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/ui/VentoyScreen.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/ui/PartitionsScreen.kt`
- Create: `usb-multitool/app/src/main/kotlin/usb/ui/DiagnosticsScreen.kt`
- Test: `usb-multitool/app/src/test/kotlin/usb/ui/ConfirmationTest.kt`

**Interfaces:**
- Consumes: all operation producers from Tasks 6–12.
- Produces: Compose navigation among four screens and `DestructiveConfirmation`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun destructiveActionIncludesIdentityAndCapacity() {
    val request = DestructiveConfirmation.request(device, DestructiveAction.WIPE)
    assertTrue(request.message.contains(device.identity))
    assertTrue(request.message.contains(device.capacityLabel))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.ui.ConfirmationTest`
Expected: FAIL with unresolved confirmation type.

- [ ] **Step 3: Write minimal implementation**

Build four screens over one app state. Files shows partition tabs, VTOYEFI protection, and ISO actions. Every destructive action passes through confirmation. Disable actions while `OperationState.Running` exists. Show byte progress, speed, and truthful completion or failure states.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: confirmation and state tests PASS; debug APK builds.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/ui app/src/test/kotlin/usb/ui
git commit -m "feat: add four USB tool screens"
```

### Task 14: End-to-End Verification and Source Notices

**Files:**
- Create: `usb-multitool/NOTICE.md`
- Create: `usb-multitool/docs/physical-acceptance.md`
- Modify: `usb-multitool/CONVENTIONS.md`
- Test: `usb-multitool/app/src/androidTest/kotlin/usb/EndToEndFaultTest.kt`

**Interfaces:**
- Consumes: the completed application.
- Produces: recorded commands, third-party notices, and physical acceptance checklist.

- [ ] **Step 1: Write the failing fault test**

```kotlin
@Test fun disconnectDuringWriteKeepsPartialTruthfulState() {
    val result = runRawWrite(disconnectAfterSectors = 4UL)
    assertEquals(OperationState.Failed(DiskException.Disconnected()), result.state)
    assertEquals(4UL, result.sectorsCommitted)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=usb.EndToEndFaultTest`
Expected: FAIL until fault injection is connected through the service.

- [ ] **Step 3: Write minimal implementation**

Connect the test transport to the service boundary. Add GPL and upstream notices. Record unit, instrumentation, native, lint, typecheck, and page-size commands. Write the physical checklist for files, Ventoy, raw write, backup, and restore; label PC boot as a separate manual result.

- [ ] **Step 4: Run all verification**

Run the commands recorded in `CONVENTIONS.md`, then run 16 KB ELF and APK alignment checks.
Expected: all automated commands exit 0. Physical and PC checks remain explicitly unchecked until performed.

- [ ] **Step 5: Commit**

```bash
git add NOTICE.md docs/physical-acceptance.md CONVENTIONS.md app/src/androidTest/kotlin/usb/EndToEndFaultTest.kt
git commit -m "test: verify faults notices and acceptance limits"
```

### Task 15: Creation Names, Filesystem Choice, and Section Pass

**Files:**
- Modify: `usb-multitool/app/src/main/kotlin/usb/ui/PartitionsScreen.kt`
- Modify: `usb-multitool/app/src/main/kotlin/usb/ui/FilesScreen.kt`
- Modify: `usb-multitool/app/src/main/kotlin/usb/ui/VentoyScreen.kt`
- Modify: `usb-multitool/app/src/main/kotlin/usb/ui/DiagnosticsScreen.kt`
- Modify: `usb-multitool/app/src/main/kotlin/usb/ui/AppShell.kt`
- Modify: `usb-multitool/app/src/main/res/values/strings.xml`, `values-ru/strings.xml`
- Test: `usb-multitool/app/src/test/kotlin/usb/ui/PartitionNameCreationTest.kt`

**Interfaces:**
- Consumes: `PartitionName`, `VolumeLabel`, `FormatCatalog.supportsVolumeLabel`, `UiIntent.RequestCreatePartition(startLba, sectorCount, name, formatId, volumeLabel)`, `UiIntent.RequestRenamePartition(name)`, `AppUiState.partitionScheme`.
- Produces: one confirmed create action that can format and carry a GPT name; a GPT-only rename action; a volume-label rename action only where the engine has a setter; a separate confirmed MBR/GPT table initialization; LBA start–end rows; compact sections with the header icons outside the header block.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun createConfirmationPinsFilesystemAndGptName() {
    val state = ready(partitionScheme = TableScheme.GPT).copy(freeRegions = listOf(FreeRegionUi(2048UL, 4096UL)))
    val pending = UiReducer
        .reduce(state, UiIntent.RequestCreatePartition(2048UL, 2048UL, name = "WORK", formatId = FilesystemId.NTFS, volumeLabel = "WORK"))
        .state.pendingConfirmation
    assertEquals("WORK", pending?.createName)
    assertEquals(FilesystemId.NTFS, pending?.createFormatId)
    assertEquals("WORK", pending?.volumeLabel)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests usb.ui.PartitionNameCreationTest`
Expected: FAIL — the intent and confirmation do not carry name or filesystem yet.

- [ ] **Step 3: Write minimal implementation**

Validate the GPT name (36 UTF-16 code units, no control characters) and the NTFS-only volume label in the reducer, pin both plus the filesystem in the confirmation, and format only after the table commit succeeds; report `Partial` when mkfs fails after the commit. Add a GPT-only rename request with the ambiguity, damaged-table and geometry guards, refusing protected ESP/VTOYEFI entries. Add a table-scheme section (current scheme, MBR/GPT target, its own confirmation) that is also shown for a table with partitions, warning that every entry is erased and repeating the partition count; disable it when the scheme already matches, the layout is ambiguous, the primary GPT is damaged or the table cannot be read. In the screens: hide create-name and rename on MBR, offer the filesystem choice (including “do not format”) and the NTFS label in the create dialog, show LBA start–end for free regions, partitions and the selected entry, keep the range slider outside the scrolling block, centre the info glyph and move long policies into the section-heading help button, and collapse the empty partitions/files states into one statement plus one action.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'usb.ui.*'`
Expected: PASS for name/label validation, confirmation pinning, MBR refusal, protected-rename refusal, and the existing section tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/usb/ui app/src/main/res app/src/test/kotlin/usb/ui
git commit -m "feat: name partitions and group screens by section"
```

### Task 16: Filesystem Space Measurement

- Consumes: Task 7 native boundary, Task 8 file operations, Task 13 UI state.
- Produces: `usb.fs.FilesystemSpace`, `FilesystemUsage`, `FilesystemUsageKey`, `FilesystemUsageSnapshot`; `UsbFsUsageVolume.space()` on FatVolume/ExtVolume/NtfsVolume; native `*_engine_usage` and JNI `nativeUsage`; `FilesystemUsageProbe.measure` / `measureLocked` on the IO dispatcher; `AppUiState.filesystemUsage` + `UiIntent.RequestFilesystemUsage`/`FilesystemUsageResulted` wiring in the reducer and host.

Steps:

- [ ] Query free/total bytes for FAT/exFAT (`f_getfree`), EXT (`ext4_mount_point_stats`) and NTFS (`$Bitmap` free bits) without mounting, allocating or writing.
- [ ] Return `Unavailable` (unsupported filesystem, missing engine, closed volume, I/O failure) instead of zero or a stale value; never derive filesystem free space from partition-table free regions.
- [ ] Bind the snapshot to attach generation, device identity, partition geometry and filesystem; render only when the key matches the selected partition.
- [ ] Call the query only from the serialized volume/operation path, on the IO dispatcher, never from the Compose main thread.
- [ ] Tests: `usb.fs.FilesystemUsageTest` for known/unknown mapping, stale-key rejection and off-caller-thread execution; host assertions in `ext_host_test.c` and `ntfs_host_test.c`.

### Task 17: Raw Range Write UI

- Consumes: `usb.partition.RawRangePlan`, `usb.partition.RawRangeGeometry`, `usb.image.RangeProbe`, `usb.image.RawWriteResult`.
- Produces: `usb/ui/RawPlanMap.kt`; the raw card in `PartitionsScreen.kt`; the readback line in `AppShell.kt`; strings `raw_*`.

Steps:

- [x] One write tool, not two: start and size fields default to `0` and `diskSectorCount`, the end sector and byte size are read-only, and the single action sends `RequestWriteRawRange(start, count)`, which builds the confirmation itself.
- [x] Validate with `RawRangePlan.check` on every keystroke; list every `RawRangeProblem`; show `UnalignedStart` and `SchemeNotTrusted` warnings; leave `OverlapsObjects` to the map so the same facts are not printed twice.
- [x] Draw the disk map from `geometry.objects` with range handles that snap to `snapPoints` only while the scheme is confirmed, keep typing exact, list every overlap as text, and speak a summary from the canvas.
- [x] Print one line under the map when `metadataComplete` is false, choosing the sentence from `metadataProblem` (hybrid, unknown backup GPT, or neutral), and keep the write enabled.
- [x] Require a probe whose `matches(draft, diskSectorCount, sectorSize, attachment)` holds before the write arms; keep all three findings confirmable and never surface `detail`.
- [x] Render readback honestly: verified (whole range only when `writtenBytes == range.byteLength`), mismatch with count and first LBA, incomplete with its reason, unverified with the attempted subrange.
- [ ] Manual check on device: handle drag does not scroll the page, drag does not get cancelled by recomposition, and TalkBack reads the summary and the overlap lines.

### Task 18: Ventoy Autounattend Section (pending)

- Consumes: `usb.ventoy.Autounattend`, `WindowsTemplate`, `VentoySetup`, `VentoyLayout`, `VentoyIsoLibrary`.
- Produces: a Ventoy subsection only — existing-file summary with preserved unknown elements, opt-in parameter blocks, template and image pickers over one candidate list, binding rows with scope warnings, a separate target-PC layout confirmation, and raw-value global settings replacing the two blind toggles.

Steps:

- [ ] Land the `usb.ui` model/intent delta additively (owner required) so screens can compile against real declarations.
- [ ] Replace the blind bypass toggles with rows that show the raw `control` value and offer set-`"1"`/set-`"0"`/remove.
- [ ] Block generation when no template resolves, and re-prompt rather than reuse a stale `windowsTemplatePath`.
- [ ] Show `scopeLimitedBy` and `truncated`/`error` on the candidate list; never treat them as filters.
- [ ] Keep the layout confirmation free of USB identity and gate it with its own acknowledgement.
- [ ] Tests: strings parity for the new `ventoy_*` keys and a host check for the candidate/scope rendering decisions.
