package usb.multitool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import usb.ui.RememberedAppShell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Starts Absent; service/facade feeds DeviceChanged intents later.
            RememberedAppShell()
        }
    }
}
