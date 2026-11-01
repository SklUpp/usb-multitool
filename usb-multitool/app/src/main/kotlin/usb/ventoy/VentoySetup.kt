package usb.ventoy

import usb.device.BlockDevice
import usb.fs.BlockDeviceFatCallback
import usb.fs.FatFs
import usb.fs.FatVolume
import usb.partition.VirtualPartition

/**
 * Windows unattended-setup files on the Ventoy data partition (partition 1, the
 * FAT/exFAT volume that carries `/ISO` and `/ventoy`). All work happens on the
 * real volume: an ISO that is not in `/ISO` is an error, never a silent success.
 */
object VentoySetup {
    const val ISO_DIR = "ISO"
    const val VENTOY_DIR = "ventoy"
    const val CONFIG_PATH = "/ventoy/ventoy.json"

    /** Volume-relative key [WindowsSetup.write] reads; a leading slash hides the seed. */
    internal const val CONFIG_KEY = VENTOY_DIR + "/ventoy.json"

    /** ISO file names present in `/ISO` on the Ventoy partition. */
    fun listIsos(disk: BlockDevice): List<String> =
        withVolume(disk) { volume ->
            volume.list("/$ISO_DIR")
                .filter { !it.isDirectory && it.name.endsWith(".iso", ignoreCase = true) }
                .map { it.name }
        }

    /**
     * Writes `/ventoy/<iso base name>.xml` and merges the ISO → XML link into
     * `/ventoy/ventoy.json`, preserving unrelated keys. Returns the JSON written.
     *
     * [overwrite] must be true to replace configuration that already exists for this
     * volume; the caller owns that explicit choice (the UI asks per ISO).
     */
    fun linkXml(
        disk: BlockDevice,
        isoName: String,
        options: SetupOptions = SetupOptions(),
        overwrite: Boolean = false,
    ): String =
        withVolume(disk) { volume ->
            val wanted = isoName.substringAfterLast('/')
            val iso =
                volume.list("/$ISO_DIR").firstOrNull {
                    !it.isDirectory && it.name.equals(wanted, ignoreCase = true)
                } ?: throw IsoNotOnVentoyPartitionException(
                    "ISO \"$wanted\" is not in /$ISO_DIR on the Ventoy partition; copy it there first",
                )

            val root = configRoot(readText(volume, CONFIG_PATH))

            val json = WindowsSetup.write(root, iso.name, options, overwrite)
            val xmlName = iso.name.substringBeforeLast('.') + ".xml"
            val xml =
                root["$VENTOY_DIR/$xmlName"] as? String
                    ?: throw IllegalStateException("WindowsSetup did not produce $VENTOY_DIR/$xmlName")

            ensureDirectory(volume, VENTOY_DIR)
            volume.write("/$VENTOY_DIR/$xmlName", xml.toByteArray(Charsets.UTF_8))
            volume.write(CONFIG_PATH, json.toByteArray(Charsets.UTF_8))
            volume.sync()
            json
        }

    class IsoNotOnVentoyPartitionException(message: String) : Exception(message)

    /**
     * Root map handed to [WindowsSetup.write]. Seeded under the same volume-relative
     * key it reads, so an existing `/ventoy/ventoy.json` is merged, never replaced.
     */
    internal fun configRoot(existingJson: String?): MutableMap<String, Any?> =
        linkedMapOf<String, Any?>().also { root ->
            if (existingJson != null) root[CONFIG_KEY] = existingJson
        }

    private inline fun <T> withVolume(disk: BlockDevice, block: (FatVolume) -> T): T {
        val probe =
            VentoyLayout.probe(disk)
                ?: throw IncompatibleVentoyException(
                    "No Ventoy layout on this disk; install Ventoy before editing setup files",
                )
        val partition = VirtualPartition(probe.part1StartLba, probe.part1SectorCount, disk)
        val volume =
            FatFs.mount(
                BlockDeviceFatCallback(partition),
                partition.sectorSize,
                partition.sectorCount.toLong(),
            )
        return volume.use(block)
    }

    private fun ensureDirectory(volume: FatVolume, name: String) {
        val exists = volume.list("/").any { it.isDirectory && it.name.equals(name, ignoreCase = true) }
        if (!exists) volume.mkdir("/$name")
    }

    private fun readText(volume: FatVolume, path: String): String? {
        val name = path.substringAfterLast('/')
        val parent = path.substringBeforeLast('/', "/")
        val entry =
            volume.list(parent).firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) }
                ?: return null
        if (entry.size <= 0L) return ""
        if (entry.size > Int.MAX_VALUE) {
            throw IllegalStateException("$path is ${entry.size} bytes; refusing to load it as text")
        }
        return String(volume.read(path, 0L, entry.size.toInt()), Charsets.UTF_8)
    }
}
