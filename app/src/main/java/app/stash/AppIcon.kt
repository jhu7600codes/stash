package app.stash

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * the launcher icon: light blue with dark bars, or black with white bars while the app is
 * dark (settings → appearance → dark icon in dark mode). android can't swap an icon's colors
 * by itself, so the manifest has two launcher entries (.IconLight / .IconDark) and this turns
 * on the right one. some launchers move the icon back to the app drawer when it switches.
 */
object AppIcon {
    fun apply(context: Context, dark: Boolean) {
        val pm = context.packageManager
        val light = ComponentName(context, "app.stash.IconLight")
        val darkIcon = ComponentName(context, "app.stash.IconDark")
        val (on, off) = if (dark) darkIcon to light else light to darkIcon
        // already right: don't touch it (each switch makes the launcher redraw / reshuffle)
        if (pm.getComponentEnabledSetting(on) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
            pm.getComponentEnabledSetting(off) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ) return
        pm.setComponentEnabledSetting(on, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(off, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }

    /**
     * android 12+ draws the splash before stash starts, in the app's night mode. stash pins that
     * mode to match the icon, so the splash is black only while the dark icon is on (the app's
     * own colors don't depend on it, see MainActivity). older android: the splash just follows
     * the phone.
     */
    fun applySplash(context: Context, dark: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val prefs = context.getSharedPreferences("splash", Context.MODE_PRIVATE)
        val mode = if (dark) android.app.UiModeManager.MODE_NIGHT_YES else android.app.UiModeManager.MODE_NIGHT_NO
        // changing it restarts the screen, so only when it really changes
        if (prefs.getInt("mode", -1) == mode) return
        prefs.edit().putInt("mode", mode).apply()
        context.getSystemService(android.app.UiModeManager::class.java).setApplicationNightMode(mode)
    }
}
