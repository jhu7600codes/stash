package app.stash.providers

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * an optional youtube login, only for getting song audio (search and catalogs stay
 * anonymous). like pipepipe and yt-dlp's --cookies-from-browser: no api, no oauth, just the
 * cookie jar youtube.com's own web login leaves in a webview, handed to yt-dlp as a cookies.txt.
 * it never leaves the phone except to youtube.
 */
object YouTubeLogin {
    fun cookieFile(context: Context) = File(context.filesDir, "youtube_cookies.txt")

    fun isLoggedIn(context: Context) = cookieFile(context).exists()

    /** true once the webview's cookies hold a signed-in youtube session */
    fun hasSession(cookieHeader: String?) =
        cookieHeader != null && ("SAPISID=" in cookieHeader || "__Secure-3PAPISID=" in cookieHeader)

    /** saves the webview's youtube cookies in the netscape cookies.txt format yt-dlp reads */
    fun save(context: Context, cookieHeader: String) {
        val expires = System.currentTimeMillis() / 1000 + 365L * 24 * 60 * 60
        val lines = cookieHeader.split(";").map { it.trim() }.filter { "=" in it }.map { pair ->
            val name = pair.substringBefore("=")
            val value = pair.substringAfter("=")
            listOf(".youtube.com", "TRUE", "/", "TRUE", expires.toString(), name, value).joinToString("\t")
        }
        cookieFile(context).writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
    }

    /**
     * pasted cookies in any of the shapes cookie exporters give: a header string
     * ("SID=…; SAPISID=…"), a netscape cookies.txt, or Cookie-Editor's json. returns them as a
     * header string, or null when there's no youtube session in it.
     */
    fun parsePasted(raw: String): String? {
        val text = raw.trim().removePrefix("Cookie:").trim()
        val pairs: List<Pair<String, String>> = when {
            text.startsWith("[") -> Regex(""""name"\s*:\s*"([^"]+)"[^}]*?"value"\s*:\s*"([^"]*)"""")
                .findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
                .ifEmpty {
                    // value before name in some exports
                    Regex(""""value"\s*:\s*"([^"]*)"[^}]*?"name"\s*:\s*"([^"]+)"""")
                        .findAll(text).map { it.groupValues[2] to it.groupValues[1] }.toList()
                }
            "\t" in text -> text.lines().filter { it.isNotBlank() && !it.startsWith("#") || it.startsWith("#HttpOnly_") }
                .map { it.removePrefix("#HttpOnly_").split('\t') }
                .filter { it.size >= 7 && "youtube" in it[0] }
                .map { it[5] to it[6] }
            else -> text.split(";").map { it.trim() }.filter { "=" in it }.map { it.substringBefore("=") to it.substringAfter("=") }
        }
        val header = pairs.distinctBy { it.first }.joinToString("; ") { "${it.first}=${it.second}" }
        return header.takeIf { hasSession(it) }
    }

    fun logOut(context: Context) {
        cookieFile(context).delete()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
