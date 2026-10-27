package app.stash.providers

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * does a youtube audio link really play? asked the way the player asks (an open-ended range
 * from the start, and again from further in) and actually reading audio: some links answer a
 * tiny "bytes=0-1" probe but are refused (403) once real playback starts.
 */
object StreamCheck {
    fun plays(http: OkHttpClient, url: String, ua: String): Boolean =
        listOf("bytes=0-", "bytes=1000000-").all { range ->
            runCatching {
                http.newCall(Request.Builder().url(url).header("User-Agent", ua).header("Range", range).build()).execute().use { r ->
                    when (r.code) {
                        206, 200 -> r.body?.byteStream()?.readNBytes(4096)?.isNotEmpty() == true
                        // shorter than a megabyte: the first range already proved it
                        416 -> range != "bytes=0-"
                        else -> false
                    }
                }
            }.getOrDefault(false)
        }
}
