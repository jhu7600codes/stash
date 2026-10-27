package app.stash.cloud

import app.stash.BuildConfig
import app.stash.providers.ProviderException
import app.stash.providers.httpIo
import app.stash.providers.lenientJson
import app.stash.providers.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * logging a tv / tablet in with a code instead of typing a password with a remote. the tv asks
 * the `pair` edge function for a code and shows it; a logged-in phone sends the code back
 * (Create → Pair a device); the tv then picks up a one-time login token for that account and
 * swaps it for its own session. the phone's own login never leaves the phone.
 */
@Singleton
class Pairing @Inject constructor(
    private val http: OkHttpClient,
    private val account: Account,
) {
    data class Code(val code: String, val deviceKey: String, val expiresInSec: Long)

    sealed interface Poll {
        data object Waiting : Poll
        data object Expired : Poll
        /** logged in as [email] */
        data class Done(val email: String) : Poll
    }

    /** the tv's side: a fresh code to show */
    suspend fun start(deviceName: String): Code {
        val o = call(buildJsonObject { put("action", "start"); put("name", deviceName) })
        return Code(
            o.str("code") ?: throw ProviderException("Couldn't get a code"),
            o.str("device_key") ?: throw ProviderException("Couldn't get a code"),
            o.str("expires_in")?.toDoubleOrNull()?.toLong() ?: 600,
        )
    }

    /** the tv's side: has a phone entered the code yet? logs in when it has */
    suspend fun poll(code: Code): Poll {
        val o = call(buildJsonObject { put("action", "poll"); put("code", code.code); put("device_key", code.deviceKey) })
        if (o["expired"] != null) return Poll.Expired
        val hash = o.str("token_hash") ?: return Poll.Waiting
        val email = o.str("email").orEmpty()
        account.signInWithTokenHash(hash, email)
        return Poll.Done(email)
    }

    /** the phone's side: hands this account to the device showing [code]; returns its name */
    suspend fun claim(code: String): String {
        val token = account.accessToken() ?: throw ProviderException("Log in first (Settings → Account)")
        val o = call(buildJsonObject { put("action", "claim"); put("code", code) }, token)
        o.str("error")?.let { throw ProviderException(it) }
        return o.str("device_name") ?: "your device"
    }

    private suspend fun call(body: JsonObject, token: String? = null): JsonObject = httpIo {
        val req = Request.Builder()
            .url(BuildConfig.SUPABASE_URL + "/functions/v1/pair")
            .header("apikey", BuildConfig.SUPABASE_KEY)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { lenientJson.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!r.isSuccessful) throw ProviderException(o?.str("error") ?: "Pairing failed (${r.code})")
            o ?: throw ProviderException("Pairing failed")
        }
    }
}
