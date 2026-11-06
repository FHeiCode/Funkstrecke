package de.funkstrecke.data

import de.funkstrecke.util.formatDistance
import de.funkstrecke.util.tr

/*
 * Bewertung erkannter Zellen:
 *  - zuverlässig: eigene Standortschätzung genauer als 200 m (95-%-Radius),
 *                 höchstens 200 m von der OpenCelliD-Position derselben Zelle entfernt
 *                 oder höchstens 100 m von einem Funkmast in OpenStreetMap
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
        e != null && station.osmMastDistanceM != null && station.osmMastDistanceM <= OsmMasts.CONFIRM_M ->
            Assessment(Reliability.RELIABLE, tr("OSM-Mast ${m(station.osmMastDistanceM)} entfernt", "OSM mast ${m(station.osmMastDistanceM)} away"))
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

/** Unter diesem Abstand zur eigenen Schätzung wird keine Verbindungslinie zu OpenCelliD gezeichnet. */
const val OCID_LINE_MIN_M = 30.0

/** In OpenCelliD gefundene Zellen einer Station mit Position, je Zelle (für Verbindungslinien auf der Karte). */
fun ocidTargets(station: BaseStation): List<OcidEntity> {
    val e = station.estimate ?: return emptyList()
    return station.ocid.filter {
        it.found && it.latitude != null && it.longitude != null &&
            distanceM(e.latitude, e.longitude, it.latitude, it.longitude) >= OCID_LINE_MIN_M
    }
}
