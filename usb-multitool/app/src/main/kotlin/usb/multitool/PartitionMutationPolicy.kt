package usb.multitool

import usb.partition.Partition
import usb.partition.findConfirmedPartition

internal fun isProtectedSystemPartition(partition: Partition): Boolean =
    partition.esp ||
        partition.name.contains("VTOYEFI", ignoreCase = true) ||
        partition.type.equals("0xEF", ignoreCase = true) ||
        partition.type.equals("EFI", ignoreCase = true) ||
        partition.type.equals("C12A7328-F81F-11D2-BA4B-00A0C93EC93B", ignoreCase = true)

internal fun requirePartitionMutationAllowed(partition: Partition, vtoyEfiWriteEnabled: Boolean) {
    require(vtoyEfiWriteEnabled || !isProtectedSystemPartition(partition)) {
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
