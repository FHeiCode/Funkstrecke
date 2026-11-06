package de.funkstrecke.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Signalblau als Hauptfarbe, Warnorange (wie die Tagesmarkierung eines Masts) als Akzent.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1D5C7E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE6F4),
    onPrimaryContainer = Color(0xFF0A2E40),
    secondary = Color(0xFFA14B1C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0CF),
    onSecondaryContainer = Color(0xFF3A1404),
    background = Color(0xFFF2F5F7),
    onBackground = Color(0xFF151C21),
    surface = Color(0xFFF8FAFB),
    onSurface = Color(0xFF151C21),
    surfaceVariant = Color(0xFFDCE4E9),
    onSurfaceVariant = Color(0xFF42515B),
    outline = Color(0xFF72828D),
    outlineVariant = Color(0xFFC3CDD3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8DC6E6),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF0F4B69),
    onPrimaryContainer = Color(0xFFCDE6F4),
    secondary = Color(0xFFFFB68F),
    onSecondary = Color(0xFF552000),
    secondaryContainer = Color(0xFF7A3510),
    onSecondaryContainer = Color(0xFFFFE0CF),
    background = Color(0xFF0E1418),
    onBackground = Color(0xFFDEE4E8),
    surface = Color(0xFF12191E),
    onSurface = Color(0xFFDEE4E8),
    surfaceVariant = Color(0xFF3A4850),
    onSurfaceVariant = Color(0xFFBCC8CF),
    outline = Color(0xFF86959E),
    outlineVariant = Color(0xFF3A4850),
)

@Composable
fun FunkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/** Feste Farbe je Netzbetreiber (Karte, Chips). */
fun operatorColorRaw(operator: String?): Color = when (operator) {
    "Telekom" -> Color(0xFFD4006C)
    "Vodafone" -> Color(0xFFD71A1A)
    "Telefónica (O2)" -> Color(0xFF2A4BC9)
    "1&1" -> Color(0xFF00897B)
    else -> Color(0xFF6B7A85)
}

@Composable
fun operatorColor(operator: String?): Color =
    adaptive(operatorColorRaw(operator))

/** Farbe je Funktechnik. */
@Composable
fun ratColor(rat: String): Color = adaptive(
    when (rat) {
        "5G" -> Color(0xFF00796B)
        "4G" -> Color(0xFF1D5C7E)
        "3G" -> Color(0xFF6A4FB0)
        else -> Color(0xFF7A5C3E)
    },
)

/** Signalstufe 0–4 aus dem Pegel; Schwellen für 4G/5G (RSRP) bzw. 2G/3G. */
fun signalLevel(rat: String, dbm: Int?): Int {
    if (dbm == null) return 0
    val t = if (rat == "4G" || rat == "5G") intArrayOf(-80, -90, -100, -110) else intArrayOf(-70, -85, -95, -105)
    return when {
        dbm >= t[0] -> 4
        dbm >= t[1] -> 3
        dbm >= t[2] -> 2
        dbm >= t[3] -> 1
        else -> 0
    }
}

fun signalColorRaw(level: Int): Color = when (level) {
    4 -> Color(0xFF237A4B)
    3 -> Color(0xFF6E8F1E)
    2 -> Color(0xFFB07A00)
    1 -> Color(0xFFC4561C)
    else -> Color(0xFFB3261E)
}

@Composable
fun signalColor(level: Int): Color = adaptive(signalColorRaw(level))

@Composable
private fun adaptive(base: Color): Color =
    if (isSystemInDarkTheme()) lerp(base, Color.White, 0.38f) else base

/** Farbe der Bewertung: zuverlässig grün, Kandidat bernstein, mögliches Artefakt grau. */
fun reliabilityColorRaw(r: de.funkstrecke.data.Reliability): Color = when (r) {
    de.funkstrecke.data.Reliability.RELIABLE -> Color(0xFF237A4B)
    de.funkstrecke.data.Reliability.CANDIDATE -> Color(0xFFB07A00)
    de.funkstrecke.data.Reliability.ARTEFACT -> Color(0xFF7A8690)
}

@Composable
fun reliabilityColor(r: de.funkstrecke.data.Reliability): Color = adaptive(reliabilityColorRaw(r))
