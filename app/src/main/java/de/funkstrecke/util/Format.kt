package de.funkstrecke.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val DATE_TIME_DE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private val DATE_TIME_EN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val DATE_TIME_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(if (Lang.english) DATE_TIME_EN else DATE_TIME_DE)

fun formatDateTimeSeconds(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE_TIME_SECONDS)

fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME)

fun formatCoordinates(lat: Double, lon: Double): String =
    String.format(Locale.US, "%.5f, %.5f", lat, lon)

fun formatDistance(meters: Double): String =
    if (meters >= 1000) String.format(Lang.locale, "%.1f km", meters / 1000) else "${meters.roundToInt()} m"

/** "vor 12 s", "vor 5 min", sonst Uhrzeit/Datum */
fun formatAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val s = (now - millis) / 1000
    return when {
        s < 5 -> tr("gerade eben", "just now")
        s < 60 -> tr("vor $s s", "$s s ago")
        s < 3600 -> tr("vor ${s / 60} min", "${s / 60} min ago")
        s < 86_400 -> tr("vor ${s / 3600} h", "${s / 3600} h ago")
        else -> formatDateTime(millis)
    }
}
