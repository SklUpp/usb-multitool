package usb.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawRangePlanTest {
    private val sectorSize = 512
    private val alignment = PartitionTable.alignmentSectors(sectorSize)

    private fun gptHeader(firstUsable: ULong = 34UL) =
        GptHeader(
            headerSize = 92,
            currentLba = 1UL,
            backupLba = 999UL,
            firstUsable = firstUsable,
            lastUsable = 998UL,
            diskGuid = ByteArray(16),
            entryLba = 2UL,
            entryCount = 128,
            entrySize = 128,
            entriesCrc = 0,
            headerCrc = 0,
        )

    private fun gptTable(
        partitions: List<Partition> = emptyList(),
        primaryEntryLba: ULong? = 2UL,
        primaryDamaged: Boolean = false,
        diskSectorCount: ULong = 1000UL,
    ) = PartitionTable(
        scheme = TableScheme.GPT,
        partitions = partitions,
        freeRegions = emptyList(),
        diskSectorCount = diskSectorCount,
        sectorSize = sectorSize,
        gptBackupHeaderLba = diskSectorCount - 1UL,
        gptBackupEntriesLba = diskSectorCount - 33UL,
        gptPrimaryEntryLba = primaryEntryLba,
        gptHeader = gptHeader(),
        gptPrimaryDamaged = primaryDamaged,
    )

    private fun mbrTable(
        partitions: List<Partition> = emptyList(),
        diskSectorCount: ULong = 10_000UL,
    ) = PartitionTable(
        scheme = TableScheme.MBR,
        partitions = partitions,
        freeRegions = emptyList(),
        diskSectorCount = diskSectorCount,
        sectorSize = sectorSize,
    )

    @Test fun snapPointsCoverDiskAndPartitionEdges() {
        val geometry = RawRangeGeometry.from(
            mbrTable(
                partitions = listOf(Partition(2048UL, 1000UL), Partition(4096UL, 500UL)),
            ),
            diskSectorCount = 10_000UL,
            sectorSize = sectorSize,
        )
        assertEquals(
            listOf(0UL, 2048UL, 3048UL, 4096UL, 4596UL, 10_000UL),
            geometry.snapPoints,
        )
    }

    @Test fun snapPointsAreEmptyForUntrustedScheme() {
        val damaged = RawRangeGeometry.from(
            gptTable(primaryDamaged = true),
            diskSectorCount = 1000UL,
            sectorSize = sectorSize,
        )
        assertTrue(damaged.snapPoints.isEmpty())
        val unreadable = RawRangeGeometry.unreadable(1000UL, sectorSize)
        assertTrue(unreadable.snapPoints.isEmpty())
    }

    @Test fun partitionOverlapIsInclusiveStartExclusiveEnd() {
        val geometry = RawRangeGeometry.from(
            mbrTable(partitions = listOf(Partition(2048UL, 1000UL, name = "Data"))),
            diskSectorCount = 10_000UL,
            sectorSize = sectorSize,
        )
        // Exactly the partition: fully covered, no neighbouring sectors.
        val exact = geometry.overlaps(2048UL, 1000UL)
        assertEquals(1, exact.size)
        assertEquals(1000UL, exact.single().overlapSectors)
        assertTrue(exact.single().fullyCovered)
        assertEquals("Data", exact.single().target.name)

        // One sector before start does not touch the partition (boot gap may still warn).
        assertTrue(
            geometry.overlaps(2047UL, 1UL).none { it.target.kind == RawObjectKind.Partition },
        )
        // Last partition sector is 3047; a range starting at 3048 misses the partition.
        assertTrue(
            geometry.overlaps(3048UL, 1UL).none { it.target.kind == RawObjectKind.Partition },
        )
        assertTrue(geometry.overlaps(3047UL, 1UL).any { it.target.kind == RawObjectKind.Partition })

        // Cutting one sector into the partition is reported, not fully covered.
        val partial =
            geometry.overlaps(1500UL, 1000UL).single { it.target.kind == RawObjectKind.Partition }
        assertEquals(452UL, partial.overlapSectors)
        assertTrue(!partial.fullyCovered)
    }

    @Test fun mbrBootAreaIsAWarningObject() {
        val geometry = RawRangeGeometry.from(mbrTable(), 10_000UL, sectorSize)
        val boot = geometry.objects.single()
        assertEquals(RawObjectKind.BootArea, boot.kind)
        assertEquals(0UL, boot.startLba)
        assertEquals(alignment, boot.sectorCount)
        assertEquals(1, geometry.overlaps(0UL, 1UL).size)
    }

    @Test fun gptPrimaryAndBackupMetadataAreSeparateZones() {
        val geometry = RawRangeGeometry.from(gptTable(), 1000UL, sectorSize)
        val tableZones = geometry.objects.filter { it.kind == RawObjectKind.TableArea }
        assertEquals(2, tableZones.size)
        // Primary: LBA 0 (protective MBR + header) through the entry array at 2..33.
        assertEquals(0UL, tableZones[0].startLba)
        assertEquals(34UL, tableZones[0].sectorCount)
        // Backup: entries 967..998 plus header 999.
        assertEquals(967UL, tableZones[1].startLba)
        assertEquals(33UL, tableZones[1].sectorCount)
        val backupHit = geometry.overlaps(999UL, 1UL).single()
        assertEquals(RawObjectKind.TableArea, backupHit.target.kind)
        assertTrue(backupHit.fullyCovered)
    }

    @Test fun damagedPrimaryUsesConservativeMetadataWindowAndDamagedTrust() {
        val geometry = RawRangeGeometry.from(
            gptTable(primaryEntryLba = null, primaryDamaged = true),
            1000UL,
            sectorSize,
        )
        val primary = geometry.objects.first { it.kind == RawObjectKind.TableArea }
        assertEquals(0UL, primary.startLba)
        assertEquals(34UL, primary.sectorCount)
        assertEquals(RawSchemeProblem.GptPrimaryDamaged, (geometry.trust as SchemeTrust.Damaged).reason)
    }

    @Test fun hybridLayoutIsDamagedTrustButKeepsPartitionOverlaps() {
        val table =
            PartitionTable(
                scheme = TableScheme.HYBRID,
                partitions = listOf(Partition(2048UL, 1000UL, name = "Win")),
                freeRegions = emptyList(),
                diskSectorCount = 10_000UL,
                sectorSize = sectorSize,
                hybridAmbiguous = true,
            )
        val geometry = RawRangeGeometry.from(table, 10_000UL, sectorSize)
        assertEquals(RawSchemeProblem.HybridAmbiguous, (geometry.trust as SchemeTrust.Damaged).reason)
        assertEquals("Win", geometry.overlaps(2048UL, 10UL).single().target.name)
        assertTrue(geometry.snapPoints.isEmpty())
        assertTrue(!geometry.metadataComplete)
        assertEquals(RawSchemeProblem.HybridAmbiguous, geometry.metadataProblem)
        val check = RawRangePlan.check(2048UL, 10UL, geometry) as RawRangeCheck.Ok
        assertTrue(check.warnings.any { it is RawRangeWarning.MetadataCoverageUnknown })
    }

    @Test fun unreadableTableHasNoObjectsAndNoClaims() {
        val geometry = RawRangeGeometry.from(null, 10_000UL, sectorSize)
        assertTrue(geometry.objects.isEmpty())
        assertTrue(geometry.overlaps(0UL, 10_000UL).isEmpty())
        assertEquals(
            RawSchemeProblem.TableUnreadable,
            (geometry.trust as SchemeTrust.Unreadable).reason,
        )
        val check = RawRangePlan.check(0UL, 10_000UL, geometry) as RawRangeCheck.Ok
        assertTrue(check.warnings.any { it is RawRangeWarning.SchemeNotTrusted })
    }

    @Test fun emptyAndOutOfBoundsRangesAreRejected() {
        val geometry = RawRangeGeometry.from(mbrTable(), 10_000UL, sectorSize)
        assertEquals(
            setOf(RawRangeProblem.EmptyRange),
            (RawRangePlan.check(0UL, 0UL, geometry) as RawRangeCheck.Rejected).problems,
        )
        assertEquals(
            setOf(RawRangeProblem.BeyondEndOfDisk),
            (RawRangePlan.check(9_999UL, 2UL, geometry) as RawRangeCheck.Rejected).problems,
        )
        // start + count would wrap around ULong; still inside the rejection, never accepted.
        assertTrue(
            RawRangePlan.check(ULong.MAX_VALUE, 2UL, geometry) is RawRangeCheck.Rejected,
        )
    }

    @Test fun unrepresentableByteLengthIsRejected() {
        val huge = RawRangeGeometry.unreadable(Long.MAX_VALUE.toULong(), sectorSize)
        val check = RawRangePlan.check(0UL, Long.MAX_VALUE.toULong(), huge)
        assertTrue((check as RawRangeCheck.Rejected).problems.contains(RawRangeProblem.RangeTooLarge))
    }

    @Test fun knownSourceLargerThanRangeIsRejectedButUnknownSizeIsNot() {
        val geometry = RawRangeGeometry.from(mbrTable(), 10_000UL, sectorSize)
        val known = RawRangePlan.check(0UL, 2UL, geometry, sourceBytes = 2048UL + 1UL)
        assertEquals(
            setOf(RawRangeProblem.SourceLargerThanRange),
            (known as RawRangeCheck.Rejected).problems,
        )
        val fits = RawRangePlan.check(0UL, 4UL, geometry, sourceBytes = 2048UL)
        assertTrue(fits is RawRangeCheck.Ok)
        val unknown = RawRangePlan.check(0UL, 4UL, geometry, sourceBytes = null)
        assertTrue(unknown is RawRangeCheck.Ok)
    }

    @Test fun overlapsAndAlignmentAreWarningsNotRejections() {
        val geometry = RawRangeGeometry.from(
            mbrTable(partitions = listOf(Partition(2048UL, 1000UL, name = "Data"))),
            10_000UL,
            sectorSize,
        )
        val check = RawRangePlan.check(2300UL, 100UL, geometry) as RawRangeCheck.Ok
        val overlap = check.warnings.filterIsInstance<RawRangeWarning.OverlapsObjects>().single()
        assertEquals(1, overlap.overlaps.size)
        assertEquals("Data", overlap.overlaps.single().target.name)
        assertTrue(check.warnings.any { it is RawRangeWarning.UnalignedStart })

        val alignedInFreeSpace = RawRangePlan.check(alignment, 2UL, geometry) as RawRangeCheck.Ok
        assertTrue(alignedInFreeSpace.warnings.none { it is RawRangeWarning.UnalignedStart })
    }

    @Test fun wholeDiskOnConfirmedTableIsAcceptedWithTableWarnings() {
        val geometry = RawRangeGeometry.from(gptTable(), 1000UL, sectorSize)
        val check = RawRangePlan.check(0UL, 1000UL, geometry) as RawRangeCheck.Ok
        val overlap = check.warnings.filterIsInstance<RawRangeWarning.OverlapsObjects>().single()
        assertEquals(2, overlap.overlaps.count { it.target.kind == RawObjectKind.TableArea })
        assertTrue(check.warnings.none { it is RawRangeWarning.SchemeNotTrusted })
    }

    @Test fun unreadableBackupHeaderLeavesArrayLocationUnknown() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        val headerOffset = 2047 * sectorSize
        bytes[headerOffset + 16] =
            (bytes[headerOffset + 16].toInt() xor 0xFF).toByte()
        val damaged = ImageBlockDevice(bytes, sectorSize, 2048UL)

        val table = PartitionTable.read(damaged)
        assertNull("no valid backup header means no entry location", table.gptBackupEntriesLba)
        val geometry = RawRangeGeometry.from(table, 2048UL, sectorSize)
        assertTrue(!geometry.metadataComplete)
        assertEquals(
            RawSchemeProblem.GptBackupMetadataUnknown,
            geometry.metadataProblem,
        )
    }

    @Test fun displacedBackupEntryArrayIsReportedAtItsRealLocation() {
        // Legal GPT: the backup array need not sit directly below the backup header.
        val disk = SyntheticGpt.build(
            sectorCount = 2048UL,
            backupEntriesLba = 2047UL - 40UL,
        )
        val table = PartitionTable.read(disk)
        assertEquals(2007UL, table.gptBackupEntriesLba)

        val geometry = RawRangeGeometry.from(table, 2048UL, sectorSize)
        assertTrue(geometry.metadataComplete)
        val hit = geometry.overlaps(2007UL, 1UL).single()
        assertEquals(RawObjectKind.TableArea, hit.target.kind)
        assertTrue(hit.fullyCovered)
    }

    @Test fun unknownBackupArrayLocationFailsClosed() {
        val table = gptTable().copy(gptBackupEntriesLba = null)
        val geometry = RawRangeGeometry.from(table, 1000UL, sectorSize)
        assertTrue(!geometry.metadataComplete)
        assertEquals(RawSchemeProblem.GptBackupMetadataUnknown, geometry.metadataProblem)
        // No fabricated backup zone: the unknown area is not claimed as checked.
        assertTrue(geometry.overlaps(999UL, 1UL).isEmpty())

        val check = RawRangePlan.check(0UL, 1000UL, geometry) as RawRangeCheck.Ok
        val warning = check.warnings.filterIsInstance<RawRangeWarning.MetadataCoverageUnknown>()
        assertEquals(RawSchemeProblem.GptBackupMetadataUnknown, warning.single().reason)
    }

    @Test fun unreadableGeometryNeverClaimsCompleteMetadata() {
        val geometry = RawRangeGeometry.unreadable(10_000UL, sectorSize)
        assertTrue(!geometry.metadataComplete)
        val check = RawRangePlan.check(0UL, 10_000UL, geometry) as RawRangeCheck.Ok
        assertTrue(check.warnings.any { it is RawRangeWarning.SchemeNotTrusted })
        assertTrue(check.warnings.any { it is RawRangeWarning.MetadataCoverageUnknown })
    }

    @Test fun plannerRoundsDecodedBytesUpToWholeSectors() {
        val geometry = RawRangeGeometry.from(mbrTable(), 10_000UL, sectorSize)
        val exact = RawRangePlan.planFromSourceSize(2048UL, 512L, geometry) as RawWritePlan.Ready
        assertEquals(RawWriteRange(2048UL, 1UL), exact.range)
        val partial = RawRangePlan.planFromSourceSize(2048UL, 513L, geometry) as RawWritePlan.Ready
        assertEquals(RawWriteRange(2048UL, 2UL), partial.range)
        val oneByte = RawRangePlan.planFromSourceSize(0UL, 1L, geometry) as RawWritePlan.Ready
        assertEquals(1UL, oneByte.range.sectorCount)
    }

    @Test fun plannerRejectsUnknownEmptyAndOverflowingSizes() {
        val geometry = RawRangeGeometry.from(mbrTable(), 10_000UL, sectorSize)
        assertEquals(
            setOf(RawRangeProblem.SourceSizeUnknown),
            (RawRangePlan.planFromSourceSize(0UL, null, geometry) as RawWritePlan.Rejected).problems,
        )
        assertEquals(
            setOf(RawRangeProblem.SourceEmpty),
            (RawRangePlan.planFromSourceSize(0UL, 0L, geometry) as RawWritePlan.Rejected).problems,
        )
        assertEquals(
            setOf(RawRangeProblem.SourceSizeOverflow),
            (RawRangePlan.planFromSourceSize(0UL, -1L, geometry) as RawWritePlan.Rejected).problems,
        )
        val huge = RawRangeGeometry.unreadable(Long.MAX_VALUE.toULong(), sectorSize)
        assertTrue(
            (RawRangePlan.planFromSourceSize(0UL, Long.MAX_VALUE, huge) as RawWritePlan.Rejected)
                .problems.contains(RawRangeProblem.RangeTooLarge),
        )
    }

    @Test fun plannerRejectsImageThatDoesNotFitFromStart() {
        val geometry = RawRangeGeometry.from(mbrTable(), 10_000UL, sectorSize)
        val rejected =
            RawRangePlan.planFromSourceSize(9_999UL, 1024L, geometry) as RawWritePlan.Rejected
        assertEquals(setOf(RawRangeProblem.BeyondEndOfDisk), rejected.problems)
        // 2 sectors starting at 9_999 end exactly at 10_001: one past the disk.
        val exactlyFits =
            RawRangePlan.planFromSourceSize(9_998UL, 1024L, geometry) as RawWritePlan.Ready
        assertEquals(RawWriteRange(9_998UL, 2UL), exactlyFits.range)
        assertEquals(10_000UL, exactlyFits.range.endLbaExclusive)
    }

    @Test fun derivedRangeOverlapUsesExclusiveEnd() {
        val geometry = RawRangeGeometry.from(
            mbrTable(partitions = listOf(
                Partition(2048UL, 1000UL, name = "A"),
                Partition(3048UL, 500UL, name = "B"),
            )),
            10_000UL,
            sectorSize,
        )
        val first = RawRangePlan.planFromSourceSize(2048UL, 512L, geometry) as RawWritePlan.Ready
        val overlap = first.warnings.filterIsInstance<RawRangeWarning.OverlapsObjects>().single()
        assertEquals(listOf("A"), overlap.overlaps.map { it.target.name })
        assertTrue(overlap.overlaps.single().fullyCovered)

        // Starting exactly at B's start overlaps only B, never A.
        val atBoundary = RawRangePlan.planFromSourceSize(3048UL, 512L, geometry) as RawWritePlan.Ready
        val boundaryOverlap =
            atBoundary.warnings.filterIsInstance<RawRangeWarning.OverlapsObjects>().single()
        assertEquals(listOf("B"), boundaryOverlap.overlaps.map { it.target.name })

        // Ending exactly at B's start (3048) does not touch B.
        val coversA = RawRangePlan.planFromSourceSize(2048UL, 1000L * 512L, geometry) as RawWritePlan.Ready
        val coversAOverlap = coversA.warnings.filterIsInstance<RawRangeWarning.OverlapsObjects>()
        assertEquals(1, coversAOverlap.size)
        assertEquals(listOf("A"), coversAOverlap.single().overlaps.map { it.target.name })
    }

    @Test fun writerPreflightRejectsSameBoundProblems() {
        val range = RawWriteRange(9UL, 2UL)
        try {
            RawRangePlan.requireWritable(range, diskSectorCount = 10UL, sectorSize = sectorSize)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        RawRangePlan.requireWritable(
            RawWriteRange(0UL, 10UL),
            diskSectorCount = 10UL,
            sectorSize = sectorSize,
        )
    }
}
