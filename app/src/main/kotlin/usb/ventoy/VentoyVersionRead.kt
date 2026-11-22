package usb.ventoy

/** Read-only installed-version classification; absence and I/O failure stay distinct. */
sealed interface VentoyInstalledVersion {
    /** Null = no version marker (not a Ventoy stick, or marker absent). */
    data class Present(val release: String?) : VentoyInstalledVersion

    data class ReadFailed(val message: String) : VentoyInstalledVersion
}

/**
 * Classifies a [VentoyProbeResult] without hiding a real FAT/IO failure as n/a.
 * Cancellation is never converted to a version fact.
 */
fun classifyVentoyVersion(probe: VentoyProbeResult?): VentoyInstalledVersion {
    if (probe == null || !probe.isVentoy) return VentoyInstalledVersion.Present(null)
    val failure = probe.versionError
    if (failure is java.util.concurrent.CancellationException) throw failure
    if (failure != null) {
        return VentoyInstalledVersion.ReadFailed(
            failure.message ?: failure.javaClass.simpleName,
        )
    }
    return VentoyInstalledVersion.Present(
        probe.version.trim().takeIf { it.isNotEmpty() },
    )
}
