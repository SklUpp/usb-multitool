package usb.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.BlockDevice
import usb.device.DiskException

class PartitionTableTest {
    @Test
    fun gptProtectiveMbrDoesNotHideEntries() {
        val disk = DiskFixture.load("partition/mbr-gpt.bin")
        val partitions = PartitionTable.read(disk).partitions
        assertEquals(listOf("EFI", "DATA"), partitions.map { it.name })
    }

    @Test
    fun emptyDiskReturnsNoPartitions() {
        val disk = DiskFixture.empty(sectorCount = 64UL)
        assertEquals(emptyList<Partition>(), PartitionTable.read(disk).partitions)
    }

    @Test
    fun overlappingGptEntriesRejected() {
        val disk = DiskFixture.load("partition/mbr-gpt-overlap.bin")
        assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(disk)
        }
    }

    @Test
    fun badGptCrcRejected() {
        val disk = DiskFixture.load("partition/mbr-gpt-badcrc.bin")
        assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(disk)
        }
    }

    @Test
    fun classicMbrFourEntries() {
        val disk = DiskFixture.load("partition/mbr-only.bin")
        val table = PartitionTable.read(disk)
        assertEquals(TableScheme.MBR, table.scheme)
        assertEquals(listOf("P1", "P2"), table.partitions.map { it.name })
        assertEquals(listOf(2048UL, 4096UL), table.partitions.map { it.startLba })
    }

    @Test
    fun gptKeepsBackupGeometryUnknownWithoutValidAlternateHeader() {
        val disk = DiskFixture.load("partition/mbr-gpt.bin")
        val table = PartitionTable.read(disk)
        assertEquals(TableScheme.GPT, table.scheme)
        // The alternate header is read from this fixture's last sector but is not CRC-valid,
        // so both backup positions stay unknown: publishing sectorCount - 1 would be a guess.
        assertNull(table.gptBackupHeaderLba)
        assertNull(table.gptBackupEntriesLba)
    }

    @Test
    fun protectiveEePlusRealPartitionIsHybridAmbiguous() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        // Keep protective 0xEE in slot 0; plant a real partition in slot 1.
        val slot1 = 446 + 16
        bytes[slot1 + 4] = 0x83.toByte()
        putLeInt(bytes, slot1 + 8, 100)
        putLeInt(bytes, slot1 + 12, 50)
        val hybrid = ImageBlockDevice(bytes, disk.sectorSize, disk.sectorCount)
        val table = PartitionTable.read(hybrid)
        assertTrue(table.hybridAmbiguous)
        assertEquals(TableScheme.HYBRID, table.scheme)
    }

    @Test
    fun blankDiskNeverOffersLbaZero() {
        val disk = DiskFixture.empty(sectorCount = 4096UL)
        val table = PartitionTable.read(disk)
        assertEquals(TableScheme.MBR, table.scheme)
        assertEquals(2048UL, table.freeRegions.single().startLba)

        val created = PartitionEditor.create(table, size = 1024UL, type = PartitionType.BASIC)
        assertEquals(2048UL, created.partitions.single().startLba)
        val committed = PartitionWriter.commit(disk, created)

        assertEquals(2048UL, committed.partitions.single().startLba)
        val mbr = sector(disk, 0UL)
        assertEquals(0x55, mbr[510].toInt() and 0xFF)
        assertEquals(0xAA, mbr[511].toInt() and 0xFF)
        assertEquals(0x83, mbr[446 + 4].toInt() and 0xFF)
        assertEquals(2048L, ByteBuffer.wrap(mbr, 446 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong())
    }

    @Test
    fun blankGptStartsWithPartitionAtOneMiB() {
        val disk = SyntheticGpt.build(sectorCount = 16384UL)
        var table = PartitionTable.read(disk)
        while (table.partitions.isNotEmpty()) table = PartitionEditor.delete(table, 0)
        val blank = PartitionWriter.commit(disk, table)
        assertEquals(emptyList<Partition>(), blank.partitions)

        val created = PartitionEditor.create(blank, size = 1024UL, type = PartitionType.BASIC)
        assertEquals(2048UL, created.partitions.single().startLba)
        val committed = PartitionWriter.commit(disk, created)

        assertEquals(2048UL, committed.partitions.single().startLba)
        val protectiveMbr = sector(disk, 0UL)
        assertEquals(0x55, protectiveMbr[510].toInt() and 0xFF)
        assertEquals(0xAA, protectiveMbr[511].toInt() and 0xFF)
        assertEquals(0xEE, protectiveMbr[446 + 4].toInt() and 0xFF)
        assertEquals(TableScheme.GPT, committed.scheme)
    }

    @Test
    fun mbrPartitionPastDiskEndRejected() {
        val disk = DiskFixture.empty(sectorCount = 100UL)
        val bytes = (disk as ImageBlockDevice).snapshot()
        bytes[510] = 0x55
        bytes[511] = 0xAA.toByte()
        // Slot 0: type 0x83, start=90, count=20 → ends past capacity 100.
        bytes[446 + 4] = 0x83.toByte()
        putLeInt(bytes, 446 + 8, 90)
        putLeInt(bytes, 446 + 12, 20)
        val crafted = ImageBlockDevice(bytes, disk.sectorSize, disk.sectorCount)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            MbrTable.read(crafted)
        }
        assertTrue(ex.message!!.contains("past end of disk"))
    }

    @Test
    fun mbrStartPlusCountOverflowRejected() {
        val disk = DiskFixture.empty(sectorCount = 64UL)
        val bytes = (disk as ImageBlockDevice).snapshot()
        bytes[510] = 0x55
        bytes[511] = 0xAA.toByte()
        bytes[446 + 4] = 0x83.toByte()
        // start=0xFFFFFFF0, count=0x20 → start+count wraps past UInt/ULong capacity check.
        putLeInt(bytes, 446 + 8, -16) // 0xFFFFFFF0
        putLeInt(bytes, 446 + 12, 0x20)
        val crafted = ImageBlockDevice(bytes, disk.sectorSize, disk.sectorCount)
        assertThrows(IllegalArgumentException::class.java) {
            MbrTable.read(crafted)
        }
    }

    @Test
    fun classicMbrGptProbeTransportFailureSurfaces() {
        // Classic MBR with one real partition; LBA 1 GPT probe must not swallow DiskException.
        val base = DiskFixture.empty(sectorCount = 128UL)
        val bytes = (base as ImageBlockDevice).snapshot()
        bytes[510] = 0x55
        bytes[511] = 0xAA.toByte()
        bytes[446 + 4] = 0x83.toByte()
        putLeInt(bytes, 446 + 8, 8)
        putLeInt(bytes, 446 + 12, 16)
        val disk = object : BlockDevice {
            override val sectorSize = base.sectorSize
            override val sectorCount = base.sectorCount
            override fun read(lba: ULong, destination: ByteBuffer) {
                if (lba == 1UL) throw DiskException.IoFailure("LBA 1 unreachable")
                val offset = (lba * sectorSize.toULong()).toInt()
                destination.put(bytes, offset, destination.remaining())
            }
            override fun write(lba: ULong, source: ByteBuffer) {
                throw UnsupportedOperationException()
            }
            override fun sync() = Unit
        }
        val failure = assertThrows(DiskException.IoFailure::class.java) {
            PartitionTable.read(disk)
        }
        assertTrue(failure.message!!.contains("LBA 1 unreachable"))
    }


    @Test
    fun gptUsableRangeStartingAtLbaZeroRejected() {
        val disk = SyntheticGpt.build(
            sectorCount = 2048UL,
            layout = listOf(SyntheticGpt.Layout(0, 0UL, 99UL, "DATA")),
            firstUsable = 0UL,
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(disk)
        }
        assertTrue(ex.message!!.contains("GPT usable range starts at LBA 0"))
    }

    @Test
    fun gptUsableRangePastDiskEndRejected() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL, lastUsable = 4096UL)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(disk)
        }
        assertTrue(ex.message!!.contains("past disk capacity"))
    }

    @Test
    fun gptUsableRangeOverBackupEntriesRejected() {
        // Backup entries occupy LBA 2015..2046 on a 2048-sector disk; both copies declare a
        // usable end inside that array, which is CRC-valid metadata, so the read must fail.
        val disk = SyntheticGpt.build(sectorCount = 2048UL, lastUsable = 2015UL)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(disk)
        }
        assertTrue(ex.message!!.contains("past its entries"))
    }

    @Test
    fun classicMbrWithValidBackupGptIsHybridAmbiguous() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        // Only the backup copy survives: the primary header sector loses its signature.
        bytes.fill(0, disk.sectorSize, 2 * disk.sectorSize)
        // Protective entry becomes a classic one, so the MBR now claims its own partition.
        bytes[446 + 4] = 0x83.toByte()
        putLeInt(bytes, 446 + 8, 100)
        putLeInt(bytes, 446 + 12, 50)
        val hybrid = ImageBlockDevice(bytes, disk.sectorSize, disk.sectorCount)

        val table = PartitionTable.read(hybrid)

        assertEquals(TableScheme.HYBRID, table.scheme)
        assertTrue(table.hybridAmbiguous)
        assertEquals(emptyList<FreeRegion>(), table.freeRegions)
        assertThrows(HybridAmbiguityException::class.java) {
            PartitionWriter.commit(hybrid, PartitionEditor.delete(table, 0))
        }
    }

    @Test
    fun normalGptKeepsFreeSpaceInsideUsableRange() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val table = PartitionTable.read(disk)
        val header = requireNotNull(table.gptHeader)

        assertEquals(TableScheme.GPT, table.scheme)
        assertEquals(false, table.gptPrimaryDamaged)
        assertTrue(table.freeRegions.all { it.startLba >= header.firstUsable })
        // EFI 34..43 and DATA 44..63, so free space resumes at LBA 64.
        assertEquals(FreeRegion(64UL, 2015UL - 64UL), table.freeRegions.single())
    }


    @Test
    fun signatureJunkDoesNotBreakClassicMbr() {
        val disk = DiskFixture.empty(sectorCount = 1024UL) as ImageBlockDevice
        val bytes = disk.snapshot()
        bytes[510] = 0x55
        bytes[511] = 0xAA.toByte()
        bytes[446 + 4] = 0x83.toByte()
        putLeInt(bytes, 446 + 8, 100)
        putLeInt(bytes, 446 + 12, 50)
        // Stray GPT magic in sectors that hold no valid copy must not deny a normal MBR disk.
        "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(bytes, 512)
        "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(bytes, 1023 * 512)

        val table = PartitionTable.read(ImageBlockDevice(bytes, 512, 1024UL))

        assertEquals(TableScheme.MBR, table.scheme)
        assertEquals(1, table.partitions.size)
        assertEquals(false, table.hybridAmbiguous)
    }

    @Test
    fun signedFatSuperfloppyIsRefusedAsInitializationTarget() {
        val disk = DiskFixture.empty(sectorCount = 1024UL) as ImageBlockDevice
        val bytes = disk.snapshot()
        bytes[0] = 0xEB.toByte()
        bytes[2] = 0x90.toByte()
        bytes[3] = 'M'.code.toByte()
        bytes[4] = 'S'.code.toByte()
        bytes[5] = 'D'.code.toByte()
        bytes[6] = 'O'.code.toByte()
        bytes[7] = 'S'.code.toByte()
        bytes[11] = 0x00
        bytes[12] = 0x02
        bytes[13] = 0x01
        bytes[14] = 0x01
        bytes[16] = 0x02
        bytes[17] = 0x00
        bytes[18] = 0x02
        bytes[19] = 0xE8.toByte()
        bytes[20] = 0x03
        bytes[21] = 0xF8.toByte()
        bytes[22] = 0x04
        bytes[23] = 0x00
        bytes[32] = 0x00
        bytes[33] = 0x00
        bytes[54] = 'F'.code.toByte()
        bytes[55] = 'A'.code.toByte()
        bytes[56] = 'T'.code.toByte()
        bytes[57] = '1'.code.toByte()
        bytes[58] = '6'.code.toByte()
        bytes[510] = 0x55
        bytes[511] = 0xAA.toByte()
        val image = ImageBlockDevice(bytes, 512, 1024UL)
        val table = PartitionTable.read(image)
        val before = image.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            TableInitializer.initialize(image, table, TableScheme.GPT)
        }
        assertTrue(before.contentEquals(image.snapshot()))
    }
    @Test
    fun metadataFenceRejectsMutationBeforeInitializationWrites() {
        val initial = DiskFixture.empty(sectorCount = 1024UL) as ImageBlockDevice
        val table = PartitionTable.read(initial)
        val expectedCurrent = metadataFingerprint(initial, metadataLbas(table))
        val expectedTarget = metadataFingerprint(
            initial,
            initializationMetadataLbas(table, TableScheme.GPT),
        )
        val mutatedBytes = initial.snapshot()
        mutatedBytes[0] = (mutatedBytes[0].toInt() xor 1).toByte()
        val mutated = ImageBlockDevice(mutatedBytes, 512, 1024UL)
        val before = mutated.snapshot()
        assertThrows(usb.diagnostics.ConfirmationMismatchException::class.java) {
            TableInitializer.initializeConfirmed(
                mutated,
                table,
                TableScheme.GPT,
                expectedCurrent,
                expectedTarget,
            )
        }
        assertTrue(before.contentEquals(mutated.snapshot()))
        assertTrue(mutated.writes.isEmpty())
    }

    @Test
    fun targetMetadataMutationRejectsInitializationBeforeWrites() {
        val initial = DiskFixture.empty(sectorCount = 1024UL) as ImageBlockDevice
        val table = PartitionTable.read(initial)
        val base = metadataLbas(table).toSet()
        val target = initializationMetadataLbas(table, TableScheme.GPT)
        val targetOnly = target.first { it !in base }
        val expectedCurrent = metadataFingerprint(initial, base)
        val expectedTarget = metadataFingerprint(initial, target)
        val mutatedBytes = initial.snapshot()
        mutatedBytes[(targetOnly * 512UL).toInt()] = 1
        val mutated = ImageBlockDevice(mutatedBytes, 512, 1024UL)
        val before = mutated.snapshot()
        assertThrows(usb.diagnostics.ConfirmationMismatchException::class.java) {
            TableInitializer.initializeConfirmed(mutated, table, TableScheme.GPT, expectedCurrent, expectedTarget)
        }
        assertTrue(before.contentEquals(mutated.snapshot()))
        assertTrue(mutated.writes.isEmpty())
    }

    @Test
    fun confirmedUnrecognizedInitializationWritesNewTable() {
        val image = DiskFixture.empty(sectorCount = 1024UL) as ImageBlockDevice
        val table = PartitionTable.read(image)
        assertEquals(TableRecognition.UNRECOGNIZED, table.recognition)
        val expectedCurrent = metadataFingerprint(image, metadataLbas(table))
        val expectedTarget = metadataFingerprint(
            image,
            initializationMetadataLbas(table, TableScheme.GPT),
        )
        TableInitializer.initializeConfirmed(
            image,
            table,
            TableScheme.GPT,
            expectedCurrent,
            expectedTarget,
        )
        assertTrue(image.writes.isNotEmpty())
        assertEquals(TableScheme.GPT, PartitionTable.read(image).scheme)
    }




    @Test
    fun signedAndUnsignedNtfsBootsAreRecognizedAndInitializationWritesNothing() {
        for (signed in listOf(true, false)) {
            val image = filesystemImage(FilesystemFixture.NTFS, signed, malformed = false)
            val table = PartitionTable.read(image)
            assertEquals(
                if (signed) TableRecognition.SUPERFLOPPY else TableRecognition.DAMAGED,
                table.recognition,
            )
            val before = image.snapshot()
            assertThrows(IllegalArgumentException::class.java) {
                TableInitializer.initialize(image, table, TableScheme.GPT)
            }
            assertTrue("NTFS signed=$signed must not be written", image.writes.isEmpty())
            assertTrue(before.contentEquals(image.snapshot()))
        }
    }

    @Test
    fun signedAndUnsignedExfatBootsAreRecognizedAndInitializationWritesNothing() {
        for (signed in listOf(true, false)) {
            val image = filesystemImage(FilesystemFixture.EXFAT, signed, malformed = false)
            val table = PartitionTable.read(image)
            assertEquals(
                if (signed) TableRecognition.SUPERFLOPPY else TableRecognition.DAMAGED,
                table.recognition,
            )
            val before = image.snapshot()
            assertThrows(IllegalArgumentException::class.java) {
                TableInitializer.initialize(image, table, TableScheme.GPT)
            }
            assertTrue("exFAT signed=$signed must not be written", image.writes.isEmpty())
            assertTrue(before.contentEquals(image.snapshot()))
        }
    }

    @Test
    fun malformedNtfsAndExfatMarkersAreDamagedAndInitializationWritesNothing() {
        for (filesystem in FilesystemFixture.entries) {
            for (signed in listOf(true, false)) {
                val image = filesystemImage(filesystem, signed, malformed = true)
                val table = PartitionTable.read(image)
                assertEquals("${filesystem.name} signed=$signed", TableRecognition.DAMAGED, table.recognition)
                val before = image.snapshot()
                assertThrows(IllegalArgumentException::class.java) {
                    TableInitializer.initialize(image, table, TableScheme.GPT)
                }
                assertTrue("$filesystem signed=$signed must not be written", image.writes.isEmpty())
                assertTrue(before.contentEquals(image.snapshot()))
            }
        }
    }

    @Test
    fun filesystemBootWithStaleMbrEntryIsAmbiguousAndWritesNothing() {
        for (filesystem in FilesystemFixture.entries) {
            val image = filesystemImage(filesystem, signed = true, malformed = false)
            val bytes = image.snapshot()
            bytes[446 + 4] = 0x07
            putLeInt(bytes, 446 + 8, 64)
            putLeInt(bytes, 446 + 12, 128)
            val staleMbr = ImageBlockDevice(bytes, 512, 2048UL)
            val table = PartitionTable.read(staleMbr)
            assertEquals("${filesystem.name}", TableRecognition.DAMAGED, table.recognition)
            assertTrue(table.hybridAmbiguous)
            val before = staleMbr.snapshot()
            assertThrows(IllegalArgumentException::class.java) {
                // Recognition guard may run before ambiguity guard; both must fail closed.
                TableInitializer.initialize(staleMbr, table, TableScheme.MBR)
            }
            assertTrue("$filesystem stale MBR must not be written", staleMbr.writes.isEmpty())
            assertTrue(before.contentEquals(staleMbr.snapshot()))
        }
    }

    private enum class FilesystemFixture { NTFS, EXFAT }

    private fun filesystemImage(
        filesystem: FilesystemFixture,
        signed: Boolean,
        malformed: Boolean,
    ): ImageBlockDevice {
        val bytes = ByteArray(2048 * 512)
        when (filesystem) {
            FilesystemFixture.NTFS -> {
                bytes[0] = 0xEB.toByte()
                bytes[1] = 0x52
                bytes[2] = 0x90.toByte()
                "NTFS    ".toByteArray(Charsets.US_ASCII).copyInto(bytes, 3)
                putLeShort(bytes, 11, 512)
                bytes[13] = 8
                bytes[21] = 0xF8.toByte()
                putLeLong(bytes, 40, if (malformed) 4096L else 2048L)
                putLeLong(bytes, 48, 4L)
                putLeLong(bytes, 56, 8L)
                bytes[64] = 0xF6.toByte()
                bytes[68] = 1
            }
            FilesystemFixture.EXFAT -> {
                bytes[0] = 0xEB.toByte()
                bytes[1] = 0x76
                bytes[2] = 0x90.toByte()
                "EXFAT   ".toByteArray(Charsets.US_ASCII).copyInto(bytes, 3)
                putLeLong(bytes, 72, if (malformed) 4096L else 2048L)
                putLeInt(bytes, 80, 24)
                putLeInt(bytes, 84, 8)
                putLeInt(bytes, 88, 32)
                putLeInt(bytes, 92, 250)
                putLeInt(bytes, 96, 2)
                bytes[108] = 9
                bytes[109] = 3
                bytes[110] = 1
            }
        }
        if (signed) {
            bytes[510] = 0x55
            bytes[511] = 0xAA.toByte()
        }
        return ImageBlockDevice(bytes, 512, 2048UL)
    }

    private fun putLeShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
    }

    private fun putLeLong(bytes: ByteArray, offset: Int, value: Long) {
        putLeInt(bytes, offset, value.toInt())
        putLeInt(bytes, offset + 4, (value ushr 32).toInt())
    }

    private fun sector(disk: BlockDevice, lba: ULong): ByteArray {
        val buffer = ByteBuffer.allocate(disk.sectorSize)
        disk.read(lba, buffer)
        return buffer.array()
    }

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
