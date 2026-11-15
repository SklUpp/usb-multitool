package usb.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import usb.device.BlockDevice
import usb.device.DiskException

class GptBackupFaultTest {
    private val sectorSizes = listOf(512, 4096)

    private fun entrySectorsFor(sectorSize: Int, entryCount: Int = 128, entrySize: Int = 128): ULong =
        ((entryCount * entrySize + sectorSize - 1) / sectorSize).toULong()

    private fun shrink(disk: ImageBlockDevice, newSectorCount: ULong): ImageBlockDevice =
        ImageBlockDevice(
            disk.snapshot().copyOf((newSectorCount * disk.sectorSize.toULong()).toInt()),
            disk.sectorSize,
            newSectorCount,
        )

    private fun grow(disk: ImageBlockDevice, newSectorCount: ULong): ImageBlockDevice =
        ImageBlockDevice(
            disk.snapshot().copyOf((newSectorCount * disk.sectorSize.toULong()).toInt()),
            disk.sectorSize,
            newSectorCount,
        )

    /**
     * Local test device modelling both a volatile write cache (lost on power-off unless synced)
     * and partial-sector torn writes (unlike [FaultyBlockDevice], which rounds 1-sector torn
     * writes down to 0 bytes).
     */
    private class VolatileCacheBlockDevice(
        initialImage: ByteArray,
        override val sectorSize: Int,
        override val sectorCount: ULong,
        private val failAtWrite: Int = Int.MAX_VALUE,
        private val failAtSync: Int = Int.MAX_VALUE,
        private val applySyncBeforeFailing: Boolean = false,
        private val tornWriteAt: Int = Int.MAX_VALUE,
        private val tornSingleSectorBytes: Int = 40,
    ) : BlockDevice {
        private val working = initialImage.copyOf()
        private val durable = initialImage.copyOf()
        private var writeCount = 0
        private var syncCount = 0
        val writeLbas = mutableListOf<ULong>()

        override fun read(lba: ULong, destination: ByteBuffer) {
            val bytes = destination.remaining()
            require(bytes % sectorSize == 0)
            val blocks = bytes / sectorSize
            require(blocks.toULong() <= sectorCount && lba <= sectorCount - blocks.toULong())
            val offset = (lba * sectorSize.toULong()).toInt()
            destination.put(working, offset, bytes)
        }

        override fun write(lba: ULong, source: ByteBuffer) {
            writeLbas += lba
            writeCount++
            val bytes = source.remaining()
            val blocks = bytes / sectorSize
            require(blocks.toULong() <= sectorCount && lba <= sectorCount - blocks.toULong())
            val offset = (lba * sectorSize.toULong()).toInt()
            if (writeCount == tornWriteAt) {
                val partial = if (blocks > 1) {
                    (blocks / 2) * sectorSize
                } else {
                    minOf(tornSingleSectorBytes, bytes)
                }
                if (partial > 0) source.get(working, offset, partial)
                throw DiskException.IoFailure(
                    "injected torn write $writeCount at LBA $lba ($partial bytes landed)",
                )
            }
            if (writeCount == failAtWrite) {
                throw DiskException.IoFailure("injected write failure $writeCount at LBA $lba")
            }
            source.get(working, offset, bytes)
        }

        override fun sync() {
            syncCount++
            if (syncCount == failAtSync) {
                if (applySyncBeforeFailing) {
                    working.copyInto(durable)
                }
                throw DiskException.IoFailure("injected sync failure $syncCount")
            }
            working.copyInto(durable)
        }

        fun liveDevice(): ImageBlockDevice = ImageBlockDevice(working.copyOf(), sectorSize, sectorCount)

        fun durableDevice(): ImageBlockDevice = ImageBlockDevice(durable.copyOf(), sectorSize, sectorCount)
    }

    @Test
    fun relocateWritesAndSyncsBackupBeforeUpdatingPrimaryFor512And4096() {
        for (sectorSize in sectorSizes) {
            val sectorCount = 512UL
            val disk = SyntheticGpt.build(sectorCount = sectorCount, sectorSize = sectorSize)
            val fault = FaultyBlockDevice(disk)

            val relocated = GptBackup.relocateBackup(fault, sectorCount)

            val entrySectors = entrySectorsFor(sectorSize)
            val backupHeaderLba = sectorCount - 1UL
            val backupEntriesLba = backupHeaderLba - entrySectors

            val writeEntries = fault.ops.indexOf("write:$backupEntriesLba")
            val writeBackupHeader = fault.ops.indexOf("write:$backupHeaderLba")
            val firstSync = fault.ops.indexOf("sync")
            val writePrimary = fault.ops.indexOf("write:1")
            val secondSync = fault.ops.lastIndexOf("sync")

            assertTrue("sectorSize=$sectorSize: missing backup entries write", writeEntries >= 0)
            assertTrue("sectorSize=$sectorSize: missing backup header write", writeBackupHeader > writeEntries)
            assertTrue("sectorSize=$sectorSize: first sync must follow backup header", firstSync > writeBackupHeader)
            assertTrue("sectorSize=$sectorSize: primary header must follow first sync", writePrimary > firstSync)
            assertTrue("sectorSize=$sectorSize: second sync must follow primary header", secondSync > writePrimary)
            assertEquals("sectorSize=$sectorSize: expected exactly 2 syncs", 2, fault.ops.count { it == "sync" })
            assertEquals(
                "sectorSize=$sectorSize: expected exactly 3 writes",
                3,
                fault.ops.count { it.startsWith("write:") },
            )
            assertEquals(backupHeaderLba, relocated.gptBackupHeaderLba)
            assertEquals(backupEntriesLba, relocated.gptBackupEntriesLba)
            assertEquals(backupEntriesLba - 1UL, relocated.gptHeader!!.lastUsable)
            val backupRead = GptTable.read(fault, backupHeaderLba)
            assertEquals(listOf("EFI", "DATA"), backupRead.partitions.map { it.name })
        }
    }

    @Test
    fun backupCriticalAndRelocateSupport4096ByteGeometry() {
        val sectorSize = 4096
        assertEquals(6, GptBackup.primarySectors(sectorSize))
        assertEquals(5, GptBackup.backupSectors(sectorSize))

        val disk = SyntheticGpt.build(sectorCount = 256UL, sectorSize = sectorSize)
        val critical = GptBackup.backupCriticalSectors(disk)
        assertEquals((6 + 5) * sectorSize, critical.size)

        val head = ByteBuffer.allocate(sectorSize)
        disk.read(0UL, head)
        assertTrue(critical.copyOfRange(0, sectorSize).contentEquals(head.array()))

        val tail = ByteBuffer.allocate(sectorSize)
        disk.read(255UL, tail)
        val tailOffset = (6 + 4) * sectorSize
        assertTrue(critical.copyOfRange(tailOffset, tailOffset + sectorSize).contentEquals(tail.array()))

        val grown = grow(disk, 512UL)
        val relocated = GptBackup.relocateBackup(grown, 512UL)
        assertEquals(sectorSize, relocated.sectorSize)
        assertEquals(511UL, relocated.gptBackupHeaderLba)
        assertEquals(507UL, relocated.gptBackupEntriesLba)
        assertEquals(506UL, relocated.gptHeader!!.lastUsable)
        assertEquals(listOf("EFI", "DATA"), relocated.partitions.map { it.name })

        val hdrBuf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        grown.read(511UL, hdrBuf)
        hdrBuf.flip()
        assertEquals(507UL, hdrBuf.getLong(72).toULong())
        assertEquals(506UL, hdrBuf.getLong(48).toULong())
        assertFalse(PartitionTable.read(grown).gptPrimaryDamaged)
        assertEquals(listOf("EFI", "DATA"), GptTable.read(grown, 511UL).partitions.map { it.name })
    }

    @Test
    fun shrunkDiskWriteAndMultiSectorTornFaultsKeepPrimaryValid() {
        for (sectorSize in sectorSizes) {
            val originalCount = if (sectorSize == 512) 2048UL else 512UL
            val shrunkCount = if (sectorSize == 512) 1024UL else 256UL
            for (failAt in 1..4) {
                for (applyBeforeFailing in listOf(false, true)) {
                    val shrunk = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
                    val fault = FaultyBlockDevice(shrunk, failAtWrite = failAt, applyBeforeFailing = applyBeforeFailing)
                    assertThrows(DiskException.IoFailure::class.java) {
                        GptBackup.relocateBackup(fault, shrunkCount)
                    }
                    assertEquals(
                        if (failAt == 4 && applyBeforeFailing) shrunkCount - 1UL else originalCount - 1UL,
                        GptTable.read(fault, 1UL).header.backupLba,
                    )
                }
            }
            val torn = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
            val fault = FaultyBlockDevice(torn, tornWriteAt = 2)
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(fault, shrunkCount)
            }
            assertEquals(
                listOf(0UL, shrunkCount - 1UL - entrySectorsFor(sectorSize)),
                fault.ops.filter { it.startsWith("write:") }.map { it.substringAfter(':').toULong() },
            )
            assertEquals(originalCount - 1UL, GptTable.read(fault, 1UL).header.backupLba)
        }
    }

    @Test
    fun singleSectorTornWritesOnBackupAndPrimaryHeadersAreHandledSafely() {
        for (sectorSize in sectorSizes) {
            val originalCount = if (sectorSize == 512) 2048UL else 512UL
            val shrunkCount = if (sectorSize == 512) 1024UL else 256UL
            val entrySectors = entrySectorsFor(sectorSize)

            // Torn backup entries: primary stays valid; multi-sector entry write fails before header.
            val shrunkTorn2 = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
            val devTorn3 = VolatileCacheBlockDevice(
                shrunkTorn2.snapshot(),
                sectorSize,
                shrunkCount,
                tornWriteAt = 3,
            )
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(devTorn3, shrunkCount)
            }
            assertNull(GptTable.readHeaderOrNull(devTorn3.liveDevice(), shrunkCount - 1UL))
            assertNotNull(GptTable.readHeaderOrNull(devTorn3.liveDevice(), 1UL))

            // Torn backup entries on grown non-overlapping disk: primary + old backup remain intact.
            val grownTorn2 = grow(SyntheticGpt.build(shrunkCount, sectorSize), originalCount)
            val devGrownTorn2 = VolatileCacheBlockDevice(
                grownTorn2.snapshot(),
                sectorSize,
                originalCount,
                tornWriteAt = 2,
            )
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(devGrownTorn2, originalCount)
            }
            val liveGrown2 = devGrownTorn2.liveDevice()
            val afterGrownTorn2 = PartitionTable.read(liveGrown2)
            assertFalse(afterGrownTorn2.gptPrimaryDamaged)
            assertEquals(shrunkCount - 1UL, afterGrownTorn2.gptBackupHeaderLba)
            assertEquals(listOf("EFI", "DATA"), GptTable.read(liveGrown2, shrunkCount - 1UL).partitions.map { it.name })
            assertNull(GptTable.readHeaderOrNull(liveGrown2, originalCount - 1UL))

            // Torn backup header: full backup reread must fail, while primary fallback stays valid.
            val shrunkTorn3 = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
            val devShrunkTorn3 = VolatileCacheBlockDevice(shrunkTorn3.snapshot(), sectorSize, shrunkCount, tornWriteAt = 3)
            assertThrows(DiskException.IoFailure::class.java) { GptBackup.relocateBackup(devShrunkTorn3, shrunkCount) }
            val liveShrunk3 = devShrunkTorn3.liveDevice()
            assertNotNull(GptTable.read(liveShrunk3, 1UL))
            assertThrows(IllegalArgumentException::class.java) { GptTable.read(liveShrunk3, shrunkCount - 1UL) }
            val fallbackShrunk3 = PartitionTable.read(liveShrunk3)
            assertFalse(fallbackShrunk3.gptPrimaryDamaged)
            assertEquals(listOf("EFI", "DATA"), fallbackShrunk3.partitions.map { it.name })

            // Torn primary header: full durable backup remains usable for fallback.
            val primaryTorn = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
            val devPrimaryTorn = VolatileCacheBlockDevice(primaryTorn.snapshot(), sectorSize, shrunkCount, tornWriteAt = 4)
            assertThrows(DiskException.IoFailure::class.java) { GptBackup.relocateBackup(devPrimaryTorn, shrunkCount) }
            val livePrimaryTorn = devPrimaryTorn.liveDevice()
            assertThrows(IllegalArgumentException::class.java) { GptTable.read(livePrimaryTorn, 1UL) }
            val durableBackup = GptTable.read(livePrimaryTorn, shrunkCount - 1UL)
            assertEquals(listOf("EFI", "DATA"), durableBackup.partitions.map { it.name })
            val fallbackPrimaryTorn = PartitionTable.read(livePrimaryTorn)
            assertTrue(fallbackPrimaryTorn.gptPrimaryDamaged)
            assertEquals(listOf("EFI", "DATA"), fallbackPrimaryTorn.partitions.map { it.name })
        }
    }

    @Test
    fun syncDurabilityBoundariesModelVolatileCacheLossOnPowerFailure() {
        for (sectorSize in sectorSizes) {
            val originalCount = if (sectorSize == 512) 2048UL else 512UL
            val shrunkCount = if (sectorSize == 512) 1024UL else 256UL
            val entrySectors = entrySectorsFor(sectorSize)

            // Power loss before Sync 1 completes: durable medium retains pre-relocate state only.
            val shrunkSync1 = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
            val devSync1 = VolatileCacheBlockDevice(
                shrunkSync1.snapshot(),
                sectorSize,
                shrunkCount,
                failAtSync = 1,
                applySyncBeforeFailing = false,
            )
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(devSync1, shrunkCount)
            }
            val durableAfterSync1Fail = devSync1.durableDevice()
            val primaryAfterSync1Fail = GptTable.read(durableAfterSync1Fail, 1UL)
            assertEquals(originalCount - 1UL, primaryAfterSync1Fail.header.backupLba)
            assertNull(GptTable.readHeaderOrNull(durableAfterSync1Fail, shrunkCount - 1UL))
            val staleView = PartitionTable.read(durableAfterSync1Fail)
            assertTrue(staleView.hybridAmbiguous || staleView.gptPrimaryDamaged)

            // Retry from durable state completes cleanly.
            val retried = GptBackup.relocateBackup(durableAfterSync1Fail, shrunkCount)
            assertEquals(shrunkCount - 1UL, retried.gptBackupHeaderLba)
            assertEquals(listOf("EFI", "DATA"), GptTable.read(durableAfterSync1Fail, shrunkCount - 1UL).partitions.map { it.name })

            val shrunkSync2 = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
            val devSync2 = VolatileCacheBlockDevice(shrunkSync2.snapshot(), sectorSize, shrunkCount, failAtSync = 2, applySyncBeforeFailing = false)
            assertThrows(DiskException.IoFailure::class.java) { GptBackup.relocateBackup(devSync2, shrunkCount) }
            val durableAfterSync2Fail = devSync2.durableDevice()
            assertEquals(originalCount - 1UL, GptTable.read(durableAfterSync2Fail, 1UL).header.backupLba)
            assertNull(GptTable.readHeaderOrNull(durableAfterSync2Fail, shrunkCount - 1UL))

            val devSync3 = VolatileCacheBlockDevice(
                shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount).snapshot(),
                sectorSize, shrunkCount, failAtSync = 3, applySyncBeforeFailing = false,
            )
            assertThrows(DiskException.IoFailure::class.java) { GptBackup.relocateBackup(devSync3, shrunkCount) }
            val durableBeforePrimary = devSync3.durableDevice()
            val durableBackup = GptTable.read(durableBeforePrimary, shrunkCount - 1UL)
            assertEquals(listOf("EFI", "DATA"), durableBackup.partitions.map { it.name })
            val fallbackBytes = durableBeforePrimary.snapshot().also {
                it[sectorSize + 16] = (it[sectorSize + 16].toInt() xor 0xFF).toByte()
            }
            val fallback = PartitionTable.read(ImageBlockDevice(fallbackBytes, sectorSize, shrunkCount))
            assertTrue(fallback.gptPrimaryDamaged)
            assertEquals(listOf("EFI", "DATA"), fallback.partitions.map { it.name })

            val devSync3Applied = VolatileCacheBlockDevice(
                shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount).snapshot(),
                sectorSize, shrunkCount, failAtSync = 3, applySyncBeforeFailing = true,
            )
            assertThrows(DiskException.IoFailure::class.java) { GptBackup.relocateBackup(devSync3Applied, shrunkCount) }
            assertEquals(shrunkCount - 1UL, GptTable.read(devSync3Applied.durableDevice(), 1UL).header.backupLba)
        }
    }

    @Test
    fun grownDiskFaultsVerifyFullOldOrNewBackupPairWhenCopiesDoNotOverlap() {
        for (sectorSize in sectorSizes) {
            val oldCount = if (sectorSize == 512) 1024UL else 256UL
            val newCount = if (sectorSize == 512) 2048UL else 512UL
            val entrySectors = entrySectorsFor(sectorSize)

            // Fault before primary header lands keeps primary and full old backup (header + entries) intact.
            for (failAt in 1..2) {
                val grown = grow(SyntheticGpt.build(oldCount, sectorSize), newCount)
                val fault = FaultyBlockDevice(grown, failAtWrite = failAt, applyBeforeFailing = true)
                assertThrows(DiskException.IoFailure::class.java) {
                    GptBackup.relocateBackup(fault, newCount)
                }
                val table = PartitionTable.read(fault)
                assertFalse(table.gptPrimaryDamaged)
                assertEquals(oldCount - 1UL, table.gptBackupHeaderLba)
                val oldBackup = GptTable.read(fault, oldCount - 1UL)
                assertEquals(listOf("EFI", "DATA"), oldBackup.partitions.map { it.name })
                assertEquals(table.gptHeader!!.entriesCrc, oldBackup.header.entriesCrc)
            }

            // Sync 3 is after backup sync and before primary sync; new backup is durable.
            val grownSync3 = grow(SyntheticGpt.build(oldCount, sectorSize), newCount)
            val devSync3 = VolatileCacheBlockDevice(grownSync3.snapshot(), sectorSize, newCount, failAtSync = 3, applySyncBeforeFailing = false)
            assertThrows(DiskException.IoFailure::class.java) { GptBackup.relocateBackup(devSync3, newCount) }
            val durableBeforePrimary = devSync3.durableDevice()
            val primaryBefore = GptTable.read(durableBeforePrimary, 1UL)
            assertEquals(oldCount - 1UL, primaryBefore.header.backupLba)
            assertNotNull(GptTable.readHeaderOrNull(durableBeforePrimary, newCount - 1UL))
            assertEquals(listOf("EFI", "DATA"), GptTable.read(durableBeforePrimary, newCount - 1UL).partitions.map { it.name })

            // Write 3 applies backup header only; primary still advertises old backup.
            val grownWrite3 = grow(SyntheticGpt.build(oldCount, sectorSize), newCount)
            val faultWrite3 = FaultyBlockDevice(grownWrite3, failAtWrite = 3, applyBeforeFailing = true)
            assertThrows(DiskException.IoFailure::class.java) { GptBackup.relocateBackup(faultWrite3, newCount) }
            val afterBackup = GptTable.read(faultWrite3, 1UL)
            assertEquals(oldCount - 1UL, afterBackup.header.backupLba)
            assertNotNull(GptTable.readHeaderOrNull(faultWrite3, newCount - 1UL))
            assertEquals(listOf("EFI", "DATA"), GptTable.read(faultWrite3, newCount - 1UL).partitions.map { it.name })

            // Write 4 applies primary header; both GPT copies advertise new backup.
            val grownWrite4 = grow(SyntheticGpt.build(oldCount, sectorSize), newCount)
            val faultWrite4 = FaultyBlockDevice(grownWrite4, failAtWrite = 4, applyBeforeFailing = true)
            assertThrows(DiskException.IoFailure::class.java) { GptBackup.relocateBackup(faultWrite4, newCount) }
            val afterPrimary = GptTable.read(faultWrite4, 1UL)
            assertEquals(newCount - 1UL, afterPrimary.header.backupLba)
            assertEquals(listOf("EFI", "DATA"), GptTable.read(faultWrite4, newCount - 1UL).partitions.map { it.name })
        }
    }

    @Test
    fun smallGrowthOverwritesOldBackupHeaderBeforeNewHeaderLands() {
        // Not crash-atomic for growth smaller than backupSectors: entries overwrite old header first.
        for (sectorSize in sectorSizes) {
            val oldCount = if (sectorSize == 512) 1024UL else 256UL
            val delta = if (sectorSize == 512) 16UL else 2UL
            val newCount = oldCount + delta
            val grown = grow(SyntheticGpt.build(oldCount, sectorSize), newCount)
            val fault = FaultyBlockDevice(grown, failAtWrite = 3, applyBeforeFailing = false)
            assertThrows(DiskException.IoFailure::class.java) { GptBackup.relocateBackup(fault, newCount) }
            val table = PartitionTable.read(fault)
            assertFalse(table.gptPrimaryDamaged)
            assertEquals(listOf("EFI", "DATA"), table.partitions.map { it.name })
            assertNull("old backup header was overwritten by new backup entries", table.gptBackupHeaderLba)
            assertNull(table.gptBackupEntriesLba)
            val noPrimaryBytes = fault.snapshot().also {
                it[sectorSize + 16] = (it[sectorSize + 16].toInt() xor 0xFF).toByte()
            }
            assertThrows(IllegalArgumentException::class.java) {
                PartitionTable.read(ImageBlockDevice(noPrimaryBytes, sectorSize, newCount))
            }
            val retryDisk = ImageBlockDevice(fault.snapshot(), sectorSize, newCount)
            val retried = GptBackup.relocateBackup(retryDisk, newCount)
            assertEquals(newCount - 1UL, retried.gptBackupHeaderLba)
            assertEquals(listOf("EFI", "DATA"), GptTable.read(retryDisk, newCount - 1UL).partitions.map { it.name })
        }
    }

    @Test
    fun preflightRejectsTooSmallTargetWithoutWriting() {
        for (sectorSize in sectorSizes) {
            val entrySectors = entrySectorsFor(sectorSize)
            val firstUsable = 2UL + entrySectors
            val minValid = firstUsable + entrySectors + 2UL // 68 (512B) or 12 (4096B)
            val disk = SyntheticGpt.build(
                sectorCount = 256UL,
                sectorSize = sectorSize,
                layout = emptyList(),
            )
            val tooSmallDisk = shrink(disk, minValid - 1UL)
            val before = tooSmallDisk.snapshot()
            val fault = FaultyBlockDevice(tooSmallDisk)

            val ex = assertThrows(IllegalArgumentException::class.java) {
                GptBackup.relocateBackup(fault, fault.sectorCount)
            }
            assertTrue(ex.message!!.contains("too small"))
            assertTrue("preflight rejection must not write", fault.ops.none { it.startsWith("write:") || it == "sync" })
            assertTrue("disk must stay byte-identical", fault.snapshot().contentEquals(before))

            // Exact minimum sector count (newLastUsable == firstUsable) is accepted.
            val shrunk = shrink(disk, minValid)
            val ok = GptBackup.relocateBackup(shrunk, minValid)
            assertEquals(minValid - 1UL, ok.gptBackupHeaderLba)
            assertEquals(firstUsable, ok.gptHeader!!.lastUsable)
            assertEquals(emptyList<Partition>(), GptTable.read(shrunk, minValid - 1UL).partitions)
        }
    }

    @Test
    fun preflightRejectsPartitionPastNewLastUsableWithoutWriting() {
        for (sectorSize in sectorSizes) {
            val entrySectors = entrySectorsFor(sectorSize)
            val firstUsable = 2UL + entrySectors
            val partEnd = 100UL
            val disk = SyntheticGpt.build(
                sectorCount = 256UL,
                sectorSize = sectorSize,
                layout = listOf(SyntheticGpt.Layout(0, firstUsable, partEnd, "DATA")),
            )
            // newLastUsable = targetCount - entrySectors - 2
            val tooSmallTarget = partEnd + entrySectors + 1UL
            val tooSmallDisk = shrink(disk, tooSmallTarget)
            val before = tooSmallDisk.snapshot()
            val fault = FaultyBlockDevice(tooSmallDisk)

            val ex = assertThrows(IllegalArgumentException::class.java) {
                GptBackup.relocateBackup(fault, fault.sectorCount)
            }
            assertTrue(ex.message!!.contains("extends past new last usable LBA"))
            assertTrue("preflight rejection must not write", fault.ops.none { it.startsWith("write:") || it == "sync" })
            assertTrue("disk must stay byte-identical", fault.snapshot().contentEquals(before))

            // Exact boundary where newLastUsable == partEnd succeeds.
            val exactTarget = partEnd + entrySectors + 2UL
            val shrunkExact = shrink(disk, exactTarget)
            val ok = GptBackup.relocateBackup(shrunkExact, exactTarget)
            assertEquals(exactTarget - 1UL, ok.gptBackupHeaderLba)
            assertEquals(partEnd, ok.gptHeader!!.lastUsable)
            assertEquals(listOf("DATA"), ok.partitions.map { it.name })
            assertEquals(listOf("DATA"), GptTable.read(shrunkExact, exactTarget - 1UL).partitions.map { it.name })
        }
    }

    @Test
    fun preflightRejectsDamagedPrimaryOn4096WithoutWriting() {
        val sectorSize = 4096
        val disk = SyntheticGpt.build(sectorCount = 256UL, sectorSize = sectorSize)
        val bytes = disk.snapshot()
        bytes[sectorSize + 16] = (bytes[sectorSize + 16].toInt() xor 0xFF).toByte()
        val damaged = ImageBlockDevice(bytes, sectorSize, 256UL)
        val before = damaged.snapshot()
        val fault = FaultyBlockDevice(damaged)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            GptBackup.relocateBackup(fault, fault.sectorCount)
        }
        assertTrue(ex.message!!.contains("GPT header CRC mismatch") || ex.message!!.contains("GPT entries CRC mismatch"))
        assertTrue(fault.ops.none { it.startsWith("write:") || it == "sync" })
        assertTrue(fault.snapshot().contentEquals(before))
    }
    @Test
    fun staleProtectivePmbrIsRepairedBeforeGptFor512And4096() {
        for (sectorSize in sectorSizes) {
            val original = if (sectorSize == 512) 2048UL else 512UL
            val target = if (sectorSize == 512) 1024UL else 256UL
            val fault = FaultyBlockDevice(shrink(SyntheticGpt.build(original, sectorSize), target))
            val result = GptBackup.relocateBackup(fault, target)
            val pmbrWrite = fault.ops.indexOf("write:0")
            val firstSync = fault.ops.indexOf("sync")
            val backupEntries = fault.ops.indexOf("write:${target - 1UL - entrySectorsFor(sectorSize)}")
            val secondSync = fault.ops.withIndex().first { it.index > firstSync && it.value == "sync" }.index
            val primaryWrite = fault.ops.indexOf("write:1")
            assertTrue(pmbrWrite >= 0)
            assertTrue(firstSync > pmbrWrite)
            assertTrue(backupEntries > firstSync)
            assertTrue(primaryWrite > secondSync)
            assertEquals(target - 1UL, result.gptBackupHeaderLba)
            assertEquals(target - 1UL, MbrTable.readProtectiveOnly(fault)!!.sectorCount)
            GptTable.read(fault, 1UL)
            GptTable.read(fault, target - 1UL)
        }
    }

    @Test
    fun pmbrWriteFailureLeavesGPTUntouchedAndRetryable() {
        for (sectorSize in sectorSizes) {
            val original = if (sectorSize == 512) 2048UL else 512UL
            val target = if (sectorSize == 512) 1024UL else 256UL
            val disk = shrink(SyntheticGpt.build(original, sectorSize), target)
            val before = disk.snapshot()
            val fault = FaultyBlockDevice(disk, failAtWrite = 1)
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(fault, target)
            }
            assertEquals(listOf("write:0"), fault.ops.filter { it.startsWith("write:") })
            assertTrue(fault.snapshot().contentEquals(before))
            val retry = GptBackup.relocateBackup(fault, target)
            assertEquals(target - 1UL, retry.gptBackupHeaderLba)
        }
    }

    @Test
    fun pmbrTornWriteAndSyncFailureStopBeforeGptWrites() {
        for (sectorSize in sectorSizes) {
            val original = if (sectorSize == 512) 4096UL else 2048UL
            val target = if (sectorSize == 512) 1024UL else 256UL
            val image = shrink(SyntheticGpt.build(original, sectorSize), target)
            val torn = VolatileCacheBlockDevice(
                image.snapshot(), sectorSize, target, tornWriteAt = 1, tornSingleSectorBytes = 459,
            )
            assertThrows(DiskException.IoFailure::class.java) { GptBackup.relocateBackup(torn, target) }
            val tornLive = torn.liveDevice()
            val tornPmbr = requireNotNull(MbrTable.readProtectiveOnly(tornLive))
            assertTrue("torn write must leave PMBR count stale", tornPmbr.sectorCount == original - 1UL)
            assertEquals(original - 1UL, GptTable.read(tornLive, 1UL).header.backupLba)
            assertEquals(listOf(0UL), torn.writeLbas)

            val syncFail = VolatileCacheBlockDevice(image.snapshot(), sectorSize, target, failAtSync = 1)
            assertThrows(DiskException.IoFailure::class.java) { GptBackup.relocateBackup(syncFail, target) }
            val syncLive = syncFail.liveDevice()
            assertEquals(original - 1UL, GptTable.read(syncLive, 1UL).header.backupLba)
            assertEquals(listOf(0UL), syncFail.writeLbas)
        }
    }

    @Test
    fun mixedTornPmbrCountFailsClosedBeforeGptWrites() {
        for (sectorSize in sectorSizes) {
            val original = if (sectorSize == 512) 4096UL else 2048UL
            val target = if (sectorSize == 512) 1025UL else 513UL
            val image = shrink(SyntheticGpt.build(original, sectorSize), target).snapshot()
            val countOffset = 446 + 12
            val mixed = image.copyOf().also {
                it[countOffset] = (target - 1UL).toByte()
                it[countOffset + 1] = ((original - 1UL) shr 8).toByte()
            }
            val fault = FaultyBlockDevice(ImageBlockDevice(mixed, sectorSize, target))
            assertThrows(HybridAmbiguityException::class.java) {
                GptBackup.relocateBackup(fault, target)
            }
            assertTrue(fault.ops.none { it.startsWith("write:") })
            assertTrue(fault.ops.none { it == "sync" })
        }
    }

}
