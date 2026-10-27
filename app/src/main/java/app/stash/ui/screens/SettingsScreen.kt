package app.stash.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stash.cloud.Account
import app.stash.cloud.CloudBackup
import app.stash.data.prefs.Settings
import app.stash.data.prefs.ThemeMode
import app.stash.providers.ProviderRegistry
import app.stash.providers.YtDlp
import app.stash.ui.LocalActions
import app.stash.ui.LocalNav
import app.stash.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settings: Settings,
    val registry: ProviderRegistry,
    private val ytDlp: YtDlp,
    private val account: Account,
    private val cloud: CloudBackup,
    resolver: app.stash.providers.Resolver,
    val frontends: app.stash.providers.Frontends,
) : ViewModel() {
    val playbackLog = resolver.log
    val frontendBusy = MutableStateFlow(false)

    fun updateFrontends() {
        frontendBusy.value = true
        viewModelScope.launch { frontends.update(force = true); frontendBusy.value = false }
    }
    val ytStatus = ytDlp.status
    val updateResult = MutableStateFlow<String?>(null)
    val accountBusy = MutableStateFlow(false)
    val accountMessage = MutableStateFlow<String?>(null)

    private fun accountAction(block: suspend () -> String?) = viewModelScope.launch {
        accountBusy.value = true
        accountMessage.value = runCatching { block() }.getOrElse { it.message ?: "Something went wrong" }
        accountBusy.value = false
    }

    fun signIn(email: String, password: String) = accountAction {
        account.signIn(email, password)
        val added = cloud.firstSync()
        if (added > 0) "Logged in, restored $added playlists" else "Logged in, your library is backed up"
    }

    fun signUp(email: String, password: String) = accountAction {
        when (account.signUp(email, password)) {
            Account.SignUpResult.CheckEmail -> "Check your email and tap the confirm link, then come back here. You'll be logged in by yourself"
            Account.SignUpResult.SignedIn -> { cloud.firstSync(); "Account made, your library is backed up" }
        }
    }

    fun backupNow() = accountAction { cloud.backup(); "Backed up" }
    fun restore() = accountAction { "restored ${cloud.restore()} playlists" }
    fun signOut() = accountAction { account.signOut(); null }

    /** how many devices are logged in (this phone, tvs, tablets) */
    val sessions = MutableStateFlow<Int?>(null)
    fun refreshSessions() = viewModelScope.launch { sessions.value = runCatching { account.sessionCount() }.getOrNull() }
    fun logOutOthers() = accountAction { account.logOutOthers(); sessions.value = 1; "Logged out every other device" }

    fun updateYtDlp() = viewModelScope.launch {
        updateResult.value = "Checking…"
        updateResult.value = ytDlp.update()
    }

    fun set(block: suspend Settings.() -> Unit) = viewModelScope.launch { settings.block() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = hiltViewModel()
    val nav = LocalNav.current
    val actions = LocalActions.current
    val s by actions.settings.collectAsState()
    val ytStatus by vm.ytStatus.collectAsState()
    val updateResult by vm.updateResult.collectAsState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings = s ?: return

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp)) {
            item { SectionHeader("Account") }
            item { AccountCard(vm, settings.accountEmail, settings.lastBackupAt) }

            item { SectionHeader("Look") }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    val modes = ThemeMode.entries
                    modes.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = settings.themeMode == m,
                            onClick = { vm.set { setTheme(m) } },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(m.name.lowercase()) }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) item {
                SwitchRow("Colors from wallpaper", "Material You dynamic color", settings.dynamicColor) { vm.set { setDynamicColor(it) } }
                SwitchRow("Dark icon in dark mode", "Black icon with white bars while the app is dark", settings.darkIcon) { vm.set { setDarkIcon(it) } }
            }

            item {
                val context = LocalContext.current
                val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                    if (granted.values.any { it }) vm.set { setLocationGreetings(true) }
                }
                SwitchRow(
                    "Greet me by place",
                    "Home says something different at the airport, a cafe, on a train… uses your location once per visit and asks OpenStreetMap what's nearby",
                    settings.locationGreetings,
                ) { on ->
                    val has = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (on && !has) askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    else vm.set { setLocationGreetings(on) }
                }
            }

            item { SectionHeader("Playback") }
            item {
                SwitchRow("Autoplay", "When your queue ends, keep going with similar songs", settings.autoplay) {
                    vm.set { setAutoplay(it) }
                }
            }

            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                val nav = app.stash.ui.LocalNav.current
                // re-read when coming back from the login screen
                var loggedIn by remember { mutableStateOf(false) }
                var pasting by remember { mutableStateOf(false) }
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                    loggedIn = app.stash.providers.YouTubeLogin.isLoggedIn(context)
                    onPauseOrDispose { }
                }
                ListItem(
                    headlineContent = { Text(if (loggedIn) "Logged in to YouTube" else "Log in to YouTube") },
                    supportingContent = {
                        Column {
                            Text(
                                "Helps songs that YouTube won't hand out anonymously (like PipePipe's login). " +
                                    "Only used for song audio, search stays anonymous.",
                            )
                            if (!loggedIn) androidx.compose.material3.OutlinedButton(
                                onClick = { pasting = true }, modifier = Modifier.padding(top = 8.dp),
                            ) { Text("Paste cookies instead (with guide)") }
                        }
                    },
                    trailingContent = {
                        if (loggedIn) TextButton(onClick = { app.stash.providers.YouTubeLogin.logOut(context); loggedIn = false }) { Text("Log out") }
                        else TextButton(onClick = { nav.navigate(app.stash.ui.Routes.YOUTUBE_LOGIN) }) { Text("Log in") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                if (pasting) PasteCookiesDialog(
                    onDismiss = { pasting = false },
                    onSave = { header ->
                        app.stash.providers.YouTubeLogin.save(context, header)
                        loggedIn = true
                        pasting = false
                        actions.toast("Logged in to YouTube")
                    },
                )
            }
            item {
                val log by vm.playbackLog.collectAsState()
                var open by remember { mutableStateOf(false) }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (open) "Hide playback log" else "Playback log (how long songs took to start)")
                    }
                    if (open) {
                        if (log.isEmpty()) Text("Play something first.", style = MaterialTheme.typography.bodySmall)
                        val starts = log.filter { it.via == "▶ playing" }
                        if (starts.isNotEmpty()) Text(
                            "Songs start in ${"%.1f".format(starts.map { it.ms }.average() / 1000)}s on average " +
                                "(${starts.size} played, ${log.count { it.error != null }} failed)",
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp),
                        )
                        log.forEach { e -> LogRow(e) }
                    }
                }
            }

            item {
                val info by vm.frontends.info.collectAsState()
                val busy by vm.frontendBusy.collectAsState()
                ListItem(
                    headlineContent = { Text("YouTube frontends") },
                    supportingContent = {
                        Text(
                            "Invidious / Piped servers that fetch YouTube for you, so it plays without a VPN. " +
                                "${info.instances} on the list" +
                                (if (info.updatedAt > 0) ", updated " + android.text.format.DateUtils.getRelativeTimeSpanString(info.updatedAt) else "") +
                                (info.error?.let { ". Last update failed: $it" } ?: "") + ". Updates by itself on launch.",
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { vm.updateFrontends() }, enabled = !busy) { Text(if (busy) "Updating…" else "Update now") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            item {
                val target by actions.heartTarget.collectAsState()
                var picking by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text("Heart button") },
                    supportingContent = { Text("Adds songs to ${target?.name ?: "Liked songs"}. Tap to pick another playlist.") },
                    modifier = Modifier.clickable { picking = true },
                )
                if (picking) app.stash.ui.components.HeartTargetDialog(onDismiss = { picking = false })
            }
            item { SectionHeader("Lyrics") }
            item {
                SwitchRow(
                    "Musixmatch", "Lyrics from Musixmatch first (with its translations), LRCLIB when it has none",
                    settings.lyricsMusixmatch,
                ) { vm.set { setLyricsMusixmatch(it) } }
            }
            item {
                // like EeveeSpotify: your own musixmatch token, else an anonymous one
                var editing by remember { mutableStateOf(false) }
                var hasOwn by remember { mutableStateOf(actions.lyrics.musixmatchToken != null) }
                ListItem(
                    headlineContent = { Text("Musixmatch token") },
                    supportingContent = {
                        Text(
                            if (hasOwn) "Using your token" else "Using an anonymous one. Tap to paste yours (more songs, fewer refusals)"
                        )
                    },
                    modifier = Modifier.clickable { editing = true },
                )
                if (editing) MusixmatchTokenDialog(
                    hasOwn = hasOwn,
                    onSave = { raw ->
                        val ok = actions.lyrics.setMusixmatchToken(raw)
                        if (ok) { hasOwn = !raw.isNullOrBlank(); editing = false; actions.toast(if (raw.isNullOrBlank()) "Back to the anonymous token" else "Musixmatch token saved") }
                        ok
                    },
                    onDismiss = { editing = false },
                )
            }
            item {
                SwitchRow("Show translation", "A translation under every line: Musixmatch's when it has one, else machine translation", settings.lyricsTranslate) {
                    vm.set { setLyricsTranslate(it) }
                }
            }
            item {
                val choices = listOf("" to "Phone's language", "en" to "English", "ru" to "Russian", "ja" to "Japanese", "uk" to "Ukrainian", "de" to "German", "es" to "Spanish")
                var open by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text("Translate to") },
                    supportingContent = { Text(choices.firstOrNull { it.first == settings.translateTo }?.second ?: settings.translateTo) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { open = true },
                    trailingContent = {
                        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            choices.forEach { (code, name) ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(name) }, onClick = { open = false; vm.set { setTranslateTo(code) } })
                            }
                        }
                    },
                )
            }

            item { SectionHeader("Downloads") }
            item {
                SwitchRow("Wi-Fi only", "Downloads wait for an unmetered network", settings.wifiOnlyDownloads) {
                    actions.downloads.setWifiOnly(it)
                }
            }

            item { SectionHeader("Sources") }
            item {
                Text(
                    "Tried in this order when something won't play or an imported song needs matching.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            items(vm.registry.all, key = { it.id }) { p ->
                val needsKey = p.id == "jamendo" && settings.jamendoClientId.isBlank()
                SwitchRow(
                    p.displayName,
                    when {
                        needsKey -> "Needs a client ID (below)"
                        p.id == "audius" -> "Independent artists, open API"
                        p.id == "soundcloud" -> "SoundCloud's own API, yt-dlp as backup"
                        p.id == "youtube" || p.id == "bandcamp" -> "Via yt-dlp"
                        else -> "Open / CC-licensed"
                    },
                    p.id !in settings.disabledProviders && !needsKey,
                    enabled = !needsKey,
                ) { on -> vm.set { setProviderEnabled(p.id, on) } }
            }
            item {
                KeyField("Jamendo client ID", settings.jamendoClientId, "Free from devportal.jamendo.com") { vm.set { setJamendoClientId(it) } }
            }

            item { SectionHeader("yt-dlp") }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Version ${settings.ytDlpVersion.ifBlank { "Bundled" }}", style = MaterialTheme.typography.titleMedium)
                        Text("Status: $ytStatus", style = MaterialTheme.typography.bodySmall)
                        Text("Updates itself every time the app starts, because YouTube keeps breaking old versions.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        updateResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        Row(Modifier.padding(top = 8.dp)) {
                            FilledTonalButton(onClick = { vm.updateYtDlp() }) { Text("Update now") }
                        }
                    }
                }
            }

            item { SectionHeader("Playlist import") }
            item {
                KeyField("Apple MusicKit developer token (optional)", settings.appleDeveloperToken,
                    "Without it, public playlist links are read from the web page", secret = true) { vm.set { setAppleDeveloperToken(it) } }
            }

            item { SectionHeader("About") }
            item {
                Text(
                    "Stash · personal use only. YouTube, SoundCloud and Bandcamp go through yt-dlp, which is against their terms, " +
                        "so keep this to yourself. Jamendo, Internet Archive and Free Music Archive are open sources.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun KeyField(label: String, value: String, hint: String, secret: Boolean = false, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        supportingText = { Text(hint) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        shape = MaterialTheme.shapes.medium,
        trailingIcon = if (text != value) ({
            TextButton(onClick = { onSave(text) }) { Text("Save") }
        }) else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** log in / sign up, or the backup controls once logged in */
@Composable
private fun AccountCard(vm: SettingsViewModel, email: String, lastBackupAt: Long) {
    val busy by vm.accountBusy.collectAsState()
    val message by vm.accountMessage.collectAsState()
    var mail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (email.isNotEmpty()) {
                Text(email, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (lastBackupAt > 0) "Playlists and liked songs backed up " +
                        DateUtils.getRelativeTimeSpanString(lastBackupAt).toString().lowercase()
                    else "Not backed up yet",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("It backs up by itself a few seconds after anything changes.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.backupNow() }, enabled = !busy) { Text("Back up now") }
                    OutlinedButton(onClick = { vm.restore() }, enabled = !busy) { Text("Restore") }
                }
                val sessions by vm.sessions.collectAsState()
                LaunchedEffect(email) { vm.refreshSessions() }
                sessions?.let { n ->
                    Text(if (n <= 1) "Logged in on this device only" else "Logged in on $n devices", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.signOut() }, enabled = !busy) { Text("Log out") }
                    if ((sessions ?: 0) > 1) TextButton(onClick = { vm.logOutOthers() }, enabled = !busy) { Text("Log out other devices") }
                }
            } else {
                Text("Save your playlists to a Stash account so they survive a reinstall or a new phone.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = mail, onValueChange = { mail = it }, label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password (6+ characters)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                )
                val ready = mail.contains('@') && password.length >= 6 && !busy
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.signIn(mail, password) }, enabled = ready) { Text("Log in") }
                    OutlinedButton(onClick = { vm.signUp(mail, password) }, enabled = ready) { Text("Create account") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** one playback log line: a time pill (green quick, amber slow, red failed), the song, where it came from */
@Composable
private fun LogRow(e: app.stash.providers.Resolver.LogEntry) {
    val secs = e.ms / 1000.0
    val pill = when {
        e.error != null -> MaterialTheme.colorScheme.error
        secs < 5 -> Color(0xFF2E7D32)
        secs < 15 -> Color(0xFFB26A00)
        else -> Color(0xFFC62828)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(58.dp).clip(RoundedCornerShape(50)).background(pill.copy(alpha = 0.2f)).padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) { Text(if (e.error != null) "failed" else "%.1fs".format(secs), style = MaterialTheme.typography.labelMedium, color = pill) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                e.error ?: e.via, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (e.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** paste the musixmatch app's debug info (or the token itself) */
@Composable
private fun MusixmatchTokenDialog(hasOwn: Boolean, onSave: (String?) -> Boolean, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Musixmatch token") },
        text = {
            Column {
                Text(
                    "In the Musixmatch app: sign in, then Settings → Get help → Copy debug info, and paste it all here. " +
                        "A bare token works too. Without one, Stash uses an anonymous token."
                )
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it; bad = false },
                    placeholder = { Text("---- Debug Info ---- …") },
                    isError = bad,
                    supportingText = { if (bad) Text("No token in that. Copy the whole debug info") },
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (!onSave(text)) bad = true }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (hasOwn) TextButton(onClick = { onSave(null) }) { Text("Use anonymous") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
