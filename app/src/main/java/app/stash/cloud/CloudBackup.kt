package app.stash.cloud

import android.util.Log
import androidx.room.withTransaction
import app.stash.BuildConfig
import app.stash.data.db.LIKED_PLAYLIST_ID
import app.stash.data.db.Playlist
import app.stash.data.db.PlaylistDao
import app.stash.data.db.PlaylistTrack
import app.stash.data.db.StashDatabase
import app.stash.data.db.Track
import app.stash.data.db.TrackDao
import app.stash.data.db.UnmatchedTrack
import app.stash.data.prefs.Settings
import app.stash.providers.ProviderException
import app.stash.providers.asObjectList
import app.stash.providers.httpIo
import app.stash.providers.lenientJson
import app.stash.providers.num
import app.stash.providers.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * keeps a copy of your playlists and liked songs in your stash account, in their exact order
 * (not-found songs included, in their spot). backs up by itself a little after anything
 * changes; restoring adds whatever this phone doesn't have yet.
 */
@Singleton
class CloudBackup @Inject constructor(
    private val http: OkHttpClient,
    private val account: Account,
    private val settings: Settings,
    private val db: StashDatabase,
    private val tracks: TrackDao,
    private val playlists: PlaylistDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @OptIn(FlowPreview::class)
    fun startAutoBackup() = scope.launch {
        combine(playlists.observeAll(), tracks.observeLiked()) { a, b -> a to b }
            .drop(1) // the first emission is just the current state
            .debounce(AUTO_BACKUP_DELAY_MS)
            .collect {
                val s = settings.current()
                if (s.accountEmail.isNotEmpty() && s.cloudReady) {
                    runCatching { backup() }.onFailure { Log.w(TAG, "auto backup failed", it) }
                }
            }
    }

    /** after logging in: pull down what's in the account, then upload the merged library */
    suspend fun firstSync(): Int {
        val added = restore()
        backup()
        settings.setCloudReady()
        return added
    }

    suspend fun backup() = lock.withLock {
        val items = buildJsonArray {
            val liked = tracks.liked()
            add(buildJsonObject {
                put("kind", "liked")
                put("sort_index", 0)
                put("name", "liked songs")
                put("tracks", JsonArray(liked.map { trackJson(it) } + playlists.unmatched(LIKED_PLAYLIST_ID).map(::missingJson)))
            })
            // mixes get rebuilt on every phone anyway, no point backing them up
            playlists.all().filter { it.origin != "mix" }.forEachIndexed { i, p ->
                val entries = playlists.entries(p.id).map { it.position to trackJson(it.track) }
                val missing = playlists.unmatched(p.id).map { it.position to missingJson(it) }
                add(buildJsonObject {
                    put("kind", "playlist")
                    put("sort_index", i + 1)
                    put("name", p.name)
                    put("cover_url", p.coverUrl)
                    put("origin", p.origin)
                    put("origin_id", p.originId)
                    // songs and not-found songs merged back into source order
                    put("tracks", JsonArray((entries + missing).sortedBy { it.first }.map { it.second }))
                })
            }
        }
        rpc("replace_library", buildJsonObject { put("items", items) }.toString())
        settings.setBackedUp(System.currentTimeMillis())
    }

    /** adds playlists from the account that this phone doesn't have (matched by name), and liked songs */
    suspend fun restore(): Int = lock.withLock {
        val rows = lenientJson.parseToJsonElement(
            get("/rest/v1/library_items?select=kind,name,cover_url,origin,origin_id,tracks&order=sort_index")
        ).asObjectList()
        var added = 0
        db.withTransaction {
            for (row in rows) {
                val items = row["tracks"].asObjectList()
                if (row.str("kind") == "liked") {
                    val base = System.currentTimeMillis()
                    // restoring twice shouldn't list the same not-found likes twice
                    val hasMissing = playlists.unmatched(LIKED_PLAYLIST_ID).isNotEmpty()
                    items.forEachIndexed { i, o ->
                        val likedAt = base - i * 1000L
                        val t = trackFrom(o)
                        if (t == null) {
                            if (!hasMissing) missingFrom(o, LIKED_PLAYLIST_ID, i, likedAt)?.let { playlists.insertUnmatched(it) }
                        }
                        else if (tracks.get(t.id)?.liked != true) {
                            tracks.ensure(t); tracks.importLike(t.id, likedAt)
                        }
                    }
                    continue
                }
                val name = row.str("name") ?: continue
                if (playlists.findByName(name) != null) continue
                val id = playlists.insert(
                    Playlist(name = name, coverUrl = row.str("cover_url"), origin = row.str("origin") ?: "local", originId = row.str("origin_id"))
                )
                items.forEachIndexed { i, o ->
                    val t = trackFrom(o)
                    if (t == null) missingFrom(o, id, i, 0)?.let { playlists.insertUnmatched(it) }
                    else {
                        tracks.ensure(t); tracks.addToLibrary(t.id)
                        playlists.insertTrack(PlaylistTrack(id, t.id, i))
                    }
                }
                added++
            }
        }
        added
    }

    private fun trackJson(t: Track) = buildJsonObject {
        put("id", t.id); put("title", t.title); put("artist", t.artist); put("album", t.album)
        put("duration", t.durationMs); put("art", t.artworkUrl)
        put("provider", t.provider); put("source", t.sourceId); put("hint", t.streamHint)
    }

    private fun missingJson(u: UnmatchedTrack) = buildJsonObject {
        put("missing", true); put("title", u.title); put("artist", u.artist); put("album", u.album); put("duration", u.durationMs)
    }

    private fun trackFrom(o: JsonObject): Track? {
        if (o.str("missing") == "true") return null
        return Track(
            id = o.str("id") ?: return null,
            title = o.str("title") ?: return null,
            artist = o.str("artist") ?: "",
            album = o.str("album"),
            durationMs = (o.num("duration") ?: 0.0).toLong(),
            artworkUrl = o.str("art"),
            provider = o.str("provider") ?: return null,
            sourceId = o.str("source") ?: return null,
            streamHint = o.str("hint"),
        )
    }

    private fun missingFrom(o: JsonObject, playlistId: Long, position: Int, likedAt: Long): UnmatchedTrack? {
        val title = o.str("title") ?: return null
        return UnmatchedTrack(
            playlistId = playlistId, title = title, artist = o.str("artist") ?: "",
            album = o.str("album"), durationMs = (o.num("duration") ?: 0.0).toLong(), position = position, likedAt = likedAt,
        )
    }

    private suspend fun authed(): Request.Builder {
        val token = account.accessToken() ?: throw ProviderException("Not logged in")
        return Request.Builder().header("apikey", BuildConfig.SUPABASE_KEY).header("Authorization", "Bearer $token")
    }

    private suspend fun rpc(fn: String, json: String) {
        val req = authed().url("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/$fn")
            .post(json.toRequestBody("application/json".toMediaType())).build()
        httpIo {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ProviderException("Backup failed (${r.code}): ${r.body?.string()?.take(200)}")
            }
        }
    }

    private suspend fun get(path: String): String {
        val req = authed().url(BuildConfig.SUPABASE_URL + path).build()
        return httpIo {
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ProviderException("Couldn't load your backup (${r.code})")
                body
            }
        }
    }

    companion object {
        private const val TAG = "CloudBackup"
        private const val AUTO_BACKUP_DELAY_MS = 20_000L
    }
}
