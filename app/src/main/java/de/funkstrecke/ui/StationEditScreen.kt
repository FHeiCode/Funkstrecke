package de.funkstrecke.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.funkstrecke.data.Catalog
import de.funkstrecke.data.StationForm
import de.funkstrecke.data.addToken
import de.funkstrecke.data.formatCoordinate
import de.funkstrecke.data.parseDecimal
import de.funkstrecke.data.toForm
import de.funkstrecke.data.toStation
import de.funkstrecke.data.toggle
import de.funkstrecke.data.withCoordinateInput
import de.funkstrecke.radio.CellRecord
import de.funkstrecke.radio.CellScanner
import de.funkstrecke.util.LocationHelper
import de.funkstrecke.util.Photos
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private enum class PendingAction { LOCATE, SCAN_CELLS }

/** Übernimmt Werte einer gescannten Zelle in das Formular, ohne Vorhandenes zu überschreiben. */
private fun StationForm.applyCell(c: CellRecord): StationForm = copy(
    technologies = if (c.tech in Catalog.technologies) (technologies + c.tech).distinct() else technologies,
    bands = c.band?.mhz?.takeIf { it.isNotEmpty() }?.let { (bands + it).distinct() } ?: bands,
    operator = operator.ifBlank { c.operatorName ?: "" },
    mcc = mcc.ifBlank { c.mcc ?: "" },
    mnc = mnc.ifBlank { c.mnc ?: "" },
    tac = tac.ifBlank { c.area?.toString() ?: "" },
    enbId = if (c.enbId != null) addToken(enbId, c.enbId) else enbId,
    cellIds = addToken(cellIds, c.cellId),
    pcis = addToken(pcis, c.pci),
)

private fun openInMaps(context: Context, lat: Double, lon: Double, label: String) {
    val la = String.format(Locale.US, "%.6f", lat)
    val lo = String.format(Locale.US, "%.6f", lon)
    val uri = Uri.parse("geo:$la,$lo?q=$la,$lo(${Uri.encode(label.ifBlank { "Standort" })})")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StationEditScreen(vm: StationsViewModel, stationId: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var form by rememberSaveable { mutableStateOf<StationForm?>(null) }
    var initial by rememberSaveable { mutableStateOf<StationForm?>(null) }

    LaunchedEffect(stationId) {
        if (form == null) {
            val start = (if (stationId > 0) vm.load(stationId)?.toForm() else null) ?: StationForm()
            form = start
            initial = start
        }
    }

    val current = form
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    fun edit(change: (StationForm) -> StationForm) {
        form = form?.let(change)
    }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    var locating by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var cells by remember { mutableStateOf<List<CellRecord>>(emptyList()) }
    var showCells by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var photoToRemove by remember { mutableStateOf<String?>(null) }

    fun locate() {
        locating = true
        scope.launch {
            val location = LocationHelper.currentLocation(context)
            locating = false
            if (location != null) {
                edit {
                    it.copy(
                        lat = formatCoordinate(location.latitude),
                        lon = formatCoordinate(location.longitude),
                        accuracy = if (location.hasAccuracy()) location.accuracy.roundToInt().toString() else "",
                    )
                }
            } else {
                message("Keine Position ermittelt. Standort einschalten und unter freiem Himmel erneut versuchen.")
            }
        }
    }

    fun scanCells() {
        showCells = true
        scanning = true
        scope.launch {
            cells = CellScanner.scan(context)
            scanning = false
        }
    }

    fun perform(action: PendingAction?) {
        when (action) {
            PendingAction.LOCATE -> locate()
            PendingAction.SCAN_CELLS -> scanCells()
            null -> Unit
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val action = pendingAction
        pendingAction = null
        if (result.values.any { it }) {
            perform(action)
        } else {
            message("Ohne Standortfreigabe funktionieren GPS und Zell-Scan nicht.")
        }
    }

    fun withLocationPermission(action: PendingAction) {
        if (LocationHelper.hasPermission(context)) {
            perform(action)
        } else {
            pendingAction = action
            permissionLauncher.launch(LocationHelper.permissions)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val name = pendingPhoto
        pendingPhoto = null
        if (name != null) {
            if (ok) edit { it.copy(photos = it.photos + name) } else Photos.delete(context, name)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = Photos.importFrom(context, uri)
                if (name != null) edit { it.copy(photos = it.photos + name) } else message("Foto konnte nicht übernommen werden.")
            }
        }
    }

    fun takePhoto() {
        val file = Photos.newFile(context)
        pendingPhoto = file.name
        try {
            cameraLauncher.launch(Photos.uri(context, file))
        } catch (e: ActivityNotFoundException) {
            pendingPhoto = null
            message("Keine Kamera-App gefunden.")
        }
    }

    val dirty = form != initial

    fun leave(save: Boolean) {
        val f = form ?: return
        val start = initial ?: StationForm()
        if (save) {
            if (f.name.isBlank() && f.siteId.isBlank()) {
                message("Bitte einen Namen oder eine Standort-ID eingeben.")
                return
            }
            (start.photos - f.photos.toSet()).forEach { Photos.delete(context, it) }
            vm.save(f.toStation(System.currentTimeMillis()))
        } else {
            (f.photos - start.photos.toSet()).forEach { Photos.delete(context, it) }
        }
        onClose()
    }

    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (stationId > 0) current.name.ifBlank { current.siteId.ifBlank { "Standort" } } else "Neuer Standort",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmDiscard = true else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (stationId > 0) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Standort löschen")
                        }
                    }
                    TextButton(onClick = { leave(save = true) }) { Text("Speichern") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Section("Stammdaten") {
                TextInput("Name / Bezeichnung", current.name) { v -> edit { it.copy(name = v) } }
                TextInput("Standort-ID", current.siteId, mono = true, placeholder = "z. B. NUE-0421") { v ->
                    edit { it.copy(siteId = v) }
                }
                SelectField("Betreiber", current.operator, Catalog.operators) { v -> edit { it.copy(operator = v) } }
                SelectField("Status", current.status, Catalog.statuses) { v -> edit { it.copy(status = v) } }
            }

            Section("Standort") {
                TextInput("Adresse / Lagebeschreibung", current.address) { v -> edit { it.copy(address = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextInput(
                        "Breite (Lat)", current.lat, Modifier.weight(1f),
                        mono = true, keyboard = KeyboardType.Decimal,
                    ) { v -> edit { it.withCoordinateInput(v, isLatitude = true) } }
                    TextInput(
                        "Länge (Lon)", current.lon, Modifier.weight(1f),
                        mono = true, keyboard = KeyboardType.Decimal,
                    ) { v -> edit { it.withCoordinateInput(v, isLatitude = false) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { withLocationPermission(PendingAction.LOCATE) }, enabled = !locating) {
                        if (locating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                        } else {
                            Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (locating) "Suche Position …" else "GPS-Position übernehmen")
                    }
                    if (current.accuracy.isNotBlank()) {
                        Text(
                            "± ${current.accuracy} m",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val lat = parseDecimal(current.lat)
                val lon = parseDecimal(current.lon)
                if (lat != null && lon != null) {
                    TextButton(onClick = { openInMaps(context, lat, lon, current.name) }) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("In Karten-App zeigen")
                    }
                }
            }

            Section("Funktechnik") {
                OutlinedButton(
                    onClick = { withLocationPermission(PendingAction.SCAN_CELLS) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Funkzellen am Standort scannen")
                }
                FieldLabel("Technologie")
                ChipGroup(Catalog.technologies, current.technologies) { v ->
                    edit { it.copy(technologies = it.technologies.toggle(v)) }
                }
                FieldLabel("Frequenzbänder")
                ChipGroup(Catalog.bands, current.bands, suffix = " MHz") { v ->
                    edit { it.copy(bands = it.bands.toggle(v)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextInput("MCC", current.mcc, Modifier.weight(1f), mono = true, keyboard = KeyboardType.Number) { v ->
                        edit { it.copy(mcc = v) }
                    }
                    TextInput("MNC", current.mnc, Modifier.weight(1f), mono = true, keyboard = KeyboardType.Number) { v ->
                        edit { it.copy(mnc = v) }
                    }
                    TextInput("TAC / LAC", current.tac, Modifier.weight(1.4f), mono = true, keyboard = KeyboardType.Number) { v ->
                        edit { it.copy(tac = v) }
                    }
                }
                TextInput("eNodeB-ID / gNodeB-ID", current.enbId, mono = true, keyboard = KeyboardType.Number) { v ->
                    edit { it.copy(enbId = v) }
                }
                TextInput("Cell-IDs (ECI / NCI)", current.cellIds, mono = true, supporting = "Mehrere Werte mit Komma trennen") { v ->
                    edit { it.copy(cellIds = v) }
                }
                TextInput("PCI", current.pcis, mono = true) { v -> edit { it.copy(pcis = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextInput("Sektoren", current.sectors, Modifier.weight(1f), keyboard = KeyboardType.Number) { v ->
                        edit { it.copy(sectors = v) }
                    }
                    TextInput("Azimute", current.azimuths, Modifier.weight(2f), mono = true, suffix = "°", placeholder = "0, 120, 240") { v ->
                        edit { it.copy(azimuths = v) }
                    }
                }
            }

            Section("Bauliches") {
                SelectField("Masttyp", current.mastType, Catalog.mastTypes) { v -> edit { it.copy(mastType = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextInput("Gesamthöhe", current.heightTotal, Modifier.weight(1f), keyboard = KeyboardType.Decimal, suffix = "m") { v ->
                        edit { it.copy(heightTotal = v) }
                    }
                    TextInput("Antennenhöhe", current.heightAntenna, Modifier.weight(1f), keyboard = KeyboardType.Decimal, suffix = "m") { v ->
                        edit { it.copy(heightAntenna = v) }
                    }
                }
                TextInput("Standortbescheinigung (BNetzA-Nr.)", current.bnetzaNr, mono = true) { v ->
                    edit { it.copy(bnetzaNr = v) }
                }
            }

            Section("Fotos") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { takePhoto() }) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Foto aufnehmen")
                    }
                    OutlinedButton(onClick = {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Aus Galerie")
                    }
                }
                if (current.photos.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.photos, key = { it }) { name ->
                            Box(
                                Modifier
                                    .size(112.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            ) {
                                PhotoThumb(
                                    Photos.file(context, name),
                                    Modifier
                                        .matchParentSize()
                                        .clickable { Photos.view(context, name) },
                                )
                                FilledTonalIconButton(
                                    onClick = { photoToRemove = name },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(28.dp),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Foto entfernen", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            Section("Termine & Notizen") {
                DateInput("Inbetriebnahme", current.commissioning) { v -> edit { it.copy(commissioning = v) } }
                DateInput("Letzte Begehung", current.lastVisit) { v -> edit { it.copy(lastVisit = v) } }
                DateInput("Nächste Wartung", current.nextMaintenance) { v -> edit { it.copy(nextMaintenance = v) } }
                TextInput("Notizen", current.notes, singleLine = false, minLines = 3) { v -> edit { it.copy(notes = v) } }
            }

            Spacer(Modifier.size(24.dp))
        }
    }

    if (showCells) {
        ModalBottomSheet(onDismissRequest = { showCells = false }) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Empfangene Funkzellen", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tippe auf eine Zelle, um ihre Werte zu übernehmen. Vorhandene Einträge bleiben erhalten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val list = cells
                when {
                    scanning -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 24.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Scanne Funkzellen …")
                    }
                    list.isEmpty() -> Text(
                        "Keine Zellen gefunden. Prüfe, ob eine SIM aktiv ist und „Genauer Standort“ für die App erlaubt ist.",
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        items(list) { cell ->
                            CellRow(cell) {
                                edit { it.applyCell(cell) }
                                showCells = false
                                message("Zelldaten übernommen")
                            }
                        }
                    }
                }
                TextButton(onClick = { scanCells() }, enabled = !scanning) { Text("Erneut scannen") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Standort löschen?") },
            text = { Text("„${current.name.ifBlank { current.siteId }}“ wird mit allen Fotos endgültig gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val start = initial ?: StationForm()
                    vm.delete(stationId, current.photos - start.photos.toSet())
                    onClose()
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Änderungen verwerfen?") },
            text = { Text("Die ungespeicherten Eingaben gehen verloren.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    leave(save = false)
                }) { Text("Verwerfen") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Weiter bearbeiten") } },
        )
    }

    photoToRemove?.let { name ->
        AlertDialog(
            onDismissRequest = { photoToRemove = null },
            title = { Text("Foto entfernen?") },
            text = { Text("Das Foto wird beim Speichern gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    edit { it.copy(photos = it.photos - name) }
                    photoToRemove = null
                }) { Text("Entfernen") }
            },
            dismissButton = { TextButton(onClick = { photoToRemove = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun CellRow(cell: CellRecord, onClick: () -> Unit) {
    val headline = listOfNotNull(
        cell.tech,
        cell.band?.label,
        cell.band?.mhz?.takeIf { it.isNotEmpty() }?.let { "$it MHz" },
        cell.operatorName,
    ).joinToString(" · ")
    val details = listOfNotNull(
        cell.enbId?.let { "eNB $it-${cell.sector}" },
        if (cell.enbId == null) cell.cellId?.let { "CI $it" } else null,
        cell.pci?.let { "PCI $it" },
        cell.area?.let { "${cell.areaLabel} $it" },
        cell.arfcn?.let { "ARFCN $it" },
        cell.band?.downlinkMhz?.let { String.format(Locale.GERMANY, "%.1f MHz", it) },
    ).joinToString("  ")
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        overlineContent = {
            Text(
                if (cell.registered) "VERBUNDEN" else "NACHBARZELLE",
                letterSpacing = 1.sp,
                color = if (cell.registered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(headline) },
        supportingContent = {
            if (details.isNotEmpty()) {
                Text(details, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = { cell.dbm?.let { Text("$it dBm", style = MaterialTheme.typography.labelLarge) } },
    )
    HorizontalDivider()
}
