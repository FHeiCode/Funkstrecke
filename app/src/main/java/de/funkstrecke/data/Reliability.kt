package de.funkstrecke.data

import de.funkstrecke.util.formatDistance
import de.funkstrecke.util.tr

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

enum class Reliability {
    RELIABLE,
    CANDIDATE,
    ARTEFACT,
    ;

    val label: String
        get() = when (this) {
            RELIABLE -> tr("zuverlässig", "reliable")
            CANDIDATE -> tr("Kandidat", "candidate")
            ARTEFACT -> tr("mögliches Artefakt", "potential artefact")
        }

    val short: String
        get() = when (this) {
            RELIABLE -> tr("zuverl.", "reliable")
            CANDIDATE -> tr("Kand.", "cand.")
            ARTEFACT -> tr("Artef.", "artef.")
        }
}

data class Assessment(val reliability: Reliability, val reason: String)

private fun m(v: Double) = formatDistance(v)

/** Bewertung einer Zelle anhand der Schätzung ihrer Station und ihres OpenCelliD-Eintrags. */
fun assessCell(station: BaseStation, cell: CellEntity): Assessment {
    val e = station.estimate
    val o = station.ocid.firstOrNull { it.cellKey == cell.id && it.found && it.latitude != null && it.longitude != null }
    val ocidDistance = if (e != null && o != null) distanceM(e.latitude, e.longitude, o.latitude!!, o.longitude!!) else null
    return when {
        e != null && e.radiusM < RELIABLE_RADIUS_M ->
            Assessment(Reliability.RELIABLE, tr("Standort auf ±${m(e.radiusM)} geschätzt", "location estimated to ±${m(e.radiusM)}"))
        ocidDistance != null && ocidDistance <= OCID_MATCH_M ->
            Assessment(Reliability.RELIABLE, tr("OpenCelliD bestätigt (${m(ocidDistance)} Abstand)", "confirmed by OpenCelliD (${m(ocidDistance)} apart)"))
        e != null && e.radiusM < CANDIDATE_RADIUS_M ->
            Assessment(Reliability.CANDIDATE, tr("Standort auf ±${m(e.radiusM)} geschätzt", "location estimated to ±${m(e.radiusM)}"))
        e != null ->
            Assessment(Reliability.ARTEFACT, tr("zu ungenau (±${m(e.radiusM)})", "too imprecise (±${m(e.radiusM)})"))
        else ->
            Assessment(Reliability.ARTEFACT, tr("kein Standort geschätzt", "no location estimate"))
    }
}

/** Bewertung einer Station: die beste ihrer Zellen. */
fun assessStation(station: BaseStation): Assessment =
    station.cells.map { assessCell(station, it) }.minByOrNull { it.reliability.ordinal }
        ?: Assessment(Reliability.ARTEFACT, tr("keine Zellen", "no cells"))

/** Mindest- und Höchstabstand zu OpenCelliD, ab/bis zu dem statt des Kreises ein Sektor gezeigt wird. */
const val OCID_SECTOR_MIN_M = 30.0
const val OCID_SECTOR_MAX_M = 1_000.0

/** Kreissektor: Mittelrichtung (Grad, 0 = Nord, im Uhrzeigersinn) und halbe Öffnung (Grad). */
data class Sector(val bearingDeg: Double, val halfWidthDeg: Double)

/**
 * Sektor des eigenen Unsicherheitskreises in Richtung der OpenCelliD-Position.
 * OpenCelliD bestätigt die Zelle, liegt aber etwas daneben: Der Mast liegt dann eher auf
 * dieser Seite. Die Öffnung ist der Winkel, unter dem der Kreis von der OpenCelliD-Position
 * aus erscheint: weit, wenn sie nah an der Schätzung liegt (schwacher Hinweis), eng, wenn
 * sie weit weg liegt (30°–75° halbe Öffnung). Über 1 km Abstand ist eher OpenCelliD oder
 * die Schätzung falsch, dann bleibt es beim vollen Kreis.
 */
fun ocidSector(station: BaseStation): Sector? {
    val e = station.estimate ?: return null
    val (lat, lon) = station.ocidPosition ?: return null
    val d = distanceM(e.latitude, e.longitude, lat, lon)
    if (d < OCID_SECTOR_MIN_M || d > OCID_SECTOR_MAX_M) return null
    val half = Math.toDegrees(kotlin.math.atan2(e.radiusM, d)).coerceIn(30.0, 75.0)
    return Sector(bearingDeg(e.latitude, e.longitude, lat, lon), half)
}

internal fun bearingDeg(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dx = (bLon - aLon) * 111_320.0 * kotlin.math.cos(Math.toRadians((aLat + bLat) / 2))
    val dy = (bLat - aLat) * 110_540.0
    return (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0
}
