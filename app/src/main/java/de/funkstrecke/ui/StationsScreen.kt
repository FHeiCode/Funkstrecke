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
import de.funkstrecke.util.tr

private val UNKNOWN_OPERATOR: String get() = tr("Unbekannt", "Unknown")

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
    val osmProgress by vm.osmProgress.collectAsStateWithLifecycle()
    val suspectGroups by vm.suspectGroups.collectAsStateWithLifecycle()
    var osmBulk by rememberSaveable { mutableStateOf(false) }

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
    val ocidImplausible = remember(filtered) { filtered.count { it.ocidPosition == null && it.ocidImplausible.isNotEmpty() } }
    val ocidNotFound = remember(filtered) { filtered.count { it.ocidUnknown } }
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
                        Text(tr("Basisstationen", "Base stations"))
                        Text(
                            tr("${stations.size} Stationen · $cellCount Zellen · $measurementCount Messwerte", "${stations.size} stations · $cellCount cells · $measurementCount measurements"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = tr("Mehr", "More")) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(tr("Zellen als CSV exportieren", "Export cells as CSV")) },
                            enabled = cellCount > 0,
                            onClick = { menuOpen = false; vm.exportCells() },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Messprotokoll als CSV exportieren", "Export measurement log as CSV")) },
                            enabled = measurementCount > 0,
                            onClick = { menuOpen = false; vm.exportMeasurements() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (ocidKey.isBlank()) tr("OpenCelliD: Schlüssel in den Einstellungen eintragen", "OpenCelliD: enter key in settings") else tr("Mit OpenCelliD abgleichen", "Compare with OpenCelliD"))
                            },
                            enabled = ocidKey.isNotBlank() && cellCount > 0 && ocidProgress?.running != true,
                            onClick = { menuOpen = false; vm.compareWithOpenCellId() },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Mit OpenStreetMap-Masten abgleichen", "Compare with OpenStreetMap masts")) },
                            enabled = stations.any { it.estimate != null } && osmProgress?.running != true,
                            onClick = { menuOpen = false; osmBulk = true; vm.loadOsmForAllStations() },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Alle Daten löschen", "Delete all data"), color = MaterialTheme.colorScheme.error) },
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
                        Text(tr("Noch keine Zellen erfasst", "No cells recorded yet"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            tr("Öffne den Live-Scan. Jede gehörte Zelle landet automatisch hier, gruppiert nach Basisstation.", "Open the live scan. Every cell heard ends up here automatically, grouped by base station."),
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
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = tr("Suche leeren", "Clear search")) }
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
                    OcidHistogramCard(deviations, ocidNotFound, ocidImplausible, ocidBin, onSelectBin = { ocidBin = it })
                }
            }
            ocidProgress?.let { p ->
                item(key = "ocid") { OcidBanner(p, onCancel = { vm.cancelOpenCellId() }) }
            }
            osmProgress?.takeIf { osmBulk }?.let { p ->
                item(key = "osm") {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                p.running -> tr("OpenStreetMap: lade Gebiet ${p.done + 1} von ${p.total} …", "OpenStreetMap: loading area ${p.done + 1} of ${p.total} …")
                                p.error != null -> tr("OpenStreetMap gestoppt: ${p.error}", "OpenStreetMap stopped: ${p.error}")
                                p.total == 0 -> tr("OpenStreetMap: alle Gebiete sind schon geladen (gilt 30 Tage).", "OpenStreetMap: all areas already loaded (valid for 30 days).")
                                else -> tr("OpenStreetMap fertig: ${p.masts} Masten in ${p.total} Gebieten.", "OpenStreetMap done: ${p.masts} masts in ${p.total} areas.")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        tr("Keine Station passt zu Suche oder Filter.", "No station matches search or filter."),
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shown, key = { it.key }) { station -> StationRow(station, suspect = station.cells.any { it.groupKey in suspectGroups }) { onOpen(station.key) } }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Alle Daten löschen?", "Delete all data?")) },
            text = { Text(tr("Alle Zellen, Messwerte und Standortschätzungen werden endgültig gelöscht. Exportiere sie vorher, wenn du sie noch brauchst.", "All cells, measurements and location estimates will be deleted permanently. Export them first if you still need them.")) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearAll() }) {
                    Text(tr("Löschen", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Abbrechen", "Cancel")) } },
        )
    }
}

@Composable
private fun StationRow(station: BaseStation, suspect: Boolean, onClick: () -> Unit) {
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
                    if (suspect) {
                        Spacer(Modifier.width(6.dp))
                        SuspectPill()
                    }
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
                    if (station.cells.size > 1) tr("${station.cells.size} Zellen", "${station.cells.size} cells") else null,
                    if (station.linked.isNotEmpty()) tr("+${station.linked.size} vermutet", "+${station.linked.size} presumed") else null,
                    when {
                        station.endcConnected -> tr("5G-Anker", "5G anchor") + (station.nrBands.takeIf { it.isNotEmpty() }?.joinToString(",", " (", ")") ?: "")
                        station.endcAvailable -> tr("5G NSA verfügbar", "5G NSA available")
                        else -> null
                    },
                    if (station.inferred) tr("vermutete Station", "presumed station") else null,
                    station.bands.joinToString(", ").ifEmpty { null },
                    station.estimate?.let { tr("Standort ±${formatDistance(it.radiusM)}", "location ±${formatDistance(it.radiusM)}") } ?: tr("kein Standort", "no location"),
                ).joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val ocidDistance = station.ocidDistanceM
                when {
                    station.ocidUnknown -> Text(
                        tr("Nicht in OpenCelliD", "Not in OpenCelliD"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    ocidDistance != null -> Text(
                        tr("OpenCelliD: ${formatDistance(ocidDistance)} von deiner Schätzung", "OpenCelliD: ${formatDistance(ocidDistance)} from your estimate"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    station.ocidPosition != null -> Text(
                        tr("In OpenCelliD bekannt", "Known in OpenCelliD"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    station.ocidImplausible.isNotEmpty() -> Text(
                        tr("OpenCelliD unplausibel", "OpenCelliD implausible"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (station.pciConflicts.isNotEmpty()) {
                    val c = station.pciConflicts.first()
                    Text(
                        tr("PCI-Konflikt: PCI ${c.cell.pci} auch bei ${c.otherTitle} (${formatDistance(c.distanceM)})", "PCI conflict: PCI ${c.cell.pci} also at ${c.otherTitle} (${formatDistance(c.distanceM)})"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    tr("zuletzt ${formatAgo(station.lastSeen)}", "last ${formatAgo(station.lastSeen)}"),
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
                    p.running -> tr("OpenCelliD-Abgleich: ${p.done} von ${p.total} Zellen · ${p.found} bekannt, ${p.notFound} unbekannt", "OpenCelliD comparison: ${p.done} of ${p.total} cells · ${p.found} known, ${p.notFound} unknown")
                    p.stopReason != null -> tr("OpenCelliD-Abgleich gestoppt: ${p.stopReason}. ${p.found} bekannt, ${p.notFound} unbekannt.", "OpenCelliD comparison stopped: ${p.stopReason}. ${p.found} known, ${p.notFound} unknown.")
                    p.total == 0 -> tr("OpenCelliD-Abgleich: alle Zellen sind bereits abgeglichen (gilt 30 Tage).", "OpenCelliD comparison: all cells already compared (valid for 30 days).")
                    else -> tr("OpenCelliD-Abgleich fertig: ${p.found} bekannt, ${p.notFound} nicht in OpenCelliD.", "OpenCelliD comparison done: ${p.found} known, ${p.notFound} not in OpenCelliD.")
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (p.running) TextButton(onClick = onCancel) { Text(tr("Abbrechen", "Cancel")) }
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
            Text(tr("Bewertung der Stationen", "Station rating"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                tr("Zuverlässig: Standort genauer als ±200 m, höchstens 200 m von OpenCelliD oder 100 m von einem eindeutigen OSM-Mast (kein anderer Maststandort im Umkreis von 300 m) entfernt. ", "Reliable: location better than ±200 m, at most 200 m from OpenCelliD or 100 m from an unambiguous OSM mast (no other mast site within 300 m). ") +
                    tr("Kandidat: genauer als ±1 km. Übrige: mögliche Artefakte (kein oder sehr unsicherer Standort).", "Candidate: better than ±1 km. Others: potential artefacts (no or very uncertain location)."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
