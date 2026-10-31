package de.funkstrecke.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import java.util.Locale
import kotlin.math.roundToInt

/** Eine erfasste Basisstation, so wie sie in der Datenbank liegt. */
@Entity(tableName = "stations")
data class Station(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val siteId: String,
    val operator: String,
    val status: String,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    /** Komma-getrennt, z. B. "4G,5G" */
    val technologies: String,
    /** Komma-getrennt in MHz, z. B. "800,1800,3600" */
    val bands: String,
    val mcc: String,
    val mnc: String,
    val tac: String,
    val enbId: String,
    val cellIds: String,
    val pcis: String,
    val sectors: Int?,
    val azimuths: String,
    val mastType: String,
    val heightTotal: Double?,
    val heightAntenna: Double?,
    val bnetzaNr: String,
    /** Datumswerte als UTC-Millisekunden (Mitternacht), wie der DatePicker sie liefert */
    val commissioning: Long?,
    val lastVisit: Long?,
    val nextMaintenance: Long?,
    val notes: String,
    /** Dateinamen im App-Ordner "photos", mit '|' getrennt */
    val photos: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Bearbeitungszustand des Formulars: alles als Text, damit Teil-Eingaben wie "49." erhalten bleiben. */
@Parcelize
data class StationForm(
    val id: Long = 0,
    val name: String = "",
    val siteId: String = "",
    val operator: String = "",
    val status: String = Catalog.statuses.first(),
    val address: String = "",
    val lat: String = "",
    val lon: String = "",
    val accuracy: String = "",
    val technologies: List<String> = emptyList(),
    val bands: List<String> = emptyList(),
    val mcc: String = "",
    val mnc: String = "",
    val tac: String = "",
    val enbId: String = "",
    val cellIds: String = "",
    val pcis: String = "",
    val sectors: String = "",
    val azimuths: String = "",
    val mastType: String = "",
    val heightTotal: String = "",
    val heightAntenna: String = "",
    val bnetzaNr: String = "",
    val commissioning: Long? = null,
    val lastVisit: Long? = null,
    val nextMaintenance: Long? = null,
    val notes: String = "",
    val photos: List<String> = emptyList(),
    val createdAt: Long = 0,
) : Parcelable

object Catalog {
    val operators = listOf("Telekom", "Vodafone", "Telefónica (O2)", "1&1", "Sonstige")
    val statuses = listOf("Planung", "Genehmigung", "Im Bau", "In Betrieb", "Wartung", "Außer Betrieb")
    val technologies = listOf("2G", "4G", "5G")
    val bands = listOf("700", "800", "900", "1500", "1800", "2100", "2600", "3600")
    val mastTypes = listOf(
        "Gittermast", "Rohrmast / Monopole", "Dachstandort", "Schornstein",
        "Kirchturm", "Fassade", "Sonstiges",
    )
}

fun String.splitList(separator: Char = ','): List<String> =
    split(separator).map { it.trim() }.filter { it.isNotEmpty() }

fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

fun Station.toForm() = StationForm(
    id = id,
    name = name,
    siteId = siteId,
    operator = operator,
    status = status,
    address = address,
    lat = latitude?.let(::formatCoordinate) ?: "",
    lon = longitude?.let(::formatCoordinate) ?: "",
    accuracy = accuracyM?.roundToInt()?.toString() ?: "",
    technologies = technologies.splitList(),
    bands = bands.splitList(),
    mcc = mcc,
    mnc = mnc,
    tac = tac,
    enbId = enbId,
    cellIds = cellIds,
    pcis = pcis,
    sectors = sectors?.toString() ?: "",
    azimuths = azimuths,
    mastType = mastType,
    heightTotal = heightTotal?.let(::formatNumber) ?: "",
    heightAntenna = heightAntenna?.let(::formatNumber) ?: "",
    bnetzaNr = bnetzaNr,
    commissioning = commissioning,
    lastVisit = lastVisit,
    nextMaintenance = nextMaintenance,
    notes = notes,
    photos = photos.splitList('|'),
    createdAt = createdAt,
)

fun StationForm.toStation(now: Long) = Station(
    id = id,
    name = name.trim(),
    siteId = siteId.trim(),
    operator = operator,
    status = status,
    address = address.trim(),
    latitude = parseDecimal(lat),
    longitude = parseDecimal(lon),
    accuracyM = accuracy.toFloatOrNull(),
    technologies = Catalog.technologies.filter { it in technologies }.joinToString(","),
    bands = bands.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.joinToString(","),
    mcc = mcc.trim(),
    mnc = mnc.trim(),
    tac = tac.trim(),
    enbId = enbId.trim(),
    cellIds = cellIds.trim(),
    pcis = pcis.trim(),
    sectors = sectors.trim().toIntOrNull(),
    azimuths = azimuths.trim(),
    mastType = mastType,
    heightTotal = parseDecimal(heightTotal),
    heightAntenna = parseDecimal(heightAntenna),
    bnetzaNr = bnetzaNr.trim(),
    commissioning = commissioning,
    lastVisit = lastVisit,
    nextMaintenance = nextMaintenance,
    notes = notes.trim(),
    photos = photos.joinToString("|"),
    createdAt = if (createdAt == 0L) now else createdAt,
    updatedAt = now,
)

private val COORDINATE_PAIR =
    Regex("""^\s*(-?\d{1,3}[.,]\d+)\s*[,;\s]\s*(-?\d{1,3}[.,]\d+)\s*$""")

/** Erkennt eingefügte Koordinatenpaare wie "49.4521, 11.0767" und verteilt sie auf beide Felder. */
fun StationForm.withCoordinateInput(input: String, isLatitude: Boolean): StationForm {
    val pair = COORDINATE_PAIR.matchEntire(input)
    if (pair != null) {
        return copy(
            lat = pair.groupValues[1].replace(',', '.'),
            lon = pair.groupValues[2].replace(',', '.'),
            accuracy = "",
        )
    }
    return if (isLatitude) copy(lat = input, accuracy = "") else copy(lon = input, accuracy = "")
}

fun List<String>.toggle(value: String): List<String> =
    if (value in this) this - value else this + value

/** Hängt einen Wert an eine komma-getrennte Liste an, ohne Dubletten. */
fun addToken(list: String, value: Any?): String {
    if (value == null) return list
    return (list.splitList() + value.toString()).distinct().joinToString(", ")
}
