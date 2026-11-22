package usb.fs

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.EngineMissingException
import usb.device.BlockGeometry
import usb.device.FakeBotSession
import usb.device.UsbDisk
import usb.requireLinkedEngine

/**
 * Mount failure must keep the probe/mount cause and attach a disk-close error as
 * suppressed — never swap primary for the secondary close failure.
 *
 * Missing-engine mapping uses injected [UnsatisfiedLinkError] so tests do not
 * depend on whether `usb_native` is loadable in CI.
 */
class PartitionVolumeSessionTest {
    @Test
    fun mountFailureKeepsPrimaryAndSuppressesDiskClose() {
        val session =
            object : FakeBotSession() {
                override fun close() {
                    throw IOException("disk close failed after mount")
                }
            }
        // Empty media: probe finds no FS signature → open fails before any mount.
        val disk = UsbDisk(session = session, geometry = BlockGeometry(512, 64UL))

        val thrown =
            assertThrows(UnsupportedFilesystemException::class.java) {
                PartitionVolumeSession.open(disk, startLba = 0UL, sectorCount = 64UL)
            }

        assertTrue(thrown.message!!.contains("no supported filesystem signature"))
        assertEquals(1, thrown.suppressed.size)
        assertTrue(thrown.suppressed.single() is IOException)
        assertTrue(thrown.suppressed.single().message!!.contains("disk close failed after mount"))
    }

    @Test
    fun engineForMapsEachFilesystemIdToRealEngineName() {
        assertEquals("FatFs", PartitionVolumeSession.engineFor(FilesystemId.FAT))
        assertEquals("FatFs", PartitionVolumeSession.engineFor(FilesystemId.EXFAT))
        assertEquals("lwext4", PartitionVolumeSession.engineFor(FilesystemId.EXT))
        assertEquals("ntfs-3g", PartitionVolumeSession.engineFor(FilesystemId.NTFS))
    }

    @Test
    fun requireLinkedEngineMapsUnsatisfiedLinkErrorWithNameAndCause() {
        val link = UnsatisfiedLinkError("nativeOpenBlock")
        val thrown =
            assertThrows(EngineMissingException::class.java) {
                requireLinkedEngine("FatFs") { throw link }
            }
        assertTrue(thrown.message!!.startsWith("FatFs engine not linked"))
        assertSame(link, thrown.cause)
    }

    @Test
    fun requireLinkedEnginePassesNonLinkFailuresUnchanged() {
        val boom = IOException("mount io")
        val thrown =
            assertThrows(IOException::class.java) {
                requireLinkedEngine("lwext4") { throw boom }
            }
        assertSame(boom, thrown)
        assertEquals(0, thrown.suppressed.size)
    }

    @Test
    fun injectedUleAtOpenUsesEngineForNameAndSuppressesDiskClose() {
        val cases =
            listOf(FilesystemId.FAT, FilesystemId.EXFAT, FilesystemId.NTFS, FilesystemId.EXT)
        for (id in cases) {
            val engine = PartitionVolumeSession.engineFor(id)
            val session =
                object : FakeBotSession() {
                    override fun close() {
                        throw IOException("disk close failed after mount")
                    }
                }
            seedSignature(session, id)
            val disk = UsbDisk(session = session, geometry = BlockGeometry(512, 64UL))
            val link = UnsatisfiedLinkError("injected-$engine")

            val thrown =
                assertThrows(EngineMissingException::class.java) {
                    PartitionVolumeSession.openInternal(
                        disk = disk,
                        startLba = 0UL,
                        sectorCount = 64UL,
                        // Same gate shape as mountProbed: engineFor(id) + requireLinkedEngine.
                        mount = { probed, _, _ ->
                            assertEquals(id, probed)
                            requireLinkedEngine(PartitionVolumeSession.engineFor(probed)) {
                                throw link
                            }
                        },
                    )
                }

            assertTrue(
                "id=$id engine=$engine message=${thrown.message}",
                thrown.message!!.startsWith("$engine engine not linked"),
            )
            assertSame(link, thrown.cause)
            assertEquals(1, thrown.suppressed.size)
            assertTrue(thrown.suppressed.single() is IOException)
            assertTrue(thrown.suppressed.single().message!!.contains("disk close failed after mount"))
        }
    }

    @Test
    fun injectedNonLinkFailureAtOpenPassesThroughWithSuppressedClose() {
        val session =
            object : FakeBotSession() {
                override fun close() {
                    throw IOException("disk close failed after mount")
                }
            }
        seedSignature(session, FilesystemId.FAT)
        val disk = UsbDisk(session = session, geometry = BlockGeometry(512, 64UL))
        val boom = IllegalStateException("mount refused dirty volume")

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                PartitionVolumeSession.openInternal(
                    disk = disk,
                    startLba = 0UL,
                    sectorCount = 64UL,
                    mount = { _, _, _ -> throw boom },
                )
            }

        assertSame(boom, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertTrue(thrown.suppressed.single() is IOException)
    }

    private fun seedSignature(session: FakeBotSession, id: FilesystemId) {
        when (id) {
            FilesystemId.FAT -> session.putSector(0UL, fatBootSector())
            FilesystemId.EXFAT -> session.putSector(0UL, exfatBootSector())
            FilesystemId.NTFS -> session.putSector(0UL, ntfsBootSector())
            FilesystemId.EXT -> session.putSector(2UL, extSuperblockSector())
        }
    }

    private fun fatBootSector(): ByteArray {
        val boot = ByteArray(512)
        boot[510] = 0x55.toByte()
        boot[511] = 0xAA.toByte()
        boot[11] = 0x00
        boot[12] = 0x02
        boot[13] = 0x01
        System.arraycopy("MSDOS5.0".toByteArray(Charsets.US_ASCII), 0, boot, 3, 8)
        System.arraycopy("FAT16   ".toByteArray(Charsets.US_ASCII), 0, boot, 54, 8)
        return boot
    }

    private fun exfatBootSector(): ByteArray {
        val boot = ByteArray(512)
        boot[510] = 0x55.toByte()
        boot[511] = 0xAA.toByte()
        System.arraycopy("EXFAT   ".toByteArray(Charsets.US_ASCII), 0, boot, 3, 8)
        return boot
    }

    private fun ntfsBootSector(): ByteArray {
        val boot = ByteArray(512)
        boot[510] = 0x55.toByte()
        boot[511] = 0xAA.toByte()
        System.arraycopy("NTFS    ".toByteArray(Charsets.US_ASCII), 0, boot, 3, 8)
        return boot
    }

    private fun extSuperblockSector(): ByteArray {
        val sector = ByteArray(512)
        sector[0x38] = 0x53
        sector[0x39] = 0xEF.toByte()
        return sector
    }
}
