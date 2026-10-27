package app.stash.greet

import java.util.Calendar
import kotlin.random.Random

sealed interface GreetingAction {
    val label: String

    data class PlayArtist(val artist: String) : GreetingAction { override val label = "Play $artist" }
    data object OpenDownloads : GreetingAction { override val label = "downloads" }
    data object ShuffleLiked : GreetingAction { override val label = "Shuffle liked songs" }
    data object ShuffleDownloads : GreetingAction { override val label = "Shuffle offline songs" }
}

/** [title] goes in the big top bar, [message] in the card under it */
data class Greeting(val title: String, val message: String?, val action: GreetingAction? = null)

/**
 * home screen greetings: the title follows the time of day, the line under it follows where
 * you are (when location greetings are on), and both pick from a few variants so it isn't
 * the same sentence every time.
 */
object Greetings {

    fun build(
        hour: Int,
        dayOfWeek: Int,
        place: Place?,
        topArtist: String?,
        random: Random = Random.Default,
    ): Greeting {
        val title = random.pick(timeTitles(hour, dayOfWeek))
        val placed = place?.let { placeLine(it, topArtist, random) }
        if (placed != null) return Greeting(title, placed.first, placed.second)
        return Greeting(title, timeLines(hour, dayOfWeek).let { random.pick(it) })
    }

    fun now(place: Place?, topArtist: String?, random: Random = Random.Default): Greeting {
        val c = Calendar.getInstance()
        return build(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.DAY_OF_WEEK), place, topArtist, random)
    }

    private fun <T> Random.pick(list: List<T>) = list[nextInt(list.size)]

    private fun weekend(day: Int) = day == Calendar.SATURDAY || day == Calendar.SUNDAY

    private fun timeTitles(hour: Int, day: Int): List<String> = when (hour) {
        in 0..5 -> listOf("Late night listening", "Still up?", "Night owl mode")
        in 6..8 -> if (weekend(day)) listOf("Lazy weekend morning", "Good morning") else listOf("Rise and shine", "Good morning")
        in 9..11 -> listOf("Good morning", "Morning!")
        in 12..13 -> listOf("Good afternoon", "Lunch time")
        in 14..17 -> listOf("Good afternoon", "Hey there")
        in 18..21 -> if (day == Calendar.FRIDAY) listOf("It's Friday", "Good evening") else listOf("Good evening", "Evening!")
        else -> listOf("Good night", "Late night listening")
    }

    private fun timeLines(hour: Int, day: Int): List<String> = when (hour) {
        in 0..5 -> listOf(
            "Don't wake up the neighborhood!",
            "Keep it quiet, everyone's asleep",
            "3am thoughts need a soundtrack",
        )
        in 6..8 -> if (weekend(day)) listOf("No alarm today. Take it slow", "Pancakes and a playlist?")
        else listOf("Something to wake up to?", "Coffee first, then music. Or both", "Let's make the commute better")
        in 9..11 -> listOf("Let's get the day going", "Focus mode, headphones on", "A good song fixes a slow morning")
        in 12..13 -> listOf("Lunch break beats?", "Halfway through the day, treat yourself")
        in 14..17 -> listOf("Afternoon slump? Fix it with a song", "Almost done for today", "One more song, then back to work")
        in 18..21 -> if (day == Calendar.FRIDAY) listOf("Weekend starts now, turn it up", "Friday night, you know what to do")
        else listOf("The day's done, press play", "Evening vibes loading", "Dinner needs a soundtrack")
        else -> listOf("Winding down?", "One more song before bed", "Lights low, volume lower")
    }

    private fun placeLine(place: Place, artist: String?, random: Random): Pair<String, GreetingAction?>? = when (place) {
        Place.AIRPORT -> random.pick(
            listOf(
                "Pick a song to fly and don't forget to download it!",
                "Flight mode soon. Download a playlist first!",
                "Boarding soon? Make sure your music works offline",
            )
        ) to GreetingAction.OpenDownloads
        Place.CAFE -> random.pick(
            listOf(
                "Pick a song for the coffee to vibe to :)",
                "Coffee and a playlist, name a better duo",
                "A cafe without music is just a room with coffee",
            )
        ) to null
        Place.STATION, Place.TRAIN -> if (artist != null) {
            random.pick(
                listOf(
                    "Welcome aboard. Should we start with some $artist?",
                    "Next stop: $artist?",
                    "Window seat and $artist, perfect",
                )
            ) to GreetingAction.PlayArtist(artist)
        } else {
            random.pick(listOf("Welcome aboard. Pick something for the ride", "Next stop: your playlist")) to null
        }
        Place.BUS_STATION -> random.pick(listOf("Waiting for the bus? Headphones in", "Bus ride soundtrack, coming up")) to null
        Place.DRIVING -> random.pick(
            listOf("Eyes on the road, we've got the music", "Road trip mode", "Windows down, volume up")
        ) to GreetingAction.ShuffleDownloads
        Place.WALKING -> random.pick(listOf("Walking soundtrack, coming up", "Every walk needs a playlist")) to GreetingAction.ShuffleLiked
        Place.GYM -> random.pick(listOf("Time to lift. Something loud?", "One more rep, one more song", "Gym mode on")) to GreetingAction.ShuffleLiked
        Place.PARK -> random.pick(listOf("Nice spot for a walk with music", "Fresh air and a good song")) to null
        Place.BEACH -> random.pick(listOf("Waves and a playlist", "Sun's out, music's on")) to null
        Place.LIBRARY -> random.pick(listOf("It's a library, headphones only!", "Quiet zone. Keep it in your ears")) to null
        Place.SCHOOL -> random.pick(listOf("After class? Or during... we won't tell", "Survived school today? You've earned a song")) to null
        Place.UNIVERSITY -> random.pick(listOf("Lecture over? Press play", "Study playlist time")) to null
        Place.MALL -> random.pick(listOf("Shopping soundtrack loaded", "The mall music is bad, use yours")) to null
        Place.RESTAURANT -> random.pick(listOf("Enjoy the food, we'll handle the music", "Good food deserves a good song")) to null
        Place.BAR -> random.pick(listOf("Turn it up a little", "Tonight's soundtrack is ready")) to null
        Place.HOSPITAL -> random.pick(listOf("Hope everything's OK. Something calm?", "Take it easy, here's something soft")) to null
        Place.CINEMA -> random.pick(listOf("Movie's about to start, pause the music?", "After the movie, the soundtrack")) to null
        Place.STADIUM -> random.pick(listOf("Game day energy", "Loud crowd, louder playlist")) to null
    }
}
