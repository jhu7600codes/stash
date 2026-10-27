package app.stash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stash.R
import app.stash.data.db.MixCover
import coil.compose.AsyncImage

/** spotify-ish mix colors: each mix keeps its own one */
private val mixColors = listOf(
    0xFFE8115B, 0xFF1E3264, 0xFF8D67AB, 0xFF148A08, 0xFFBA5D07, 0xFF477D95, 0xFFAF2896, 0xFF509BF5, 0xFFDC148C, 0xFF27856A,
).map { Color(it) }

fun mixAccent(mixId: String): Color = when {
    mixId.startsWith("daily-") -> mixColors[((mixId.removePrefix("daily-").toIntOrNull() ?: 1) - 1).mod(mixColors.size)]
    mixId == "discover" -> Color(0xFF1E3264)
    mixId == "rewind" -> Color(0xFFBA5D07)
    else -> mixColors[mixId.hashCode().mod(mixColors.size)]
}

/**
 * made-for-you covers, drawn instead of a picture:
 * - daily mix: the artist's photo with a colored band, "Daily Mix 2"
 * - artist mix: "This is" + the artist's name on a colored panel above their photo
 * - genre mix: the genre's big name over a top artist's photo, "For you by Stash"
 * - discover weekly / repeat rewind: a colored gradient with the name
 * text scales with the size, so it works from a 44dp list row up to the 240dp header.
 */
@Composable
fun MixArt(mix: MixCover, modifier: Modifier = Modifier) {
    val accent = mixAccent(mix.mixId)
    BoxWithConstraints(modifier.background(accent)) {
        val s = maxWidth
        val scale = LocalDensity.current.fontScale
        fun size(f: Float): TextUnit = (s.value * f / scale).sp
        val tiny = s < 72.dp
        when {
            mix.mixId.startsWith("artist-") -> {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxWidth().weight(0.42f).background(accent).padding(horizontal = s * 0.07f, vertical = s * 0.05f),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        if (!tiny) Text("This is", color = Color.White, fontSize = size(0.07f), fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(
                            mix.title.removeSuffix(" Mix"), color = Color.White, fontSize = size(if (tiny) 0.15f else 0.12f),
                            fontWeight = FontWeight.Black, maxLines = if (tiny) 1 else 2, overflow = TextOverflow.Ellipsis,
                            lineHeight = size(0.13f),
                        )
                    }
                    Box(Modifier.fillMaxWidth().weight(0.58f)) { Photo(mix.photo, accent) }
                }
            }
            mix.mixId.startsWith("daily-") -> {
                Photo(mix.photo, accent)
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(accent)
                        .padding(vertical = s * 0.045f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (tiny) mix.title.removePrefix("Daily Mix ").let { "Mix $it" } else mix.title,
                        color = Color.White, fontSize = size(if (tiny) 0.16f else 0.1f), fontWeight = FontWeight.Bold, maxLines = 1,
                    )
                }
            }
            mix.mixId.startsWith("genre-") -> {
                Photo(mix.photo, accent)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.95f)))))
                Column(Modifier.align(Alignment.BottomStart).padding(s * 0.07f)) {
                    Text(
                        mix.title, color = Color.White, fontSize = size(if (tiny) 0.17f else 0.14f), fontWeight = FontWeight.Black,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = size(0.15f),
                    )
                    if (!tiny) Text("For you by Stash", color = Color.White.copy(alpha = 0.85f), fontSize = size(0.06f), maxLines = 1)
                }
            }
            else -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent, Color.Black.copy(alpha = 0.6f).compositeOverSafe(accent)))))
                Text(
                    mix.title, color = Color.White, fontSize = size(if (tiny) 0.15f else 0.13f), fontWeight = FontWeight.Black,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = size(0.14f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(s * 0.07f),
                )
            }
        }
        // stash's mark in the corner, like spotify's logo on its covers
        if (!tiny) Icon(
            painterResource(R.drawable.ic_stat_stash), null, tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(s * 0.05f).size(cornerSize(s)),
        )
    }
}

private fun cornerSize(s: Dp) = s * 0.09f

private fun Color.compositeOverSafe(bg: Color) = androidx.compose.ui.graphics.Color(
    red = red * alpha + bg.red * (1 - alpha), green = green * alpha + bg.green * (1 - alpha), blue = blue * alpha + bg.blue * (1 - alpha),
)

@Composable
private fun Photo(url: String?, accent: Color) {
    if (url == null) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent, Color.Black.copy(alpha = 0.5f).compositeOverSafe(accent)))))
    } else AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}
