package de.funkstrecke.ui

import androidx.compose.foundation.background
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
import de.funkstrecke.data.coverageFor
import de.funkstrecke.ui.theme.operatorColorRaw
import de.funkstrecke.ui.theme.signalColorRaw
import de.funkstrecke.util.HomeStore
import de.funkstrecke.util.LocationHelper
import de.funkstrecke.util.formatDistance

private enum class MapMode(val label: String) { STATIONS("Stationen"), COVERAGE("Abdeckung") }

private enum class CoverageView(val label: String) { SIGNAL("Signalstärke"), ENDC("5G NSA (EN-DC)") }

/** Farben der 5G-Ansicht je EndcMode-Rang */
private fun endcColor(rank: Int): Color = when (rank) {
    4 -> Color(0xFF00695C) // 5G SA
    3 -> Color(0xFF237A4B) // EN-DC verbunden
    2 -> Color(0xFF2F6FB0) // verfügbar
    1 -> Color(0xFFC4561C) // gesperrt
    else -> Color(0xFF7A8690) // kein 5G
}

private val endcLabels = listOf(
    4 to "5G SA",
    3 to "5G NSA aktiv (EN-DC verbunden)",
    2 to "5G NSA verfügbar, nicht verbunden",
    1 to "5G gesperrt",
    0 to "kein 5G",
)

/** Bezeichnung der Signalstufen; Schwellen für 4G/5G (RSRP) in Klammern. */
private val levelLabels = listOf(
    4 to "sehr gut (≥ −80 dBm)",
    3 to "gut (−80 … −90)",
    2 to "mittel (−90 … −100)",
    1 to "schwach (−100 … −110)",
    0 to "sehr schwach (< −110)",
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

    // Stationen: Kreise nur, wenn sie aussagekräftig sind; sehr grobe Schätzungen würden die Karte zudecken.
    val circles = remember(located) {
        located.mapNotNull { s ->
            val e = s.estimate!!
            if (e.radiusM > 2_000) null else MapCircle(e.latitude, e.longitude, e.radiusM, operatorColorRaw(s.operatorName))
        }
    }
    val dots = remember(located) {
        located.map { s ->
            val e = s.estimate!!
            MapDot(
                latitude = e.latitude,
                longitude = e.longitude,
                color = operatorColorRaw(s.operatorName),
                sizeDp = 16,
                title = s.title,
                snippet = "${s.operatorName ?: "Unbekannt"} · ±${formatDistance(e.radiusM)}",
                onClick = { onOpen(s.key) },
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Karte")
                        Text(
                            when (mode) {
                                MapMode.STATIONS -> "${located.size} von ${stations.size} Stationen verortet"
                                MapMode.COVERAGE -> if (operator == null) "Noch keine Abdeckungsdaten" else
                                    "$operator · ${if (coverageView == CoverageView.ENDC) endcCells.size else cells.size} Felder à ~50 m"
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
                dots = if (mode == MapMode.STATIONS) dots else emptyList(),
                circles = homeCircle + if (mode == MapMode.STATIONS) circles else emptyList(),
                coverage = if (mode == MapMode.COVERAGE) rects else emptyList(),
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
                                leadingIcon = { OperatorDot(op.takeIf { it != "Unbekannt" }) },
                            )
                        }
                    }
                    if (coverageView == CoverageView.SIGNAL && rats.size > 1) {
                        ChipBar {
                            FilterChip(selected = ratFilter == null, onClick = { ratFilter = null }, label = { Text("Beste Technik") })
                            rats.forEach { r ->
                                FilterChip(selected = ratFilter == r, onClick = { ratFilter = r }, label = { Text(r) })
                            }
                        }
                    }
                }
            }

            if (mode == MapMode.COVERAGE) {
                if (operators.isEmpty()) {
                    InfoBubble(
                        if (coverageView == CoverageView.ENDC) {
                            "Noch keine 5G-Daten. Sie entstehen bei der Aufzeichnung ab dieser Version, sobald das Handy " +
                                "mit LTE verbunden ist und eine genaue Position hat."
                        } else {
                            "Noch keine Abdeckungsdaten. Starte im Live-Tab die Aufzeichnung: Jeder Messpunkt mit genauer " +
                                "Position färbt ein Feld von etwa 50 × 50 m."
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
                    "Noch keine Station verortet. Starte im Live-Tab die Aufzeichnung und bewege dich durch die Umgebung.",
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
                    Icon(Icons.Filled.MyLocation, contentDescription = "Auf meine Position zentrieren")
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
            Text("Bestes Signal je Feld", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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
                "Nur eigene Messungen mit genauer Position. Andere Betreiber erscheinen mit einer zweiten SIM.",
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
            Text("Bester 5G-Zustand je Feld", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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
                "Zustand der verbundenen LTE-Zelle deiner Daten-SIM, nur Messpunkte mit genauer Position.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
