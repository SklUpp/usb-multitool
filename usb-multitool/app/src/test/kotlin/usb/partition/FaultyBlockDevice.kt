package usb.partition

import java.nio.ByteBuffer
import usb.device.BlockDevice
import usb.device.DiskException

/**
 * Delegates to a real image and injects failures:
 * - [failAtWrite]-th write (1-based) fails; with [applyBeforeFailing] the whole buffer
 *   reaches the medium first, otherwise nothing lands.
 * - [tornWriteAt]-th write lands only its first half (rounded down to sectors) before
 *   failing, modelling a torn multi-sector update.
 * - [failAtSync]-th sync fails; with [applyBeforeFailing] the flush is attempted first.
 *
 * Records every operation so tests can assert ordering across sync calls.
 */
class FaultyBlockDevice(
    private val delegate: ImageBlockDevice,
    private val failAtWrite: Int = Int.MAX_VALUE,
    private val applyBeforeFailing: Boolean = false,
    private val tornWriteAt: Int = Int.MAX_VALUE,
    private val failAtSync: Int = Int.MAX_VALUE,
) : BlockDevice {
    val ops = mutableListOf<String>()
    private var writeCount = 0
    private var syncCount = 0

    override val sectorSize: Int get() = delegate.sectorSize
    override val sectorCount: ULong get() = delegate.sectorCount

    override fun read(lba: ULong, destination: ByteBuffer) {
        ops += "read:$lba"
        delegate.read(lba, destination)
    }

    override fun write(lba: ULong, source: ByteBuffer) {
        writeCount++
        ops += "write:$lba"
        if (writeCount == tornWriteAt) {
            val half = (source.remaining() / sectorSize / 2) * sectorSize
            if (half > 0) {
                val partial = source.slice()
                partial.limit(half)
                delegate.write(lba, partial)
            }
            throw DiskException.IoFailure(
                "injected torn write $writeCount to LBA $lba ($half bytes landed)",
            )
        }
        if (writeCount == failAtWrite) {
            if (applyBeforeFailing) delegate.write(lba, source)
            throw DiskException.IoFailure("injected disconnect on write $writeCount to LBA $lba")
        }
        delegate.write(lba, source)
    }

    override fun sync() {
        syncCount++
        ops += "sync"
        if (syncCount == failAtSync) {
            if (applyBeforeFailing) delegate.sync()
            throw DiskException.IoFailure("injected disconnect on sync $syncCount")
        }
        delegate.sync()
    }

    fun snapshot(): ByteArray = delegate.snapshot()
}
