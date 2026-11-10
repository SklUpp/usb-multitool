package usb.multitool

import usb.partition.Partition
import usb.partition.findConfirmedPartition
import usb.partition.isEfiWriteAllowedFor

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
