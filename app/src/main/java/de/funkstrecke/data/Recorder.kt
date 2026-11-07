package de.funkstrecke.data

import android.location.Location
import androidx.room.withTransaction
import de.funkstrecke.locate.LocationEstimator
import de.funkstrecke.locate.Terrain
import de.funkstrecke.radio.CellRecord
import de.funkstrecke.radio.EndcMode
import de.funkstrecke.radio.EndcSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Basisstation, zu der eine Zelle gehört: bei 4G der eNodeB, sonst die Zelle selbst. */
fun groupKeyOf(rat: String, mcc: String?, mnc: String?, nodeId: Long?, cellKey: String): String =
    if (rat == "4G" && nodeId != null) "4G|$mcc-$mnc|enb|$nodeId" else cellKey

/** Schreibt Scans in die Datenbank und hält die Standortschätzungen aktuell. */
object Recorder {

    /** Neuberechnung einer Station höchstens alle 15 s, damit lange Fahrten flüssig bleiben. */
    private const val ESTIMATE_INTERVAL_MS = 15_000L
    const val ESTIMATE_MAX_MEASUREMENTS = 4_000
    private val lastEstimate = ConcurrentHashMap<String, Long>()

    private fun <T : Comparable<T>> maxOfNullable(a: T?, b: T?): T? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun <T : Comparable<T>> minOfNullable(a: T?, b: T?): T? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    /**
     * Speichert einen Scan. Mit [location] (aus der Aufzeichnung) entstehen zusätzlich
     * Messpunkte, und die Schätzungen der betroffenen Stationen werden aktualisiert.
     */
    suspend fun record(
        db: AppDatabase,
        cells: List<CellRecord>,
        location: Location?,
        now: Long,
        endc: EndcSnapshot? = null,
    ) {
        if (cells.isEmpty()) return
        val dao = db.cellDao()
        val touched = mutableMapOf<String, String>() // groupKey -> RAT
        val mode = endc?.mode
        val anchor = endc?.anchor
        val anchorKey = anchor?.key
        val anchorGroup = anchor?.let { groupKeyOf(it.rat, it.mcc, it.mnc, it.nodeId, it.key) }
        val legKeys = endc?.nrLegs?.map { it.key }?.toSet().orEmpty()
        db.withTransaction {
            for (c in cells) {
                val key = c.key
                val group = groupKeyOf(c.rat, c.mcc, c.mnc, c.nodeId, key)
                val isAnchor = key == anchorKey
                val connected = isAnchor && mode == EndcMode.CONNECTED
                val available = isAnchor && (mode == EndcMode.CONNECTED || mode == EndcMode.AVAILABLE)
                val legAnchor = if (key in legKeys && mode == EndcMode.CONNECTED) anchorGroup else null
                val old = dao.getCell(key)
                val updated = if (old == null) {
                    CellEntity(
                        id = key,
                        groupKey = group,
                        rat = c.rat,
                        mcc = c.mcc,
                        mnc = c.mnc,
                        plmnInferred = c.plmnInferred,
                        area = c.area,
                        cellId = c.cellId,
                        nodeId = c.nodeId,
                        sector = c.sector,
                        pci = c.pci,
                        arfcn = c.arfcn,
                        band = c.band?.label,
                        bandMhz = c.band?.mhz?.takeIf { it.isNotEmpty() },
                        freqMhz = c.band?.downlinkMhz,
                        firstSeen = now,
                        lastSeen = now,
                        samples = 1,
                        servingSamples = if (c.registered) 1 else 0,
                        bestDbm = c.dbm,
                        lastDbm = c.dbm,
                        lastQuality = c.quality,
                        lastSinr = c.sinr,
                        minDistanceM = c.distanceM,
                        endcConnectedSamples = if (connected) 1 else 0,
                        endcAvailableSamples = if (available) 1 else 0,
                        nrAnchorKey = legAnchor,
                    )
                } else {
                    old.copy(
                        plmnInferred = old.plmnInferred && c.plmnInferred,
                        area = c.area ?: old.area,
                        pci = c.pci ?: old.pci,
                        arfcn = c.arfcn ?: old.arfcn,
                        band = c.band?.label ?: old.band,
                        bandMhz = c.band?.mhz?.takeIf { it.isNotEmpty() } ?: old.bandMhz,
                        freqMhz = c.band?.downlinkMhz ?: old.freqMhz,
                        lastSeen = now,
                        samples = old.samples + 1,
                        servingSamples = old.servingSamples + if (c.registered) 1 else 0,
                        bestDbm = maxOfNullable(old.bestDbm, c.dbm),
                        lastDbm = c.dbm ?: old.lastDbm,
                        lastQuality = c.quality ?: old.lastQuality,
                        lastSinr = c.sinr ?: old.lastSinr,
                        minDistanceM = minOfNullable(old.minDistanceM, c.distanceM),
                        endcConnectedSamples = old.endcConnectedSamples + if (connected) 1 else 0,
                        endcAvailableSamples = old.endcAvailableSamples + if (available) 1 else 0,
                        nrAnchorKey = legAnchor ?: old.nrAnchorKey,
                    )
                }
                dao.upsertCell(updated)

                if (location != null && c.dbm != null) {
                    dao.insertMeasurement(
                        MeasurementEntity(
                            cellKey = key,
                            groupKey = group,
                            time = now,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                            dbm = c.dbm,
                            quality = c.quality,
                            sinr = c.sinr,
                            timingAdvance = c.timingAdvance,
                            serving = c.registered,
                            endc = mode?.name,
                        ),
                    )
                    touched[group] = c.rat
                }
            }
        }
        for ((group, rat) in touched) {
            val last = lastEstimate[group] ?: 0L
            if (now - last >= ESTIMATE_INTERVAL_MS) {
                lastEstimate[group] = now
                updateEstimate(db, group, rat, now)
            }
        }
    }

    /** Rechnet die Standortschätzung einer Station neu. */
    suspend fun updateEstimate(db: AppDatabase, groupKey: String, rat: String, now: Long = System.currentTimeMillis()) {
        val dao = db.cellDao()
        val measurements = dao.measurementsForGroup(groupKey, ESTIMATE_MAX_MEASUREMENTS)
        // Geländekacheln für die Messorte anfordern (lädt im Hintergrund; bis dahin ohne Gelände).
        Terrain.request(measurements.map { it.latitude to it.longitude }.distinctBy { (la, lo) -> (la * 20).toInt() to (lo * 20).toInt() })
        val elevation: ((Double, Double) -> Double?)? = if (Terrain.enabled) Terrain::elevation else null
        val result = withContext(Dispatchers.Default) { LocationEstimator.estimate(rat, measurements, elevation) }
        if (result == null) {
            dao.deleteEstimate(groupKey)
            return
        }
        dao.upsertEstimate(
            EstimateEntity(
                groupKey = groupKey,
                latitude = result.latitude,
                longitude = result.longitude,
                radiusM = result.radiusM,
                method = result.method,
                spots = result.spots,
                measurements = result.measurements,
                updatedAt = now,
            ),
        )
    }

    fun reset() = lastEstimate.clear()

    /** Alle Schätzungen neu berechnen (z. B. nach einer Datenbereinigung). */
    suspend fun recomputeAll(db: AppDatabase) {
        db.cellDao().measuredGroups().distinctBy { it.groupKey }.forEach { updateEstimate(db, it.groupKey, it.rat) }
    }
}
