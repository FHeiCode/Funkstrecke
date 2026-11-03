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
import de.funkstrecke.util.PauseStore
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
                                    "Telefonat erkannt: Zell-Scans pausieren, damit das Gespräch nicht gestört wird. Die Anzeige zeigt den letzten Stand."
                                busy == Busy.CALL ->
                                    "Telefonat erkannt: Die App liest nur mit (höchstens alle 30 s) und fordert keine neuen Messungen an."
                                busyBehavior == CallBehavior.PAUSE ->
                                    "Starker Datenverkehr: Zell-Scans pausieren, bis die Übertragung nachlässt."
                                else ->
                                    "Starker Datenverkehr: Die App liest nur mit und fordert keine neuen Messungen beim Modem an."
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
            live.endc?.let { e ->
                if (e.mode != null || e.anchor != null) {
                    item(key = "endc") { EndcCard(e) }
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
                            paused -> "Scans pausiert"
                            running -> "Aufzeichnung läuft"
                            else -> "Aufzeichnung mit GPS"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (paused) {
                            "Pausiert ${PauseStore.describe(pausedUntil)}. GPS und Zell-Scans sind aus" +
                                (if (homeEnabled) ", die Heimatbasis startet nichts." else ".")
                        } else if (running) {
                            "${log.scans} Scans · ${log.measurements} Messwerte"
                        } else {
                            "Bewege dich durch die Umgebung. Jede Position verbessert die Schätzung der Mast-Standorte."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (paused) {
                    Button(onClick = onResume) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Fortsetzen")
                    }
                } else if (running) {
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
            // Pause anbieten, solange etwas im Hintergrund scannen kann (Aufzeichnung oder Heimatbasis).
            if (!paused && (running || homeEnabled)) {
                Box {
                    OutlinedButton(onClick = { pauseMenu = true }) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (running) "Pausieren …" else "Automatik pausieren …")
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
    val band = cell.band?.label
    val sameBand = band != null && band == match.band?.label
    return when {
        match.pci != pci -> "Gleiche PCI-Gruppe wie $where: vermutlich ein anderer Sektor desselben Standorts"
        // Gleiche PCI, gleicher Träger, ohne eigene Cell-ID: das ist die verbundene Zelle selbst.
        sameBand && cell.arfcn == match.arfcn -> null
        sameBand -> "Gleiche PCI wie $where auf weiterem Träger im selben Band: vermutlich derselbe Sektor"
        else -> "Gleiche PCI wie $where auf anderem Band (${band ?: "?"}): üblich, vermutlich derselbe Sektor"
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
                Text(siteHint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        RecordPhase.WAITING -> "Warte auf erste Position · $since" to
            (if (log.fused) "Google-Standortdienst sucht GPS, WLAN und Mobilfunk." else "GPS sucht Satelliten; im Freien geht es schneller.")
        RecordPhase.MOVING -> "In Bewegung · GPS $acc" to "Jeder Scan wird als Messpunkt gespeichert."
        RecordPhase.STATIONARY -> "Stillstand seit $since · GPS $acc" to
            "Zellen werden jede Minute aufgefrischt. Neue Messpunkte gibt es erst wieder nach Bewegung."
        RecordPhase.PAUSED -> "Pausiert" to "GPS und Zell-Scans sind aus."
        RecordPhase.COARSE -> "Kein genaues GPS seit $since · $acc" to
            "Zellen werden weiter erfasst. Messpunkte mit dieser Genauigkeit fließen nicht in die Standortschätzung ein."
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
                    mode?.label ?: "Zustand unbekannt",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                e.display?.label?.let {
                    Text("Anzeige: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                e.anchor?.let { a ->
                    KeyValue(
                        "LTE-Anker",
                        listOfNotNull(
                            a.nodeId?.let { "eNB $it-${a.sector}" } ?: a.cellId?.let { "ECI $it" },
                            a.band?.label,
                        ).joinToString(" · ").ifEmpty { "–" },
                    )
                }
                KeyValue("NR-Zustand", e.nrState.label)
                KeyValue("EN-DC möglich", yesNo(e.enDcAvailable))
                KeyValue("NR angekündigt", yesNo(e.nrAvailable))
                if (e.dcNrRestricted == true) KeyValue("Gesperrt", "ja")
            }
            val legs = e.nrLegs
            if (legs.isNotEmpty()) {
                Text("NR-Zweig", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    "NR-Zweig ohne Zellangaben: SS-RSRP ${e.nrRsrp} dBm" +
                        (e.nrRsrq?.let { " · SS-RSRQ $it dB" } ?: "") + (e.nrSinr?.let { " · SS-SINR $it dB" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (!e.serviceStateReadable) {
                Text(
                    "Für EN-DC-Details die Freigabe „Telefon“ erteilen (Einstellungen → Apps → Funkstrecke → Berechtigungen).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

private fun yesNo(value: Boolean?): String = when (value) {
    true -> "ja"
    false -> "nein"
    null -> "–"
}
