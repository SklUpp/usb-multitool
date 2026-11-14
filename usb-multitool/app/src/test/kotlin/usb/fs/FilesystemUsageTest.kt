package usb.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.EngineMissingException

class FilesystemUsageTest {
    private val gib = 1024uL * 1024uL * 1024uL

    private val key = FilesystemUsageKey(
        attachGeneration = 7L,
        identity = "Stick#SER",
        partitionStartLba = 2048uL,
        partitionSectorCount = 1_000_000uL,
        sectorSize = 512,
        filesystem = FilesystemId.EXFAT,
    )

    @Test fun knownSpaceCarriesValuesAndKey() = runBlocking {
        val volume = usageVolume { FilesystemSpace(totalBytes = 10uL * gib, freeBytes = 4uL * gib) }

        val snapshot = FilesystemUsageProbe.measure(volume, key)

        assertEquals(key, snapshot.key)
        assertTrue(snapshot.matches(key))
        val known = snapshot.usage as FilesystemUsage.Known
        assertEquals(10uL * gib, known.space.totalBytes)
        assertEquals(4uL * gib, known.space.freeBytes)
        assertEquals(6uL * gib, known.space.usedBytes)
        assertTrue(known.measuredAtMillis > 0L)
    }


    @Test fun driverAccessIsCarriedWithUsage() = runBlocking {
        val volume = usageVolume(
            provider = { FilesystemSpace(totalBytes = 4uL, freeBytes = 2uL) },
            access = FilesystemAccess.ReadOnly,
        )
        val snapshot = FilesystemUsageProbe.measure(volume, key)
        assertEquals(FilesystemAccess.ReadOnly, snapshot.access)
    }
    @Test fun volumeWithoutUsageQueryIsUnknownNotZero() = runBlocking {
        val snapshot = FilesystemUsageProbe.measure(plainVolume(), key)

        assertEquals(
            FilesystemUsage.Unavailable(FilesystemUsageReason.UnsupportedFilesystem),
            snapshot.usage,
        )
    }

    @Test fun engineMissingMapsToUnknown() = runBlocking {
        val snapshot = FilesystemUsageProbe.measure(
            usageVolume { throw EngineMissingException("ntfs-3g") },
            key,
        )

        assertEquals(
            FilesystemUsage.Unavailable(FilesystemUsageReason.EngineMissing),
            snapshot.usage,
        )
    }

    @Test fun unlinkedNativeSymbolMapsToEngineMissing() = runBlocking {
        val snapshot = FilesystemUsageProbe.measure(
            usageVolume { throw UnsatisfiedLinkError("nativeUsage") },
            key,
        )

        assertEquals(
            FilesystemUsage.Unavailable(FilesystemUsageReason.EngineMissing),
            snapshot.usage,
        )
    }

    @Test fun closedVolumeIsReportedClosed() = runBlocking {
        val snapshot = FilesystemUsageProbe.measure(
            usageVolume { throw IllegalStateException("EXT volume closed") },
            key,
        )

        assertEquals(
            FilesystemUsage.Unavailable(FilesystemUsageReason.Closed),
            snapshot.usage,
        )
    }

    @Test fun unrelatedStateFailureIsIoFailureNotClosed() = runBlocking {
        val snapshot = FilesystemUsageProbe.measure(
            usageVolume { throw IllegalStateException("unexpected engine state") },
            key,
        )

        assertEquals(
            FilesystemUsage.Unavailable(FilesystemUsageReason.IoFailure),
            snapshot.usage,
        )
    }

    @Test fun engineFailureIsUnknownAndDoesNotThrow() = runBlocking {
        val snapshot = FilesystemUsageProbe.measure(
            usageVolume { throw FatFsException("space query failed: 1") },
            key,
        )

        assertEquals(
            FilesystemUsage.Unavailable(FilesystemUsageReason.IoFailure),
            snapshot.usage,
        )
        assertEquals(key, snapshot.key)
    }

    @Test fun measurementRunsOffTheCallerThread() = runBlocking {
        var measuredOn: Thread? = null
        val volume = usageVolume {
            measuredOn = Thread.currentThread()
            FilesystemSpace(totalBytes = 1uL * gib, freeBytes = 0uL)
        }

        FilesystemUsageProbe.measure(volume, key, io = Dispatchers.Default)

        assertTrue("measurement ran on the caller thread", measuredOn != null)
        assertNotEquals(Thread.currentThread(), measuredOn)
    }

    @Test fun lockedMeasurementStaysOnTheSerializedCallerThread() {
        val caller = Thread.currentThread()
        var measuredOn: Thread? = null
        val volume = usageVolume {
            measuredOn = Thread.currentThread()
            FilesystemSpace(totalBytes = 3uL * gib, freeBytes = 1uL * gib)
        }

        val snapshot = FilesystemUsageProbe.measureLocked(volume, key)

        assertEquals(caller, measuredOn)
        assertEquals(2uL * gib, (snapshot.usage as FilesystemUsage.Known).space.usedBytes)
    }

    @Test fun snapshotRejectsEveryStaleKeyDimension() = runBlocking {
        val snapshot = FilesystemUsageProbe.measure(
            usageVolume { FilesystemSpace(totalBytes = 2uL * gib, freeBytes = 1uL * gib) },
            key,
        )

        assertTrue(snapshot.matches(key))
        assertFalse(snapshot.matches(key.copy(attachGeneration = 8L)))
        assertFalse(snapshot.matches(key.copy(identity = "Other#SER")))
        assertFalse(snapshot.matches(key.copy(partitionStartLba = 4096uL)))
        assertFalse(snapshot.matches(key.copy(partitionSectorCount = 999_999uL)))
        assertFalse(snapshot.matches(key.copy(sectorSize = 4096)))
        assertFalse(snapshot.matches(key.copy(filesystem = FilesystemId.EXT)))
    }

    @Test fun keyRejectsImpossibleGeometry() {
        assertThrows(IllegalArgumentException::class.java) {
            key.copy(partitionSectorCount = 0uL)
        }
        assertThrows(IllegalArgumentException::class.java) {
            key.copy(sectorSize = 0)
        }
    }

    @Test fun spaceRejectsFreeAboveTotal() {
        assertThrows(IllegalArgumentException::class.java) {
            FilesystemSpace(totalBytes = 1uL, freeBytes = 2uL)
        }
    }

    private fun usageVolume(
        access: FilesystemAccess = FilesystemAccess.Unknown,
        provider: () -> FilesystemSpace,
    ): UsbFsVolume =
        object : UsbFsVolume, UsbFsUsageVolume, UsbFsAccessVolume {
            override fun space(): FilesystemSpace = provider()
            override val access: FilesystemAccess = access
            override fun list(path: String) = error("unused")
            override fun stat(path: String) = error("unused")
            override fun statOrNull(path: String) = error("unused")
            override fun mkdir(path: String) = error("unused")
            override fun delete(path: String) = error("unused")
            override fun rename(from: String, to: String) = error("unused")
            override fun read(path: String, offset: Long, length: Int) = error("unused")
            override fun write(
                path: String,
                data: ByteArray,
                offset: Long,
                create: Boolean,
                plannedTotalSize: Long,
                exclusiveCreate: Boolean,
            ) = error("unused")
            override fun sync() = error("unused")
            override fun close() = error("unused")
        }

    private fun plainVolume(): UsbFsVolume = object : UsbFsVolume {
        override fun list(path: String) = error("unused")
        override fun stat(path: String) = error("unused")
        override fun statOrNull(path: String) = error("unused")
        override fun mkdir(path: String) = error("unused")
        override fun delete(path: String) = error("unused")
        override fun rename(from: String, to: String) = error("unused")
        override fun read(path: String, offset: Long, length: Int) = error("unused")
        override fun write(
            path: String,
            data: ByteArray,
            offset: Long,
            create: Boolean,
            plannedTotalSize: Long,
            exclusiveCreate: Boolean,
        ) = error("unused")
        override fun sync() = error("unused")
        override fun close() = error("unused")
    }
}
