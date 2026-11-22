package usb.multitool

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/**
 * Startup permission contract of the real launcher Activity: Android 13 must ask for
 * POST_NOTIFICATIONS per fresh launch while it is missing, and a granted permission or
 * an older platform must not ask at all. Nothing here mocks the app: the Activity, the
 * Launcher, the merged manifest and the platform check are the production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityPermissionsTest {
    @Before
    fun seedExternalDir() {
        // API 30+ Environment.isExternalStorageManager() indexes getExternalDirs()[0];
        // Robolectric 4.17 ships that list empty. Harmless once the probe is gone.
        ShadowEnvironment.addExternalDir("usb-multitool-test")
    }

    @Test
    fun android13LaunchRequestsPostNotifications() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            shadowOf(activity).lastRequestedPermission?.requestedPermissions?.toList(),
        )
    }

    @Test
    fun grantedPostNotificationsAreNotRequestedAgain() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [31])
    fun android12DoesNotAskForPostNotifications() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertNull(shadowOf(activity).lastRequestedPermission)
    }
}
