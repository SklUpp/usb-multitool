package usb.multitool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import usb.service.ContextServiceControl
import usb.service.DiskController
import usb.service.NoOpWakeLock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiskController.get().bind(
            wakeLock = NoOpWakeLock,
            serviceControl = ContextServiceControl(this),
        )
        setContent { AppRoot() }
    }
}

@Composable
private fun AppRoot() {
    Text("USB Multitool")
}
