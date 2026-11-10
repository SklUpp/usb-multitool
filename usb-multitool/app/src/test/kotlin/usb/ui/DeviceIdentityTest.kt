package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Format and generation contracts for [DeviceIdentity]. Pure JVM — no Android types.
 * Live USB mapping must keep these rules byte-for-byte for confirmations.
 */
class DeviceIdentityTest {
    @Test
    fun identityIsLabelAloneWhenSerialMissingOrBlank() {
        assertEquals("Stick", DeviceIdentity(label = "Stick", capacityBytes = 1uL).identity)
        assertEquals(
            "Stick",
            DeviceIdentity(label = "Stick", capacityBytes = 1uL, serialNumber = null).identity,
        )
        assertEquals(
            "Stick",
            DeviceIdentity(label = "Stick", capacityBytes = 1uL, serialNumber = "").identity,
        )
        assertEquals(
            "Stick",
            DeviceIdentity(label = "Stick", capacityBytes = 1uL, serialNumber = "   ").identity,
        )
        assertEquals("Stick", DeviceIdentity.identityString("Stick", null))
        assertEquals("Stick", DeviceIdentity.identityString("Stick", " \t "))
    }

    @Test
    fun identityAppendsTrimmedSerialWithHash() {
        assertEquals(
            "SanDisk#ABC123",
            DeviceIdentity(
                label = "SanDisk",
                capacityBytes = 8_000_000_000uL,
                serialNumber = "ABC123",
            ).identity,
        )
        assertEquals(
            "SanDisk#ABC123",
            DeviceIdentity(
                label = "SanDisk",
                capacityBytes = 1uL,
                serialNumber = "  ABC123  ",
            ).identity,
        )
        assertEquals("SanDisk#ABC123", DeviceIdentity.identityString("SanDisk", "  ABC123  "))
        assertEquals(
            DeviceIdentity.identityString("SanDisk", "ABC123"),
            DeviceIdentity(label = "SanDisk", capacityBytes = 0uL, serialNumber = "ABC123").identity,
        )
    }

    @Test
    fun labelOfFallsBackProductThenDeviceThenDefault() {
        assertEquals("Product", DeviceIdentity.labelOf("Product", "Device"))
        assertEquals("Device", DeviceIdentity.labelOf(null, "Device"))
        // Empty string is not null: same as productName ?: deviceName (empty wins).
        assertEquals("", DeviceIdentity.labelOf("", "Device"))
        assertEquals(DeviceIdentity.DEFAULT_LABEL, DeviceIdentity.labelOf(null, null))
        assertEquals("USB Storage", DeviceIdentity.DEFAULT_LABEL)
    }

    @Test
    fun ofPreservesCapacitySerialAndAttachGenerationExactly() {
        val id =
            DeviceIdentity.of(
                label = "Kingston",
                capacityBytes = 15_728_640_000uL,
                serialNumber = "SN-42",
                attachGeneration = 9L,
            )
        assertEquals("Kingston", id.label)
        assertEquals(15_728_640_000uL, id.capacityBytes)
        assertEquals("SN-42", id.serialNumber)
        assertEquals(9L, id.attachGeneration)
        assertEquals("Kingston#SN-42", id.identity)
        assertTrue(id.hasStableSerial)

        val permissionStyle =
            DeviceIdentity.of(label = DeviceIdentity.DEFAULT_LABEL, capacityBytes = 0uL)
        assertEquals(0uL, permissionStyle.capacityBytes)
        assertEquals(0L, permissionStyle.attachGeneration)
        assertNull(permissionStyle.serialNumber)
        assertEquals(DeviceIdentity.DEFAULT_LABEL, permissionStyle.identity)
        assertFalse(permissionStyle.hasStableSerial)
    }

    @Test
    fun attachGenerationIsOrthogonalToIdentityString() {
        val a =
            DeviceIdentity.of(
                label = "Same",
                capacityBytes = 100uL,
                serialNumber = "S",
                attachGeneration = 1L,
            )
        val b =
            DeviceIdentity.of(
                label = "Same",
                capacityBytes = 100uL,
                serialNumber = "S",
                attachGeneration = 2L,
            )
        assertEquals(a.identity, b.identity)
        assertEquals("Same#S", a.identity)
        assertEquals(1L, a.attachGeneration)
        assertEquals(2L, b.attachGeneration)
        assertTrue(a != b)
    }

    @Test
    fun formatCapacityBucketsUnchanged() {
        assertEquals("512 B", DeviceIdentity.formatCapacity(512uL))
        assertEquals("1.0 KiB", DeviceIdentity.formatCapacity(1024uL))
        assertTrue(DeviceIdentity.formatCapacity(5uL * 1024uL * 1024uL).endsWith("MiB"))
        assertTrue(DeviceIdentity.formatCapacity(3uL * 1024uL * 1024uL * 1024uL).endsWith("GiB"))
    }
}
