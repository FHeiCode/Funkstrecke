package de.funkstrecke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.funkstrecke.data.CoverageCell
import de.funkstrecke.data.Reliability
import de.funkstrecke.data.coverageFor
import de.funkstrecke.data.ocidSector
import de.funkstrecke.ui.theme.operatorColorRaw
import de.funkstrecke.ui.theme.signalColorRaw
import de.funkstrecke.util.HomeStore
import de.funkstrecke.util.LocationHelper
import de.funkstrecke.util.formatDistance
import de.funkstrecke.util.tr

private enum class MapMode(private val de: String, private val en: String) {
    STATIONS("Stationen", "Stations"),
    COVERAGE("Abdeckung", "Coverage"),
    TRACKING("TA-Grenzen", "TA borders"),
    ;

    val label: String get() = tr(de, en)
}

/** Gut unterscheidbare Farben für Tracking Areas, nach Größe vergeben */
private val taPalette = listOf(
    Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA),
    Color(0xFF00ACC1), Color(0xFFC0CA33), Color(0xFF6D4C41), Color(0xFFD81B60), Color(0xFF546E7A),
)
private val taBorder = Color(0xFF15191C)

private enum class CoverageView(private val de: String, private val en: String) {
    SIGNAL("Signalstärke", "Signal strength"),
    ENDC("5G NSA (EN-DC)", "5G NSA (EN-DC)"),
    ;

    val label: String get() = tr(de, en)
}

/** Farben der 5G-Ansicht je EndcMode-Rang */
private fun endcColor(rank: Int): Color = when (rank) {
    4 -> Color(0xFF00695C) // 5G SA
    3 -> Color(0xFF237A4B) // EN-DC verbunden
    2 -> Color(0xFF2F6FB0) // verfügbar
    1 -> Color(0xFFC4561C) // gesperrt
    else -> Color(0xFF7A8690) // kein 5G
}

private val endcLabels get() = listOf(
    4 to "5G SA",
    3 to tr("5G NSA aktiv (EN-DC verbunden)", "5G NSA active (EN-DC connected)"),
    2 to tr("5G NSA verfügbar, nicht verbunden", "5G NSA available, not connected"),
    1 to tr("5G gesperrt", "5G restricted"),
    0 to tr("kein 5G", "no 5G"),
)

/** Bezeichnung der Signalstufen; Schwellen für 4G/5G (RSRP) in Klammern. */
private val levelLabels get() = listOf(
    4 to tr("sehr gut (≥ −80 dBm)", "very good (≥ −80 dBm)"),
    3 to tr("gut (−80 … −90)", "good (−80 … −90)"),
    2 to tr("mittel (−90 … −100)", "fair (−90 … −100)"),
    1 to tr("schwach (−100 … −110)", "weak (−100 … −110)"),
    0 to tr("sehr schwach (< −110)", "very weak (< −110)"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(vm: ScannerViewModel, onOpen: (String) -> Unit) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val located = remember(stations) { stations.filter { it.estimate != null } }
    val home by HomeStore.flow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val canLocate = remember { LocationHelper.hasPermission(context) }
    var recenterKey by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(MapMode.STATIONS) }

    var coverageView by rememberSaveable { mutableStateOf(CoverageView.SIGNAL) }
    // Abdeckung nur abonnieren, solange sie angezeigt wird.
    val tiles = if (mode == MapMode.COVERAGE && coverageView == CoverageView.SIGNAL) {
        vm.coverage.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val endcTiles = if (mode == MapMode.COVERAGE && coverageView == CoverageView.ENDC) {
        vm.endcCoverage.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    val operators = remember(tiles, endcTiles) {
        val names = tiles.map { it.operatorName } + endcTiles.map { it.operatorName }
        names.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
    var chosenOperator by rememberSaveable { mutableStateOf<String?>(null) }
    val operator = chosenOperator?.takeIf { it in operators } ?: operators.firstOrNull()
    var ratFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val rats = remember(tiles, operator) { tiles.filter { it.operatorName == operator }.map { it.rat }.distinct().sorted() }
    val cells: List<CoverageCell> = remember(tiles, operator, ratFilter) {
        if (operator == null) emptyList() else coverageFor(tiles, operator, ratFilter?.takeIf { it in rats })
    }
    val endcCells = remember(endcTiles, operator) { endcTiles.filter { it.operatorName == operator } }
    val rects = remember(cells, endcCells, coverageView) {
        if (coverageView == CoverageView.ENDC) {
            endcCells.map { t -> CoverageRect(t.south, t.west, t.north, t.east, endcColor(t.endcRank).copy(alpha = 0.55f).toArgb()) }
        } else {
            cells.map { c -> CoverageRect(c.south, c.west, c.north, c.east, signalColorRaw(c.level).copy(alpha = 0.55f).toArgb()) }
        }
    }

    // Tracking Areas: beim Öffnen berechnen, während der Aufzeichnung mit neuen Messwerten auffrischen.
    val measurementCount by vm.measurementCount.collectAsStateWithLifecycle()
    LaunchedEffect(mode) { if (mode == MapMode.TRACKING) vm.loadTrackingAreas(force = true) }
    LaunchedEffect(mode, measurementCount) { if (mode == MapMode.TRACKING) vm.loadTrackingAreas() }
    val taMaps by vm.trackingAreas.collectAsStateWithLifecycle()
    val taOperators = remember(taMaps) {
        taMaps.values.groupBy { it.key.operator }.mapValues { (_, l) -> l.sumOf { it.tiles.size } }
            .entries.sortedByDescending { it.value }.map { it.key }
    }
    var chosenTaOperator by rememberSaveable { mutableStateOf<String?>(null) }
    val taOperator = chosenTaOperator?.takeIf { it in taOperators } ?: taOperators.firstOrNull()
    val taGroups = remember(taMaps, taOperator) {
        taMaps.values.filter { it.key.operator == taOperator }.sortedByDescending { it.tiles.size }.map { it.key.group }
    }
    var chosenTaGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val taGroup = chosenTaGroup?.takeIf { it in taGroups } ?: taGroups.firstOrNull()
    val taMap = taMaps.values.firstOrNull { it.key.operator == taOperator && it.key.group == taGroup }
    val taColorOf = remember(taMap) {
        taMap?.areas?.mapIndexed { i, (area, _) -> area to taPalette[i % taPalette.size] }?.toMap().orEmpty()
    }
    val taRects = remember(taMap) {
        taMap?.tiles?.map { t ->
            CoverageRect(t.south, t.west, t.north, t.east, (taColorOf[t.area] ?: taPalette.last()).copy(alpha = 0.40f).toArgb())
        }.orEmpty()
    }
    val taLines = remember(taMap) {
        taMap?.edges?.map { e -> CoverageLine(e.lat1, e.lon1, e.lat2, e.lon2, taBorder.copy(alpha = 0.85f).toArgb(), 2.5f) }.orEmpty()
    }
    val taDots = remember(taMap) {
        taMap?.crossings?.map { c ->
            MapDot(
                latitude = c.latitude,
                longitude = c.longitude,
                color = taBorder,
                sizeDp = 11,
                title = "TAC ${c.from} → ${c.to}",
                snippet = tr("${de.funkstrecke.util.formatDateTime(c.time)} · Grenze auf ±${formatDistance(c.gapM / 2)} genau", "${de.funkstrecke.util.formatDateTime(c.time)} · border accurate to ±${formatDistance(c.gapM / 2)}"),
            )
        }.orEmpty()
    }

    // Stationen: Kreise nur, wenn sie aussagekräftig sind; sehr grobe Schätzungen würden die Karte zudecken.
    // Bewertung: zuverlässig = gefüllter Punkt mit Kreis, Kandidat = kleiner, halbtransparent mit Kreis,
    // mögliches Artefakt = nur Ring ohne Kreis (standardmäßig ausgeblendet).
    var showCandidates by rememberSaveable { mutableStateOf(true) }
    var showArtefacts by rememberSaveable { mutableStateOf(false) }
    val assessed = remember(located) { located.map { it to it.assessment } }
    val reliabilityCounts = remember(assessed) { assessed.groupingBy { it.second.reliability }.eachCount() }
    val visible = remember(assessed, showCandidates, showArtefacts) {
        assessed.filter { (_, a) ->
            when (a.reliability) {
                Reliability.RELIABLE -> true
                Reliability.CANDIDATE -> showCandidates
                Reliability.ARTEFACT -> showArtefacts
            }
        }
    }
    val circles = remember(visible) {
        visible.mapNotNull { (s, a) ->
            val e = s.estimate!!
            if (a.reliability == Reliability.ARTEFACT || e.radiusM > 2_000) {
                null
            } else {
                val c = operatorColorRaw(s.operatorName)
                MapCircle(
                    e.latitude, e.longitude, e.radiusM,
                    if (a.reliability == Reliability.RELIABLE) c else c.copy(alpha = 0.5f),
                    sector = ocidSector(s),
                )
            }
        }
    }
    val dots = remember(visible) {
        visible.map { (s, a) ->
            val e = s.estimate!!
            val c = operatorColorRaw(s.operatorName)
            MapDot(
                latitude = e.latitude,
                longitude = e.longitude,
                color = if (a.reliability == Reliability.CANDIDATE) c.copy(alpha = 0.6f) else c,
                sizeDp = when (a.reliability) {
                    Reliability.RELIABLE -> 18
                    Reliability.CANDIDATE -> 14
                    Reliability.ARTEFACT -> 12
                },
                title = "${s.title} · ${a.reliability.label}",
                snippet = "${s.operatorName ?: tr("Unbekannt", "Unknown")} · ${a.reason}",
                onClick = { onOpen(s.key) },
                hollow = a.reliability == Reliability.ARTEFACT,
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tr("Karte", "Map"))
                        Text(
                            when (mode) {
                                MapMode.STATIONS -> tr("${located.size} von ${stations.size} Stationen verortet", "${located.size} of ${stations.size} stations located")
                                MapMode.TRACKING -> if (taMap == null) tr("Noch keine Tracking-Area-Daten", "No tracking area data yet") else
                                    tr("$taOperator · ${taMap.areas.size} Tracking Areas · ${taMap.crossings.size} Übergänge", "$taOperator · ${taMap.areas.size} tracking areas · ${taMap.crossings.size} crossings")
                                MapMode.COVERAGE -> if (operator == null) tr("Noch keine Abdeckungsdaten", "No coverage data yet") else
                                    tr("$operator · ${if (coverageView == CoverageView.ENDC) endcCells.size else cells.size} Felder à ~50 m", "$operator · ${if (coverageView == CoverageView.ENDC) endcCells.size else cells.size} tiles of ~50 m")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val homeLat = home.latitude
            val homeLon = home.longitude
            val homeCircle = if (homeLat != null && homeLon != null) {
                listOf(MapCircle(homeLat, homeLon, home.radiusM.toDouble(), HomeBaseColor))
            } else {
                emptyList()
            }
            OsmMap(
                dots = when (mode) {
                    MapMode.STATIONS -> dots
                    MapMode.TRACKING -> taDots
                    MapMode.COVERAGE -> emptyList()
                },
                circles = homeCircle + if (mode == MapMode.STATIONS) circles else emptyList(),
                coverage = when (mode) {
                    MapMode.COVERAGE -> rects
                    MapMode.TRACKING -> taRects
                    MapMode.STATIONS -> emptyList()
                },
                lines = if (mode == MapMode.TRACKING) taLines else emptyList(),
                modifier = Modifier.fillMaxSize(),
                focus = MapFocus.OwnPosition(radiusM = 1_000.0),
                recenterKey = recenterKey,
            )

            // Bedienleiste oben
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    MapMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = MapMode.entries.size),
                            colors = SegmentedButtonDefaults.colors(inactiveContainerColor = MaterialTheme.colorScheme.surface),
                        ) { Text(m.label) }
                    }
                }
                if (mode == MapMode.STATIONS && located.isNotEmpty()) {
                    ChipBar {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text("${Reliability.RELIABLE.label} ${reliabilityCounts[Reliability.RELIABLE] ?: 0}") },
                            leadingIcon = { ReliabilityDot(Reliability.RELIABLE) },
                        )
                        FilterChip(
                            selected = showCandidates,
                            onClick = { showCandidates = !showCandidates },
                            label = { Text(tr("Kandidaten ${reliabilityCounts[Reliability.CANDIDATE] ?: 0}", "Candidates ${reliabilityCounts[Reliability.CANDIDATE] ?: 0}")) },
                            leadingIcon = { ReliabilityDot(Reliability.CANDIDATE) },
                        )
                        FilterChip(
                            selected = showArtefacts,
                            onClick = { showArtefacts = !showArtefacts },
                            label = { Text(tr("Artefakte ${reliabilityCounts[Reliability.ARTEFACT] ?: 0}", "Artefacts ${reliabilityCounts[Reliability.ARTEFACT] ?: 0}")) },
                            leadingIcon = { ReliabilityDot(Reliability.ARTEFACT) },
                        )
                    }
                }
                if (mode == MapMode.TRACKING && taOperators.isNotEmpty()) {
                    ChipBar {
                        taOperators.forEach { op ->
                            FilterChip(
                                selected = op == taOperator,
                                onClick = { chosenTaOperator = op },
                                label = { Text(op) },
                                leadingIcon = { OperatorDot(op.takeIf { it != tr("Unbekannt", "Unknown") }) },
                            )
                        }
                    }
                    if (taGroups.size > 1) {
                        ChipBar {
                            taGroups.forEach { g ->
                                FilterChip(selected = g == taGroup, onClick = { chosenTaGroup = g }, label = { Text(g) })
                            }
                        }
                    }
                }
                if (mode == MapMode.COVERAGE) {
                    ChipBar {
                        CoverageView.entries.forEach { v ->
                            FilterChip(selected = coverageView == v, onClick = { coverageView = v }, label = { Text(v.label) })
                        }
                    }
                }
                if (mode == MapMode.COVERAGE && operators.isNotEmpty()) {
                    ChipBar {
                        operators.forEach { op ->
                            FilterChip(
                                selected = op == operator,
                                onClick = { chosenOperator = op },
                                label = { Text(op) },
                                leadingIcon = { OperatorDot(op.takeIf { it != tr("Unbekannt", "Unknown") }) },
                            )
                        }
                    }
                    if (coverageView == CoverageView.SIGNAL && rats.size > 1) {
                        ChipBar {
                            FilterChip(selected = ratFilter == null, onClick = { ratFilter = null }, label = { Text(tr("Beste Technik", "Best technology")) })
                            rats.forEach { r ->
                                FilterChip(selected = ratFilter == r, onClick = { ratFilter = r }, label = { Text(r) })
                            }
                        }
                    }
                }
            }

            if (mode == MapMode.TRACKING) {
                if (taMap == null) {
                    InfoBubble(
                        tr("Noch keine Tracking-Area-Daten. Sie entstehen bei der Aufzeichnung aus der TAC der verbundenen ", "No tracking area data yet. It is created during recording from the TAC of the serving ") +
                            tr("Zelle an jedem Messpunkt mit genauer Position.", "cell at every measurement point with a precise position."),
                        Modifier.align(Alignment.Center),
                    )
                } else {
                    TrackingLegend(taMap, taColorOf, Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
            } else if (mode == MapMode.COVERAGE) {
                if (operators.isEmpty()) {
                    InfoBubble(
                        if (coverageView == CoverageView.ENDC) {
                            tr("Noch keine 5G-Daten. Sie entstehen bei der Aufzeichnung ab dieser Version, sobald das Handy ", "No 5G data yet. It is created during recording from this version on, as soon as the phone ") +
                                tr("mit LTE verbunden ist und eine genaue Position hat.", "is connected to LTE and has a precise position.")
                        } else {
                            tr("Noch keine Abdeckungsdaten. Starte im Live-Tab die Aufzeichnung: Jeder Messpunkt mit genauer ", "No coverage data yet. Start recording in the Live tab: every measurement point with a precise ") +
                                tr("Position färbt ein Feld von etwa 50 × 50 m.", "position colours a tile of about 50 × 50 m.")
                        },
                        Modifier.align(Alignment.Center),
                    )
                } else if (coverageView == CoverageView.ENDC) {
                    EndcLegend(endcCells, Modifier.align(Alignment.BottomStart).padding(12.dp))
                } else {
                    CoverageLegend(cells, Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
            } else if (located.isEmpty()) {
                InfoBubble(
                    tr("Noch keine Station verortet. Starte im Live-Tab die Aufzeichnung und bewege dich durch die Umgebung.", "No station located yet. Start recording in the Live tab and move around the area."),
                    Modifier.align(Alignment.Center),
                )
            }

            if (canLocate) {
                SmallFloatingActionButton(
                    onClick = { recenterKey++ },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = tr("Auf meine Position zentrieren", "Center on my position"))
                }
            }
        }
    }
}

@Composable
private fun ChipBar(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

@Composable
private fun InfoBubble(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(24.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Legende mit Anteil der Felder je Signalstufe. */
@Composable
private fun CoverageLegend(cells: List<CoverageCell>, modifier: Modifier) {
    val total = cells.size.coerceAtLeast(1)
    val counts = cells.groupingBy { it.level }.eachCount()
    Surface(
        modifier = modifier.widthIn(max = 260.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("Bestes Signal je Feld", "Best signal per tile"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            levelLabels.forEach { (level, label) ->
                val share = (counts[level] ?: 0) * 100 / total
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(signalColorRaw(level).copy(alpha = 0.75f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("$share %", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                tr("Nur eigene Messungen mit genauer Position. Andere Betreiber erscheinen mit einer zweiten SIM.", "Only your own measurements with a precise position. Other operators appear with a second SIM."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Legende der 5G-NSA-Ansicht mit Anteil der Felder je Zustand. */
@Composable
private fun EndcLegend(tiles: List<de.funkstrecke.data.EndcTile>, modifier: Modifier) {
    val total = tiles.size.coerceAtLeast(1)
    val counts = tiles.groupingBy { it.endcRank }.eachCount()
    Surface(
        modifier = modifier.widthIn(max = 280.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("Bester 5G-Zustand je Feld", "Best 5G state per tile"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            endcLabels.forEach { (rank, label) ->
                val share = (counts[rank] ?: 0) * 100 / total
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(endcColor(rank).copy(alpha = 0.75f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("$share %", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                tr("Zustand der verbundenen LTE-Zelle deiner Daten-SIM, nur Messpunkte mit genauer Position.", "State of the serving LTE cell of your data SIM, only measurement points with a precise position."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Legende der Tracking-Area-Karte: TACs mit Farbe und Anteil der Felder, dazu die Bedeutung der Marker. */
@Composable
private fun TrackingLegend(map: de.funkstrecke.data.TrackingAreaMap, colors: Map<Int, Color>, modifier: Modifier) {
    val total = map.tiles.size.coerceAtLeast(1)
    val label = if (map.key.group.contains("LAC")) "LAC" else "TAC"
    Surface(
        modifier = modifier.widthIn(max = 260.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${map.key.group}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            map.areas.take(8).forEach { (area, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background((colors[area] ?: taPalette.last()).copy(alpha = 0.8f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("$label $area", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("${n * 100 / total} %", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (map.areas.size > 8) {
                Text(tr("+ ${map.areas.size - 8} weitere", "+ ${map.areas.size - 8} more"), style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(taBorder),
                )
                Spacer(Modifier.width(8.dp))
                Text(tr("Wechsel der $label unterwegs (antippen für Details)", "$label changes on the way (tap for details)"), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                tr("Linien: Grenze zwischen gemessenen Nachbarfeldern. Nur die verbundene Zelle deiner SIM zählt.", "Lines: border between measured neighbouring tiles. Only your SIM's serving cell counts."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Kleines Symbol wie auf der Karte: gefüllt, halbtransparent oder Ring. */
@Composable
private fun ReliabilityDot(r: Reliability) {
    val color = de.funkstrecke.ui.theme.reliabilityColor(r)
    val shape = androidx.compose.foundation.shape.CircleShape
    Box(
        Modifier
            .size(12.dp)
            .clip(shape)
            .then(
                when (r) {
                    Reliability.RELIABLE -> Modifier.background(color)
                    Reliability.CANDIDATE -> Modifier.background(color.copy(alpha = 0.45f))
                    Reliability.ARTEFACT -> Modifier.border(2.dp, color, shape)
                },
            ),
    )
}
