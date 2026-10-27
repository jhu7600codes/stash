package app.stash.providers

import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * youtube audio through NewPipeExtractor, the library newpipe / pipepipe / youtube classic use:
 * plain java, with rhino solving youtube's player code, so a song takes a second or two
 * instead of yt-dlp's 30+ (it boots a whole python each time). anonymous, like newpipe.
 */
@Singleton
class NewPipeStreams @Inject constructor(private val client: OkHttpClient) {
    private val downloader = Okhttp(client)

    private val ready by lazy { NewPipe.init(downloader) }

    suspend fun audioStream(videoUrl: String): StreamSource? = httpIo {
        ready
        val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        val plain = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        // opus first (best sounding per bit), then m4a, then whatever's biggest
        val best = plain.filter { it.format?.name?.contains("WEBMA", ignoreCase = true) == true || it.format?.suffix == "webm" }
            .maxByOrNull { it.averageBitrate }
            ?: plain.filter { it.format?.suffix == "m4a" }.maxByOrNull { it.averageBitrate }
            ?: plain.maxByOrNull { it.averageBitrate }
            ?: return@httpIo null
        val url = best.content
        // the streaming servers want the same app identity the link was made for
        val ua = when {
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(Localization.DEFAULT)
            YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(Localization.DEFAULT)
            "c=ANDROID" in url -> YoutubeParsingHelper.getAndroidUserAgent(Localization.DEFAULT)
            else -> UA
        }
        // first two bytes: a refused link falls through to yt-dlp instead of failing in the player
        val ok = StreamCheck.plays(client, url, ua)
        if (ok) StreamSource(url, mapOf("User-Agent" to ua), via = "NewPipe") else null
    }

    /** NewPipeExtractor brings no networking of its own; this is newpipe's own DownloaderImpl, on okhttp */
    private class Okhttp(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val builder = OkRequest.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody(null))
                .url(request.url())
                .addHeader("User-Agent", UA)
            for ((name, values) in request.headers()) {
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            client.newCall(builder.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string() ?: "", r.request.url.toString())
            }
        }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
