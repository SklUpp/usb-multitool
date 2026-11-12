package usb.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure translation between the SectorForge engine's byte-oriented block list and the
 * app's LBA-oriented `usb-rescue-map 2`. The engine itself is exercised by
 * `cargo test --manifest-path app/src/main/rust/sf-bridge/Cargo.toml`.
 */
class SectorForgeBlocksTest {
    private val sectorSize = 512

    @Test fun seedsInheritCopiedAndRetryFailed() {
        val map = RescueMap.forCopy(8UL, sectorSize = sectorSize)
        map.markCopied(0UL, 2UL)
        map.markFailed(5UL, 1UL)

        val seeds = SectorForgeBlocks.seeds(map)

        assertEquals(6, seeds.size)
        assertEquals(listOf(0L, 2L, SectorForgeBlocks.COPIED), seeds.toList().subList(0, 3))
        assertEquals(listOf(5L, 1L, SectorForgeBlocks.NON_TRIMMED), seeds.toList().subList(3, 6))
    }

    @Test fun snapshotRepaintsStatusesAndConvertsToLba() {
        val map = RescueMap.forCopy(4UL, sectorSize = sectorSize)
        val blocks =
            longArrayOf(
                0L, 2L * sectorSize, SectorForgeBlocks.COPIED,
                2L * sectorSize, sectorSize.toLong(), SectorForgeBlocks.FAILED,
                3L * sectorSize, sectorSize.toLong(), SectorForgeBlocks.NON_TRIED,
            )

        SectorForgeBlocks.apply(map, blocks, sectorSize, inheritedFailed = emptyList())

        assertEquals(listOf(0UL until 2UL), map.copied)
        assertEquals(listOf(2UL until 3UL), map.failed)
        assertEquals(listOf(3UL until 4UL), map.nonTried)
    }

    @Test fun parkedAndHalfTrimmedStagesStayNonTried() {
        val map = RescueMap.forCopy(3UL, sectorSize = sectorSize)
        val blocks =
            longArrayOf(
                0L, sectorSize.toLong(), SectorForgeBlocks.NON_TRIMMED,
                sectorSize.toLong(), sectorSize.toLong(), SectorForgeBlocks.NON_SCRAPED,
                (2L * sectorSize), sectorSize.toLong(), SectorForgeBlocks.COPIED,
            )

        SectorForgeBlocks.apply(map, blocks, sectorSize, inheritedFailed = emptyList())

        assertEquals(listOf(0UL until 2UL), map.nonTried)
        assertEquals(listOf(2UL until 3UL), map.copied)
    }

    @Test fun interruptedRunKeepsInheritedFailedKnowledge() {
        val map = RescueMap.forCopy(4UL, sectorSize = sectorSize)
        map.markFailed(1UL, 1UL)
        val inherited = map.failed
        // The run resolved sector 0 and was cancelled before retrying sector 1.
        val blocks =
            longArrayOf(
                0L, sectorSize.toLong(), SectorForgeBlocks.COPIED,
                sectorSize.toLong(), 3L * sectorSize, SectorForgeBlocks.NON_TRIED,
            )

        SectorForgeBlocks.apply(map, blocks, sectorSize, inherited)

        assertEquals(listOf(2UL until 4UL), map.nonTried)
        assertEquals(listOf(1UL until 2UL), map.failed)
    }

    @Test fun inheritedFailedDoesNotOverwriteRecoveredSector() {
        val map = RescueMap.forCopy(2UL, sectorSize = sectorSize)
        map.markFailed(1UL, 1UL)
        val inherited = map.failed
        val blocks = longArrayOf(0L, 2L * sectorSize, SectorForgeBlocks.COPIED)

        SectorForgeBlocks.apply(map, blocks, sectorSize, inherited)

        assertTrue(map.failed.isEmpty())
        assertEquals(listOf(0UL until 2UL), map.copied)
    }

    @Test fun inheritedFailedOnlySplitsUnresolvedRuns() {
        val map = RescueMap.forCopy(6UL, sectorSize = sectorSize)
        map.markFailed(0UL, 1UL)
        map.markFailed(4UL, 1UL)
        val inherited = map.failed
        val blocks =
            longArrayOf(
                0L, sectorSize.toLong(), SectorForgeBlocks.COPIED,
                sectorSize.toLong(), 5L * sectorSize, SectorForgeBlocks.NON_TRIED,
            )

        SectorForgeBlocks.apply(map, blocks, sectorSize, inherited)

        assertEquals(listOf(0UL until 1UL), map.copied)
        assertEquals(listOf(4UL until 5UL), map.failed)
        assertEquals(listOf(1UL until 4UL, 5UL until 6UL), map.nonTried)
    }


    @Test fun snapshotReplacesStaleCheckpointProgress() {
        val map = RescueMap.forCopy(4UL, sectorSize = sectorSize)
        map.markCopied(0UL, 4UL) // stale: a previous checkpoint claimed everything
        map.markFailed(3UL, 1UL)

        SectorForgeBlocks.apply(
            map,
            longArrayOf(
                0L, sectorSize.toLong(), SectorForgeBlocks.COPIED,
                sectorSize.toLong(), 3L * sectorSize, SectorForgeBlocks.NON_TRIED,
            ),
            sectorSize,
            inheritedFailed = emptyList(),
        )

        assertEquals(listOf(0UL until 1UL), map.copied)
        assertEquals(listOf(1UL until 4UL), map.nonTried)
        assertTrue(map.failed.isEmpty())
    }

    @Test
    fun uncertainWriteOverridesInheritedFailedForRestoreSafety() {
        val map = RescueMap.forCopy(4UL, sectorSize = 512)
        SectorForgeBlocks.apply(
            map,
            longArrayOf(0L, 4L * 512, SectorForgeBlocks.NON_TRIED),
            512,
            inheritedFailed = listOf(1UL until 2UL),
            uncertainWrites = listOf(1UL until 2UL),
        )
        assertEquals(listOf(0UL until 4UL), map.nonTried)
        assertTrue(map.failed.isEmpty())
        val saved = RescueMap.decode(map.encode())
        assertEquals(listOf(1UL until 2UL), saved.nonTried)
        assertThrows(IllegalStateException::class.java) {
            RescueRestorePrep.prepare(saved, "disk", 512, liveHasSerial = true)
        }
    }

    @Test fun demoteFailedToNonTriedIsLinearAndCoalesces() {
        val map =
            RescueMap.forCopy(8UL, sectorSize = sectorSize, identity = "SN-42")
        map.markCopied(0UL, 1UL)
        map.markFailed(1UL, 2UL)
        map.markFailed(4UL, 1UL)

        map.demoteFailedToNonTried()

        assertEquals(listOf(0UL until 1UL), map.copied)
        assertTrue(map.failed.isEmpty())
        assertEquals(listOf(1UL until 8UL), map.nonTried)
        assertEquals(2, map.runCount)
        assertEquals(8UL, map.sectorCount)
        assertEquals(MapKind.COPY, map.kind)
        assertEquals("SN-42", map.identity)
    }

    @Test fun snapshotRetainsIdentityKindAndSectorSize() {
        val map =
            RescueMap.forCopy(2UL, sectorSize = sectorSize, identity = "SN-0123456789")

        SectorForgeBlocks.apply(
            map,
            longArrayOf(0L, 2L * sectorSize, SectorForgeBlocks.COPIED),
            sectorSize,
            inheritedFailed = emptyList(),
        )

        assertEquals(2UL, map.sectorCount)
        assertEquals(MapKind.COPY, map.kind)
        assertEquals(sectorSize, map.sectorSize)
        assertEquals("SN-0123456789", map.identity)
        assertEquals(listOf(0UL until 2UL), map.copied)
    }

    @Test
    fun nativeUntriedAfterAbortCannotBecomeFailed() {
        val map = RescueMap.forCopy(3UL, sectorSize = sectorSize)
        SectorForgeBlocks.apply(
            map,
            longArrayOf(
                0L, sectorSize.toLong(), SectorForgeBlocks.COPIED,
                sectorSize.toLong(), 2L * sectorSize, SectorForgeBlocks.NON_TRIED,
            ),
            sectorSize,
            inheritedFailed = emptyList(),
        )
        assertEquals(listOf(0UL until 1UL), map.copied)
        assertEquals(listOf(1UL until 3UL), map.nonTried)
        assertTrue(map.failed.isEmpty())
    }

    @Test fun fragmentedSnapshotReconstructsEveryRun() {
        val sectors = 3_000
        val blocks = LongArray(sectors * 3)
        for (sector in 0 until sectors) {
            val i = sector * 3
            blocks[i] = sector.toLong() * sectorSize
            blocks[i + 1] = sectorSize.toLong()
            blocks[i + 2] =
                if (sector % 2 == 0) SectorForgeBlocks.COPIED else SectorForgeBlocks.FAILED
        }
        val map = RescueMap.forCopy(sectors.toULong(), sectorSize = sectorSize)

        SectorForgeBlocks.apply(map, blocks, sectorSize, inheritedFailed = emptyList())

        assertEquals(sectors, map.runCount)
        assertEquals(sectors / 2, map.copied.size)
        assertEquals(sectors / 2, map.failed.size)
        assertEquals(0UL until 1UL, map.copied.first())
        assertEquals((sectors - 1).toULong() until sectors.toULong(), map.failed.last())
        assertEquals(RescueStatus.FAILED, map.statusAt((sectors - 1).toULong()))
        assertTrue(map.partial)
    }

    @Test fun overFragmentedSnapshotIsRefusedAtomically() {
        val sectors = RescueMap.MAX_RUN_LINES + 1
        val blocks = LongArray(sectors * 3)
        for (sector in 0 until sectors) {
            val i = sector * 3
            blocks[i] = sector.toLong() * sectorSize
            blocks[i + 1] = sectorSize.toLong()
            blocks[i + 2] =
                if (sector % 2 == 0) SectorForgeBlocks.COPIED else SectorForgeBlocks.NON_TRIED
        }
        val map = RescueMap.forCopy(sectors.toULong(), sectorSize = sectorSize)
        map.markCopied(0UL, 1UL)

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SectorForgeBlocks.apply(map, blocks, sectorSize, inheritedFailed = emptyList())
            }

        assertTrue(error.message!!.contains("max ${RescueMap.MAX_RUN_LINES}"))
        assertEquals(2, map.runCount)
        assertEquals(listOf(0UL until 1UL), map.copied)
        assertEquals(listOf(1UL until sectors.toULong()), map.nonTried)
    }

    @Test fun inheritedFailedOverlayRespectsRunCapAtomically() {
        val pairs = 2_501
        val sectors = pairs * 4
        val blocks = LongArray(pairs * 6)
        val inherited = ArrayList<ULongRange>(pairs)
        for (pair in 0 until pairs) {
            val base = (pair * 4).toLong() * sectorSize
            var i = pair * 6
            blocks[i] = base
            blocks[i + 1] = sectorSize.toLong()
            blocks[i + 2] = SectorForgeBlocks.COPIED
            blocks[i + 3] = base + sectorSize
            blocks[i + 4] = 3L * sectorSize
            blocks[i + 5] = SectorForgeBlocks.NON_TRIED
            val middle = (pair * 4 + 2).toULong()
            inherited += middle..middle
        }
        val map = RescueMap.forCopy(sectors.toULong(), sectorSize = sectorSize)

        // 2_501 copied + 2_501 non-tried runs split by one inherited failure each
        // would leave 10_004 runs — one map over the encode cap.
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SectorForgeBlocks.apply(map, blocks, sectorSize, inherited)
            }

        assertTrue(error.message!!.contains("max ${RescueMap.MAX_RUN_LINES}"))
        assertEquals(1, map.runCount)
        assertEquals(listOf(0UL until sectors.toULong()), map.nonTried)
    }

    @Test fun incompleteSnapshotIsRefusedAtomically() {
        val map = RescueMap.forCopy(4UL, sectorSize = sectorSize)
        map.markCopied(0UL, 2UL)

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SectorForgeBlocks.apply(
                    map,
                    longArrayOf(0L, sectorSize.toLong(), SectorForgeBlocks.COPIED),
                    sectorSize,
                    inheritedFailed = emptyList(),
                )
            }

        assertTrue(error.message!!.contains("complete"))
        assertEquals(listOf(0UL until 2UL), map.copied)
        assertEquals(listOf(2UL until 4UL), map.nonTried)
    }

    @Test fun overlappingSnapshotIsRefusedAtomically() {
        val map = RescueMap.forCopy(3UL, sectorSize = sectorSize)
        map.markCopied(0UL, 3UL)

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SectorForgeBlocks.apply(
                    map,
                    longArrayOf(
                        0L, sectorSize.toLong(), SectorForgeBlocks.COPIED,
                        sectorSize.toLong(), sectorSize.toLong(), SectorForgeBlocks.FAILED,
                        sectorSize.toLong(), 2L * sectorSize, SectorForgeBlocks.NON_TRIED,
                    ),
                    sectorSize,
                    inheritedFailed = emptyList(),
                )
            }

        assertTrue(error.message!!.contains("ordered"))
        assertEquals(listOf(0UL until 3UL), map.copied)
    }

    @Test fun snapshotPastSectorCountIsRefusedAtomically() {
        val map = RescueMap.forCopy(3UL, sectorSize = sectorSize)
        map.markFailed(2UL, 1UL)

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SectorForgeBlocks.apply(
                    map,
                    longArrayOf(0L, 4L * sectorSize, SectorForgeBlocks.NON_TRIED),
                    sectorSize,
                    inheritedFailed = emptyList(),
                )
            }

        assertTrue(error.message!!.contains("past sectorCount"))
        assertEquals(listOf(2UL until 3UL), map.failed)
        assertEquals(listOf(0UL until 2UL), map.nonTried)
    }

    @Test fun emptySnapshotIsRefusedAtomically() {
        val map = RescueMap.forCopy(2UL, sectorSize = sectorSize)
        map.markCopied(0UL, 2UL)

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SectorForgeBlocks.apply(map, LongArray(0), sectorSize, inheritedFailed = emptyList())
            }

        assertTrue(error.message!!.contains("expected 2"))
        assertEquals(listOf(0UL until 2UL), map.copied)
    }

    @Test fun unknownStatusIsRefusedAtomically() {
        val map = RescueMap.forCopy(2UL, sectorSize = sectorSize)
        map.markCopied(0UL, 2UL)

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SectorForgeBlocks.apply(
                    map,
                    longArrayOf(0L, 2L * sectorSize, 9L),
                    sectorSize,
                    inheritedFailed = emptyList(),
                )
            }

        assertTrue(error.message!!.contains("unknown native block status 9"))
        assertEquals(listOf(0UL until 2UL), map.copied)
    }

    @Test fun unalignedSnapshotIsRefused() {
        val map = RescueMap.forCopy(4UL, sectorSize = sectorSize)
        try {
            SectorForgeBlocks.apply(
                map,
                longArrayOf(1L, sectorSize.toLong(), SectorForgeBlocks.COPIED),
                sectorSize,
                inheritedFailed = emptyList(),
            )
            org.junit.Assert.fail("expected an alignment failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sector aligned"))
        }
    }
}
