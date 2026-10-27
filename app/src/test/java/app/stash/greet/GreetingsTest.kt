package app.stash.greet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

class GreetingsTest {
    @Test
    fun nightTimeKeepsItQuiet() {
        val lines = (0 until 30).map { Greetings.build(2, Calendar.TUESDAY, null, null, Random(it)).message }.toSet()
        assertTrue("Don't wake up the neighborhood!" in lines)
    }

    @Test
    fun trainOffersTheTopArtist() {
        val g = Greetings.build(15, Calendar.MONDAY, Place.TRAIN, "Overtonight", Random(1))
        assertTrue(g.message!!.contains("Overtonight"))
        assertEquals(GreetingAction.PlayArtist("Overtonight"), g.action)
    }

    @Test
    fun airportPointsAtDownloads() {
        val g = Greetings.build(10, Calendar.MONDAY, Place.AIRPORT, null, Random(3))
        assertEquals(GreetingAction.OpenDownloads, g.action)
    }

    @Test
    fun classifiesPlaces() {
        assertEquals(Place.AIRPORT, PlaceDetector.classify(listOf(mapOf("aeroway" to "aerodrome")), 0f))
        assertEquals(Place.TRAIN, PlaceDetector.classify(listOf(mapOf("railway" to "rail")), 25f))
        assertEquals(Place.STATION, PlaceDetector.classify(listOf(mapOf("railway" to "station")), 0f))
        assertEquals(Place.CAFE, PlaceDetector.classify(listOf(mapOf("amenity" to "cafe")), 0f))
        assertEquals(Place.DRIVING, PlaceDetector.classify(emptyList(), 20f))
        assertNull(PlaceDetector.classify(emptyList(), 0f))
    }
}
