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

/** OpenCelliD-Treffer, die weiter als ihre Reichweite, mindestens aber so weit entfernt liegen, gelten als unplausibel */
const val OCID_IMPLAUSIBLE_MIN_M = 5_000.0
/** Treffer mit weniger Messungen … */
const val OCID_FEW_SAMPLES = 3
/** … gelten ab diesem Abstand als unplausibel */
const val OCID_FEW_SAMPLES_MAX_M = 1_000.0

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
    // OpenCelliD zählt je Station: Schwerpunkt der plausiblen Zellen, nach Messungen gewichtet.
    val ocidDistance = station.ocidDistanceM
    return when {
        e != null && e.radiusM < RELIABLE_RADIUS_M ->
            Assessment(Reliability.RELIABLE, tr("Standort auf ±${m(e.radiusM)} geschätzt", "location estimated to ±${m(e.radiusM)}"))
        ocidDistance != null && ocidDistance <= OCID_MATCH_M ->
            Assessment(Reliability.RELIABLE, tr("OpenCelliD bestätigt (${m(ocidDistance)} Abstand)", "confirmed by OpenCelliD (${m(ocidDistance)} apart)"))
        // Nur ein eindeutiger Mast bestätigt: In der Stadt liegt fast immer zufällig einer in der Nähe.
        e != null && station.osmConfirmed ->
            Assessment(Reliability.RELIABLE, tr("OSM-Mast ${m(station.osmMastDistanceM!!)} entfernt, eindeutig", "OSM mast ${m(station.osmMastDistanceM!!)} away, unambiguous"))
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

/** OpenCelliD-Schwerpunkt einer Station für die Karte. */
data class OcidMarker(val latitude: Double, val longitude: Double, val plausible: Boolean, val cells: Int, val samples: Int)

/**
 * Ein Punkt je Station: der Schwerpunkt der plausiblen OpenCelliD-Zellen; gibt es keine,
 * der der unplausiblen (grau dargestellt). Null ohne Schätzung oder Treffer.
 */
fun ocidMarker(station: BaseStation): OcidMarker? {
    station.estimate ?: return null
    station.ocidPosition?.let { (lat, lon) ->
        return OcidMarker(lat, lon, true, station.ocidPlausible.size, station.ocidSamples)
    }
    val bad = station.ocidImplausible
    val (lat, lon) = station.ocidImplausiblePosition ?: return null
    return OcidMarker(lat, lon, false, bad.size, bad.sumOf { it.samples ?: 0 })
}

/** Abstand Schätzung–Marker groß genug für eine Verbindungslinie */
fun ocidLineWorthy(station: BaseStation, m: OcidMarker): Boolean {
    val e = station.estimate ?: return false
    return distanceM(e.latitude, e.longitude, m.latitude, m.longitude) >= OCID_LINE_MIN_M
}
