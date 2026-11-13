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
import de.funkstrecke.locate.ModelCheck
import de.funkstrecke.locate.SignalModelStore
import de.funkstrecke.locate.Terrain
import de.funkstrecke.service.Geofencer
import de.funkstrecke.service.HomeStatus
import de.funkstrecke.util.AppLanguage
import de.funkstrecke.util.HomeStore
import de.funkstrecke.util.CallBehavior
import de.funkstrecke.util.Lang
import de.funkstrecke.util.ScanPreset
import de.funkstrecke.util.formatDateTime
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

            Section(tr("Bewertung", "Rating")) {
                val strict by SettingsStore.strictOsm.collectAsStateWithLifecycle()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Nur eindeutige OSM-Masten bestätigen", "Only unambiguous OSM masts confirm"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr(
                                "An: Ein OSM-Mast höchstens 100 m neben der Schätzung macht eine Station nur zuverlässig, wenn kein anderer " +
                                    "Maststandort näher als 300 m liegt. In der Stadt steht alle 150–200 m ein Mast, die Nähe ist dort oft Zufall. " +
                                    "Aus: wie bis Version 2.24 genügt jeder Mast in 100 m. Das Lernen nutzt immer nur eindeutige Masten.",
                                "On: an OSM mast within 100 m of the estimate makes a station reliable only if no other mast site is " +
                                    "closer than 300 m. In cities there is a mast every 150–200 m, so proximity is often coincidence. " +
                                    "Off: as up to version 2.24, any mast within 100 m is enough. Learning always uses unambiguous masts only.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = strict, onCheckedChange = { SettingsStore.setStrictOsm(it) })
                }
            }

            Section(tr("Verdächtige Stationen", "Suspect stations")) {
                val min by SettingsStore.suspectMinMeasurements.collectAsStateWithLifecycle()
                Text(
                    tr(
                        "In der Stationsansicht kannst du eine Station als verdächtig markieren. Zeichnest du danach im Umkreis von 1 km " +
                            "wieder auf und wird sie dabei seltener als hier eingestellt neu gemessen, wird sie mit allen Zellen und Messungen gelöscht.",
                        "In the station view you can mark a station as suspect. If you then record within 1 km again and it is measured " +
                            "fewer times than set here, it is deleted with all its cells and measurements.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Mindestens neue Messungen", "Minimum new measurements"), modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { SettingsStore.setSuspectMinMeasurements(min - 1) }, enabled = min > 1) { Text("−") }
                    Text("$min", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
                    OutlinedButton(onClick = { SettingsStore.setSuspectMinMeasurements(min + 1) }, enabled = min < 50) { Text("+") }
                }
            }

            Section(tr("Lernen", "Learning")) {
                LearningSettings(vm)
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
                "Wirkung prüfen: An Stationen mit einem eindeutigen OSM-Mast (höchstens 300 m neben der Schätzung, nächster andere " +
                    "Maststandort mindestens 600 m entfernt) wird mit und ohne Gelände gerechnet und der Abstand zum Mast verglichen.",
                "Check effect: at stations with an unambiguous OSM mast (at most 300 m from the estimate, next other mast site " +
                    "at least 600 m away) the location is computed with and without terrain and the distance to the mast compared.",
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

/** Zeile mit dem Ergebnis der Prüfung eines Modellteils. */
@Composable
private fun CheckLine(check: ModelCheck?) {
    Text(
        if (check == null || check.medianDefaultM == null || check.medianLearnedM == null) {
            tr(
                "Prüfung: noch keine Station mit eindeutigem OSM-Mast (höchstens 300 m entfernt, nächster andere Maststandort mindestens 600 m weg).",
                "Check: no station with an unambiguous OSM mast yet (at most 300 m away, next other mast site at least 600 m away).",
            )
        } else {
            tr(
                "Prüfung an ${check.stations} Stationen mit eindeutigem OSM-Mast (mindestens ${ModelCheck.MIN_STATIONS} nötig): Median-Abstand zum Mast " +
                    "mit Vorgabe ${formatDistance(check.medianDefaultM)}, gelernt ${formatDistance(check.medianLearnedM)} · " +
                    "${check.improved} näher, ${check.worse} weiter weg",
                "Check at ${check.stations} stations with an unambiguous OSM mast (at least ${ModelCheck.MIN_STATIONS} needed): median distance to the mast " +
                    "with default ${formatDistance(check.medianDefaultM)}, learned ${formatDistance(check.medianLearnedM)} · " +
                    "${check.improved} closer, ${check.worse} farther",
            )
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Gelerntes Signalmodell: Schalter und je Teil (Timing Advance, Pegelabfall) Stand, Werte und Prüfergebnis. */
@Composable
private fun LearningSettings(vm: ScannerViewModel) {
    val state by SignalModelStore.state.collectAsStateWithLifecycle()
    val learning by vm.learning.collectAsStateWithLifecycle()
    val model = state.learned
    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
    fun status(applied: Boolean) = when {
        !state.enabled -> tr("ausgeschaltet", "switched off")
        applied -> tr("in Verwendung", "in use")
        else -> tr("nicht in Verwendung, Prüfung nicht bestanden", "not in use, check not passed")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Aus bekannten Stationen lernen", "Learn from known stations"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr(
                        "An Stationen mit gut bekanntem Standort (eindeutiger OSM-Mast oder enge eigene Schätzung) lernt die App zwei Dinge: " +
                            "wie genau der Timing-Advance-Abstand deines Handys ist, und wie schnell der Pegel je Band mit dem Abstand fällt. " +
                            "Jeder Teil wird für sich an Stationen mit eindeutigem OSM-Mast geprüft und nur verwendet, wenn er nicht schlechter " +
                            "abschneidet als die feste Vorgabe. Gelernt wird höchstens einmal am Tag.",
                        "At stations with a well-known location (unambiguous OSM mast or tight own estimate) the app learns two things: " +
                            "how precise your phone's timing-advance distance is, and how fast the level drops with distance per band. " +
                            "Each part is checked separately at stations with an unambiguous OSM mast and only used if it does no worse " +
                            "than the fixed default. Learning runs at most once a day.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = state.enabled, onCheckedChange = { vm.setLearnedModelEnabled(it) })
        }
        when {
            learning -> Text(tr("Lerne und prüfe …", "Learning and checking …"), style = MaterialTheme.typography.bodyMedium)
            model == null -> Text(tr("Noch nicht gelernt.", "Not learned yet."), style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text(
                    tr("Gelernt an ${model.references} Stationen, ${formatDateTime(model.learnedAt)}", "Learned at ${model.references} stations, ${formatDateTime(model.learnedAt)}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(tr("Timing Advance: ", "Timing advance: ") + if (model.ta.isEmpty()) tr("noch zu wenige Daten", "not enough data yet") else status(state.taApplied), style = MaterialTheme.typography.bodyMedium)
                if (model.ta.isNotEmpty()) {
                    model.ta.toSortedMap().forEach { (rat, t) ->
                        Text(
                            "$rat: ${if (t.offsetM >= 0) "+" else "−"}${kotlin.math.abs(t.offsetM).toInt()} m · ±${t.sigmaM.toInt()} m · ${t.samples} " + tr("Messungen", "measurements"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = mono,
                        )
                    }
                    CheckLine(state.taCheck)
                    Text(tr("Vorgabe: 4G +0 m · ±80 m", "Default: 4G +0 m · ±80 m"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text(tr("Pegelabfall je Band: ", "Level drop per band: ") + if (model.bands.isEmpty()) tr("noch zu wenige Daten", "not enough data yet") else status(state.bandsApplied), style = MaterialTheme.typography.bodyMedium)
                if (model.bands.isEmpty()) {
                    val progress = de.funkstrecke.data.SignalLearning.lastProgress
                    Text(
                        tr(
                            "Gelernt wird nur aus Zellen, die auch als Nachbarzelle gemessen wurden (mindestens 5 Messorte): Nur dort sind auch " +
                                "schwache Pegel in der Ferne erfasst. Bisher ${progress.cellsWithNeighbours} Zellen, nötig sind ${progress.needed} je Band " +
                                "mit zusammen 80 Messorten. Das füllt sich mit jeder Aufzeichnung.",
                            "Learning only uses cells that were also measured as a neighbour cell (at least 5 measuring spots): only there are " +
                                "weak levels far away captured too. So far ${progress.cellsWithNeighbours} cells; ${progress.needed} per band " +
                                "with 80 measuring spots in total are needed. This fills up with every recording.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    model.bands.toSortedMap().forEach { (key, b) ->
                        Text(
                            (if (key.endsWith("/*")) key.removeSuffix("/*") + tr(" alle Bänder", " all bands") else key.replace("/", " ")) +
                                ": n = ${"%.2f".format(Lang.locale, b.exponent)} · ${b.levelAt(1_000.0).toInt()} dBm " + tr("in 1 km", "at 1 km") +
                                " · ±${"%.0f".format(Lang.locale, b.sigmaDb)} dB · ${b.cells} " + tr("Zellen", "cells"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = mono,
                        )
                    }
                    CheckLine(state.bandCheck)
                    Text(
                        tr("Vorgabe für alle Bänder: n = 3,00 · −95 dBm in 1 km · ±6 dB", "Default for all bands: n = 3.00 · −95 dBm at 1 km · ±6 dB"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        OutlinedButton(onClick = { vm.learnSignalModel() }, enabled = !learning) {
            Text(tr("Jetzt lernen", "Learn now"))
        }
    }
}
