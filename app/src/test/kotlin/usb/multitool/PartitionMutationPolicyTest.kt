package usb.multitool

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import usb.partition.HybridAmbiguityException
import usb.partition.Partition
import usb.partition.PartitionTable
import usb.partition.TableScheme

class PartitionMutationPolicyTest {
    private val efi = Partition(2048UL, 4096UL, name = "VTOYEFI", type = "EFI")
    private val otherEfi = Partition(8192UL, 4096UL, name = "ESP2", type = "EFI")
    private val data = Partition(8192UL, 4096UL, name = "DATA", type = "Linux")

    @Test
    fun parserRepresentationsAreProtected() {
        try {
            requirePartitionMutationAllowed(
                usb.partition.Partition(1UL, 1UL, type = "0xEF"),
                allowedStartLba = 1UL,
                allowedSectorCount = 1UL,
            )
        } catch (error: IllegalArgumentException) {
            fail("matching geometry must unlock the named EFI: ${error.message}")
        }
        try {
            requirePartitionMutationAllowed(
                usb.partition.Partition(1UL, 1UL, type = "0xEF"),
                allowedStartLba = null,
                allowedSectorCount = null,
            )
            throw AssertionError("expected protected partition rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            requirePartitionMutationAllowed(
                usb.partition.Partition(1UL, 1UL, esp = true),
                allowedStartLba = null,
                allowedSectorCount = null,
            )
            throw AssertionError("expected ESP rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun writableMountRefusedForAmbiguousLayout() {
        val ambiguous =
            PartitionTable(
                scheme = TableScheme.HYBRID,
                partitions = listOf(data),
                freeRegions = emptyList(),
                diskSectorCount = 2048UL,
                sectorSize = 512,
                hybridAmbiguous = true,
            )
        try {
            requireWritableMountAllowed(ambiguous)
            fail("writable mount must be refused for an ambiguous layout")
        } catch (error: HybridAmbiguityException) {
            assertEquals(true, error.message!!.contains("ambiguous"))
        }
        requireWritableMountAllowed(
            ambiguous.copy(scheme = TableScheme.GPT, hybridAmbiguous = false),
        )
    }

    @Test
    fun efiMutationRejectedWithoutOverride() {
        try {
            requirePartitionMutationAllowed(efi, allowedStartLba = null, allowedSectorCount = null)
            fail("expected read-only rejection")
        } catch (error: IllegalArgumentException) {
            assertEquals("VTOYEFI is read-only: enable VTOYEFI writes first", error.message)
        }
    }

    @Test
    fun efiMutationAllowedOnlyForTheBoundGeometry() {
        requirePartitionMutationAllowed(
            efi,
            allowedStartLba = efi.startLba,
            allowedSectorCount = efi.sectorCount,
        )
        requirePartitionMutationAllowed(data, allowedStartLba = null, allowedSectorCount = null)
        try {
            requirePartitionMutationAllowed(
                otherEfi,
                allowedStartLba = efi.startLba,
                allowedSectorCount = efi.sectorCount,
            )
            fail("another EFI partition must stay protected")
        } catch (_: IllegalArgumentException) {
        }
    }
}
