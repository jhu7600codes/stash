package app.stash.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import app.stash.providers.YouTubeLogin
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** what the webview is playing: [trackId]'s song, at [positionMs] of [durationMs] */
data class WebPlayback(
    val trackId: String,
    val playing: Boolean = false,
    val loading: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * the last resort, when no link stash can find will play: music.youtube.com itself, in a hidden
 * webview, logged in with your youtube cookies. youtube sees a normal browser playing a song, so
 * none of the bot checks / refused links apply. stash drives it with a bit of javascript (play,
 * pause, seek, skip ads) and reads the position back twice a second.
 *
 * only ever after asking ([offer]); the player is paused meanwhile and skips on if nobody answers.
 */
@Singleton
class WebViewPlayer @Inject constructor(@ApplicationContext private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    private val _offer = MutableStateFlow<String?>(null)
    /** the song waiting for a yes / no on "use a webview?" */
    val offer: StateFlow<String?> = _offer

    private val _active = MutableStateFlow<WebPlayback?>(null)
    val active: StateFlow<WebPlayback?> = _active

    private var web: WebView? = null
    private var wantPlaying = true
    private var seenId: String? = null
    private var onDecline: (() -> Unit)? = null
    private var onEnded: (() -> Unit)? = null
    private var onAccept: ((String) -> Unit)? = null
    private val timeout = Runnable { decline() }

    /** asks the user; [accept] gets the track id on yes, [decline] runs on no or after 30s */
    fun offer(trackId: String, accept: (String) -> Unit, decline: () -> Unit): Boolean = main.post {
        main.removeCallbacks(timeout)
        onAccept = accept
        onDecline = decline
        _offer.value = trackId
        main.postDelayed(timeout, OFFER_MS)
    }

    fun accept(): Boolean = main.post {
        main.removeCallbacks(timeout)
        val id = _offer.value ?: return@post
        _offer.value = null
        onAccept?.invoke(id)
        onAccept = null; onDecline = null
    }

    /** forget the question without skipping (the queue already moved on) */
    fun dismiss(): Boolean = main.post {
        main.removeCallbacks(timeout)
        _offer.value = null
        onAccept = null; onDecline = null
    }

    fun decline(): Boolean = main.post {
        main.removeCallbacks(timeout)
        if (_offer.value == null) return@post
        _offer.value = null
        onDecline?.invoke()
        onAccept = null; onDecline = null
    }

    /** plays [videoId] (or the first song a youtube music search for [query] finds) in the hidden webview */
    @SuppressLint("SetJavaScriptEnabled")
    fun start(trackId: String, videoId: String?, query: String, ended: () -> Unit): Boolean = main.post {
        stopNow()
        onEnded = ended
        wantPlaying = true
        seenId = videoId
        _active.value = WebPlayback(trackId)
        shareLogin()
        val w = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = DESKTOP_UA
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = WebViewClient()
            // never shown, but a page with no size never builds its player: give it a desktop-sized one
            measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1280, 720)
        }
        web = w
        w.loadUrl(
            if (videoId != null) "https://music.youtube.com/watch?v=$videoId"
            else "https://music.youtube.com/search?q=" + android.net.Uri.encode(query)
        )
        main.postDelayed(poll, POLL_MS)
    }

    fun play() {
        wantPlaying = true
        js("var v=document.querySelector('video'); if(v) v.play();")
    }

    fun pause() {
        wantPlaying = false
        js("var v=document.querySelector('video'); if(v) v.pause();")
    }

    fun seekTo(ms: Long) {
        js("var v=document.querySelector('video'); if(v) v.currentTime=${ms / 1000.0};")
    }

    fun stop() {
        main.post { stopNow() }
    }

    private fun stopNow() {
        main.removeCallbacks(poll)
        web?.run { stopLoading(); loadUrl("about:blank"); destroy() }
        web = null
        onEnded = null
        _active.value = null
    }

    private fun js(code: String) {
        main.post { web?.evaluateJavascript(code, null) }
    }

    /** the youtube login stash already has (webview login or pasted cookies), so it plays as you, no ads where premium */
    private fun shareLogin() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        if (YouTubeLogin.hasSession(cm.getCookie("https://music.youtube.com"))) return
        val file = YouTubeLogin.cookieFile(context)
        if (!file.exists()) return
        file.readLines().filter { !it.startsWith("#") && it.count { c -> c == '\t' } >= 6 }.forEach { line ->
            val p = line.split('\t')
            val cookie = "${p[5]}=${p[6]}; Domain=.youtube.com; Path=/; Secure"
            cm.setCookie("https://music.youtube.com", cookie)
            cm.setCookie("https://www.youtube.com", cookie)
        }
        cm.flush()
    }

    private val poll = object : Runnable {
        override fun run() {
            val w = web ?: return
            w.evaluateJavascript(POLL_JS.replace("WANT", wantPlaying.toString())) { raw -> read(raw) }
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun read(raw: String?) {
        val cur = _active.value ?: return
        // evaluateJavascript hands back a json string literal holding our json
        val text = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw ?: return).jsonPrimitive.content }.getOrNull() ?: return
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        fun num(k: String) = o[k]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val hasVideo = o["v"]?.jsonPrimitive?.content == "true"
        val ad = o["ad"]?.jsonPrimitive?.content == "true"
        // youtube music goes on to its own next song by itself: a new video id means ours finished
        val id = o["id"]?.jsonPrimitive?.content?.takeIf { it != "null" }
        if (hasVideo && !ad && seenId == null) seenId = id
        val movedOn = id != null && seenId != null && id != seenId
        if (movedOn || (hasVideo && !ad && o["ended"]?.jsonPrimitive?.content == "true" && num("t") > 1)) {
            val done = onEnded
            stopNow()
            done?.invoke()
            return
        }
        _active.value = cur.copy(
            playing = hasVideo && !ad && o["paused"]?.jsonPrimitive?.content == "false",
            loading = !hasVideo || ad || num("d") <= 0,
            positionMs = if (ad) cur.positionMs else (num("t") * 1000).toLong(),
            durationMs = if (ad) cur.durationMs else (num("d") * 1000).toLong(),
        )
    }

    private companion object {
        const val OFFER_MS = 30_000L
        const val POLL_MS = 500L
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /**
         * runs twice a second: on a search page opens the first song; skips ads (jumps to their
         * end, presses skip); keeps it playing or paused like stash wants; reports where it is
         */
        val POLL_JS = """
            (function(){
              if (location.pathname.indexOf('/search') === 0) {
                var a = document.querySelector('a[href*="watch?v="]');
                if (a) location.href = a.href;
                return JSON.stringify({v:false});
              }
              var v = document.querySelector('video');
              if (!v) return JSON.stringify({v:false});
              var ad = !!document.querySelector('.ad-showing, .ad-interrupting');
              if (ad) {
                var s = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                if (s) s.click();
                if (isFinite(v.duration)) v.currentTime = v.duration;
              }
              v.muted = false; v.volume = 1;
              if (WANT && v.paused && !v.ended) { var p = v.play(); if (p) p.catch(function(){}); }
              if (!WANT && !v.paused) v.pause();
              return JSON.stringify({v:true, id:new URLSearchParams(location.search).get('v'), t:v.currentTime, d:isFinite(v.duration)?v.duration:0, paused:v.paused, ended:v.ended, ad:ad});
            })()
        """.trimIndent()
    }
}
