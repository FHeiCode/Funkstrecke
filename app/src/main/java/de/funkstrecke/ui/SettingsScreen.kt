package de.funkstrecke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.funkstrecke.locate.Terrain
import de.funkstrecke.service.Geofencer
import de.funkstrecke.service.HomeStatus
import de.funkstrecke.util.AppLanguage
import de.funkstrecke.util.HomeStore
import de.funkstrecke.util.CallBehavior
import de.funkstrecke.util.Lang
import de.funkstrecke.util.ScanPreset
import de.funkstrecke.util.formatDistance
import de.funkstrecke.util.SettingsStore
import de.funkstrecke.util.tr

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: ScannerViewModel, onBack: () -> Unit, onHomeBase: () -> Unit) {
    val settings by SettingsStore.flow.collectAsStateWithLifecycle()
    val home by HomeStore.flow.collectAsStateWithLifecycle()
    val homeStatus by Geofencer.status.collectAsStateWithLifecycle()
    val preset = ScanPreset.matching(settings)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(tr("Einstellungen", "Settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Zurück", "Back")) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Section(tr("Sprache", "Language")) {
                val context = LocalContext.current
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.entries.forEach { l ->
                        FilterChip(
                            selected = Lang.language == l,
                            onClick = { Lang.set(context, l) },
                            label = { Text(l.label) },
                        )
                    }
                }
            }
            Section(tr("Energiemodus", "Power mode")) {
                ScanPreset.entries.forEach { p ->
                    val selected = p == preset
                    OutlinedCard(
                        onClick = { SettingsStore.update { p.settings } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(p.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(p.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (preset == null) {
                    Text(
                        tr("Eigene Einstellung (unten angepasst)", "Custom setting (adjusted below)"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            Section(tr("Heimatbasis", "Home base")) {
                OutlinedCard(onClick = onHomeBase, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(tr("Automatisch starten beim Verlassen", "Start automatically when leaving"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                !home.hasPoint -> tr("Nicht eingerichtet. Tippen, um Ort und Radius festzulegen.", "Not set up. Tap to set location and radius.")
                                home.enabled && homeStatus == HomeStatus.Active ->
                                    tr("Aktiv · Radius ${formatDistance(home.radiusM.toDouble())}", "Active · radius ${formatDistance(home.radiusM.toDouble())}")
                                home.enabled -> tr("Eingeschaltet, aber nicht aktiv. Tippen für Details.", "Switched on, but not active. Tap for details.")
                                else -> tr("Ausgeschaltet · Radius ${formatDistance(home.radiusM.toDouble())}", "Off · radius ${formatDistance(home.radiusM.toDouble())}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Section(tr("Aufzeichnung", "Recording")) {
                FieldTitle(tr("Scan höchstens alle", "Scan at most every"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 10, 30, 60).forEach { v ->
                        FilterChip(
                            selected = settings.recordIntervalS == v,
                            onClick = { SettingsStore.update { it.copy(recordIntervalS = v) } },
                            label = { Text("$v s") },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                FieldTitle(tr("Nur scannen nach Bewegung von mindestens", "Only scan after moving at least"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 10, 15, 25, 50, 100).forEach { v ->
                        FilterChip(
                            selected = settings.recordMinDistanceM == v,
                            onClick = { SettingsStore.update { it.copy(recordMinDistanceM = v) } },
                            label = { Text(if (v == 0) tr("immer", "always") else "$v m") },
                        )
                    }
                }
                Hint(
                    tr("Im Stand bringen weitere Scans kaum neue Information für die Standortschätzung. ", "When standing still, further scans add little information for the location estimate. ") +
                        tr("Ein Mindestabstand spart deshalb Akku, ohne die Schätzung zu verschlechtern.", "A minimum distance therefore saves battery without degrading the estimate."),
                )
            }

            Section(tr("Live-Ansicht", "Live view")) {
                FieldTitle(tr("Aktualisieren alle", "Refresh every"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 10, 15, 30).forEach { v ->
                        FilterChip(
                            selected = settings.liveIntervalS == v,
                            onClick = { SettingsStore.update { it.copy(liveIntervalS = v) } },
                            label = { Text("$v s") },
                        )
                    }
                }
                Hint(tr("Gilt nur, solange der Live-Tab offen ist und keine Aufzeichnung läuft. Während der Aufzeichnung zeigt er deren Scans.", "Only applies while the Live tab is open and no recording is running. During recording it shows the recording's scans."))
            }

            Section(tr("Telefonate & Datenverkehr", "Calls & data traffic")) {
                val callBehavior by SettingsStore.callBehavior.collectAsStateWithLifecycle()
                val dataBehavior by SettingsStore.dataBehavior.collectAsStateWithLifecycle()
                Text(
                    tr("Zell-Scans bitten das Modem um zusätzliche Messungen. Während eines Gesprächs oder bei großen ", "Cell scans ask the modem for extra measurements. During a call or large ") +
                        tr("Downloads kann das auf manchen Geräten stören.", "downloads this can interfere on some devices."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(tr("Während eines Telefonats", "During a call"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BehaviorChoices(callBehavior, { it.callDescription }, SettingsStore::setCallBehavior)
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("Bei starkem Datenverkehr (ab etwa 256 kbit/s mobil)", "During heavy data traffic (from about 256 kbit/s mobile)"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BehaviorChoices(dataBehavior, { it.dataDescription }, SettingsStore::setDataBehavior)
            }

            Section(tr("Geländemodell", "Terrain model")) {
                TerrainSettings(vm)
            }

            Section(tr("Messung", "Measurement")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Frische Messung anfordern", "Request fresh measurement"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("An: Das Modem misst bei jedem Scan neu, die Werte passen genau zur GPS-Position. ", "On: the modem measures anew on every scan; values match the GPS position exactly. ") +
                                tr("Aus: Android liefert den letzten Stand des Modems, oft einige Sekunden alt, dafür sparsamer.", "Off: Android returns the modem's last state, often a few seconds old, but uses less power."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = settings.forceRefresh,
                        onCheckedChange = { v -> SettingsStore.update { it.copy(forceRefresh = v) } },
                    )
                }
            }

            Section("OpenCelliD") {
                val ocidKey by SettingsStore.openCellIdKey.collectAsStateWithLifecycle()
                var keyInput by rememberSaveable { mutableStateOf(ocidKey) }
                Text(
                    tr("Gleicht gefundene Zellen mit der offenen Zelldatenbank opencellid.org ab. Dafür brauchst du einen ", "Compares found cells with the open cell database opencellid.org. This requires a ") +
                        tr("kostenlosen API-Schlüssel (Konto auf opencellid.org anlegen, Schlüssel unter „My Account“).", "free API key (create an account on opencellid.org, key under \"My Account\")."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = {
                        keyInput = it
                        SettingsStore.setOpenCellIdKey(it)
                    },
                    label = { Text(tr("API-Schlüssel", "API key")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    tr("Beim Abgleich werden die Kennungen deiner gefundenen Zellen an OpenCelliD gesendet, keine Positionen. ", "The comparison sends the identifiers of your found cells to OpenCelliD, no positions. ") +
                        tr("Ergebnisse gelten 30 Tage; danach wird neu gefragt.", "Results are valid for 30 days; then they are requested again."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(tr("Wohin der Akku geht", "Where the battery goes")) {
                Hint(
                    tr("Den größten Anteil hat das GPS. Funkstrecke fragt die Position so oft ab, wie gescannt wird, ", "GPS takes the largest share. Funkstrecke requests the position as often as it scans, ") +
                        tr("mindestens aber alle 10 s, damit Halt und Weiterfahrt schnell erkannt werden. ", "but at least every 10 s so that stops and departures are detected quickly. ") +
                        tr("Im Stand gibt es nur einmal pro Minute einen Zell-Scan. ", "When stationary there is only one cell scan per minute. ") +
                        tr("Danach folgen die Zellmessungen des Modems und ein eingeschalteter Bildschirm. ", "Next come the modem's cell measurements and a switched-on screen. ") +
                        tr("Für lange Touren: Modus „Sparsam“ und Bildschirm aus, die Aufzeichnung läuft im Hintergrund weiter.", "For long trips: \"Saver\" mode and screen off; recording continues in the background."),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FieldTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BehaviorChoices(
    selected: CallBehavior,
    description: (CallBehavior) -> String,
    onSelect: (CallBehavior) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CallBehavior.entries.forEach { b ->
            val isSelected = b == selected
            OutlinedCard(
                onClick = { onSelect(b) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(b.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(description(b), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Einstellungen und Prüfung des Geländemodells (Copernicus GLO-90). */
@Composable
private fun TerrainSettings(vm: ScannerViewModel) {
    val status by Terrain.status.collectAsStateWithLifecycle()
    val effect by vm.terrainEffect.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Hügel und Täler berücksichtigen", "Account for hills and valleys"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr(
                        "Messungen, bei denen ein Hügel zwischen Mast und Messort liegt, zählen bei der Standortschätzung weniger. " +
                            "Höhendaten: Copernicus GLO-90 (90-m-Raster), einmal je 1° × 1°-Gebiet (etwa 110 × 75 km) geladen, 3–4 MB.",
                        "Measurements with a hill between mast and measuring point count less in the location estimate. " +
                            "Elevation data: Copernicus GLO-90 (90 m grid), loaded once per 1° × 1° area (about 110 × 75 km), 3–4 MB.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = status.enabled, onCheckedChange = { vm.setTerrainEnabled(it) })
        }
        if (status.enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Nur über WLAN laden", "Download over Wi-Fi only"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = status.wifiOnly, onCheckedChange = { Terrain.setWifiOnly(it) })
            }
        }
        Text(
            buildString {
                append(
                    tr(
                        "${status.tiles} Gebiete gespeichert (${"%.1f".format(Lang.locale, status.bytes / 1_048_576.0)} MB)",
                        "${status.tiles} areas stored (${"%.1f".format(Lang.locale, status.bytes / 1_048_576.0)} MB)",
                    ),
                )
                if (status.loading > 0) append(tr(" · lade ${status.loading} …", " · loading ${status.loading} …"))
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            tr(
                "Wirkung prüfen: An Stationen mit einem OSM-Mast höchstens 500 m neben der Schätzung wird mit und ohne Gelände gerechnet " +
                    "und der Abstand zum Mast verglichen.",
                "Check effect: at stations with an OSM mast within 500 m of the estimate, the location is computed with and without terrain " +
                    "and the distance to the mast compared.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        effect?.let { e ->
            Text(
                when {
                    e.running -> tr("Rechne …", "Computing …")
                    e.stations == 0 -> tr(
                        "Keine passende Station: Es braucht Stationen mit OSM-Mast in der Nähe und geladenen Geländedaten.",
                        "No suitable station: stations with a nearby OSM mast and loaded terrain data are needed.",
                    )
                    else -> tr(
                        "${e.stations} Stationen · Median-Abstand zum OSM-Mast ohne Gelände ${formatDistance(e.medianWithoutM!!)}, " +
                            "mit Gelände ${formatDistance(e.medianWithM!!)} · ${e.improved} näher, ${e.worse} weiter weg",
                        "${e.stations} stations · median distance to OSM mast without terrain ${formatDistance(e.medianWithoutM!!)}, " +
                            "with terrain ${formatDistance(e.medianWithM!!)} · ${e.improved} closer, ${e.worse} farther",
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.measureTerrainEffect() }, enabled = status.enabled && effect?.running != true) {
                Text(tr("Wirkung prüfen", "Check effect"))
            }
            if (status.tiles > 0) {
                TextButton(onClick = { vm.clearTerrain() }) { Text(tr("Daten löschen", "Delete data")) }
            }
        }
    }
}
