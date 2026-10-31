package de.funkstrecke.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Signalblau als Hauptfarbe, Warnorange (wie Mast-Tagesmarkierung) als Akzent.
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

/** Feste Statusfarben, im Dunkelmodus aufgehellt. */
@Composable
fun statusColor(status: String): Color {
    val base = when (status) {
        "Planung" -> Color(0xFF5F7383)
        "Genehmigung" -> Color(0xFF9C6B00)
        "Im Bau" -> Color(0xFFB8541A)
        "In Betrieb" -> Color(0xFF237A4B)
        "Wartung" -> Color(0xFF6A4FB0)
        "Außer Betrieb" -> Color(0xFFB3261E)
        else -> Color(0xFF5F7383)
    }
    return if (isSystemInDarkTheme()) lerp(base, Color.White, 0.4f) else base
}
