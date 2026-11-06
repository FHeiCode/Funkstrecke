package de.funkstrecke.data

import de.funkstrecke.radio.Operators
import de.funkstrecke.ui.theme.signalLevel
import de.funkstrecke.util.tr

/*
 * Abdeckungskarte: Messpunkte mit genauer Position werden auf ein Raster von etwa 50 × 50 m
 * zusammengefasst, getrennt nach Betreiber (MCC/MNC) und Funktechnik. Je Feld zählt das beste
 * gemessene Signal aller Zellen dieses Betreibers (verbundene und Nachbarzellen), also das,
 * was ein Handy dort mindestens empfangen kann.
 */

/** Rastergröße in Grad: 0,00045° Breite ≈ 50 m, 0,0007° Länge ≈ 50 m bei 50° N. */
const val COVERAGE_LAT_STEP = 0.00045
const val COVERAGE_LON_STEP = 0.0007

/** Ein Rasterfeld je Betreiber und Technik, so wie die Datenbank es liefert. */
data class CoverageTile(
    val mcc: String?,
    val mnc: String?,
    val rat: String,
    val latIdx: Int,
    val lonIdx: Int,
    val bestDbm: Int,
    val avgDbm: Double,
    val n: Int,
    val lastTime: Long,
) {
    val operatorName: String get() = Operators.name(mcc, mnc) ?: if (mcc != null) "$mcc-$mnc" else tr("Unbekannt", "Unknown")
}

/** Ein Feld auf der Karte, für einen Betreiber zusammengefasst. */
data class CoverageCell(
    val latIdx: Int,
    val lonIdx: Int,
    /** Signalstufe 0–4 (wie die Balken) */
    val level: Int,
    val bestDbm: Int,
    /** Technik mit dem besten Signal in diesem Feld */
    val rat: String,
    val n: Int,
) {
    val south: Double get() = latIdx * COVERAGE_LAT_STEP
    val north: Double get() = (latIdx + 1) * COVERAGE_LAT_STEP
    val west: Double get() = lonIdx * COVERAGE_LON_STEP
    val east: Double get() = (lonIdx + 1) * COVERAGE_LON_STEP
}

/**
 * Felder für einen Betreiber. [ratFilter] = null nimmt je Feld die beste Technik
 * (verglichen über die Signalstufe, weil RSRP und RSSI nicht direkt vergleichbar sind).
 */
fun coverageFor(tiles: List<CoverageTile>, operator: String, ratFilter: String?): List<CoverageCell> =
    tiles.asSequence()
        .filter { it.operatorName == operator && (ratFilter == null || it.rat == ratFilter) }
        .groupBy { it.latIdx to it.lonIdx }
        .map { (key, list) ->
            val best = list.maxWith(compareBy<CoverageTile> { signalLevel(it.rat, it.bestDbm) }.thenBy { ratRank(it.rat) }.thenBy { it.bestDbm })
            CoverageCell(
                latIdx = key.first,
                lonIdx = key.second,
                level = signalLevel(best.rat, best.bestDbm),
                bestDbm = best.bestDbm,
                rat = best.rat,
                n = list.sumOf { it.n },
            )
        }

private fun ratRank(rat: String) = when (rat) {
    "5G" -> 4
    "4G" -> 3
    "3G" -> 2
    else -> 1
}

/** Ein Rasterfeld der 5G-NSA-Ansicht: bester EN-DC-Zustand (Rang von EndcMode) je Betreiber. */
data class EndcTile(
    val mcc: String?,
    val mnc: String?,
    val latIdx: Int,
    val lonIdx: Int,
    val endcRank: Int,
    val n: Int,
) {
    val operatorName: String get() = Operators.name(mcc, mnc) ?: if (mcc != null) "$mcc-$mnc" else tr("Unbekannt", "Unknown")
    val south: Double get() = latIdx * COVERAGE_LAT_STEP
    val north: Double get() = (latIdx + 1) * COVERAGE_LAT_STEP
    val west: Double get() = lonIdx * COVERAGE_LON_STEP
    val east: Double get() = (lonIdx + 1) * COVERAGE_LON_STEP
}
