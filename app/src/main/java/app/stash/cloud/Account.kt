package app.stash.cloud

import android.net.Uri
import app.stash.BuildConfig
import app.stash.data.prefs.Settings
import app.stash.providers.ProviderException
import app.stash.providers.httpIo
import app.stash.providers.lenientJson
import app.stash.providers.num
import app.stash.providers.obj
import app.stash.providers.str
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** stash accounts: email + password through supabase auth's rest api */
@Singleton
class Account @Inject constructor(
    private val http: OkHttpClient,
    private val settings: Settings,
) {
    private val refreshLock = Mutex()

    sealed interface SignUpResult {
        data object SignedIn : SignUpResult
        /** supabase wants the email confirmed first */
        data object CheckEmail : SignUpResult
    }

    /**
     * a signup waiting for its email to be confirmed. only kept in memory (never on disk) so
     * coming back to the app after tapping the email link can log straight in.
     */
    private var pending: Pair<String, String>? = null
    val hasPendingSignup get() = pending != null

    suspend fun signUp(email: String, password: String): SignUpResult {
        // the confirm link sends you back into the app (stash://auth-callback) when that's an
        // allowed redirect in the supabase project, see handleRedirect
        val o = post(
            "/auth/v1/signup?redirect_to=${Uri.encode(REDIRECT)}",
            buildJsonObject { put("email", email.trim()); put("password", password) }.toString(),
        )
        // with email confirmation on, signup returns the user but no session
        if (o.str("access_token") == null) {
            pending = email to password
            return SignUpResult.CheckEmail
        }
        save(o, email)
        return SignUpResult.SignedIn
    }

    /** true once the pending signup's email got confirmed and we're logged in */
    suspend fun completePendingSignup(): Boolean {
        val (email, password) = pending ?: return false
        return try {
            signIn(email, password)
            pending = null
            true
        } catch (e: ProviderException) {
            false // still not confirmed
        }
    }

    /** stash://auth-callback#access_token=…&refresh_token=… from the confirm email */
    suspend fun handleRedirect(uri: Uri) {
        val params = (uri.fragment ?: uri.query ?: "").split('&').mapNotNull {
            val i = it.indexOf('='); if (i > 0) it.substring(0, i) to Uri.decode(it.substring(i + 1)) else null
        }.toMap()
        params["error_description"]?.let { throw ProviderException(it.replace('+', ' ')) }
        val access = params["access_token"] ?: throw ProviderException("That link didn't have a login in it")
        val refresh = params["refresh_token"] ?: throw ProviderException("That link didn't have a login in it")
        val expiresIn = params["expires_in"]?.toLongOrNull() ?: 3600
        val email = httpIo {
            http.newCall(
                Request.Builder().url(BuildConfig.SUPABASE_URL + "/auth/v1/user")
                    .header("apikey", BuildConfig.SUPABASE_KEY).header("Authorization", "Bearer $access").build()
            ).execute().use { r -> lenientJson.parseToJsonElement(r.body!!.string()).jsonObject.str("email") }
        } ?: pending?.first ?: ""
        settings.saveSession(Settings.Session(email, access, refresh, System.currentTimeMillis() + (expiresIn - 60) * 1000))
        pending = null
    }

    suspend fun signIn(email: String, password: String) {
        val o = post("/auth/v1/token?grant_type=password", buildJsonObject { put("email", email.trim()); put("password", password) }.toString())
        save(o, email)
    }

    /** a one-time login from pairing (see Pairing): swapped for this device's own session */
    suspend fun signInWithTokenHash(tokenHash: String, email: String) {
        val o = post("/auth/v1/verify", buildJsonObject { put("type", "magiclink"); put("token_hash", tokenHash) }.toString())
        save(o, email)
    }

    /** how many devices are logged into this account (phones, tvs…) */
    suspend fun sessionCount(): Int? {
        val token = accessToken() ?: return null
        return httpIo {
            val req = Request.Builder().url(BuildConfig.SUPABASE_URL + "/rest/v1/rpc/my_session_count")
                .header("apikey", BuildConfig.SUPABASE_KEY).header("Authorization", "Bearer $token")
                .post("{}".toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string()?.trim()?.toIntOrNull() else null }
        }
    }

    /** logs every other device out of this account (this one stays) */
    suspend fun logOutOthers() {
        val token = accessToken() ?: throw ProviderException("Not logged in")
        httpIo {
            val req = Request.Builder().url(BuildConfig.SUPABASE_URL + "/auth/v1/logout?scope=others")
                .header("apikey", BuildConfig.SUPABASE_KEY).header("Authorization", "Bearer $token")
                .post("".toRequestBody(null)).build()
            http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw ProviderException("Couldn't log the others out (${r.code})") }
        }
    }

    suspend fun signOut() = settings.clearSession()

    companion object {
        const val REDIRECT = "stash://auth-callback"
    }

    /** a valid access token, refreshed when it's close to expiring; null when logged out */
    suspend fun accessToken(): String? = refreshLock.withLock {
        val s = settings.session() ?: return null
        if (s.access.isNotEmpty() && System.currentTimeMillis() < s.expiresAt) return s.access
        val o = runCatching {
            post("/auth/v1/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", s.refresh) }.toString())
        }.getOrElse { e ->
            // a refresh token that's been revoked means logging in again
            if (e is ProviderException && e.message?.contains("invalid", true) == true) settings.clearSession()
            throw e
        }
        save(o, s.email)
        o.str("access_token")
    }

    private suspend fun save(o: JsonObject, email: String) {
        val access = o.str("access_token") ?: throw ProviderException("Login failed")
        val refresh = o.str("refresh_token") ?: throw ProviderException("Login failed")
        val expiresIn = (o.num("expires_in") ?: 3600.0).toLong()
        val userEmail = o.obj("user")?.str("email") ?: email.trim()
        settings.saveSession(Settings.Session(userEmail, access, refresh, System.currentTimeMillis() + (expiresIn - 60) * 1000))
    }

    private suspend fun post(path: String, json: String): JsonObject = httpIo {
        val req = Request.Builder()
            .url(BuildConfig.SUPABASE_URL + path)
            .header("apikey", BuildConfig.SUPABASE_KEY)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ProviderException(authError(body, r.code))
            lenientJson.parseToJsonElement(body).jsonObject
        }
    }

    private fun authError(body: String, code: Int): String {
        val o = runCatching { lenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
        val msg = o?.str("msg") ?: o?.str("error_description") ?: o?.str("message") ?: "Error $code"
        return when {
            msg.contains("Invalid login credentials", true) -> "Wrong email or password"
            msg.contains("Email not confirmed", true) -> "Confirm your email first (check your inbox)"
            msg.contains("already registered", true) -> "That email already has an account, log in instead"
            else -> msg
        }
    }
}
