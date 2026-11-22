package usb.partition

/** GPT EFI System Partition type GUID (UEFI 2.x). */
const val EFI_SYSTEM_TYPE_GUID = "C12A7328-F81F-11D2-BA4B-00A0C93EC93B"

/**
 * Real ESP type markers only: [Partition.esp], MBR `0xEF`, app alias `EFI`, or the ESP GUID.
 * Does not look at partition names — a VTOYEFI label alone is not an EFI type.
 */
fun isEfiSystemType(partition: Partition): Boolean =
    partition.esp ||
        partition.type.equals("0xEF", ignoreCase = true) ||
        partition.type.equals("EFI", ignoreCase = true) ||
        partition.type.equals(EFI_SYSTEM_TYPE_GUID, ignoreCase = true)

/**
 * Conservative write protection: real ESP type **or** a name containing `VTOYEFI`.
 * Name protection is intentional: Ventoy's second partition may use a non-ESP type GUID
 * while still carrying the boot image under that label.
 */
fun isProtectedSystemPartition(partition: Partition): Boolean =
    isEfiSystemType(partition) ||
        partition.name.contains("VTOYEFI", ignoreCase = true)

/**
 * Unprotected partitions are always writable. A protected one is writable only when the
 * override binds the exact live geometry (start LBA + sector count).
 */
fun isEfiWriteAllowedFor(
    partition: Partition,
    allowedStartLba: ULong?,
    allowedSectorCount: ULong?,
): Boolean =
    !isProtectedSystemPartition(partition) ||
        (allowedStartLba != null &&
            allowedSectorCount != null &&
            partition.startLba == allowedStartLba &&
            partition.sectorCount == allowedSectorCount)
