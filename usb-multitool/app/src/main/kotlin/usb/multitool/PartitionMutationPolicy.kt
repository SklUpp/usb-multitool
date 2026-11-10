package usb.multitool

import usb.partition.Partition
import usb.partition.findConfirmedPartition

internal fun isProtectedSystemPartition(partition: Partition): Boolean =
    partition.esp ||
        partition.name.contains("VTOYEFI", ignoreCase = true) ||
        partition.type.equals("0xEF", ignoreCase = true) ||
        partition.type.equals("EFI", ignoreCase = true) ||
        partition.type.equals("C12A7328-F81F-11D2-BA4B-00A0C93EC93B", ignoreCase = true)

internal fun isEfiWriteAllowedFor(
    partition: Partition,
    allowedStartLba: ULong?,
    allowedSectorCount: ULong?,
): Boolean =
    !isProtectedSystemPartition(partition) ||
        (allowedStartLba != null &&
            allowedSectorCount != null &&
            partition.startLba == allowedStartLba &&
            partition.sectorCount == allowedSectorCount)

/**
 * A writable mount is a mutation boundary. Refuse it while the MBR and GPT layouts disagree:
 * a filesystem write could otherwise overwrite the area the surviving MBR record still owns.
 */
internal fun requireWritableMountAllowed(table: usb.partition.PartitionTable) {
    table.requireUnambiguousLayout()
}

internal fun requirePartitionMutationAllowed(
    partition: Partition,
    allowedStartLba: ULong?,
    allowedSectorCount: ULong?,
) {
    require(isEfiWriteAllowedFor(partition, allowedStartLba, allowedSectorCount)) {
        "${partition.name} is read-only: enable VTOYEFI writes first"
    }
}

internal inline fun <T> mutateConfirmedPartition(
    partitions: List<Partition>,
    startLba: ULong?,
    sectorCount: ULong?,
    mutation: (Partition) -> T,
): T {
    val target = findConfirmedPartition(partitions, startLba, sectorCount)
        ?: throw usb.diagnostics.ConfirmationMismatchException("confirmed partition geometry missing")
    return mutation(target)
}
