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
import de.funkstrecke.data.buildStations
import de.funkstrecke.radio.CellScanner
import de.funkstrecke.radio.EndcMonitor
import de.funkstrecke.radio.LiveScan
import de.funkstrecke.util.Busy
import de.funkstrecke.util.CallGuard
import de.funkstrecke.util.CsvExport
import de.funkstrecke.util.SettingsStore
import de.funkstrecke.util.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
    }
}
