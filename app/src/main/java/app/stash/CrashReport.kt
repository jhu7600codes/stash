package app.stash

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * "crash on launch" with no way to see why (a tv has no logcat at hand): the crash is written
 * to a file, and the next launch shows it before anything else, with a copy button.
 */
object CrashReport {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                file(context).writeText(
                    "stash $version · ${Build.MANUFACTURER} ${Build.MODEL} · android ${Build.VERSION.RELEASE} (${Build.SUPPORTED_ABIS.joinToString()})\n" +
                        "thread ${thread.name}\n\n" + e.stackTraceToString().take(12_000)
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** shows the last crash if there is one, then [then]; straight to [then] otherwise */
    fun showIfAny(activity: Activity, then: () -> Unit) {
        val f = file(activity)
        val text = runCatching { f.takeIf { it.exists() }?.readText() }.getOrNull()
        if (text.isNullOrBlank()) return then()
        f.delete()
        val view = ScrollView(activity).apply {
            setPadding(48, 24, 48, 0)
            addView(TextView(activity).apply { this.text = text; textSize = 11f; setTextIsSelectable(true) })
        }
        AlertDialog.Builder(activity)
            .setTitle("Stash crashed last time")
            .setView(view)
            .setPositiveButton("Continue") { _, _ -> then() }
            .setNeutralButton("Copy") { _, _ ->
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("stash crash", text))
                then()
            }
            .setCancelable(false)
            .show()
    }
}
