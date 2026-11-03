package de.funkstrecke.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private val DATE_TIME_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE_TIME)

fun formatDateTimeSeconds(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE_TIME_SECONDS)

fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME)

fun formatCoordinates(lat: Double, lon: Double): String =
    String.format(Locale.US, "%.5f, %.5f", lat, lon)

fun formatDistance(meters: Double): String =
    if (meters >= 1000) String.format(Locale.GERMANY, "%.1f km", meters / 1000) else "${meters.roundToInt()} m"

/** "vor 12 s", "vor 5 min", sonst Uhrzeit/Datum */
fun formatAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val s = (now - millis) / 1000
    return when {
        s < 5 -> "gerade eben"
        s < 60 -> "vor $s s"
        s < 3600 -> "vor ${s / 60} min"
        s < 86_400 -> "vor ${s / 3600} h"
        else -> formatDateTime(millis)
    }
}
