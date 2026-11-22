package usb.ui

import androidx.lifecycle.SavedStateHandle

/**
 * Persists only the selected [ShellTab] across Activity/process recreation.
 * Device, partitions, files, and operations stay session-scoped and are never restored.
 */
object ShellTabPersistence {
    const val KEY = "usb.ui.shell_tab"

    fun read(handle: SavedStateHandle): ShellTab? =
        decode(handle.get<Any?>(KEY) as? String)

    fun write(handle: SavedStateHandle, tab: ShellTab) {
        handle[KEY] = encode(tab)
    }

    fun encode(tab: ShellTab): String = tab.name

    fun decode(raw: String?): ShellTab? =
        raw?.let { name -> ShellTab.entries.firstOrNull { it.name == name } }
}
