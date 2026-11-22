package usb.ventoy

import android.content.Context

/**
 * Last successful upstream Ventoy release, scoped to the installed app version.
 * It survives force-stop/restart; a different version code invalidates it, so an
 * APK update always performs a fresh network check. Reinstall clears app data.
 */
internal class VentoyUpstreamCache(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val installStamp: String = run {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "${info.longVersionCode}:${info.lastUpdateTime}"
    }

    fun readRelease(): String? {
        if (prefs.getString(KEY_INSTALL_STAMP, null) != installStamp) {
            prefs.edit().clear().apply()
            return null
        }
        return prefs.getString(KEY_RELEASE, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun saveRelease(release: String) {
        prefs.edit()
            .clear()
            .putString(KEY_INSTALL_STAMP, installStamp)
            .putString(KEY_RELEASE, release)
            .apply()
    }

    private companion object {
        const val PREFS = "ventoy_upstream_cache"
        const val KEY_INSTALL_STAMP = "install_stamp"
        const val KEY_RELEASE = "release"
    }
}
