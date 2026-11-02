package de.funkstrecke.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.funkstrecke.data.pciGroup
import de.funkstrecke.data.pciPss
import de.funkstrecke.data.supportsPciGroups
import de.funkstrecke.radio.CellRecord
import de.funkstrecke.radio.qualityLabel
import de.funkstrecke.radio.signalLabel
import de.funkstrecke.service.Geofencer
import de.funkstrecke.service.HomeStatus
import de.funkstrecke.service.LogState
import de.funkstrecke.service.ScanService
import de.funkstrecke.util.HomeStore
import de.funkstrecke.util.LocationHelper
import de.funkstrecke.util.ScanPreset
import de.funkstrecke.util.ScanSettings
import de.funkstrecke.util.SettingsStore
import de.funkstrecke.util.formatAgo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(vm: ScannerViewModel, onSettings: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var hasPermission by remember { mutableStateOf(LocationHelper.hasPermission(context)) }
    var asked by remember { mutableStateOf(false) }
    val ownScan by vm.live.collectAsStateWithLifecycle()
    val serviceScan by ScanService.lastScan.collectAsStateWithLifecycle()
    val log by ScanService.state.collectAsStateWithLifecycle()
    val settings by SettingsStore.flow.collectAsStateWithLifecycle()
    val home by HomeStore.flow.collectAsStateWithLifecycle()
    val homeStatus by Geofencer.status.collectAsStateWithLifecycle()
    val homeEnabled = home.enabled && homeStatus == HomeStatus.Active
    // Während der Aufzeichnung scannt nur der Dienst; die Live-Ansicht zeigt dessen Ergebnis.
    val live = if (log.running && serviceScan.at != null) serviceScan else ownScan
    val totalCells by vm.cellCount.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = LocationHelper.hasPermission(context)
        asked = true
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ScanService.start(context)
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(LocationHelper.permissions)
    }
    LaunchedEffect(hasPermission, log.running, settings.liveIntervalS, settings.forceRefresh) {
        if (!hasPermission || log.running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                vm.refreshLive(settings.forceRefresh)
                now = System.currentTimeMillis()
                delay(settings.liveIntervalS * 1000L)
            }
        }
    }
    // Uhr für „vor x s“, unabhängig vom Scan-Takt
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                now = System.currentTimeMillis()
                delay(2_000)
            }
        }
    }

    fun startLogging() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ScanService.start(context)
        }
    }

    val serving = live.cells.filter { it.registered }
    val neighbours = live.cells.filter { !it.registered }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Funkstrecke")
                        Text(
                            live.at?.let { "Live-Scan · ${formatAgo(it, now)} · ${live.cells.size} Zellen hörbar" }
                                ?: "Live-Scan",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
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
            if (!hasPermission) {
                item {
                    PermissionCard(asked) { permissionLauncher.launch(LocationHelper.permissions) }
                }
                return@LazyColumn
            }
            item {
                LoggingCard(
                    log = log,
                    settings = settings,
                    homeEnabled = homeEnabled,
                    totalCells = totalCells,
                    onStart = { startLogging() },
                    onStop = { ScanService.stop(context) },
                )
            }
            if (live.at == null) {
                item {
                    Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Scanne Funkzellen …")
                    }
                }
            } else if (live.cells.isEmpty()) {
                item {
                    Text(
                        "Keine Zellen hörbar. Prüfe, ob eine SIM aktiv, der Flugmodus aus und der Standort eingeschaltet ist.",
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (serving.isNotEmpty()) {
                item { SectionLabel(if (serving.size == 1) "Verbundene Zelle" else "Verbundene Zellen") }
                items(serving, key = { "s|" + it.key }) { LiveCellCard(it, null) }
            }
            if (neighbours.isNotEmpty()) {
                item { SectionLabel("Nachbarzellen (${neighbours.size})") }
                items(neighbours, key = { "n|" + it.key + "|" + it.arfcn }) { cell -> LiveCellCard(cell, siteHint(cell, serving)) }
            }
            if (live.cells.isNotEmpty()) {
                item {
                    Text(
                        "Das Handy meldet nur Zellen des Netzes seiner SIM. Andere Betreiber erscheinen mit einer zweiten SIM (Dual-SIM).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(asked: Boolean, onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Standortfreigabe nötig", style = MaterialTheme.typography.titleMedium)
            Text(
                "Android gibt Funkzellen-Daten nur an Apps mit „Genauer Standort“ heraus. " +
                    "Die Freigabe für „Telefon“ ist optional und erlaubt das Scannen beider SIMs.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (asked) {
                Text(
                    "Falls kein Dialog erscheint: Einstellungen → Apps → Funkstrecke → Berechtigungen → Standort → „Genauen Standort verwenden“.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRequest) { Text("Freigabe erteilen") }
        }
    }
}

@Composable
private fun LoggingCard(
    log: LogState,
    settings: ScanSettings,
    homeEnabled: Boolean,
    totalCells: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val running = log.running
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (running) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) "Aufzeichnung läuft" else "Aufzeichnung mit GPS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            running && log.waitingForGps -> "Warte auf GPS-Position …"
                            running -> "${log.scans} Scans · ${log.measurements} Messwerte" +
                                (log.lastAccuracyM?.let { " · GPS ±${it.roundToInt()} m" } ?: "")
                            else -> "Bewege dich durch die Umgebung. Jede Position verbessert die Schätzung der Mast-Standorte."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (running) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stopp")
                    }
                } else {
                    Button(onClick = onStart) {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start")
                    }
                }
            }
            if (log.running && log.autoStarted) {
                Text("Automatisch gestartet: Heimatbasis verlassen", style = MaterialTheme.typography.labelMedium)
            } else if (!running && homeEnabled) {
                Text("Heimatbasis aktiv: startet automatisch beim Verlassen", style = MaterialTheme.typography.labelMedium)
            }
            val mode = ScanPreset.matching(settings)?.label ?: "Eigene Einstellung"
            val rhythm = buildString {
                append("alle ${settings.recordIntervalS} s")
                if (settings.recordMinDistanceM > 0) append(", ab ${settings.recordMinDistanceM} m Bewegung")
            }
            Text(
                "$mode: $rhythm · $totalCells Zellen in der Datenbank",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Hinweis, wenn eine Nachbarzelle dieselbe PCI-Gruppe hat wie eine verbundene Zelle. */
private fun siteHint(cell: CellRecord, serving: List<CellRecord>): String? {
    val pci = cell.pci ?: return null
    if (!supportsPciGroups(cell.rat)) return null
    val match = serving.firstOrNull { s ->
        s.rat == cell.rat && s.mcc == cell.mcc && s.mnc == cell.mnc && s.pci != null && pciGroup(s.pci) == pciGroup(pci)
    } ?: return null
    val where = match.nodeId?.let { "eNB $it" } ?: "die verbundene Zelle"
    return if (match.pci == pci) {
        "Gleiche PCI wie $where: vermutlich derselbe Sektor auf einem anderen Träger"
    } else {
        "Gleiche PCI-Gruppe wie $where: vermutlich ein anderer Sektor desselben Standorts"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveCellCard(cell: CellRecord, siteHint: String?) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RatBadge(cell.rat)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OperatorDot(cell.operatorName)
                        Spacer(Modifier.width(6.dp))
                        Text(cell.operatorName ?: "Unbekannter Betreiber", style = MaterialTheme.typography.titleSmall)
                    }
                    val sub = listOfNotNull(
                        cell.plmn?.let { if (cell.plmnInferred) "$it*" else it },
                        cell.band?.label,
                        cell.band?.mhz?.takeIf { it.isNotEmpty() }?.let { "$it MHz" },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    SignalBars(cell.rat, cell.dbm)
                    Text(
                        cell.dbm?.let { "$it dBm" } ?: "–",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(signalLabel(cell.rat), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cell.area?.let { KeyValue(if (cell.rat == "4G" || cell.rat == "5G") "TAC" else "LAC", it.toString()) }
                cell.cellId?.let { KeyValue(if (cell.rat == "5G") "NCI" else if (cell.rat == "4G") "ECI" else "CID", it.toString()) }
                if (cell.nodeId != null) KeyValue("eNB-Sektor", "${cell.nodeId}-${cell.sector}")
                cell.pci?.let { KeyValue(if (cell.rat == "3G") "PSC" else if (cell.rat == "2G") "BSIC" else "PCI", it.toString()) }
                if (supportsPciGroups(cell.rat)) cell.pci?.let { KeyValue("SSS/PSS", "${pciGroup(it)}/${pciPss(it)}") }
                cell.arfcn?.let { KeyValue(if (cell.rat == "4G") "EARFCN" else if (cell.rat == "5G") "NR-ARFCN" else "ARFCN", it.toString()) }
                cell.band?.downlinkMhz?.let { KeyValue("Downlink", String.format(Locale.GERMANY, "%.1f MHz", it)) }
                cell.quality?.let { KeyValue(qualityLabel(cell.rat), "$it dB") }
                cell.sinr?.let { KeyValue("SINR", "$it dB") }
                cell.timingAdvance?.let { ta -> KeyValue("TA", cell.distanceM?.let { "$ta ≈ $it m" } ?: ta.toString()) }
            }
            if (siteHint != null) {
                Text(siteHint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            if (cell.plmnInferred) {
                Text(
                    "* MCC/MNC von der verbundenen Zelle übernommen",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
