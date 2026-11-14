package usb.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

        override fun read(lba: ULong, destination: ByteBuffer) {
            val bytes = destination.remaining()
            require(bytes % sectorSize == 0)
            val blocks = bytes / sectorSize
            require(blocks.toULong() <= sectorCount && lba <= sectorCount - blocks.toULong())
            val offset = (lba * sectorSize.toULong()).toInt()
            destination.put(working, offset, bytes)
        }

        override fun write(lba: ULong, source: ByteBuffer) {
            writeCount++
            val bytes = source.remaining()
            require(bytes % sectorSize == 0)
            val blocks = bytes / sectorSize
            require(blocks.toULong() <= sectorCount && lba <= sectorCount - blocks.toULong())
            val offset = (lba * sectorSize.toULong()).toInt()
            if (writeCount == tornWriteAt) {
                val partial = if (blocks > 1) {
                    (blocks / 2) * sectorSize
                } else {
                    minOf(tornSingleSectorBytes, bytes)
                }
                if (partial > 0) {
                    source.get(working, offset, partial)
                }
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
            val entrySectors = entrySectorsFor(sectorSize)

            for (failAt in 1..3) {
                for (applyBeforeFailing in listOf(false, true)) {
                    val shrunk = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
                    val fault = FaultyBlockDevice(
                        delegate = shrunk,
                        failAtWrite = failAt,
                        applyBeforeFailing = applyBeforeFailing,
                    )

                    assertThrows(DiskException.IoFailure::class.java) {
                        GptBackup.relocateBackup(fault, shrunkCount)
                    }

                    val recovered = PartitionTable.read(fault)
                    assertEquals(TableScheme.GPT, recovered.scheme)
                    assertFalse(recovered.gptPrimaryDamaged)
                    assertEquals(listOf("EFI", "DATA"), recovered.partitions.map { it.name })
                    if (failAt == 3 && applyBeforeFailing) {
                        assertEquals(shrunkCount - 1UL, recovered.gptBackupHeaderLba)
                        assertEquals(shrunkCount - 1UL - entrySectors, recovered.gptBackupEntriesLba)
                        val backup = GptTable.read(fault, shrunkCount - 1UL)
                        assertEquals(listOf("EFI", "DATA"), backup.partitions.map { it.name })
                        val damagedPrimary = fault.snapshot().also {
                            it[sectorSize + 16] = (it[sectorSize + 16].toInt() xor 0xFF).toByte()
                        }
                        val fallback = PartitionTable.read(
                            ImageBlockDevice(damagedPrimary, sectorSize, shrunkCount),
                        )
                        assertTrue(fallback.gptPrimaryDamaged)
                        assertEquals(shrunkCount - 1UL, fallback.gptBackupHeaderLba)
                        assertEquals(listOf("EFI", "DATA"), fallback.partitions.map { it.name })
                    } else {
                        assertNull(recovered.gptBackupHeaderLba)
                        assertNull(recovered.gptBackupEntriesLba)
                        assertEquals(originalCount - 1UL, recovered.gptHeader!!.backupLba)
                    }
                }
            }

            // FaultyBlockDevice tornWriteAt=1 lands half of the multi-sector entry array write.
            val shrunk = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
            val fault = FaultyBlockDevice(shrunk, tornWriteAt = 1)
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(fault, shrunkCount)
            }
            val recovered = PartitionTable.read(fault)
            assertEquals(TableScheme.GPT, recovered.scheme)
            assertFalse(recovered.gptPrimaryDamaged)
            assertEquals(listOf("EFI", "DATA"), recovered.partitions.map { it.name })
            assertNull(recovered.gptBackupHeaderLba)
        }
    }

    @Test
    fun singleSectorTornWritesOnBackupAndPrimaryHeadersAreHandledSafely() {
        for (sectorSize in sectorSizes) {
            val originalCount = if (sectorSize == 512) 2048UL else 512UL
            val shrunkCount = if (sectorSize == 512) 1024UL else 256UL
            val entrySectors = entrySectorsFor(sectorSize)

            // Torn Write 2 (backup header on shrunk disk): primary stays valid; torn sector rejected.
            val shrunkTorn2 = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
            val devTorn2 = VolatileCacheBlockDevice(
                shrunkTorn2.snapshot(),
                sectorSize,
                shrunkCount,
                tornWriteAt = 2,
            )
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(devTorn2, shrunkCount)
            }
            val live2 = devTorn2.liveDevice()
            val afterTorn2 = PartitionTable.read(live2)
            assertFalse(afterTorn2.gptPrimaryDamaged)
            assertEquals(listOf("EFI", "DATA"), afterTorn2.partitions.map { it.name })
            assertNull(afterTorn2.gptBackupHeaderLba)
            assertNull(GptTable.readHeaderOrNull(live2, shrunkCount - 1UL))

            // Torn Write 2 on grown non-overlapping disk: primary + old backup remain intact.
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

            // Torn Write 3 (primary header after backup synced): falls back to synced backup copy.
            val shrunkTorn3 = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
            val devShrunkTorn3 = VolatileCacheBlockDevice(
                shrunkTorn3.snapshot(),
                sectorSize,
                shrunkCount,
                tornWriteAt = 3,
            )
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(devShrunkTorn3, shrunkCount)
            }
            val liveShrunk3 = devShrunkTorn3.liveDevice()
            assertNull("torn primary header must fail CRC", GptTable.readHeaderOrNull(liveShrunk3, 1UL))
            val fallbackShrunk = PartitionTable.read(liveShrunk3)
            assertTrue(fallbackShrunk.gptPrimaryDamaged)
            assertEquals(shrunkCount - 1UL, fallbackShrunk.gptBackupHeaderLba)
            assertEquals(shrunkCount - 1UL - entrySectors, fallbackShrunk.gptBackupEntriesLba)
            assertEquals(listOf("EFI", "DATA"), fallbackShrunk.partitions.map { it.name })

            val grownTorn3 = grow(SyntheticGpt.build(shrunkCount, sectorSize), originalCount)
            val devGrownTorn3 = VolatileCacheBlockDevice(
                grownTorn3.snapshot(),
                sectorSize,
                originalCount,
                tornWriteAt = 3,
            )
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(devGrownTorn3, originalCount)
            }
            val liveGrown3 = devGrownTorn3.liveDevice()
            assertNull("torn primary header must fail CRC", GptTable.readHeaderOrNull(liveGrown3, 1UL))
            val fallbackGrown = PartitionTable.read(liveGrown3)
            assertTrue(fallbackGrown.gptPrimaryDamaged)
            assertEquals(originalCount - 1UL, fallbackGrown.gptBackupHeaderLba)
            assertEquals(originalCount - 1UL - entrySectors, fallbackGrown.gptBackupEntriesLba)
            assertEquals(listOf("EFI", "DATA"), fallbackGrown.partitions.map { it.name })
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
            val tableSync1Fail = PartitionTable.read(durableAfterSync1Fail)
            assertFalse(tableSync1Fail.gptPrimaryDamaged)
            assertEquals(originalCount - 1UL, tableSync1Fail.gptHeader!!.backupLba)
            assertNull(tableSync1Fail.gptBackupHeaderLba)
            assertNull(
                "un-synced backup header must not be assumed durable",
                GptTable.readHeaderOrNull(durableAfterSync1Fail, shrunkCount - 1UL),
            )

            // Retry from durable state completes cleanly.
            val retried = GptBackup.relocateBackup(durableAfterSync1Fail, shrunkCount)
            assertEquals(shrunkCount - 1UL, retried.gptBackupHeaderLba)
            assertEquals(listOf("EFI", "DATA"), GptTable.read(durableAfterSync1Fail, shrunkCount - 1UL).partitions.map { it.name })

            // Sync 1 succeeded, power loss before Sync 2 flushes primary: new backup IS durable.
            val shrunkSync2 = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
            val devSync2 = VolatileCacheBlockDevice(
                shrunkSync2.snapshot(),
                sectorSize,
                shrunkCount,
                failAtSync = 2,
                applySyncBeforeFailing = false,
            )
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(devSync2, shrunkCount)
            }
            val durableAfterSync2Fail = devSync2.durableDevice()
            val tableSync2Fail = PartitionTable.read(durableAfterSync2Fail)
            assertFalse(tableSync2Fail.gptPrimaryDamaged)
            assertEquals(originalCount - 1UL, tableSync2Fail.gptHeader!!.backupLba)
            // Verify the durable backup copy itself (both header and entry array CRC).
            val durableBackup = GptTable.read(durableAfterSync2Fail, shrunkCount - 1UL)
            assertEquals(shrunkCount - 1UL - entrySectors, durableBackup.header.entryLba)
            assertEquals(shrunkCount - 2UL - entrySectors, durableBackup.header.lastUsable)
            assertEquals(listOf("EFI", "DATA"), durableBackup.partitions.map { it.name })
            val damagedPrimarySync2 = durableAfterSync2Fail.snapshot().also {
                it[sectorSize + 16] = (it[sectorSize + 16].toInt() xor 0xFF).toByte()
            }
            val fallbackSync2 = PartitionTable.read(
                ImageBlockDevice(damagedPrimarySync2, sectorSize, shrunkCount),
            )
            assertTrue(fallbackSync2.gptPrimaryDamaged)
            assertEquals(shrunkCount - 1UL, fallbackSync2.gptBackupHeaderLba)
            assertEquals(listOf("EFI", "DATA"), fallbackSync2.partitions.map { it.name })

            // Sync 2 flushed before reporting error: both primary and backup are durable and updated.
            val shrunkSync2Applied = shrink(SyntheticGpt.build(originalCount, sectorSize), shrunkCount)
            val devSync2Applied = VolatileCacheBlockDevice(
                shrunkSync2Applied.snapshot(),
                sectorSize,
                shrunkCount,
                failAtSync = 2,
                applySyncBeforeFailing = true,
            )
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(devSync2Applied, shrunkCount)
            }
            val durableUpdated = devSync2Applied.durableDevice()
            val tableUpdated = PartitionTable.read(durableUpdated)
            assertFalse(tableUpdated.gptPrimaryDamaged)
            assertEquals(shrunkCount - 1UL, tableUpdated.gptBackupHeaderLba)
            assertEquals(shrunkCount - 1UL - entrySectors, tableUpdated.gptBackupEntriesLba)
            assertEquals(listOf("EFI", "DATA"), GptTable.read(durableUpdated, shrunkCount - 1UL).partitions.map { it.name })
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

            // After Sync 1 succeeds (power loss before Sync 2), both old backup and new backup are durable & CRC-valid.
            val grownSync2 = grow(SyntheticGpt.build(oldCount, sectorSize), newCount)
            val devSync2 = VolatileCacheBlockDevice(
                grownSync2.snapshot(),
                sectorSize,
                newCount,
                failAtSync = 2,
                applySyncBeforeFailing = false,
            )
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(devSync2, newCount)
            }
            val durableBeforePrimary = devSync2.durableDevice()
            val primaryView = PartitionTable.read(durableBeforePrimary)
            assertFalse(primaryView.gptPrimaryDamaged)
            assertEquals(oldCount - 1UL, primaryView.gptBackupHeaderLba)
            assertEquals(listOf("EFI", "DATA"), GptTable.read(durableBeforePrimary, oldCount - 1UL).partitions.map { it.name })
            val durableNewBackup = GptTable.read(durableBeforePrimary, newCount - 1UL)
            assertEquals(newCount - 1UL - entrySectors, durableNewBackup.header.entryLba)
            assertEquals(listOf("EFI", "DATA"), durableNewBackup.partitions.map { it.name })
            val damagedPrimaryGrown = durableBeforePrimary.snapshot().also {
                it[sectorSize + 16] = (it[sectorSize + 16].toInt() xor 0xFF).toByte()
            }
            val fallbackGrown = PartitionTable.read(
                ImageBlockDevice(damagedPrimaryGrown, sectorSize, newCount),
            )
            assertTrue(fallbackGrown.gptPrimaryDamaged)
            assertEquals(newCount - 1UL, fallbackGrown.gptBackupHeaderLba)
            assertEquals(listOf("EFI", "DATA"), fallbackGrown.partitions.map { it.name })

            // Write 3 (primary) applied: primary and new backup match with full entries CRC verified.
            val grownWrite3 = grow(SyntheticGpt.build(oldCount, sectorSize), newCount)
            val faultWrite3 = FaultyBlockDevice(grownWrite3, failAtWrite = 3, applyBeforeFailing = true)
            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(faultWrite3, newCount)
            }
            val afterPrimary = PartitionTable.read(faultWrite3)
            assertFalse(afterPrimary.gptPrimaryDamaged)
            assertEquals(newCount - 1UL, afterPrimary.gptBackupHeaderLba)
            assertEquals(newCount - 1UL - entrySectors, afterPrimary.gptBackupEntriesLba)
            val verifiedNewBackup = GptTable.read(faultWrite3, newCount - 1UL)
            assertEquals(listOf("EFI", "DATA"), verifiedNewBackup.partitions.map { it.name })
            assertEquals(afterPrimary.gptHeader!!.entriesCrc, verifiedNewBackup.header.entriesCrc)
            val damagedPrimaryAfterWrite3 = faultWrite3.snapshot().also {
                it[sectorSize + 16] = (it[sectorSize + 16].toInt() xor 0xFF).toByte()
            }
            val fallbackAfterWrite3 = PartitionTable.read(
                ImageBlockDevice(damagedPrimaryAfterWrite3, sectorSize, newCount),
            )
            assertTrue(fallbackAfterWrite3.gptPrimaryDamaged)
            assertEquals(newCount - 1UL, fallbackAfterWrite3.gptBackupHeaderLba)
            assertEquals(listOf("EFI", "DATA"), fallbackAfterWrite3.partitions.map { it.name })
        }
    }

    @Test
    fun smallGrowthOverwritesOldBackupHeaderBeforeNewHeaderLands() {
        // Not crash-atomic for growth smaller than backupSectors: Write 1 (new backup entries)
        // overwrites the old backup header before Write 2 writes the new backup header.
        for (sectorSize in sectorSizes) {
            val oldCount = if (sectorSize == 512) 1024UL else 256UL
            val delta = if (sectorSize == 512) 16UL else 2UL
            val newCount = oldCount + delta
            val grown = grow(SyntheticGpt.build(oldCount, sectorSize), newCount)
            // Fail at Write 2 before new backup header lands; Write 1 already overwrote oldCount - 1.
            val fault = FaultyBlockDevice(grown, failAtWrite = 2, applyBeforeFailing = false)

            assertThrows(DiskException.IoFailure::class.java) {
                GptBackup.relocateBackup(fault, newCount)
            }

            // Primary remains readable and retryable, but no valid backup copy exists until retry finishes.
            val table = PartitionTable.read(fault)
            assertFalse(table.gptPrimaryDamaged)
            assertEquals(listOf("EFI", "DATA"), table.partitions.map { it.name })
            assertNull("old backup header was overwritten by new backup entries", table.gptBackupHeaderLba)
            assertNull(table.gptBackupEntriesLba)

            // Simultaneous primary loss during that window leaves no recoverable backup header.
            val noPrimaryBytes = fault.snapshot()
            noPrimaryBytes[sectorSize + 16] = (noPrimaryBytes[sectorSize + 16].toInt() xor 0xFF).toByte()
            assertThrows(IllegalArgumentException::class.java) {
                PartitionTable.read(ImageBlockDevice(noPrimaryBytes, sectorSize, newCount))
            }

            // Retrying from the surviving primary restores a valid backup at newCount - 1.
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
            val before = disk.snapshot()
            val fault = FaultyBlockDevice(disk)

            val ex = assertThrows(IllegalArgumentException::class.java) {
                GptBackup.relocateBackup(fault, minValid - 1UL)
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
            val tooSmallTarget = partEnd + entrySectors + 1UL // newLastUsable == partEnd - 1
            val before = disk.snapshot()
            val fault = FaultyBlockDevice(disk)

            val ex = assertThrows(IllegalArgumentException::class.java) {
                GptBackup.relocateBackup(fault, tooSmallTarget)
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
            GptBackup.relocateBackup(fault, 256UL)
        }
        assertTrue(ex.message!!.contains("refusing to relocate"))
        assertTrue(fault.ops.none { it.startsWith("write:") || it == "sync" })
        assertTrue(fault.snapshot().contentEquals(before))
    }
}
