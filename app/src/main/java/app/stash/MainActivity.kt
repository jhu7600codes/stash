package app.stash

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import app.stash.cloud.Account
import app.stash.cloud.CloudBackup
import app.stash.ui.tv.TvApp
import app.stash.ui.tv.TvHooks
import app.stash.data.prefs.ThemeMode
import app.stash.ui.AppViewModel
import app.stash.ui.Routes
import app.stash.ui.StashRoot
import app.stash.ui.theme.StashTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var account: Account
    @Inject lateinit var cloud: CloudBackup

    private val app: AppViewModel by viewModels()
    private val navRequests = Channel<String>(Channel.BUFFERED)
    private val openPlayer = Channel<Unit>(Channel.CONFLATED)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // the splash (the icon's bars), then the bars grow and fade into the app
        installSplashScreen().setOnExitAnimationListener { splash ->
            val view = splash.view
            // some tvs show the splash with no icon (iconView throws): then stash puts its own bars
            // on it, so the grow-and-fade still happens
            val icon = runCatching { splash.iconView }.getOrNull() ?: (view as? android.view.ViewGroup)?.let { group ->
                val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val size = (240 * resources.displayMetrics.density).toInt()
                android.widget.ImageView(this).apply {
                    setImageResource(if (night) R.drawable.ic_launcher_dark_foreground else R.drawable.ic_launcher_foreground)
                    group.addView(this, android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER))
                }
            }
            icon?.animate()?.scaleX(1.6f)?.scaleY(1.6f)?.alpha(0f)?.setDuration(320)
                ?.setInterpolator(android.view.animation.AccelerateInterpolator())?.start()
            view.animate().alpha(0f).setStartDelay(120).setDuration(280)
                .withEndAction { runCatching { splash.remove() } }.start()
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readSystemNight()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // a crash last time: show it first (photo / copy), then start as usual
        CrashReport.showIfAny(this) { startUi() }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun startUi() {
        setContent {
            val settings by app.settings.collectAsState()
            // the launcher icon follows the app: dark icon while the app is dark (unless turned off)
            // the phone's own dark mode. on android 12+ the app's night mode is pinned to match the
            // splash (below), so read the system's setting, not the app's
            val systemDark = if (Build.VERSION.SDK_INT >= 31) systemNight.value
            else androidx.compose.foundation.isSystemInDarkTheme()
            settings?.let { s ->
                val dark = when (s.themeMode) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> systemDark
                }
                androidx.compose.runtime.LaunchedEffect(dark && s.darkIcon) {
                    runCatching { AppIcon.apply(this@MainActivity, dark && s.darkIcon) }
                    // the splash follows the icon: black only while the dark icon is showing
                    runCatching { AppIcon.applySplash(this@MainActivity, dark && s.darkIcon) }
                }
            }
            StashTheme(
                // tvs are always dark, with stash's own colors
                themeMode = if (isTv) ThemeMode.DARK else (settings?.themeMode ?: ThemeMode.SYSTEM).let {
                    if (it == ThemeMode.SYSTEM && Build.VERSION.SDK_INT >= 31) (if (systemDark) ThemeMode.DARK else ThemeMode.LIGHT) else it
                },
                dynamicColor = !isTv && (settings?.dynamicColor ?: true),
            ) {
                if (isTv) TvApp(app, tvHooks)
                else StashRoot(app, navRequests.receiveAsFlow(), openPlayer.receiveAsFlow())
            }
        }
    }

    private val tvSyncing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** the tv app's account bits. the sync runs on the activity's scope: started from the pairing
     *  screen, it used to get cancelled the moment the login swapped that screen out (0 playlists) */
    private val tvHooks by lazy {
        TvHooks(
            sync = {
                if (tvSyncing.compareAndSet(false, true)) lifecycleScope.launch {
                    app.toast("Getting your playlists…")
                    runCatching { cloud.firstSync() }
                        .onSuccess { added -> app.toast(if (added > 0) "Got $added playlists" else "Your library is up to date") }
                        .onFailure { app.toast("Couldn't get your playlists: ${it.message}") }
                    tvSyncing.set(false)
                }
            },
            logOut = { account.signOut(); Unit },
            sessions = { account.sessionCount() },
            logOutOthers = { account.logOutOthers() },
        )
    }

    /** android tv / google tv: the remote-friendly app (ui/tv) instead of the phone one */
    private val isTv by lazy { packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) }

    /** the system's dark mode (not the app's, which is pinned for the splash) */
    private val systemNight = androidx.compose.runtime.mutableStateOf(false)

    private fun readSystemNight() {
        systemNight.value = (android.content.res.Resources.getSystem().configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onResume() {
        super.onResume()
        readSystemNight()
        // back from tapping the confirm link in the email app: finish logging in
        if (account.hasPendingSignup) lifecycleScope.launch {
            if (account.completePendingSignup()) afterLogin()
        }
    }

    private suspend fun afterLogin() {
        val added = runCatching { cloud.firstSync() }.getOrDefault(0)
        app.toast(if (added > 0) "Logged in, restored $added playlists" else "Logged in, your library is backed up")
        navRequests.send(Routes.SETTINGS)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == "stash" && data.host == "auth-callback") {
            lifecycleScope.launch {
                runCatching { account.handleRedirect(data) }
                    .onSuccess { afterLogin() }
                    .onFailure { app.toast(it.message ?: "Couldn't log in from that link") }
            }
            return
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            openPlayer.trySend(Unit)
            return
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_RESCAN, false)) {
            navRequests.trySend(Routes.RESCAN)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_IMPORTS, false)) {
            navRequests.trySend(Routes.IMPORT_PROGRESS)
            return
        }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            navRequests.trySend(Routes.import(text))
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        const val EXTRA_OPEN_IMPORTS = "open_imports"
        const val EXTRA_OPEN_RESCAN = "open_rescan"
    }
}
