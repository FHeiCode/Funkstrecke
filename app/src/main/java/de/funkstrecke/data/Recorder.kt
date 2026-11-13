package de.funkstrecke.data

import android.location.Location
import androidx.room.withTransaction
import de.funkstrecke.locate.LocationEstimator
import de.funkstrecke.locate.SignalModelStore
import de.funkstrecke.locate.Terrain
import de.funkstrecke.radio.CellRecord
import de.funkstrecke.radio.EndcMode
import de.funkstrecke.radio.EndcSnapshot
import de.funkstrecke.util.SettingsStore
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
                    // Nachbarzelle ohne Cell-ID: Ihre Messung zählt auch für die Station, die genau diese Zelle
                    // (Träger + PCI) mit Cell-ID kennt, siehe measurementsForEstimate.
                    if (c.cellId == null && c.arfcn != null && c.pci != null) {
                        dao.groupsWithCell(c.rat, c.arfcn, c.pci).forEach { touched.putIfAbsent(it, c.rat) }
                    }
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
        // Verdächtige Stationen prüfen (höchstens einmal pro Minute).
        if (location != null) Suspects.check(db, SettingsStore.suspectMinMeasurements.value, now)
    }

    /** Messungen einer Station für die Schätzung, das Band je Zelle und die Zahl übernommener Nachbarmessungen. */
    class EstimateInput(val measurements: List<MeasurementEntity>, val bandOf: Map<String, String?>, val adopted: Int)

    /** Übernommene Nachbarmessungen nur aus diesem Umkreis um die eigenen Messorte (PCI-Wiederholung) */
    private const val ADOPT_RADIUS_M = 5_000.0
    private const val ADOPT_MAX_PER_CELL = 1_500

    /**
     * Messungen der Station [groupKey] plus Messungen ihrer Zellen aus der Zeit, als sie nur Nachbarzelle
     * waren: Diese stehen ohne Cell-ID unter „nb|ARFCN|PCI“. Übernommen wird, was dieselbe Technik, dasselbe
     * Netz, denselben Träger und dieselbe PCI hat und höchstens [ADOPT_RADIUS_M] vom Schwerpunkt der eigenen
     * Messorte entfernt liegt. So startet eine Zelle bei der ersten Verbindung nicht bei null.
     */
    suspend fun measurementsForEstimate(db: AppDatabase, groupKey: String): EstimateInput {
        val dao = db.cellDao()
        val own = dao.measurementsForGroup(groupKey, ESTIMATE_MAX_MEASUREMENTS)
        val cells = dao.cellsOfGroup(groupKey)
        val bandOf = cells.associate { it.id to it.band }
        if (own.isEmpty()) return EstimateInput(own, bandOf, 0)
        val lat = own.map { it.latitude }.average()
        val lon = own.map { it.longitude }.average()
        val adopted = cells.filter { it.cellId != null && it.arfcn != null && it.pci != null }.flatMap { c ->
            val nbKey = "${c.rat}|${c.mcc}-${c.mnc}|nb|${c.arfcn}|${c.pci}"
            dao.measurementsForGroup(nbKey, ADOPT_MAX_PER_CELL)
                .filter { distanceM(lat, lon, it.latitude, it.longitude) <= ADOPT_RADIUS_M }
                .map { it.copy(cellKey = c.id, groupKey = groupKey, serving = false, timingAdvance = null) }
        }
        return EstimateInput(own + adopted, bandOf, adopted.size)
    }

    /** Rechnet die Standortschätzung einer Station neu. */
    suspend fun updateEstimate(db: AppDatabase, groupKey: String, rat: String, now: Long = System.currentTimeMillis()) {
        val dao = db.cellDao()
        val input = measurementsForEstimate(db, groupKey)
        val measurements = input.measurements
        // Geländekacheln für die Messorte anfordern (lädt im Hintergrund; bis dahin ohne Gelände).
        Terrain.request(measurements.map { it.latitude to it.longitude }.distinctBy { (la, lo) -> (la * 20).toInt() to (lo * 20).toInt() })
        val elevation: ((Double, Double) -> Double?)? = if (Terrain.enabled) Terrain::elevation else null
        val model = SignalModelStore.active
        val result = withContext(Dispatchers.Default) {
            LocationEstimator.estimate(rat, measurements, elevation, model, input.bandOf)
        }
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
                method = result.method + if (input.adopted > 0) ", +${input.adopted} Nachbarmessungen" else "",
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
