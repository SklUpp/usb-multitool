package usb

import org.junit.Assert.assertEquals
import org.junit.Test

class SkeletonTest {
    @Test fun licenseIsGpl3() {
        assertEquals("GPL-3.0-or-later", BuildFacts.LICENSE)
    }
}
