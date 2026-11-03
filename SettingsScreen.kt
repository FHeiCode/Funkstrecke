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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.funkstrecke.service.Geofencer
import de.funkstrecke.service.HomeStatus
import de.funkstrecke.util.HomeStore
import de.funkstrecke.util.CallBehavior
import de.funkstrecke.util.ScanPreset
import de.funkstrecke.util.formatDistance
import de.funkstrecke.util.SettingsStore

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onHomeBase: () -> Unit) {
    val settings by SettingsStore.flow.collectAsStateWithLifecycle()
    val home by HomeStore.flow.collectAsStateWithLifecycle()
    val homeStatus by Geofencer.status.collectAsStateWithLifecycle()
    val preset = ScanPreset.matching(settings)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
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
            Section("Energiemodus") {
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
                        "Eigene Einstellung (unten angepasst)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            Section("Heimatbasis") {
                OutlinedCard(onClick = onHomeBase, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Automatisch starten beim Verlassen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                !home.hasPoint -> "Nicht eingerichtet. Tippen, um Ort und Radius festzulegen."
                                home.enabled && homeStatus == HomeStatus.Active ->
                                    "Aktiv · Radius ${formatDistance(home.radiusM.toDouble())}"
                                home.enabled -> "Eingeschaltet, aber nicht aktiv. Tippen für Details."
                                else -> "Ausgeschaltet · Radius ${formatDistance(home.radiusM.toDouble())}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Section("Aufzeichnung") {
                FieldTitle("Scan höchstens alle")
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
                FieldTitle("Nur scannen nach Bewegung von mindestens")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 10, 15, 25, 50, 100).forEach { v ->
                        FilterChip(
                            selected = settings.recordMinDistanceM == v,
                            onClick = { SettingsStore.update { it.copy(recordMinDistanceM = v) } },
                            label = { Text(if (v == 0) "immer" else "$v m") },
                        )
                    }
                }
                Hint(
                    "Im Stand bringen weitere Scans kaum neue Information für die Standortschätzung. " +
                        "Ein Mindestabstand spart deshalb Akku, ohne die Schätzung zu verschlechtern.",
                )
            }

            Section("Live-Ansicht") {
                FieldTitle("Aktualisieren alle")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 10, 15, 30).forEach { v ->
                        FilterChip(
                            selected = settings.liveIntervalS == v,
                            onClick = { SettingsStore.update { it.copy(liveIntervalS = v) } },
                            label = { Text("$v s") },
                        )
                    }
                }
                Hint("Gilt nur, solange der Live-Tab offen ist und keine Aufzeichnung läuft. Während der Aufzeichnung zeigt er deren Scans.")
            }

            Section("Telefonate & Datenverkehr") {
                val callBehavior by SettingsStore.callBehavior.collectAsStateWithLifecycle()
                val dataBehavior by SettingsStore.dataBehavior.collectAsStateWithLifecycle()
                Text(
                    "Zell-Scans bitten das Modem um zusätzliche Messungen. Während eines Gesprächs oder bei großen " +
                        "Downloads kann das auf manchen Geräten stören.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Während eines Telefonats", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BehaviorChoices(callBehavior, { it.callDescription }, SettingsStore::setCallBehavior)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bei starkem Datenverkehr (ab etwa 256 kbit/s mobil)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BehaviorChoices(dataBehavior, { it.dataDescription }, SettingsStore::setDataBehavior)
            }

            Section("Messung") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Frische Messung anfordern", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "An: Das Modem misst bei jedem Scan neu, die Werte passen genau zur GPS-Position. " +
                                "Aus: Android liefert den letzten Stand des Modems, oft einige Sekunden alt, dafür sparsamer.",
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

            Section("Wohin der Akku geht") {
                Hint(
                    "Den größten Anteil hat das GPS. Funkstrecke fragt die Position so oft ab, wie gescannt wird, " +
                        "mindestens aber alle 10 s, damit Halt und Weiterfahrt schnell erkannt werden. " +
                        "Im Stand gibt es nur einmal pro Minute einen Zell-Scan. " +
                        "Danach folgen die Zellmessungen des Modems und ein eingeschalteter Bildschirm. " +
                        "Für lange Touren: Modus „Sparsam“ und Bildschirm aus, die Aufzeichnung läuft im Hintergrund weiter.",
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
