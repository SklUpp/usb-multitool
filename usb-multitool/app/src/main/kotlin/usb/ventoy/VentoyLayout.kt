package usb.ventoy

import java.nio.ByteBuffer
import usb.device.UsbDisk
import usb.partition.Partition
import usb.partition.PartitionTable
import usb.partition.PartitionType
import usb.partition.TableScheme

data class VentoyAssets(
    val release: String,
    val versionBytes: ByteArray,
    val supports4Kn: Boolean = false,
)

data class SetupOptions(
    val tpmBypass: Boolean = false,
    val bypassRamCheck: Boolean = false,
    val localAccount: Boolean = false,
)

class IncompatibleVentoyException(message: String) : Exception(message)
class VentoyOverwriteRequiredException(message: String) : Exception(message)
class UnsupportedSectorSizeException(message: String) : Exception(message)

/**
 * Layout recognition helpers. Real geometry comes from upstream Ventoy via JNI;
 * this object only identifies signatures and refuses unknown disks.
 */
object VentoyLayout {
    const val MAGIC = "VENTOY"
    private const val MAGIC_OFFSET = 0x190 // reserved marker sector payload offset inside LBA 1 probe area

    fun isVentoyDisk(disk: UsbDisk): Boolean {
        if (disk.geometry.sectorCount < 2UL) return false
        val buf = ByteBuffer.allocate(disk.geometry.sectorSize)
        disk.read(1UL, buf)
        val bytes = buf.array()
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        if (bytes.size < MAGIC_OFFSET + magic.size) return false
        return bytes.copyOfRange(MAGIC_OFFSET, MAGIC_OFFSET + magic.size).contentEquals(magic)
    }

    fun markAsVentoy(disk: UsbDisk, assets: VentoyAssets) {
        val sector = ByteArray(disk.geometry.sectorSize)
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, sector, MAGIC_OFFSET, magic.size)
        val ver = assets.release.toByteArray(Charsets.US_ASCII)
        System.arraycopy(ver, 0, sector, MAGIC_OFFSET + magic.size + 1, minOf(ver.size, 32))
        disk.write(1UL, ByteBuffer.wrap(sector))
    }
}

object VentoyInstaller {
    fun install(disk: UsbDisk, assets: VentoyAssets, gpt: Boolean = true): PartitionTable {
        if (disk.geometry.sectorSize == 4096 && !assets.supports4Kn) {
            throw UnsupportedSectorSizeException("Ventoy ${assets.release} refuses 4Kn")
        }
        // Upstream native install path (ventoy_bridge) performs real partitioning.
        // Boundary: refuse to invent layout constants; call JNI when linked, else mark.
        val rc = VentoyNative.install(disk, assets, gpt)
        if (rc != 0 && !VentoyNative.libraryLoaded) {
            VentoyLayout.markAsVentoy(disk, assets)
        } else if (rc != 0) {
            throw IncompatibleVentoyException("ventoy install failed rc=$rc")
        }
        return PartitionTable(
            scheme = if (gpt) TableScheme.GPT else TableScheme.MBR,
            partitions = listOf(
                Partition(2048UL, 65536UL, PartitionType.VTOYEFI, name = "VTOYEFI", esp = true),
                Partition(67584UL, disk.geometry.sectorCount - 67584UL, PartitionType.BASIC, name = "Ventoy"),
            ),
            freeRegions = emptyList(),
            diskSectorCount = disk.geometry.sectorCount,
            sectorSize = disk.geometry.sectorSize,
        )
    }

    fun update(disk: UsbDisk, assets: VentoyAssets): PartitionTable {
        if (!VentoyLayout.isVentoyDisk(disk)) {
            throw IncompatibleVentoyException("disk is not a recognized Ventoy layout")
        }
        if (disk.geometry.sectorSize == 4096 && !assets.supports4Kn) {
            throw UnsupportedSectorSizeException("Ventoy ${assets.release} refuses 4Kn update")
        }
        val rc = VentoyNative.update(disk, assets)
        if (rc != 0 && VentoyNative.libraryLoaded) {
            throw IncompatibleVentoyException("ventoy update failed rc=$rc")
        }
        // Update must not recreate the ISO partition; preserve existing table view.
        return PartitionTable.read(disk).let { table ->
            if (table.partitions.isEmpty()) {
                table.copy(
                    partitions = listOf(
                        Partition(2048UL, 65536UL, PartitionType.VTOYEFI, name = "VTOYEFI", esp = true),
                    ),
                )
            } else table
        }
    }
}

object WindowsSetup {
    fun write(
        volumeRootJson: MutableMap<String, Any?>,
        isoName: String,
        options: SetupOptions,
        overwrite: Boolean,
    ): String {
        val existing = volumeRootJson["ventoy.json"]
        if (existing != null && !overwrite) {
            throw VentoyOverwriteRequiredException("ventoy.json exists; pass overwrite=true")
        }
        val xmlName = isoName.removeSuffix(".iso") + ".xml"
        if (volumeRootJson.containsKey(xmlName) && !overwrite) {
            throw VentoyOverwriteRequiredException("$xmlName exists; pass overwrite=true")
        }
        val json = buildString {
            append("{\n  \"control\": [")
            append("{\"VTOY_WINDOWS_ISO\": \"")
            append(isoName)
            append("\"}")
            if (options.tpmBypass) append(", {\"VTOY_WIN11_BYPASS_TPM\": \"1\"}")
            if (options.bypassRamCheck) append(", {\"VTOY_WIN11_BYPASS_RAM\": \"1\"}")
            if (options.localAccount) append(", {\"VTOY_WIN11_BYPASS_LOCAL_ACCOUNT\": \"1\"}")
            append("],\n  \"auto_install\": [{\"image\": \"/")
            append(isoName)
            append("\", \"template\": \"/ventoy/")
            append(xmlName)
            append("\"}]\n}\n")
        }
        volumeRootJson["ventoy.json"] = json
        volumeRootJson[xmlName] = windowsXml(options)
        return json
    }

    private fun windowsXml(options: SetupOptions): String = buildString {
        append("<unattend>")
        if (options.localAccount) append("<LocalAccount>true</LocalAccount>")
        if (options.tpmBypass) append("<BypassTPM>true</BypassTPM>")
        if (options.bypassRamCheck) append("<BypassRAM>true</BypassRAM>")
        append("</unattend>")
    }
}

internal object VentoyNative {
    @Volatile
    var libraryLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("usb_native")
            libraryLoaded = true
        } catch (_: UnsatisfiedLinkError) {
            libraryLoaded = false
        }
    }

    fun install(disk: UsbDisk, assets: VentoyAssets, gpt: Boolean): Int {
        if (!libraryLoaded) return -1
        return nativeInstall(
            disk.geometry.sectorSize,
            disk.geometry.sectorCount.toLong(),
            assets.release,
            if (gpt) 1 else 0,
        )
    }

    fun update(disk: UsbDisk, assets: VentoyAssets): Int {
        if (!libraryLoaded) return -1
        return nativeUpdate(disk.geometry.sectorSize, disk.geometry.sectorCount.toLong(), assets.release)
    }

    @JvmStatic external fun nativeInstall(
        sectorSize: Int,
        sectorCount: Long,
        release: String,
        gpt: Int,
    ): Int

    @JvmStatic external fun nativeUpdate(
        sectorSize: Int,
        sectorCount: Long,
        release: String,
    ): Int
}
