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

    @Test
    fun decimalUnitsUseThousandByteSteps() {
        assertEquals("999 B", DeviceIdentity.formatCapacity(999uL, SizeUnit.Decimal))
        assertEquals("1.0 KB", DeviceIdentity.formatCapacity(1_000uL, SizeUnit.Decimal))
        assertEquals("1.0 MB", DeviceIdentity.formatCapacity(1_000_000uL, SizeUnit.Decimal))
        assertEquals("1.50 GB", DeviceIdentity.formatCapacity(1_500_000_000uL, SizeUnit.Decimal))
        assertEquals("976.6 KiB", DeviceIdentity.formatCapacity(1_000_000uL, SizeUnit.Binary))
        assertEquals("1.0 MiB", DeviceIdentity.formatCapacity(1_048_576uL, SizeUnit.Binary))
        assertEquals(
            "1.50 / 2.00 GB",
            DeviceIdentity.formatCapacityPair(1_500_000_000uL, 2_000_000_000uL, SizeUnit.Decimal),
        )
    }

    @Test
    fun displayNameShowsLabelAndCapacityAndIdentityStaysMatchingFormat() {
        val id = DeviceIdentity.of(
            label = "Cruzer Blade",
            capacityBytes = 4_000_000_000uL,
            serialNumber = "S1",
        )
        // Matching/revalidation keeps the hash form; display never replaces it.
        assertEquals("Cruzer Blade#S1", id.identity)
        assertEquals("Cruzer Blade (${id.capacityLabel})", id.displayName)
    }

    @Test
    fun formatCapacityPairKeepsBothNumbersInOneUnit() {
        // Two numbers, one unit, same precision rules as formatCapacity.
        assertEquals("256 / 512 B", DeviceIdentity.formatCapacityPair(256uL, 512uL))
        val mixed = DeviceIdentity.formatCapacityPair(
            freeBytes = 512uL * 1024uL * 1024uL,
            totalBytes = 2uL * 1024uL * 1024uL * 1024uL,
        )
        assertTrue("mixed pair stays in one unit: $mixed", Regex("^\\d+[.,]\\d{2} / \\d+[.,]\\d{2} GiB$").matches(mixed))
        val full = DeviceIdentity.formatCapacityPair(
            freeBytes = 3uL * 1024uL * 1024uL * 1024uL,
            totalBytes = 3uL * 1024uL * 1024uL * 1024uL,
        )
        assertTrue(Regex("^\\d+[.,]\\d{2} / \\d+[.,]\\d{2} GiB$").matches(full))
    }
}
