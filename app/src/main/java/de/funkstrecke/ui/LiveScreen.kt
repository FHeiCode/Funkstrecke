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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
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
import de.funkstrecke.locate.LocationEstimator
import de.funkstrecke.radio.CellRecord
import de.funkstrecke.ui.theme.signalColor
import de.funkstrecke.radio.EndcMode
import de.funkstrecke.radio.EndcSnapshot
import de.funkstrecke.radio.qualityLabel
import de.funkstrecke.radio.signalLabel
import de.funkstrecke.service.Geofencer
import de.funkstrecke.service.HomeStatus
import de.funkstrecke.service.LogState
import de.funkstrecke.service.RecordPhase
import de.funkstrecke.service.ScanService
import de.funkstrecke.util.Busy
import de.funkstrecke.util.CallBehavior
import de.funkstrecke.util.HomeStore
import de.funkstrecke.util.Lang
import de.funkstrecke.util.PauseStore
import de.funkstrecke.util.LocationHelper
import de.funkstrecke.util.ScanPreset
import de.funkstrecke.util.ScanSettings
import de.funkstrecke.util.SettingsStore
import de.funkstrecke.util.formatAgo
import de.funkstrecke.util.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    val liveBusy by vm.liveBusy.collectAsStateWithLifecycle()
    val pausedUntil by PauseStore.pausedUntil.collectAsStateWithLifecycle()
    val livePaused = pausedUntil > System.currentTimeMillis()
    val callBehavior by SettingsStore.callBehavior.collectAsStateWithLifecycle()
    val dataBehavior by SettingsStore.dataBehavior.collectAsStateWithLifecycle()
    val busy = if (log.running) log.busy else liveBusy
    val busyBehavior = when (busy) {
        Busy.CALL -> callBehavior
        Busy.DATA -> dataBehavior
        Busy.NONE -> CallBehavior.NORMAL
    }
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
    LaunchedEffect(hasPermission, log.running, settings.liveIntervalS, settings.forceRefresh, livePaused) {
        if (!hasPermission || log.running || livePaused) return@LaunchedEffect
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
                            live.at?.let { tr("Live-Scan · ${formatAgo(it, now)} · ${live.cells.size} Zellen hörbar", "Live scan · ${formatAgo(it, now)} · ${live.cells.size} cells audible") }
                                ?: tr("Live-Scan", "Live scan"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = tr("Einstellungen", "Settings"))
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
                    pausedUntil = pausedUntil,
                    onPause = { minutes -> PauseStore.pause(context, minutes) },
                    onResume = { PauseStore.resume(context) },
                )
            }
            if (busy != Busy.NONE && busyBehavior != CallBehavior.NORMAL) {
                item(key = "call") {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                busy == Busy.CALL && busyBehavior == CallBehavior.PAUSE ->
                                    tr("Telefonat erkannt: Zell-Scans pausieren, damit das Gespräch nicht gestört wird. Die Anzeige zeigt den letzten Stand.", "Call detected: cell scans are paused so the call is not disturbed. The display shows the last state.")
                                busy == Busy.CALL ->
                                    tr("Telefonat erkannt: Die App liest nur mit (höchstens alle 30 s) und fordert keine neuen Messungen an.", "Call detected: the app only listens (at most every 30 s) and requests no new measurements.")
                                busyBehavior == CallBehavior.PAUSE ->
                                    tr("Starker Datenverkehr: Zell-Scans pausieren, bis die Übertragung nachlässt.", "Heavy data traffic: cell scans are paused until the transfer eases.")
                                else ->
                                    tr("Starker Datenverkehr: Die App liest nur mit und fordert keine neuen Messungen beim Modem an.", "Heavy data traffic: the app only listens and requests no new measurements from the modem.")
                            },
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (live.at == null) {
                item {
                    Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(tr("Scanne Funkzellen …", "Scanning radio cells …"))
                    }
                }
            } else if (live.cells.isEmpty()) {
                item {
                    Text(
                        tr("Keine Zellen hörbar. Prüfe, ob eine SIM aktiv, der Flugmodus aus und der Standort eingeschaltet ist.", "No cells audible. Check that a SIM is active, airplane mode is off and location is on."),
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            live.endc?.let { e ->
                if (e.mode != null || e.anchor != null) {
                    item(key = "endc") { EndcCard(e) }
                }
            }
            if (serving.isNotEmpty()) {
                item { SectionLabel(if (serving.size == 1) tr("Verbundene Zelle", "Serving cell") else tr("Verbundene Zellen", "Serving cells")) }
                items(serving, key = { "s|" + it.key }) { LiveCellCard(it, null) }
            }
            if (neighbours.isNotEmpty()) {
                item { SectionLabel(tr("Nachbarzellen (${neighbours.size})", "Neighbour cells (${neighbours.size})")) }
                items(neighbours, key = { "n|" + it.key + "|" + it.arfcn }) { cell -> LiveCellCard(cell, siteHint(cell, serving)) }
            }
            if (live.cells.isNotEmpty()) {
                item {
                    Text(
                        tr("Das Handy meldet nur Zellen des Netzes seiner SIM. Andere Betreiber erscheinen mit einer zweiten SIM (Dual-SIM).", "The phone only reports cells of its SIM's network. Other operators appear with a second SIM (dual SIM)."),
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
            Text(tr("Standortfreigabe nötig", "Location permission required"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("Android gibt Funkzellen-Daten nur an Apps mit „Genauer Standort“ heraus. ", "Android only provides cell data to apps with \"Precise location\". ") +
                    tr("Die Freigabe für „Telefon“ ist optional und erlaubt das Scannen beider SIMs.", "The \"Phone\" permission is optional and allows scanning both SIMs."),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (asked) {
                Text(
                    tr("Falls kein Dialog erscheint: Einstellungen → Apps → Funkstrecke → Berechtigungen → Standort → „Genauen Standort verwenden“.", "If no dialog appears: Settings → Apps → Funkstrecke → Permissions → Location → \"Use precise location\"."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRequest) { Text(tr("Freigabe erteilen", "Grant permission")) }
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
    pausedUntil: Long,
    onPause: (minutes: Int?) -> Unit,
    onResume: () -> Unit,
) {
    val running = log.running
    val paused = pausedUntil > System.currentTimeMillis()
    var pauseMenu by remember { mutableStateOf(false) }
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
                        when {
                            paused -> tr("Scans pausiert", "Scans paused")
                            running -> tr("Aufzeichnung läuft", "Recording")
                            else -> tr("Aufzeichnung mit GPS", "Recording with GPS")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (paused) {
                            tr("Pausiert ${PauseStore.describe(pausedUntil)}. GPS und Zell-Scans sind aus", "Paused ${PauseStore.describe(pausedUntil)}. GPS and cell scans are off") +
                                (if (homeEnabled) tr(", die Heimatbasis startet nichts.", "; the home base starts nothing.") else ".")
                        } else if (running) {
                            tr("${log.scans} Scans · ${log.measurements} Messwerte", "${log.scans} scans · ${log.measurements} measurements")
                        } else {
                            tr("Bewege dich durch die Umgebung. Jede Position verbessert die Schätzung der Mast-Standorte.", "Move around the area. Every position improves the estimate of the mast locations.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (paused) {
                    Button(onClick = onResume) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Fortsetzen", "Resume"))
                    }
                } else if (running) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Stopp", "Stop"))
                    }
                } else {
                    Button(onClick = onStart) {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start")
                    }
                }
            }
            // Pause anbieten, solange etwas im Hintergrund scannen kann (Aufzeichnung oder Heimatbasis).
            if (!paused && (running || homeEnabled)) {
                Box {
                    OutlinedButton(onClick = { pauseMenu = true }) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (running) tr("Pausieren …", "Pause …") else tr("Automatik pausieren …", "Pause automation …"))
                    }
                    DropdownMenu(expanded = pauseMenu, onDismissRequest = { pauseMenu = false }) {
                        PauseStore.choices.forEach { (label, minutes) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    pauseMenu = false
                                    onPause(minutes)
                                },
                            )
                        }
                    }
                }
            }
            if (running && !paused) PhaseLine(log)
            if (log.running && log.autoStarted) {
                Text(tr("Automatisch gestartet: Heimatbasis verlassen", "Started automatically: left home base"), style = MaterialTheme.typography.labelMedium)
            } else if (!running && homeEnabled) {
                Text(tr("Heimatbasis aktiv: startet automatisch beim Verlassen", "Home base active: starts automatically when leaving"), style = MaterialTheme.typography.labelMedium)
            }
            val mode = ScanPreset.matching(settings)?.label ?: tr("Eigene Einstellung", "Custom setting")
            val rhythm = buildString {
                append(tr("alle ${settings.recordIntervalS} s", "every ${settings.recordIntervalS} s"))
                if (settings.recordMinDistanceM > 0) append(tr(", ab ${settings.recordMinDistanceM} m Bewegung", ", after ${settings.recordMinDistanceM} m of movement"))
            }
            Text(
                tr("$mode: $rhythm · $totalCells Zellen in der Datenbank", "$mode: $rhythm · $totalCells cells in the database"),
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
    val where = match.nodeId?.let { "eNB $it" } ?: tr("die verbundene Zelle", "the serving cell")
    val band = cell.band?.label
    val sameBand = band != null && band == match.band?.label
    return when {
        match.pci != pci -> tr("Gleiche PCI-Gruppe wie $where: vermutlich ein anderer Sektor desselben Standorts", "Same PCI group as $where: probably another sector of the same site")
        // Gleiche PCI, gleicher Träger, ohne eigene Cell-ID: das ist die verbundene Zelle selbst.
        sameBand && cell.arfcn == match.arfcn -> null
        sameBand -> tr("Gleiche PCI wie $where auf weiterem Träger im selben Band: vermutlich derselbe Sektor", "Same PCI as $where on another carrier in the same band: probably the same sector")
        else -> tr("Gleiche PCI wie $where auf anderem Band (${band ?: "?"}): üblich, vermutlich derselbe Sektor", "Same PCI as $where on another band (${band ?: "?"}): common, probably the same sector")
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
                        Text(cell.operatorName ?: tr("Unbekannter Betreiber", "Unknown operator"), style = MaterialTheme.typography.titleSmall)
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
                if (cell.nodeId != null) KeyValue(tr("eNB-Sektor", "eNB sector"), "${cell.nodeId}-${cell.sector}")
                cell.pci?.let { KeyValue(if (cell.rat == "3G") "PSC" else if (cell.rat == "2G") "BSIC" else "PCI", it.toString()) }
                if (supportsPciGroups(cell.rat)) cell.pci?.let { KeyValue("SSS/PSS", "${pciGroup(it)}/${pciPss(it)}") }
                cell.arfcn?.let { KeyValue(if (cell.rat == "4G") "EARFCN" else if (cell.rat == "5G") "NR-ARFCN" else "ARFCN", it.toString()) }
                cell.band?.downlinkMhz?.let { KeyValue("Downlink", String.format(Lang.locale, "%.1f MHz", it)) }
                cell.quality?.let { KeyValue(qualityLabel(cell.rat), "$it dB") }
                cell.sinr?.let { KeyValue("SINR", "$it dB") }
                cell.timingAdvance?.let { ta -> KeyValue("TA", cell.distanceM?.let { "$ta ≈ $it m" } ?: ta.toString()) }
            }
            if (siteHint != null) {
                Text(siteHint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (cell.plmnInferred) {
                Text(
                    tr("* MCC/MNC von der verbundenen Zelle übernommen", "* MCC/MNC taken from the serving cell"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Was die Aufzeichnung gerade tut, mit Uhr seit dem letzten Zustandswechsel. */
@Composable
private fun PhaseLine(log: LogState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val since = log.phaseSince?.let { formatDuration(now - it) } ?: ""
    val acc = log.lastAccuracyM?.let { "±${it.roundToInt()} m" } ?: "?"
    val (title, detail) = when (log.phase) {
        RecordPhase.WAITING -> tr("Warte auf erste Position · $since", "Waiting for first position · $since") to
            (if (log.fused) tr("Google-Standortdienst sucht GPS, WLAN und Mobilfunk.", "Google location service is searching GPS, Wi-Fi and mobile network.") else tr("GPS sucht Satelliten; im Freien geht es schneller.", "GPS is searching for satellites; it is faster outdoors."))
        RecordPhase.MOVING -> tr("In Bewegung · GPS $acc", "Moving · GPS $acc") to tr("Jeder Scan wird als Messpunkt gespeichert.", "Every scan is saved as a measurement point.")
        RecordPhase.STATIONARY -> tr("Stillstand seit $since · GPS $acc", "Stationary for $since · GPS $acc") to
            tr("Zellen werden jede Minute aufgefrischt. Neue Messpunkte gibt es erst wieder nach Bewegung.", "Cells are refreshed every minute. New measurement points only after moving again.")
        RecordPhase.PAUSED -> tr("Pausiert", "Paused") to tr("GPS und Zell-Scans sind aus.", "GPS and cell scans are off.")
        RecordPhase.COARSE -> tr("Ungenaues GPS seit $since · $acc", "Imprecise GPS for $since · $acc") to
            if ((log.lastAccuracyM ?: Float.MAX_VALUE) <= LocationEstimator.MAX_GPS_ERROR) {
                tr("Zellen werden erfasst. Die Messpunkte zählen für Schätzung und Abdeckung, aber schwächer gewichtet.", "Cells are recorded. The measurement points count for estimate and coverage, but with lower weight.")
            } else {
                tr("Zellen werden erfasst. Messpunkte ungenauer als ${LocationEstimator.MAX_GPS_ERROR.toInt()} m fließen nicht in Schätzung und Abdeckung ein.", "Cells are recorded. Measurement points less precise than ${LocationEstimator.MAX_GPS_ERROR.toInt()} m are not used for estimate and coverage.")
            }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

/** EN-DC (5G NSA): LTE-Anker, NR-Zustand und gemessener NR-Zweig. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EndcCard(e: EndcSnapshot) {
    val mode = e.mode
    val accent = when (mode) {
        EndcMode.SA, EndcMode.CONNECTED -> signalColor(4)
        EndcMode.AVAILABLE -> MaterialTheme.colorScheme.primary
        EndcMode.RESTRICTED -> signalColor(1)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("EN-DC", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(10.dp))
                Text(
                    mode?.label ?: tr("Zustand unbekannt", "State unknown"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                e.display?.label?.let {
                    Text(tr("Anzeige: $it", "Display: $it"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                e.anchor?.let { a ->
                    KeyValue(
                        tr("LTE-Anker", "LTE anchor"),
                        listOfNotNull(
                            a.nodeId?.let { "eNB $it-${a.sector}" } ?: a.cellId?.let { "ECI $it" },
                            a.band?.label,
                        ).joinToString(" · ").ifEmpty { "–" },
                    )
                }
                KeyValue(tr("NR-Zustand", "NR state"), e.nrState.label)
                KeyValue(tr("EN-DC möglich", "EN-DC possible"), yesNo(e.enDcAvailable))
                KeyValue(tr("NR angekündigt", "NR announced"), yesNo(e.nrAvailable))
                if (e.dcNrRestricted == true) KeyValue(tr("Gesperrt", "Restricted"), tr("ja", "yes"))
            }
            val legs = e.nrLegs
            if (legs.isNotEmpty()) {
                Text(tr("NR-Zweig", "NR leg"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                legs.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOfNotNull(
                                l.band?.label ?: "NR",
                                l.pci?.let { "PCI $it (${pciGroup(it)}/${pciPss(it)})" },
                                l.arfcn?.let { "ARFCN $it" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            listOfNotNull(l.dbm?.let { "$it dBm" }, l.sinr?.let { "SINR $it dB" }).joinToString(" · ").ifEmpty { "–" },
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            } else if (e.nrRsrp != null) {
                Text(
                    tr("NR-Zweig ohne Zellangaben: SS-RSRP ${e.nrRsrp} dBm", "NR leg without cell details: SS-RSRP ${e.nrRsrp} dBm") +
                        (e.nrRsrq?.let { " · SS-RSRQ $it dB" } ?: "") + (e.nrSinr?.let { " · SS-SINR $it dB" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (!e.serviceStateReadable) {
                Text(
                    tr("Für EN-DC-Details die Freigabe „Telefon“ erteilen (Einstellungen → Apps → Funkstrecke → Berechtigungen).", "Grant the \"Phone\" permission for EN-DC details (Settings → Apps → Funkstrecke → Permissions)."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

private fun yesNo(value: Boolean?): String = when (value) {
    true -> tr("ja", "yes")
    false -> tr("nein", "no")
    null -> "–"
}
