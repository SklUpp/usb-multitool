package usb.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionClassificationTest {
    @Test
    fun efiTypeRecognizesEspFlagMbrByteGuidAndAlias() {
        assertTrue(isEfiSystemType(Partition(1UL, 1UL, esp = true)))
        assertTrue(isEfiSystemType(Partition(1UL, 1UL, type = "0xEF")))
        assertTrue(isEfiSystemType(Partition(1UL, 1UL, type = "0xef")))
        assertTrue(isEfiSystemType(Partition(1UL, 1UL, type = "EFI")))
        assertTrue(isEfiSystemType(Partition(1UL, 1UL, type = "efi")))
        assertTrue(isEfiSystemType(Partition(1UL, 1UL, type = EFI_SYSTEM_TYPE_GUID)))
        assertTrue(
            isEfiSystemType(
                Partition(1UL, 1UL, type = EFI_SYSTEM_TYPE_GUID.lowercase()),
            ),
        )
    }

    @Test
    fun efiTypeIgnoresNameOnlyAndDataTypes() {
        assertFalse(isEfiSystemType(Partition(1UL, 1UL, type = "BASIC", name = "VTOYEFI")))
        assertFalse(isEfiSystemType(Partition(1UL, 1UL, type = "VTOYEFI", name = "")))
        assertFalse(isEfiSystemType(Partition(1UL, 1UL, type = "0x83", name = "ESP")))
        assertFalse(isEfiSystemType(Partition(1UL, 1UL, type = "Linux", name = "boot")))
        assertFalse(isEfiSystemType(Partition(1UL, 1UL, type = "0xEE")))
    }

    @Test
    fun protectionCoversEfiTypeOrVtoyefiName() {
        assertTrue(isProtectedSystemPartition(Partition(1UL, 1UL, type = "0xEF")))
        assertTrue(isProtectedSystemPartition(Partition(1UL, 1UL, esp = true, name = "data")))
        assertTrue(isProtectedSystemPartition(Partition(1UL, 1UL, type = "BASIC", name = "VTOYEFI")))
        assertTrue(isProtectedSystemPartition(Partition(1UL, 1UL, type = "BASIC", name = "vtoyefi-backup")))
        assertTrue(isProtectedSystemPartition(Partition(1UL, 1UL, type = "BASIC", name = "xxVTOYEFIyy")))
        assertFalse(isProtectedSystemPartition(Partition(1UL, 1UL, type = "BASIC", name = "DATA")))
        assertFalse(isProtectedSystemPartition(Partition(1UL, 1UL, type = "0x83", name = "P1")))
        // App alias without name/esp stays unprotected: only writer maps VTOYEFI→ESP on commit.
        assertFalse(isProtectedSystemPartition(Partition(1UL, 1UL, type = "VTOYEFI", name = "")))
    }

    @Test
    fun writeOverrideBindsExactGeometryOnly() {
        val efi = Partition(2048UL, 4096UL, type = "EFI", name = "ESP")
        val other = Partition(8192UL, 4096UL, type = "EFI", name = "ESP2")
        val data = Partition(16384UL, 8192UL, type = "BASIC", name = "DATA")

        assertTrue(isEfiWriteAllowedFor(data, null, null))
        assertFalse(isEfiWriteAllowedFor(efi, null, null))
        assertFalse(isEfiWriteAllowedFor(efi, efi.startLba, null))
        assertFalse(isEfiWriteAllowedFor(efi, null, efi.sectorCount))
        assertTrue(isEfiWriteAllowedFor(efi, efi.startLba, efi.sectorCount))
        assertFalse(isEfiWriteAllowedFor(efi, efi.startLba, efi.sectorCount + 1UL))
        assertFalse(isEfiWriteAllowedFor(efi, efi.startLba + 1UL, efi.sectorCount))
        assertFalse(isEfiWriteAllowedFor(other, efi.startLba, efi.sectorCount))
    }

    @Test
    fun nameProtectionWithoutEfiTypeStillNeedsOverride() {
        val named = Partition(100UL, 200UL, type = "BASIC", name = "VTOYEFI")
        assertFalse(isEfiSystemType(named))
        assertTrue(isProtectedSystemPartition(named))
        assertFalse(isEfiWriteAllowedFor(named, null, null))
        assertTrue(isEfiWriteAllowedFor(named, 100UL, 200UL))
    }

    @Test
    fun classificationAxesStayIndependent() {
        val cases = listOf(
            Partition(1UL, 1UL, type = EFI_SYSTEM_TYPE_GUID, name = "") to (true to true),
            Partition(1UL, 1UL, type = "0xEF", name = "P1") to (true to true),
            Partition(1UL, 1UL, type = "EFI", name = "boot") to (true to true),
            Partition(1UL, 1UL, type = "BASIC", name = "VTOYEFI", esp = false) to (false to true),
            Partition(1UL, 1UL, type = "BASIC", name = "DATA", esp = true) to (true to true),
            Partition(1UL, 1UL, type = "BASIC", name = "DATA") to (false to false),
        )
        cases.forEach { (partition, expected) ->
            val (efiType, protected) = expected
            assertEquals(
                "efiType for type='${partition.type}' name='${partition.name}' esp=${partition.esp}",
                efiType,
                isEfiSystemType(partition),
            )
            assertEquals(
                "protected for type='${partition.type}' name='${partition.name}' esp=${partition.esp}",
                protected,
                isProtectedSystemPartition(partition),
            )
        }
    }
}
