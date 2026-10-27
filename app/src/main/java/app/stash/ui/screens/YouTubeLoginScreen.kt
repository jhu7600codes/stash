package app.stash.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.stash.providers.YouTubeLogin
import app.stash.ui.LocalActions
import app.stash.ui.LocalNav

/**
 * google's own sign-in page in a webview, the way youtube classic / pipepipe do it: a desktop
 * chrome identity and no X-Requested-With header (google's main "this app may not be secure"
 * signal). once youtube.com's cookies show a signed-in session they're saved for yt-dlp.
 */
@SuppressLint("SetJavaScriptEnabled", "RestrictedApi")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLoginScreen() {
    val nav = LocalNav.current
    val actions = LocalActions.current
    var progress by remember { mutableIntStateOf(0) }
    var pasting by remember { androidx.compose.runtime.mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log in to YouTube") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text(
                "Only used to get song audio when YouTube's quick way refuses. Search stays anonymous. " +
                    "The login stays on this phone.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            androidx.compose.material3.TextButton(onClick = { pasting = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Google won't let you log in here? Paste cookies instead")
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.WHITE)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = DESKTOP_UA
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (url == null || !url.startsWith("https://www.youtube.com")) return
                                val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com")
                                if (!YouTubeLogin.hasSession(cookies)) return
                                YouTubeLogin.save(ctx, cookies!!)
                                actions.toast("Logged in to YouTube")
                                nav.popBackStack()
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) =
                                !request.url.scheme.orEmpty().startsWith("http")
                        }
                        loadUrl(LOGIN_URL)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
    if (pasting) PasteCookiesDialog(
        onDismiss = { pasting = false },
        onSave = { header ->
            YouTubeLogin.save(context, header)
            actions.toast("Logged in to YouTube")
            pasting = false
            nav.popBackStack()
        },
    )
}

/** the cookie-paste way in, with how to get them (same as youtube classic's) */
@Composable
fun PasteCookiesDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { androidx.compose.runtime.mutableStateOf("") }
    var error by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste YouTube cookies") },
        text = {
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Text("How to get them:", style = MaterialTheme.typography.titleSmall)
                listOf(
                    "1. Get Firefox (or Kiwi / Edge) on your phone, or use a computer.",
                    "2. Install the \"Cookie-Editor\" extension (by cgagnier) from its add-ons store.",
                    "3. Open youtube.com and log in to your account there.",
                    "4. Tap Cookie-Editor on youtube.com → Export → \"Header String\" (Netscape or JSON work too). It's copied now.",
                    "5. Come back here and tap \"Paste from clipboard\", then Save. (From a computer: send it to yourself first.)",
                ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Treat these like a password: they log into your account. Stash keeps them on this phone and only " +
                        "sends them to YouTube. Log out in settings removes them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com")))
                        }
                    }) { Text("Open youtube.com") }
                    androidx.compose.material3.FilledTonalButton(onClick = {
                        val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (clip.isNullOrBlank()) error = "Clipboard is empty" else { text = clip; error = null }
                    }) { Text("Paste from clipboard") }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null }, maxLines = 4,
                    label = { Text("Cookies") }, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = text.isNotBlank(), onClick = {
                val header = YouTubeLogin.parsePasted(text)
                if (header == null) error = "No YouTube login in there (it needs SAPISID). Export again while logged in on youtube.com."
                else onSave(header)
            }) { Text("Save") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/signin"
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
