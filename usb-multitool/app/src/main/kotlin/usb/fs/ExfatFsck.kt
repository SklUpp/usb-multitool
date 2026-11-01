package usb.fs

import usb.device.BlockDevice

/**
 * Read-only check and explicit repair for exFAT via vendored exfatprogs.
 * Repair requires [REPAIR_CONFIRMATION] and [MountedVolumeGate.closeAll]
 * before any write path runs.
 */
object ExfatFsck {
    const val REPAIR_CONFIRMATION: String = "CONFIRM_EXFAT_REPAIR"

    @Volatile private var nativeLoaded: Boolean = false

    private fun ensureNative() {
        if (!nativeLoaded) {
            synchronized(this) {
                if (!nativeLoaded) {
                    System.loadLibrary("usb_native")
                    nativeLoaded = true
                }
            }
        }
    }

    enum class Result(val code: Int) {
        NoErrors(0x00),
        Corrected(0x01),
        ErrorsLeft(0x04),
        OperationError(0x08),
        ;

        companion object {
            fun fromCode(code: Int): Result =
                entries.firstOrNull { it.code == code } ?: OperationError
        }
    }

    /**
     * Closes every mounted volume on [device] before repair writes.
     * Production wires DiskService / MountedVolume registry here.
     */
    fun interface MountedVolumeGate {
        fun closeAll(device: BlockDevice)

        companion object {
            val NOOP: MountedVolumeGate = MountedVolumeGate { }
        }
    }

    fun check(device: BlockDevice): Result {
        ensureNative()
        val handle = openHandle(device)
        return try {
            Result.fromCode(nativeCheck(handle))
        } finally {
            nativeClose(handle)
        }
    }

    fun repair(
        device: BlockDevice,
        confirmationToken: String,
        volumeGate: MountedVolumeGate,
    ): Result {
        if (confirmationToken != REPAIR_CONFIRMATION) {
            return Result.OperationError
        }
        volumeGate.closeAll(device)
        ensureNative()
        val handle = openHandle(device)
        return try {
            nativeMarkVolumesClosed(handle)
            Result.fromCode(nativeRepair(handle, confirmationToken))
        } finally {
            nativeClose(handle)
        }
    }

    private fun openHandle(device: BlockDevice): Long {
        require(device.sectorSize > 0)
        require(device.sectorCount > 0uL)
        val handle = nativeOpen(
            device,
            device.sectorSize.toLong(),
            device.sectorCount.toLong(),
        )
        require(handle != 0L) { "exFAT fsck handle exhausted" }
        return handle
    }

    @JvmStatic private external fun nativeOpen(
        device: BlockDevice,
        sectorSize: Long,
        sectorCount: Long,
    ): Long

    @JvmStatic private external fun nativeClose(handle: Long)
    @JvmStatic private external fun nativeMarkVolumesClosed(handle: Long)
    @JvmStatic private external fun nativeCheck(handle: Long): Int
    @JvmStatic private external fun nativeRepair(handle: Long, confirmationToken: String): Int
}
