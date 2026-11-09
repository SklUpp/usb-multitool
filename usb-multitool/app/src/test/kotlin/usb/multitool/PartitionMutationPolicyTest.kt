package usb.multitool

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import usb.partition.Partition

class PartitionMutationPolicyTest {
    private val efi = Partition(2048UL, 4096UL, name = "VTOYEFI", type = "EFI")
    private val data = Partition(8192UL, 4096UL, name = "DATA", type = "Linux")

    @Test
    fun parserRepresentationsAreProtected() {
        requirePartitionMutationAllowed(usb.partition.Partition(1UL, 1UL, type = "0xEF"), true)
        try {
            requirePartitionMutationAllowed(usb.partition.Partition(1UL, 1UL, type = "0xEF"), false)
            throw AssertionError("expected protected partition rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            requirePartitionMutationAllowed(usb.partition.Partition(1UL, 1UL, esp = true), false)
            throw AssertionError("expected ESP rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun efiMutationRejectedWithoutOverride() {
        try {
            requirePartitionMutationAllowed(efi, vtoyEfiWriteEnabled = false)
            fail("expected read-only rejection")
        } catch (error: IllegalArgumentException) {
            assertEquals("VTOYEFI is read-only: enable VTOYEFI writes first", error.message)
        }
    }

    @Test
    fun efiMutationAllowedWithOverrideAndDataAlwaysAllowed() {
        requirePartitionMutationAllowed(efi, vtoyEfiWriteEnabled = true)
        requirePartitionMutationAllowed(data, vtoyEfiWriteEnabled = false)
    }
}
