package usb.partition

/**
 * Finds partition matching geometry captured during destructive confirmation.
 * Missing geometry or changed geometry returns null; callers must reject before writes.
 */
fun findConfirmedPartition(
    partitions: List<Partition>,
    confirmedStartLba: ULong?,
    confirmedSectorCount: ULong?,
): Partition? {
    if (confirmedStartLba == null || confirmedSectorCount == null) return null
    return partitions.firstOrNull { partition ->
        partition.startLba == confirmedStartLba && partition.sectorCount == confirmedSectorCount
    }
}
