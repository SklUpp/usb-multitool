package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The slider thumb and the strip pan must describe the same position: whatever px the
 * thumb is dragged to has to come back as the same px after the round trip through the
 * pan offset, and both directions clamp at the track ends.
 */
class RawMapPanTest {
    @Test
    fun thumbAndPanRoundTrip() {
        val travel = 300f
        val maxPan = 1200f
        for (left in listOf(0f, 1f, 75f, 150f, 299f, 300f)) {
            val pan = panForThumbLeft(travel, left, maxPan)
            assertEquals("thumb left $left", left, thumbLeftForPan(travel, pan, maxPan), 0.01f)
        }
    }

    @Test
    fun panFromThumbClampsToTrack() {
        assertEquals(0f, panForThumbLeft(travelPx = 300f, thumbLeftPx = -40f, maxPanPx = 1200f), 0f)
        assertEquals(1200f, panForThumbLeft(travelPx = 300f, thumbLeftPx = 500f, maxPanPx = 1200f), 0f)
        assertEquals(600f, panForThumbLeft(travelPx = 300f, thumbLeftPx = 150f, maxPanPx = 1200f), 0f)
    }

    @Test
    fun degenerateTravelOrContentPinsToStart() {
        assertEquals(0f, panForThumbLeft(travelPx = 0f, thumbLeftPx = 10f, maxPanPx = 1200f), 0f)
        assertEquals(0f, thumbLeftForPan(travelPx = 300f, panPx = 50f, maxPanPx = 0f), 0f)
        assertEquals(0f, thumbLeftForPan(travelPx = 0f, panPx = 50f, maxPanPx = 1200f), 0f)
    }
}
