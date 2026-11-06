package de.funkstrecke.data

import kotlin.math.roundToInt

/*
 * Bewertung erkannter Zellen:
 *  - zuverlässig: eigene Standortschätzung genauer als 200 m (95-%-Radius)
 *                 oder höchstens 200 m von der OpenCelliD-Position derselben Zelle entfernt
 *  - Kandidat:    eigene Standortschätzung genauer als 1 km
 *  - mögliches Artefakt: alles Übrige (kein oder ein sehr unsicherer Standort)
 * Eine Station übernimmt die beste Bewertung ihrer Zellen.
 */

const val RELIABLE_RADIUS_M = 200.0
const val OCID_MATCH_M = 200.0
const val CANDIDATE_RADIUS_M = 1_000.0

enum class Reliability(val label: String, val short: String) {
    RELIABLE("zuverlässig", "zuverl."),
    CANDIDATE("Kandidat", "Kand."),
    ARTEFACT("mögliches Artefakt", "Artef."),
}

data class Assessment(val reliability: Reliability, val reason: String)

private fun m(v: Double) = if (v >= 1000) String.format(java.util.Locale.GERMANY, "%.1f km", v / 1000) else "${v.roundToInt()} m"

/** Bewertung einer Zelle anhand der Schätzung ihrer Station und ihres OpenCelliD-Eintrags. */
fun assessCell(station: BaseStation, cell: CellEntity): Assessment {
    val e = station.estimate
    val o = station.ocid.firstOrNull { it.cellKey == cell.id && it.found && it.latitude != null && it.longitude != null }
    val ocidDistance = if (e != null && o != null) distanceM(e.latitude, e.longitude, o.latitude!!, o.longitude!!) else null
    return when {
        e != null && e.radiusM < RELIABLE_RADIUS_M ->
            Assessment(Reliability.RELIABLE, "Standort auf ±${m(e.radiusM)} geschätzt")
        ocidDistance != null && ocidDistance <= OCID_MATCH_M ->
            Assessment(Reliability.RELIABLE, "OpenCelliD bestätigt (${m(ocidDistance)} Abstand)")
        e != null && e.radiusM < CANDIDATE_RADIUS_M ->
            Assessment(Reliability.CANDIDATE, "Standort auf ±${m(e.radiusM)} geschätzt")
        e != null ->
            Assessment(Reliability.ARTEFACT, "zu ungenau (±${m(e.radiusM)})")
        else ->
            Assessment(Reliability.ARTEFACT, "kein Standort geschätzt")
    }
}

/** Bewertung einer Station: die beste ihrer Zellen. */
fun assessStation(station: BaseStation): Assessment =
    station.cells.map { assessCell(station, it) }.minByOrNull { it.reliability.ordinal }
        ?: Assessment(Reliability.ARTEFACT, "keine Zellen")
