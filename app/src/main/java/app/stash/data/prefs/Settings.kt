package app.stash.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * how big downloads are. youtube streams already come in several sizes, so yt-dlp just picks
 * the right one ([ytFormat]); other sources get re-encoded to aac at [aacBitrate] on the phone.
 */
enum class DownloadQuality(val label: String, val about: String, val ytFormat: String, val aacBitrate: Int?) {
    HIGH("High", "Best available, about 130–160 kbps", "bestaudio[ext=m4a]/bestaudio/best", null),
    BALANCED(
        "Balanced", "About 70–96 kbps. Still sounds great, about half the size",
        "bestaudio[abr<=100]/bestaudio[abr<=140]/bestaudio/best", 96_000,
    ),
    SAVER("Data saver", "About 50–64 kbps, the smallest files", "worstaudio[abr>=40]/bestaudio[abr<=70]/bestaudio/best", 64_000),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** black launcher icon with white bars while the app is dark */
    val darkIcon: Boolean = true,
    val wifiOnlyDownloads: Boolean = false,
    val disabledProviders: Set<String> = emptySet(),
    val jamendoClientId: String = "",
    val appleDeveloperToken: String = "",
    val lastYtDlpUpdate: Long = 0,
    val ytDlpVersion: String = "",
    /** signed-in stash account, empty when logged out */
    val accountEmail: String = "",
    val lastBackupAt: Long = 0,
    /** turned on after the first sync on this phone, so an empty new install never wipes the backup */
    val cloudReady: Boolean = false,
    val locationGreetings: Boolean = false,
    /** keep playing similar songs when the queue runs out */
    val autoplay: Boolean = true,
    /** lyrics from musixmatch first (lrclib when it has none) */
    val lyricsMusixmatch: Boolean = true,
    /** show a translation under each lyric line */
    val lyricsTranslate: Boolean = false,
    /** language code to translate lyrics into; empty = the phone's language */
    val translateTo: String = "",
    val downloadQuality: DownloadQuality = DownloadQuality.BALANCED,
    val recentSearches: List<String> = emptyList(),
    /** playlists pinned to the top of home and the library, in the order they were pinned */
    val pinnedPlaylists: List<Long> = emptyList(),
    /** made-for-you mixes (by their mix id, "daily-1", "artist-…") saved to the library; the rest stay on home only */
    val savedMixes: Set<String> = emptySet(),
    /** where the heart button puts songs: null = liked songs, else that playlist */
    val heartPlaylist: Long? = null,
)

@Singleton
class Settings @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val darkIcon = booleanPreferencesKey("dark_icon")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val disabled = stringSetPreferencesKey("disabled_providers")
        val pinned = stringPreferencesKey("pinned_playlists")
        val savedMixes = stringSetPreferencesKey("saved_mixes")
        val heart = longPreferencesKey("heart_playlist")
        val jamendo = stringPreferencesKey("jamendo_client_id")
        val apple = stringPreferencesKey("apple_dev_token")
        val ytUpdated = longPreferencesKey("ytdlp_updated")
        val ytVersion = stringPreferencesKey("ytdlp_version")
        val email = stringPreferencesKey("account_email")
        val access = stringPreferencesKey("account_access")
        val refresh = stringPreferencesKey("account_refresh")
        val expiresAt = longPreferencesKey("account_expires")
        val lastBackup = longPreferencesKey("last_backup")
        val cloudReady = booleanPreferencesKey("cloud_ready")
        val locationGreetings = booleanPreferencesKey("location_greetings")
        val autoplay = booleanPreferencesKey("autoplay")
        val mxm = booleanPreferencesKey("lyrics_musixmatch")
        val translate = booleanPreferencesKey("lyrics_translate")
        val translateTo = stringPreferencesKey("lyrics_translate_to")
        val quality = stringPreferencesKey("download_quality")
        val recent = stringPreferencesKey("recent_searches")
        val scClient = stringPreferencesKey("soundcloud_client_id")
    }

    private val store get() = context.dataStore

    val flow: Flow<AppSettings> = store.data.map { it.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    private fun Preferences.toSettings() = AppSettings(
        themeMode = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = this[Keys.dynamic] ?: true,
        darkIcon = this[Keys.darkIcon] ?: true,
        wifiOnlyDownloads = this[Keys.wifiOnly] ?: false,
        disabledProviders = this[Keys.disabled] ?: emptySet(),
        jamendoClientId = this[Keys.jamendo] ?: "",
        appleDeveloperToken = this[Keys.apple] ?: "",
        lastYtDlpUpdate = this[Keys.ytUpdated] ?: 0,
        ytDlpVersion = this[Keys.ytVersion] ?: "",
        accountEmail = this[Keys.email] ?: "",
        lastBackupAt = this[Keys.lastBackup] ?: 0,
        cloudReady = this[Keys.cloudReady] ?: false,
        locationGreetings = this[Keys.locationGreetings] ?: false,
        autoplay = this[Keys.autoplay] ?: true,
        lyricsMusixmatch = this[Keys.mxm] ?: true,
        lyricsTranslate = this[Keys.translate] ?: false,
        translateTo = this[Keys.translateTo] ?: "",
        recentSearches = this[Keys.recent]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
        savedMixes = this[Keys.savedMixes] ?: emptySet(),
        heartPlaylist = this[Keys.heart]?.takeIf { it > 0 },
        pinnedPlaylists = this[Keys.pinned]?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
        downloadQuality = this[Keys.quality]?.let { runCatching { DownloadQuality.valueOf(it) }.getOrNull() } ?: DownloadQuality.BALANCED,
    )

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = store.edit { it[Keys.dynamic] = on }
    suspend fun setDarkIcon(on: Boolean) = store.edit { it[Keys.darkIcon] = on }
    suspend fun setWifiOnly(on: Boolean) = store.edit { it[Keys.wifiOnly] = on }
    suspend fun setProviderEnabled(id: String, enabled: Boolean) = store.edit {
        val set = (it[Keys.disabled] ?: emptySet()).toMutableSet()
        if (enabled) set.remove(id) else set.add(id)
        it[Keys.disabled] = set
    }
    suspend fun setJamendoClientId(v: String) = store.edit { it[Keys.jamendo] = v.trim() }
    suspend fun setAppleDeveloperToken(v: String) = store.edit { it[Keys.apple] = v.trim() }
    suspend fun setYtDlpUpdated(version: String) = store.edit {
        it[Keys.ytUpdated] = System.currentTimeMillis()
        it[Keys.ytVersion] = version
    }
    suspend fun soundCloudClientId(): String? = store.data.first()[Keys.scClient]
    suspend fun setSoundCloudClientId(id: String) = store.edit { it[Keys.scClient] = id }

    suspend fun addRecentSearch(q: String) = store.edit {
        val list = listOf(q.trim()) + (it[Keys.recent]?.split('\n').orEmpty().filter { old -> !old.equals(q.trim(), true) })
        it[Keys.recent] = list.filter { s -> s.isNotBlank() }.take(12).joinToString("\n")
    }
    suspend fun removeRecentSearch(q: String) = store.edit {
        it[Keys.recent] = it[Keys.recent]?.split('\n').orEmpty().filter { old -> old != q }.joinToString("\n")
    }
    suspend fun setPinned(playlistId: Long, pinned: Boolean) = store.edit {
        val now = it[Keys.pinned]?.split(',')?.mapNotNull { s -> s.toLongOrNull() }.orEmpty() - playlistId
        it[Keys.pinned] = (if (pinned) now + playlistId else now).joinToString(",")
    }

    suspend fun setMixSaved(mixId: String, saved: Boolean) = store.edit {
        val now = it[Keys.savedMixes].orEmpty()
        it[Keys.savedMixes] = if (saved) now + mixId else now - mixId
    }

    /** null = liked songs */
    suspend fun setHeartPlaylist(id: Long?) = store.edit { it[Keys.heart] = id ?: 0L }

    suspend fun setDownloadQuality(q: DownloadQuality) = store.edit { it[Keys.quality] = q.name }
    suspend fun setAutoplay(on: Boolean) = store.edit { it[Keys.autoplay] = on }
    suspend fun setLyricsMusixmatch(on: Boolean) = store.edit { it[Keys.mxm] = on }
    suspend fun setLyricsTranslate(on: Boolean) = store.edit { it[Keys.translate] = on }
    suspend fun setTranslateTo(lang: String) = store.edit { it[Keys.translateTo] = lang }
    suspend fun setLocationGreetings(on: Boolean) = store.edit { it[Keys.locationGreetings] = on }

    // account session (supabase auth)
    data class Session(val email: String, val access: String, val refresh: String, val expiresAt: Long)

    suspend fun session(): Session? {
        val p = store.data.first()
        val refresh = p[Keys.refresh] ?: return null
        return Session(p[Keys.email] ?: "", p[Keys.access] ?: "", refresh, p[Keys.expiresAt] ?: 0)
    }

    suspend fun saveSession(s: Session) = store.edit {
        it[Keys.email] = s.email
        it[Keys.access] = s.access
        it[Keys.refresh] = s.refresh
        it[Keys.expiresAt] = s.expiresAt
    }

    suspend fun clearSession() = store.edit {
        listOf(Keys.email, Keys.access, Keys.refresh).forEach(it::remove)
        it.remove(Keys.expiresAt)
        it.remove(Keys.cloudReady)
    }

    suspend fun setBackedUp(at: Long) = store.edit { it[Keys.lastBackup] = at }
    suspend fun setCloudReady() = store.edit { it[Keys.cloudReady] = true }
}
