package de.funkstrecke.data

import de.funkstrecke.radio.Operators
import kotlin.math.floor

/*
 * Tracking Areas (LTE/NR: TAC) bzw. Location Areas (2G/3G: LAC) aus den Messpunkten der
 * verbundenen Zelle. Jedes ~50-m-Feld bekommt die TAC, die dort am häufigsten gemessen wurde.
 * Grenzen ergeben sich, wo benachbarte Felder verschiedene TACs haben; zusätzlich werden die
 * Stellen markiert, an denen das Handy unterwegs die TAC gewechselt hat (Tracking Area Update).
 */

/** Ein Messpunkt der verbundenen Zelle mit ihrer TAC/LAC, wie die Datenbank ihn liefert. */
data class TaPoint(
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val area: Int,
    val rat: String,
    val mcc: String?,
    val mnc: String?,
)

/** Betreiber und Technikgruppe (TACs von LTE, NR und LACs von 2G/3G sind getrennte Nummernkreise). */
data class TaKey(val operator: String, val group: String)

fun taGroupOf(rat: String): String = when (rat) {
    "4G" -> "4G (TAC)"
    "5G" -> "5G SA (TAC)"
    else -> "2G/3G (LAC)"
}

data class TaTile(val latIdx: Int, val lonIdx: Int, val area: Int, val n: Int) {
    val south: Double get() = latIdx * COVERAGE_LAT_STEP
    val north: Double get() = (latIdx + 1) * COVERAGE_LAT_STEP
    val west: Double get() = lonIdx * COVERAGE_LON_STEP
    val east: Double get() = (lonIdx + 1) * COVERAGE_LON_STEP
}

/** Ein Wechsel der TAC auf der Route, mittig zwischen dem letzten Punkt vorher und dem ersten danach. */
data class TaCrossing(
    val latitude: Double,
    val longitude: Double,
    val from: Int,
    val to: Int,
    val time: Long,
    /** Abstand der beiden Punkte: so genau ist die Lage der Grenze bekannt */
    val gapM: Double,
)

/** Grenzlinie zwischen zwei benachbarten Feldern verschiedener TAC. */
data class TaEdge(val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double)

data class TrackingAreaMap(
    val key: TaKey,
    val tiles: List<TaTile>,
    val edges: List<TaEdge>,
    val crossings: List<TaCrossing>,
    /** TACs nach Anzahl der Felder, größte zuerst */
    val areas: List<Pair<Int, Int>>,
)

/** Höchstens so viel Zeit zwischen zwei Punkten, damit ein TAC-Wechsel als Übergang zählt */
private const val CROSSING_MAX_GAP_MS = 120_000L
/** Übergänge mit größerem Abstand der Punkte sind zu ungenau für eine Markierung */
private const val CROSSING_MAX_DISTANCE_M = 1_500.0

/** [points] zeitlich sortiert. */
fun buildTrackingAreas(points: List<TaPoint>): Map<TaKey, TrackingAreaMap> {
    val byKey = points.groupBy { TaKey(Operators.name(it.mcc, it.mnc) ?: (if (it.mcc != null) "${it.mcc}-${it.mnc}" else "Unbekannt"), taGroupOf(it.rat)) }
    return byKey.mapValues { (key, list) ->
        // Felder: häufigste TAC je Feld
        val tiles = list
            .groupBy { floor(it.latitude / COVERAGE_LAT_STEP).toInt() to floor(it.longitude / COVERAGE_LON_STEP).toInt() }
            .map { (idx, pts) ->
                val counts = pts.groupingBy { it.area }.eachCount()
                val (area, n) = counts.maxBy { it.value }
                TaTile(idx.first, idx.second, area, n)
            }
        val tileAt = tiles.associateBy { it.latIdx to it.lonIdx }

        // Grenzlinien zwischen Nachbarfeldern (Ost und Nord prüfen genügt, jede Kante einmal)
        val edges = mutableListOf<TaEdge>()
        for (t in tiles) {
            tileAt[t.latIdx to t.lonIdx + 1]?.let { e ->
                if (e.area != t.area) edges += TaEdge(t.south, t.east, t.north, t.east)
            }
            tileAt[t.latIdx + 1 to t.lonIdx]?.let { n ->
                if (n.area != t.area) edges += TaEdge(t.north, t.west, t.north, t.east)
            }
        }

        // Übergänge entlang der Route
        val crossings = mutableListOf<TaCrossing>()
        for (i in 1 until list.size) {
            val a = list[i - 1]
            val b = list[i]
            if (a.area == b.area || b.time - a.time > CROSSING_MAX_GAP_MS) continue
            val gap = distanceM(a.latitude, a.longitude, b.latitude, b.longitude)
            if (gap > CROSSING_MAX_DISTANCE_M) continue
            crossings += TaCrossing(
                latitude = (a.latitude + b.latitude) / 2,
                longitude = (a.longitude + b.longitude) / 2,
                from = a.area,
                to = b.area,
                time = b.time,
                gapM = gap,
            )
        }

        val areas = tiles.groupingBy { it.area }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        TrackingAreaMap(key, tiles, edges, crossings, areas)
    }
}
