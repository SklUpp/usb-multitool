package usb.ventoy

object VentoyNative {
    init {
        try {
            System.loadLibrary("usb_native")
        } catch (_: UnsatisfiedLinkError) {
            // Native library may not be present in host JVM environment unless explicitly loaded
        }
    }

    @JvmStatic
    external fun nativeEngineLinked(): Boolean

    @JvmStatic
    external fun nativeProbe(
        blockHandle: Long,
        sectorSize: Int,
        sectorCount: Long,
    ): Int

    @JvmStatic
    external fun nativeInstall(
        blockHandle: Long,
        sectorSize: Int,
        sectorCount: Long,
        release: String,
        bootImg: ByteArray,
        coreImg: ByteArray,
        efiPartImg: ByteArray,
        gpt: Int,
        reserveBytes: Long,
        align4k: Int,
    ): Int

    @JvmStatic
    external fun nativeUpdate(
        blockHandle: Long,
        sectorSize: Int,
        sectorCount: Long,
        release: String,
        bootImg: ByteArray,
        coreImg: ByteArray,
        efiPartImg: ByteArray,
    ): Int

    fun install(
        blockHandle: Long,
        sectorSize: Int,
        sectorCount: Long,
        assets: VentoyAssets,
        gpt: Boolean = true,
        reserveBytes: Long = 0L,
        align4k: Boolean = true,
    ): Int {
        val decompressedCore = assets.decompressCoreImg()
        val decompressedEfi = assets.decompressEfiPartImg()
        return nativeInstall(
            blockHandle = blockHandle,
            sectorSize = sectorSize,
            sectorCount = sectorCount,
            release = assets.release,
            bootImg = assets.bootImg,
            coreImg = decompressedCore,
            efiPartImg = decompressedEfi,
            gpt = if (gpt) 1 else 0,
            reserveBytes = reserveBytes,
            align4k = if (align4k) 1 else 0,
        )
    }

    fun update(
        blockHandle: Long,
        sectorSize: Int,
        sectorCount: Long,
        assets: VentoyAssets,
    ): Int {
        val decompressedCore = assets.decompressCoreImg()
        val decompressedEfi = assets.decompressEfiPartImg()
        return nativeUpdate(
            blockHandle = blockHandle,
            sectorSize = sectorSize,
            sectorCount = sectorCount,
            release = assets.release,
            bootImg = assets.bootImg,
            coreImg = decompressedCore,
            efiPartImg = decompressedEfi,
        )
    }
}
