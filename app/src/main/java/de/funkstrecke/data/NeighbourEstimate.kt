package de.funkstrecke.data

import de.funkstrecke.locate.LocationEstimator
import de.funkstrecke.locate.Terrain
import de.funkstrecke.radio.CellRecord
import kotlin.math.pow

/**
 * Positionsschätzung für eine gerade gemessene Nachbarzelle, die keiner verorteten Station
 * zugeordnet werden konnte.
 */
sealed interface NeighbourEstimate {
    val cell: CellRecord

    /** Aus früher aufgezeichneten Messungen dieser Zelle in der Nähe gerechnet */
    data class Located(
        override val cell: CellRecord,
        val latitude: Double,
        val longitude: Double,
        val radiusM: Double,
        val measurements: Int,
    ) : NeighbourEstimate

    /** Nur der Abstand aus dem aktuellen Pegel; die Richtung ist unbekannt */
    data class Range(
        override val cell: CellRecord,
        val latitude: Double,
        val longitude: Double,
        val distanceM: Double,
    ) : NeighbourEstimate
}

object NeighbourEstimator {
    /** Nur Messungen dieser Zelle in diesem Umkreis um die eigene Position (PCI-Wiederholung) */
    private const val NEARBY_M = 5_000.0
    /** Mindestens so viele Messorte für eine eigene Schätzung */
    private const val MIN_SPOTS = 4
    /** Pegelmodell wie in [LocationEstimator]: P0 in 1 m, Exponent 3 */
    private const val P0_DBM = -5.0
    private const val PATH_LOSS_EXPONENT = 3.0

    /** Grober Abstand aus dem Pegel, 50 m bis 15 km */
    fun rangeFromSignal(dbm: Int): Double =
        10.0.pow((P0_DBM - dbm) / (10 * PATH_LOSS_EXPONENT)).coerceIn(50.0, 15_000.0)

    /**
     * Schätzt eine Nachbarzelle aus ihren gespeicherten Messungen in der Nähe von ([lat], [lon]);
     * reicht das nicht, bleibt nur der Abstand aus dem aktuellen Pegel.
     */
    suspend fun estimate(db: AppDatabase, cell: CellRecord, lat: Double, lon: Double): NeighbourEstimate? {
        val nearby = db.cellDao().measurementsForGroup(groupKeyOf(cell.rat, cell.mcc, cell.mnc, cell.nodeId, cell.key), 2_000)
            .filter { distanceM(lat, lon, it.latitude, it.longitude) <= NEARBY_M }
        val spots = nearby.map { (it.latitude * 2_000).toInt() to (it.longitude * 2_000).toInt() }.distinct().size
        if (spots >= MIN_SPOTS) {
            val elevation: ((Double, Double) -> Double?)? = if (Terrain.enabled) Terrain::elevation else null
            LocationEstimator.estimate(cell.rat, nearby, elevation)?.let { r ->
                if (r.radiusM <= 5_000) return NeighbourEstimate.Located(cell, r.latitude, r.longitude, r.radiusM, r.measurements)
            }
        }
        val dbm = cell.dbm ?: return null
        return NeighbourEstimate.Range(cell, lat, lon, rangeFromSignal(dbm))
    }
}
