package app.stash.greet

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.stash.providers.asObjectList
import app.stash.providers.httpIo
import app.stash.providers.lenientJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

enum class Place {
    AIRPORT, TRAIN, STATION, BUS_STATION, DRIVING, WALKING, CAFE, RESTAURANT, BAR, GYM, PARK, BEACH,
    LIBRARY, SCHOOL, UNIVERSITY, MALL, HOSPITAL, CINEMA, STADIUM,
}

/**
 * where you roughly are, for the home greeting: one location fix, then openstreetmap's
 * overpass api says what's around (airport, cafe, station…). opt-in, cached for 10 minutes.
 */
@Singleton
class PlaceDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    http: OkHttpClient,
) {
    private val http = http.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()
    private var cached: Pair<Long, Place?>? = null

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun detect(): Place? {
        cached?.let { (at, place) -> if (System.currentTimeMillis() - at < CACHE_MS) return place }
        if (!hasPermission()) return null
        val loc = currentLocation() ?: return null
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        val tags = runCatching { nearby(loc.latitude, loc.longitude) }.getOrNull()
        val place = classify(tags.orEmpty(), speed)
        cached = System.currentTimeMillis() to place
        return place
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return null
        }
        val fresh = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { cont ->
                val signal = androidx.core.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) {
                    if (cont.isActive) cont.resume(it)
                }
            }
        }
        return fresh ?: runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }

    /** tags of everything relevant around the point (areas you're inside + things very close by) */
    private suspend fun nearby(lat: Double, lon: Double): List<Map<String, String>> {
        val q = """
            [out:json][timeout:8];
            is_in($lat,$lon)->.a;
            (area.a[aeroway=aerodrome];area.a[leisure~"park|stadium|beach_resort"];area.a[natural=beach];
             area.a[amenity~"university|school|college|hospital"];area.a[shop=mall];area.a[railway=station];);
            out tags 10;
            (nwr(around:400,$lat,$lon)[aeroway=terminal];
             nwr(around:60,$lat,$lon)[amenity~"cafe|restaurant|fast_food|bar|pub|biergarten|library|cinema"];
             nwr(around:150,$lat,$lon)[railway~"station|halt"];
             nwr(around:150,$lat,$lon)[public_transport=station];
             nwr(around:80,$lat,$lon)[amenity=bus_station];
             nwr(around:60,$lat,$lon)[leisure~"fitness_centre|sports_centre"];
             way(around:40,$lat,$lon)[railway~"rail|subway|light_rail"];);
            out tags 20;
        """.trimIndent()
        var lastError: Exception? = null
        for (endpoint in ENDPOINTS) {
            try {
                val text = httpIo {
                    http.newCall(Request.Builder().url(endpoint).post(FormBody.Builder().add("data", q).build()).build())
                        .execute().use { r -> if (r.isSuccessful) r.body!!.string() else error("overpass ${r.code}") }
                }
                return lenientJson.parseToJsonElement(text).jsonObject["elements"].asObjectList().map { el ->
                    (el["tags"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" } ?: emptyMap()
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("overpass unreachable")
    }

    companion object {
        private const val CACHE_MS = 10 * 60 * 1000L
        private val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

        /** picks the most telling place; moving fast beats everything except an airport */
        fun classify(tags: List<Map<String, String>>, speedMs: Float): Place? {
            fun has(key: String, vararg values: String) = tags.any { t -> t[key]?.let { v -> values.isEmpty() || v in values } == true }
            val nearRail = has("railway", "rail", "subway", "light_rail")
            return when {
                has("aeroway", "aerodrome", "terminal") -> Place.AIRPORT
                speedMs > 12 && (nearRail || has("railway", "station", "halt")) -> Place.TRAIN
                speedMs > 8 -> Place.DRIVING
                has("railway", "station", "halt") || has("public_transport", "station") -> Place.STATION
                has("amenity", "bus_station") -> Place.BUS_STATION
                has("amenity", "hospital") -> Place.HOSPITAL
                has("amenity", "cinema") -> Place.CINEMA
                has("amenity", "library") -> Place.LIBRARY
                has("amenity", "cafe") -> Place.CAFE
                has("amenity", "bar", "pub", "biergarten") -> Place.BAR
                has("amenity", "restaurant", "fast_food") -> Place.RESTAURANT
                has("leisure", "fitness_centre", "sports_centre") -> Place.GYM
                has("leisure", "stadium") -> Place.STADIUM
                has("shop", "mall") -> Place.MALL
                has("amenity", "university", "college") -> Place.UNIVERSITY
                has("amenity", "school") -> Place.SCHOOL
                has("natural", "beach") || has("leisure", "beach_resort") -> Place.BEACH
                has("leisure", "park") -> Place.PARK
                speedMs in 1f..2.5f -> Place.WALKING
                else -> null
            }
        }
    }
}
