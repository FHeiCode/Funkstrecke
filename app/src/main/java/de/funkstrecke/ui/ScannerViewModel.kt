package de.funkstrecke.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.funkstrecke.data.AppDatabase
import de.funkstrecke.data.BaseStation
import de.funkstrecke.data.CoverageTile
import de.funkstrecke.data.EndcTile
import de.funkstrecke.data.OcidProgress
import de.funkstrecke.data.OpenCellId
import de.funkstrecke.data.OsmIndex
import de.funkstrecke.data.OsmMastEntity
import de.funkstrecke.data.OsmMasts
import de.funkstrecke.data.OsmProgress
import de.funkstrecke.data.TaKey
import de.funkstrecke.data.TrackingAreaMap
import de.funkstrecke.data.buildTrackingAreas
import de.funkstrecke.data.MeasurementEntity
import de.funkstrecke.data.Recorder
import de.funkstrecke.data.NeighbourEstimate
import de.funkstrecke.data.NeighbourEstimator
import de.funkstrecke.data.buildStations
import de.funkstrecke.data.distanceM
import de.funkstrecke.locate.LocationEstimator
import de.funkstrecke.locate.Terrain
import de.funkstrecke.radio.CellScanner
import de.funkstrecke.radio.EndcMonitor
import de.funkstrecke.radio.LiveScan
import de.funkstrecke.util.Busy
import de.funkstrecke.util.CallGuard
import de.funkstrecke.util.CsvExport
import de.funkstrecke.util.LocationHelper
import de.funkstrecke.util.SettingsStore
import de.funkstrecke.util.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScannerViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val dao = db.cellDao()
    private val osmDao = db.osmDao()

    /** OSM-Funkmasten im Zwischenspeicher */
    val osmMasts: StateFlow<List<OsmMastEntity>> = osmDao.observeMasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val osmIndex: Flow<OsmIndex> = combine(
        osmDao.observeMasts(),
        osmDao.observeFreshTiles(System.currentTimeMillis() - OsmMasts.CACHE_DAYS * 24 * 3600 * 1000),
    ) { masts, tiles -> OsmIndex(masts, tiles.toSet()) }

    init {
        // Einmalig nach dem Update auf Datenbankversion 4: Schätzungen ohne die falschen TA-Werte neu rechnen.
        val prefs = app.getSharedPreferences("maintenance", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean(RECOMPUTE_TA_FIX, false)) {
            viewModelScope.launch {
                Recorder.recomputeAll(db)
                prefs.edit().putBoolean(RECOMPUTE_TA_FIX, true).apply()
            }
        }
        // Gelände: einmalig alle Schätzungen neu rechnen (fordert dabei die Kacheln an) …
        if (!prefs.getBoolean(RECOMPUTE_TERRAIN, false)) {
            viewModelScope.launch {
                Recorder.recomputeAll(db)
                prefs.edit().putBoolean(RECOMPUTE_TERRAIN, true).apply()
            }
        }
        // … und jedes Mal, wenn neue Kacheln angekommen sind (gesammelt, höchstens alle paar Sekunden).
        viewModelScope.launch {
            Terrain.ready.collectLatest {
                delay(5_000)
                Recorder.recomputeAll(db)
            }
        }
    }

    /** Geländemodell ein- oder ausschalten; alle Schätzungen werden neu berechnet. */
    fun setTerrainEnabled(value: Boolean) {
        Terrain.setEnabled(value)
        viewModelScope.launch { Recorder.recomputeAll(db) }
    }

    fun clearTerrain() {
        Terrain.clear()
        viewModelScope.launch { Recorder.recomputeAll(db) }
    }

    /** Ergebnis des Vergleichs mit und ohne Gelände an Stationen mit OSM-Mast in der Nähe. */
    data class TerrainEffect(
        val running: Boolean,
        val stations: Int = 0,
        val medianWithoutM: Double? = null,
        val medianWithM: Double? = null,
        val improved: Int = 0,
        val worse: Int = 0,
    )

    private val _terrainEffect = MutableStateFlow<TerrainEffect?>(null)
    val terrainEffect: StateFlow<TerrainEffect?> = _terrainEffect.asStateFlow()

    /**
     * Wirkung des Geländemodells messen: Für jede Station mit einem OSM-Mast höchstens 500 m neben der
     * Schätzung wird ohne und mit Gelände gerechnet und jeweils der Abstand zu diesem Mast bestimmt.
     */
    fun measureTerrainEffect() {
        if (_terrainEffect.value?.running == true) return
        _terrainEffect.value = TerrainEffect(running = true)
        viewModelScope.launch {
            val pairs = withContext(Dispatchers.Default) {
                stations.value.filter { !it.inferred && it.estimate != null }.mapNotNull { s ->
                    val measurements = dao.measurementsForGroup(s.key, Recorder.ESTIMATE_MAX_MEASUREMENTS)
                    val without = LocationEstimator.estimate(s.rat, measurements) ?: return@mapNotNull null
                    val mast = s.osmMast?.takeIf {
                        distanceM(without.latitude, without.longitude, it.latitude, it.longitude) <= 500.0
                    } ?: return@mapNotNull null
                    val with = LocationEstimator.estimate(s.rat, measurements, Terrain::elevation) ?: return@mapNotNull null
                    if (!with.method.contains("Gelände")) return@mapNotNull null
                    distanceM(without.latitude, without.longitude, mast.latitude, mast.longitude) to
                        distanceM(with.latitude, with.longitude, mast.latitude, mast.longitude)
                }
            }
            fun median(v: List<Double>) = v.sorted().let { if (it.isEmpty()) null else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
            _terrainEffect.value = TerrainEffect(
                running = false,
                stations = pairs.size,
                medianWithoutM = median(pairs.map { it.first }),
                medianWithM = median(pairs.map { it.second }),
                improved = pairs.count { it.second < it.first - 10 },
                worse = pairs.count { it.second > it.first + 10 },
            )
        }
    }

    val stations: StateFlow<List<BaseStation>> = combine(
        dao.observeCells(),
        dao.observeEstimates(),
        dao.observeCentroids(),
        dao.observeOcid(),
        osmIndex,
    ) { cells, estimates, centroids, ocid, osm ->
        buildStations(cells, estimates, centroids, ocid, osm)
    }
        .conflate()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cellCount: StateFlow<Int> = dao.observeCells().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Abdeckungsfelder aller Betreiber; wird nur berechnet, solange die Karte sie anzeigt. */
    val coverage: StateFlow<List<CoverageTile>> = dao.observeCoverage()
        .conflate()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 5G-NSA-Felder aller Betreiber; nur berechnet, solange die Karte sie anzeigt. */
    val endcCoverage: StateFlow<List<EndcTile>> = dao.observeEndcCoverage()
        .conflate()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _trackingAreas = MutableStateFlow<Map<TaKey, TrackingAreaMap>>(emptyMap())
    val trackingAreas: StateFlow<Map<TaKey, TrackingAreaMap>> = _trackingAreas.asStateFlow()
    private var taJob: kotlinx.coroutines.Job? = null
    private var taLoadedAt = 0L

    /** Tracking-Area-Karte neu berechnen; während der Aufzeichnung höchstens alle 20 s. */
    fun loadTrackingAreas(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (taJob?.isActive == true) return
        if (!force && now - taLoadedAt < 20_000) return
        taLoadedAt = now
        taJob = viewModelScope.launch {
            val points = dao.servingTaPoints()
            _trackingAreas.value = withContext(Dispatchers.Default) { buildTrackingAreas(points) }
        }
    }

    val measurementCount: StateFlow<Int> = dao.observeMeasurementCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _live = MutableStateFlow(LiveScan())
    val live: StateFlow<LiveScan> = _live.asStateFlow()

    /** Ein Live-Scan; die gehörten Zellen landen ohne Position in der Datenbank. */
    private val _liveBusy = MutableStateFlow(Busy.NONE)
    val liveBusy: StateFlow<Busy> = _liveBusy.asStateFlow()

    private val _neighbourEstimates = MutableStateFlow<Map<String, NeighbourEstimate>>(emptyMap())
    /** Schätzungen für gemessene, aber keiner verorteten Station zugeordnete Nachbarzellen (Schlüssel = Zellkennung) */
    val neighbourEstimates: StateFlow<Map<String, NeighbourEstimate>> = _neighbourEstimates.asStateFlow()
    private var neighbourCache = mapOf<String, Pair<NeighbourEstimate, Long>>()

    /**
     * Rechnet die Schätzungen für [cells] neu. Ergebnisse aus aufgezeichneten Messungen werden eine Minute
     * wiederverwendet; der Abstand aus dem Pegel folgt jedem Scan.
     */
    fun updateNeighbourEstimates(cells: List<de.funkstrecke.radio.CellRecord>) {
        viewModelScope.launch {
            val here = LocationHelper.lastKnown(getApplication<Application>())
            if (here == null || cells.isEmpty()) {
                _neighbourEstimates.value = emptyMap()
                return@launch
            }
            val now = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) {
                cells.mapNotNull { c ->
                    val cached = neighbourCache[c.key]
                    val est = if (cached != null && cached.first is NeighbourEstimate.Located && now - cached.second < 60_000) {
                        (cached.first as NeighbourEstimate.Located).copy(cell = c)
                    } else {
                        NeighbourEstimator.estimate(db, c, here.latitude, here.longitude)
                    }
                    est?.let { c.key to it }
                }.toMap()
            }
            neighbourCache = result.mapValues { (k, v) ->
                val old = neighbourCache[k]
                v to if (old != null && v is NeighbourEstimate.Located && old.first is NeighbourEstimate.Located) old.second else now
            }
            _neighbourEstimates.value = result
        }
    }

    suspend fun refreshLive(forceRefresh: Boolean) {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        // Während eines Telefonats nach Einstellung pausieren oder nur mitlesen.
        val decision = CallGuard.decide(app, forceRefresh, _live.value.at ?: 0L, now)
        _liveBusy.value = decision.busy
        val force = decision.forceRefresh ?: return
        val cells = CellScanner.scan(app, force)
        val endc = EndcMonitor.snapshot(app, cells)
        _live.value = LiveScan(cells, now, endc)
        Recorder.record(db, cells, null, now, endc)
    }

    fun measurementsFor(groupKeys: List<String>): Flow<List<MeasurementEntity>> =
        if (groupKeys.size == 1) {
            dao.observeMeasurementsForGroup(groupKeys[0], 400)
        } else {
            dao.observeMeasurementsForGroups(groupKeys, 600)
        }

    fun recompute(station: BaseStation) {
        viewModelScope.launch {
            // Vermutete Stationen haben keinen eigenen Datensatz: ihre Zellen einzeln neu berechnen.
            val keys = if (station.inferred) station.cells.map { it.groupKey }.distinct() else listOf(station.key)
            keys.forEach { Recorder.updateEstimate(db, it, station.rat) }
        }
    }

    private val _ocidProgress = MutableStateFlow<OcidProgress?>(null)
    /** Stand des OpenCelliD-Abgleichs; null = noch keiner gelaufen */
    val ocidProgress: StateFlow<OcidProgress?> = _ocidProgress.asStateFlow()
    private var ocidJob: kotlinx.coroutines.Job? = null

    /**
     * Gleicht Zellen mit OpenCelliD ab: alle Stationen oder nur [station].
     * Bei einer einzelnen Station wird immer neu abgefragt, sonst nur Fehlendes und Veraltetes.
     */
    fun compareWithOpenCellId(station: BaseStation? = null) {
        val key = SettingsStore.openCellIdKey.value
        if (key.isBlank() || ocidJob?.isActive == true) return
        ocidJob = viewModelScope.launch {
            val cells = station?.cells ?: dao.allCells()
            OpenCellId.compare(db, key, cells, force = station != null) { _ocidProgress.value = it }
        }
    }

    private val _osmProgress = MutableStateFlow<OsmProgress?>(null)
    /** Stand des Ladens von OSM-Masten; null = noch nichts geladen */
    val osmProgress: StateFlow<OsmProgress?> = _osmProgress.asStateFlow()
    private var osmJob: kotlinx.coroutines.Job? = null
    private var osmPending: List<String>? = null

    private fun loadOsm(tiles: List<String>) {
        if (tiles.isEmpty()) return
        if (osmJob?.isActive == true) {
            osmPending = tiles
            return
        }
        osmJob = viewModelScope.launch {
            var next: List<String>? = tiles
            while (next != null) {
                osmPending = null
                OsmMasts.ensure(db, next) { _osmProgress.value = it }
                next = osmPending
            }
        }
    }

    /** OSM-Masten für einen Kartenausschnitt laden (nur bei ausreichend kleinem Ausschnitt). */
    fun loadOsmView(south: Double, west: Double, north: Double, east: Double) {
        val tiles = OsmMasts.tilesFor(south, west, north, east)
        if (tiles.size <= OsmMasts.MAX_VIEW_TILES) loadOsm(OsmMasts.sortedByDistance(tiles, (south + north) / 2, (west + east) / 2))
    }

    /** OSM-Masten im Umkreis einer Station laden. */
    fun loadOsmAround(lat: Double, lon: Double) = loadOsm(OsmMasts.tilesAround(lat, lon, OsmMasts.SEARCH_M))

    /** OSM-Masten rund um alle verorteten Stationen laden. */
    fun loadOsmForAllStations() {
        val tiles = stations.value.mapNotNull { it.estimate }
            .flatMap { OsmMasts.tilesAround(it.latitude, it.longitude, OsmMasts.SEARCH_M) }
            .distinct()
        loadOsm(tiles)
    }

    fun cancelOpenCellId() {
        ocidJob?.cancel()
        _ocidProgress.value = _ocidProgress.value?.copy(running = false, stopReason = tr("Abgebrochen", "Cancelled"))
    }

    fun clearAll() {
        viewModelScope.launch {
            dao.clearMeasurements()
            dao.clearEstimates()
            dao.clearOcid()
            dao.clearCells()
            Recorder.reset()
        }
    }

    fun exportCells() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val csv = withContext(Dispatchers.IO) {
                val cells = dao.allCells()
                val estimates = dao.allEstimatesOnce()
                // Bewertung je Zelle aus den Stationen (inkl. OpenCelliD-Abgleich)
                val osm = OsmIndex(
                    osmDao.allMasts(),
                    osmDao.freshTiles(System.currentTimeMillis() - OsmMasts.CACHE_DAYS * 24 * 3600 * 1000).toSet(),
                )
                val assessments = buildStations(cells, estimates, emptyList(), dao.allOcid(), osm)
                    .flatMap { s -> s.cells.map { c -> c.id to de.funkstrecke.data.assessCell(s, c) } }
                    .toMap()
                CsvExport.cellsCsv(cells, estimates, assessments)
            }
            CsvExport.share(app, tr("zellen", "cells"), csv)
        }
    }

    fun exportMeasurements() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val csv = withContext(Dispatchers.IO) { CsvExport.measurementsCsv(dao.allMeasurements(), dao.allCells()) }
            CsvExport.share(app, tr("messprotokoll", "measurements"), csv)
        }
    }

    private companion object {
        const val RECOMPUTE_TA_FIX = "recomputed_after_ta_fix"
        const val RECOMPUTE_TERRAIN = "recomputed_with_terrain"
    }
}
