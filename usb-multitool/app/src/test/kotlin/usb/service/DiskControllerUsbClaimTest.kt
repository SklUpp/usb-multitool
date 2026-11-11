package usb.service

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The USB claim must be process-wide: every ViewModel builds its files session from
 * `DiskController.get().usbClaim`, so two Activity instances in one process contend for the
 * same lease instead of each opening its own BOT session on the stick.
 */
class DiskControllerUsbClaimTest {
    @Test
    fun sharedAccessorSuppliesTheSameClaimToEveryViewModel() {
        val first = DiskController.get()
        val second = DiskController.get()
        assertSame("one controller per process", first, second)
        assertSame("one USB claim per process", first.usbClaim, second.usbClaim)
    }
}
