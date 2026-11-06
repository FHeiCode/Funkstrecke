package de.funkstrecke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.funkstrecke.data.BaseStation
import de.funkstrecke.ui.theme.signalColor
import de.funkstrecke.ui.theme.signalLevel
import de.funkstrecke.util.formatAgo
import de.funkstrecke.data.Reliability
import de.funkstrecke.util.SettingsStore
import de.funkstrecke.util.formatDistance

private const val UNKNOWN_OPERATOR = "Unbekannt"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StationsScreen(vm: ScannerViewModel, onOpen: (String) -> Unit) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val cellCount by vm.cellCount.collectAsStateWithLifecycle()
    val measurementCount by vm.measurementCount.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var operatorFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var ratFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val ocidKey by SettingsStore.openCellIdKey.collectAsStateWithLifecycle()
    val ocidProgress by vm.ocidProgress.collectAsStateWithLifecycle()

    val operators = remember(stations) { stations.map { it.operatorName ?: UNKNOWN_OPERATOR }.distinct().sorted() }
    val rats = remember(stations) { stations.map { it.rat }.distinct().sorted() }
    // Betreiber-/Technikfilter gelten auch für das OpenCelliD-Histogramm.
    val filtered = remember(stations, operatorFilter, ratFilter) {
        stations.filter { s ->
            (operatorFilter == null || (s.operatorName ?: UNKNOWN_OPERATOR) == operatorFilter) &&
                (ratFilter == null || s.rat == ratFilter)
        }
    }
    val deviations = remember(filtered) { ocidDeviations(filtered) }
    val ocidNotFound = remember(filtered) { filtered.sumOf { s -> s.ocid.count { it.status == de.funkstrecke.data.OcidEntity.STATUS_NOT_FOUND } } }
    var ocidBin by rememberSaveable { mutableStateOf<Int?>(null) }
    var reliabilityFilter by rememberSaveable { mutableStateOf<Reliability?>(null) }
    val reliabilityCounts = remember(filtered) { filtered.groupingBy { it.assessment.reliability }.eachCount() }
    val binStations = remember(deviations, ocidBin) {
        ocidBin?.let { b -> deviations.filter { ocidBinOf(it.distanceM) == b }.map { it.stationKey }.toSet() }
    }
    val shown = remember(filtered, query, binStations, reliabilityFilter) {
        val q = query.trim().lowercase()
        filtered.filter { s ->
            (binStations == null || s.key in binStations) &&
                (reliabilityFilter == null || s.assessment.reliability == reliabilityFilter) &&
                (
                    q.isEmpty() || s.title.lowercase().contains(q) ||
                        (s.plmn ?: "").contains(q) ||
                        s.area?.toString()?.contains(q) == true ||
                        s.cells.any { c -> c.cellId?.toString()?.contains(q) == true || c.pci?.toString() == q } ||
                        s.linked.any { l -> l.cell.pci?.toString() == q }
                    )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Basisstationen")
                        Text(
                            "${stations.size} Stationen · $cellCount Zellen · $measurementCount Messwerte",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mehr") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Zellen als CSV exportieren") },
                            enabled = cellCount > 0,
                            onClick = { menuOpen = false; vm.exportCells() },
                        )
                        DropdownMenuItem(
                            text = { Text("Messprotokoll als CSV exportieren") },
                            enabled = measurementCount > 0,
                            onClick = { menuOpen = false; vm.exportMeasurements() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (ocidKey.isBlank()) "OpenCelliD: Schlüssel in den Einstellungen eintragen" else "Mit OpenCelliD abgleichen")
                            },
                            enabled = ocidKey.isNotBlank() && cellCount > 0 && ocidProgress?.running != true,
                            onClick = { menuOpen = false; vm.compareWithOpenCellId() },
                        )
                        DropdownMenuItem(
                            text = { Text("Alle Daten löschen", color = MaterialTheme.colorScheme.error) },
                            enabled = cellCount > 0,
                            onClick = { menuOpen = false; confirmClear = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (stations.isEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Noch keine Zellen erfasst", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Öffne den Live-Scan. Jede gehörte Zelle landet automatisch hier, gruppiert nach Basisstation.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@LazyColumn
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("eNB, Cell-ID, PCI, TAC, MCC-MNC …") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Suche leeren") }
                        }
                    },
                    singleLine = true,
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    operators.forEach { op ->
                        FilterChip(
                            selected = operatorFilter == op,
                            onClick = { operatorFilter = if (operatorFilter == op) null else op },
                            label = { Text(op) },
                            leadingIcon = { OperatorDot(if (op == UNKNOWN_OPERATOR) null else op) },
                        )
                    }
                    rats.forEach { rat ->
                        FilterChip(
                            selected = ratFilter == rat,
                            onClick = { ratFilter = if (ratFilter == rat) null else rat },
                            label = { Text(rat) },
                        )
                    }
                }
            }
            item(key = "reliability") {
                ReliabilitySummary(reliabilityCounts, reliabilityFilter, onSelect = { reliabilityFilter = it })
            }
            if (deviations.isNotEmpty() || ocidNotFound > 0) {
                item(key = "ocid-histogram") {
                    OcidHistogramCard(deviations, ocidNotFound, ocidBin, onSelectBin = { ocidBin = it })
                }
            }
            ocidProgress?.let { p ->
                item(key = "ocid") { OcidBanner(p, onCancel = { vm.cancelOpenCellId() }) }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        "Keine Station passt zu Suche oder Filter.",
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shown, key = { it.key }) { station -> StationRow(station) { onOpen(station.key) } }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Alle Daten löschen?") },
            text = { Text("Alle Zellen, Messwerte und Standortschätzungen werden endgültig gelöscht. Exportiere sie vorher, wenn du sie noch brauchst.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearAll() }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun StationRow(station: BaseStation, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RatBadge(station.rat)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val assessment = station.assessment
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        station.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    ReliabilityPill(assessment.reliability)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OperatorDot(station.operatorName)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOfNotNull(
                            station.operatorName ?: UNKNOWN_OPERATOR,
                            station.plmn,
                            station.area?.let { "TAC $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val detail = listOfNotNull(
                    if (station.cells.size > 1) "${station.cells.size} Zellen" else null,
                    if (station.linked.isNotEmpty()) "+${station.linked.size} vermutet" else null,
                    when {
                        station.endcConnected -> "5G-Anker" + (station.nrBands.takeIf { it.isNotEmpty() }?.joinToString(",", " (", ")") ?: "")
                        station.endcAvailable -> "5G NSA verfügbar"
                        else -> null
                    },
                    if (station.inferred) "vermutete Station" else null,
                    station.bands.joinToString(", ").ifEmpty { null },
                    station.estimate?.let { "Standort ±${formatDistance(it.radiusM)}" } ?: "kein Standort",
                ).joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val ocidDistance = station.ocidDistanceM
                when {
                    station.ocidUnknown -> Text(
                        "Nicht in OpenCelliD",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    ocidDistance != null -> Text(
                        "OpenCelliD: ${formatDistance(ocidDistance)} von deiner Schätzung",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    station.ocidPosition != null -> Text(
                        "In OpenCelliD bekannt",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (station.pciConflicts.isNotEmpty()) {
                    val c = station.pciConflicts.first()
                    Text(
                        "PCI-Konflikt: PCI ${c.cell.pci} auch bei ${c.otherTitle} (${formatDistance(c.distanceM)})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "zuletzt ${formatAgo(station.lastSeen)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                SignalBars(station.rat, station.bestDbm)
                Text(
                    station.bestDbm?.let { "$it" } ?: "–",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = signalColor(signalLevel(station.rat, station.bestDbm)),
                )
                Text("max dBm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Fortschritt und Ergebnis des OpenCelliD-Abgleichs. */
@Composable
private fun OcidBanner(p: de.funkstrecke.data.OcidProgress, onCancel: () -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    p.running -> "OpenCelliD-Abgleich: ${p.done} von ${p.total} Zellen · ${p.found} bekannt, ${p.notFound} unbekannt"
                    p.stopReason != null -> "OpenCelliD-Abgleich gestoppt: ${p.stopReason}. ${p.found} bekannt, ${p.notFound} unbekannt."
                    p.total == 0 -> "OpenCelliD-Abgleich: alle Zellen sind bereits abgeglichen (gilt 30 Tage)."
                    else -> "OpenCelliD-Abgleich fertig: ${p.found} bekannt, ${p.notFound} nicht in OpenCelliD."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (p.running) TextButton(onClick = onCancel) { Text("Abbrechen") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
/**
 * Übersicht der Bewertungen als gestapelter Balken mit Zählern. Die Zähler sind zugleich Filter:
 * Antippen zeigt nur Stationen dieser Bewertung, erneutes Antippen hebt den Filter auf.
 */
@Composable
private fun ReliabilitySummary(counts: Map<Reliability, Int>, selected: Reliability?, onSelect: (Reliability?) -> Unit) {
    val total = counts.values.sum()
    if (total == 0) return
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Bewertung der Stationen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)),
            ) {
                Reliability.entries.forEach { r ->
                    val n = counts[r] ?: 0
                    if (n > 0) {
                        Box(
                            Modifier
                                .weight(n.toFloat())
                                .fillMaxHeight()
                                .background(
                                    de.funkstrecke.ui.theme.reliabilityColor(r)
                                        .copy(alpha = if (selected == null || selected == r) 1f else 0.3f),
                                ),
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Reliability.entries.forEach { r ->
                    val n = counts[r] ?: 0
                    FilterChip(
                        selected = selected == r,
                        enabled = n > 0 || selected == r,
                        onClick = { onSelect(if (selected == r) null else r) },
                        label = { Text("${r.label} $n") },
                        leadingIcon = {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(de.funkstrecke.ui.theme.reliabilityColor(r)),
                            )
                        },
                    )
                }
            }
            Text(
                "Zuverlässig: Standort genauer als ±200 m oder höchstens 200 m von OpenCelliD entfernt. " +
                    "Kandidat: genauer als ±1 km. Übrige: mögliche Artefakte (kein oder sehr unsicherer Standort).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
