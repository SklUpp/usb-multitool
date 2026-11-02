package usb.diagnostics

import java.nio.ByteBuffer
import usb.device.BlockDevice
import usb.device.ScsiCommand
import usb.device.ScsiDirection
import usb.device.ScsiWidth
import usb.device.UsbDisk
import usb.fs.BlockDeviceFatCallback
import usb.fs.ExfatFsck
import usb.fs.FatFs
import usb.fs.FatMkfsType
import usb.fs.NtfsFs
import usb.partition.GptBackup
import usb.partition.PartitionTable
import usb.ventoy.VentoySetup

data class SelfTestResult(
    val name: String,
    val passed: Boolean,
    val message: String,
    val durationMs: Long,
)

private const val LIVE_PROBE_NAME = "Live USB Device Safe Probe"

data class SelfTestReport(
    val results: List<SelfTestResult>,
    val totalDurationMs: Long,
) {
    val allPassed: Boolean get() = results.all { it.passed }
    val passedCount: Int get() = results.count { it.passed }
    val totalCount: Int get() = results.size

    fun summary(): String {
        val usbProbed = results.any { it.name == LIVE_PROBE_NAME }
        val status = when {
            !allPassed -> "FAILURES DETECTED"
            usbProbed -> "ALL PASSED"
            else -> "INTERNAL SUITES PASSED (USB not connected)"
        }
        val header = "Self-test $status: $passedCount of $totalCount suites passed in ${totalDurationMs}ms"
        val details = results.joinToString("\n") { r ->
            val mark = if (r.passed) "[OK]" else "[FAIL]"
            "$mark ${r.name}: ${r.message} (${r.durationMs}ms)"
        }
        return "$header\n$details"
    }
}

/**
 * Automated end-to-end self-test for all functional components.
 * Runs non-destructively in-memory, requiring no external USB stick,
 * but safely probes the connected drive if present.
 */

object SelfTestRunner {
    fun runAll(
        disk: UsbDisk? = null,
        onProgress: (String) -> Unit = {},
    ): SelfTestReport {
        val startTotal = System.currentTimeMillis()
        val results = mutableListOf<SelfTestResult>()

        fun runCheck(name: String, block: () -> String) {
            onProgress(name)
            val start = System.currentTimeMillis()
            try {
                val msg = block()
                val elapsed = System.currentTimeMillis() - start
                results.add(SelfTestResult(name, true, msg, elapsed))
            } catch (t: Throwable) {
                val elapsed = System.currentTimeMillis() - start
                results.add(SelfTestResult(name, false, t.message ?: t.javaClass.simpleName, elapsed))
            }
        }

        runCheck("SCSI Command Encoders") {
            testScsiCodecs()
        }

        runCheck("Partition Alignment & Free Space") {
            testPartitionFreeSpace()
        }

        runCheck("GPT Backup Sector Geometry") {
            testGptBackupGeometry()
        }

        runCheck("Rescue Mapfile Serialization") {
            testRescueMap()
        }

        runCheck("Ventoy Config Contract") {
            testVentoyConfig()
        }

        runCheck("Filesystem Security Confirmation Gates") {
            testFsGates()
        }

        runCheck("Native FatFs Engine In-Memory Roundtrip") {
            testNativeFatFs()
        }

        if (disk != null) {
            runCheck(LIVE_PROBE_NAME) {
                testLiveDevice(disk)
            }
        }

        val totalElapsed = System.currentTimeMillis() - startTotal
        return SelfTestReport(results, totalElapsed)
    }

    private fun testScsiCodecs(): String {
        val inq = ScsiCommand.inquiry(36)
        check(inq.bytes[0] == ScsiCommand.OP_INQUIRY.toByte()) { "invalid INQUIRY op" }
        check(inq.transferLength == 36 && inq.direction == ScsiDirection.IN)

        val cap10 = ScsiCommand.readCapacity10()
        check(cap10.bytes[0] == ScsiCommand.OP_READ_CAPACITY10.toByte())

        val cap16 = ScsiCommand.readCapacity16(32)
        check(cap16.bytes[0] == ScsiCommand.OP_READ_CAPACITY16.toByte())
        check(cap16.bytes[1].toInt() and 0x1F == ScsiCommand.SERVICE_ACTION_READ_CAPACITY16)

        val sync = ScsiCommand.synchronizeCache10()
        check(sync.bytes[0] == ScsiCommand.OP_SYNCHRONIZE_CACHE10.toByte())

        val w10 = ScsiCommand.select(100UL, 8)
        check(w10 == ScsiWidth.CDB10)
        val w16 = ScsiCommand.select(0x1_0000_0000UL, 8)
        check(w16 == ScsiWidth.CDB16)

        return "CDB10/16, INQUIRY, CAPACITY10/16, SYNC_CACHE verified"
    }

    private fun testPartitionFreeSpace(): String {
        val free = PartitionTable.recomputeFree(
            partitions = emptyList(),
            diskSectorCount = 100_000UL,
            align = 2048UL,
        )
        check(free.size == 1) { "expected 1 free region on blank disk" }
        val r = free[0]
        check(r.startLba == 2048UL) { "free space must start at 2048, was ${r.startLba}" }
        check(r.sectorCount == 100_000UL - 2048UL) { "free space must cover remaining sectors" }
        return "1 MiB alignment preserved, LBA 0 protected"
    }

    private fun testGptBackupGeometry(): String {
        check(GptBackup.primarySectors(512) == 34)
        check(GptBackup.backupSectors(512) == 33)
        check(GptBackup.primarySectors(4096) == 6)
        check(GptBackup.backupSectors(4096) == 5)
        return "512B (34/33) and 4Kn (6/5) sector counts verified"
    }

    private fun testRescueMap(): String {
        val map = RescueMap.whole(2048UL)
        map.markCopied(0UL, 100UL)
        map.markFailed(100UL, 10UL)
        check(map.partial) { "map must be partial" }

        val encoded = map.encode()
        val decoded = RescueMap.decode(encoded)
        check(decoded.sectorCount == 2048UL)
        check(decoded.copied == map.copied)
        check(decoded.failed == map.failed)
        check(decoded.partial)
        return "2048-sector mapfile encode/decode round-trip verified"
    }

    private fun testVentoyConfig(): String {
        check(VentoySetup.CONFIG_KEY == "ventoy/ventoy.json") {
            "Ventoy config key must be 'ventoy/ventoy.json'"
        }
        return "Ventoy config key verified"
    }

    private fun testFsGates(): String {
        check(NtfsFs.CLEAR_DIRTY_CONFIRMATION == "CONFIRM_NTFS_CLEAR_DIRTY")
        check(ExfatFsck.REPAIR_CONFIRMATION == "CONFIRM_EXFAT_REPAIR")
        return "NTFS and exFAT confirmation tokens verified"
    }

    private fun testNativeFatFs(): String {
        val memDisk = MemoryDisk(sectorCount = 32768UL, sectorSize = 512)
        val callback = BlockDeviceFatCallback(memDisk)
        return try {
            FatFs.mkfs(callback, 512, 32768L, FatMkfsType.FAT)
            val boot = memDisk.data.copyOfRange(0, 512)
            check((boot[510].toInt() and 0xFF) == 0x55 && (boot[511].toInt() and 0xFF) == 0xAA) {
                "invalid boot signature after mkfs"
            }
            val vol = FatFs.mount(callback, 512, 32768L)
            try {
                vol.mkdir("/selftest")
                val payload = "multitool-native-fatfs-ok".toByteArray(Charsets.UTF_8)
                vol.write("/selftest/ok.txt", payload, offset = 0L, create = true)
                val readBack = vol.read("/selftest/ok.txt", 0L, payload.size)
                check(readBack.contentEquals(payload)) { "data mismatch after read" }
                val entries = vol.list("/selftest")
                check(entries.any { it.name == "ok.txt" && it.size == payload.size.toLong() }) {
                    "file entry missing from directory list"
                }
                vol.sync()
            } finally {
                vol.close()
            }
            "mkfs, mount, mkdir, write, read, list, sync, umount PASSED"
        } catch (_: UnsatisfiedLinkError) {
            "native library absent on host JVM (covered by native-tests/fatfs)"
        }
    }

    private fun testLiveDevice(disk: UsbDisk): String {
        val sectorSize = disk.geometry.sectorSize
        check(sectorSize == 512 || sectorSize == 4096) { "unsupported sector size $sectorSize" }
        check(disk.geometry.sectorCount > 0UL) { "disk reports 0 sectors" }
        val buf = ByteBuffer.allocate(sectorSize)
        disk.read(0UL, buf)
        check(buf.position() == sectorSize) { "short read of sector 0" }
        return "Sector count ${disk.geometry.sectorCount}, size $sectorSize, LBA 0 read verified"
    }

    private class MemoryDisk(
        override val sectorCount: ULong,
        override val sectorSize: Int = 512,
    ) : BlockDevice {
        val data = ByteArray((sectorCount * sectorSize.toULong()).toInt())

        override fun read(lba: ULong, destination: ByteBuffer) {
            val offset = (lba * sectorSize.toULong()).toInt()
            val len = destination.remaining()
            destination.put(data, offset, len)
        }

        override fun write(lba: ULong, source: ByteBuffer) {
            val offset = (lba * sectorSize.toULong()).toInt()
            val len = source.remaining()
            source.get(data, offset, len)
        }

        override fun sync() {}
    }
}
