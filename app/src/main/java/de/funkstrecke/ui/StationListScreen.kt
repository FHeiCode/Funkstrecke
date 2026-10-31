package de.funkstrecke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.funkstrecke.data.Catalog
import de.funkstrecke.data.Station
import de.funkstrecke.data.splitList
import de.funkstrecke.util.CsvExport
import de.funkstrecke.util.localDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Hinweis zur nächsten Wartung, wenn sie überfällig ist oder in den nächsten 30 Tagen ansteht. */
fun maintenanceNote(nextMaintenance: Long?): Pair<String, Boolean>? {
    if (nextMaintenance == null) return null
    val days = ChronoUnit.DAYS.between(LocalDate.now(), localDate(nextMaintenance))
    return when {
        days < 0 -> "Wartung überfällig seit ${-days} ${if (days == -1L) "Tag" else "Tagen"}" to true
        days == 0L -> "Wartung heute fällig" to true
        days <= 30 -> "Wartung in $days ${if (days == 1L) "Tag" else "Tagen"}" to false
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StationListScreen(
    vm: StationsViewModel,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onMap: () -> Unit,
) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }

    val counts = remember(stations) { stations.groupingBy { it.status }.eachCount() }
    val dueCount = remember(stations) { stations.count { maintenanceNote(it.nextMaintenance) != null } }
    val shown = remember(stations, query, filter) {
        val q = query.trim().lowercase()
        stations.filter { s ->
            (filter == null || s.status == filter) &&
                (q.isEmpty() || listOf(s.name, s.siteId, s.address, s.operator, s.cellIds, s.enbId, s.bnetzaNr)
                    .any { it.lowercase().contains(q) })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Funkstrecke")
                        Text(
                            when (stations.size) {
                                0 -> "Noch keine Basisstation erfasst"
                                1 -> "1 Basisstation erfasst"
                                else -> "${stations.size} Basisstationen erfasst"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onMap) { Icon(Icons.Filled.Map, contentDescription = "Karte") }
                    IconButton(
                        onClick = { CsvExport.share(context, stations) },
                        enabled = stations.isNotEmpty(),
                    ) { Icon(Icons.Filled.Share, contentDescription = "Als CSV exportieren") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Neuer Standort") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (stations.isEmpty()) {
                item { EmptyState(onNew) }
            } else {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Name, ID, Adresse, Cell-ID …") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Suche leeren")
                                }
                            }
                        },
                        singleLine = true,
                    )
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = filter == null,
                            onClick = { filter = null },
                            label = { Text("Alle ${stations.size}") },
                        )
                        Catalog.statuses.forEach { status ->
                            val n = counts[status] ?: 0
                            if (n > 0) {
                                FilterChip(
                                    selected = filter == status,
                                    onClick = { filter = if (filter == status) null else status },
                                    label = { Text("$status $n") },
                                )
                            }
                        }
                    }
                }
                if (dueCount > 0) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (dueCount == 1) {
                                    "1 Standort mit fälliger Wartung (≤ 30 Tage)"
                                } else {
                                    "$dueCount Standorte mit fälliger Wartung (≤ 30 Tage)"
                                },
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            "Keine Standorte passen zur Suche.",
                            modifier = Modifier.padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(shown, key = { it.id }) { station ->
                    StationCard(station) { onOpen(station.id) }
                }
            }
        }
    }
}

@Composable
private fun StationCard(s: Station, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.name.ifBlank { s.siteId.ifBlank { "Ohne Namen" } },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (s.siteId.isNotBlank() && s.name.isNotBlank()) {
                        Text(
                            s.siteId,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(s.status)
            }

            val tech = s.technologies.splitList().joinToString(" / ")
            val bands = s.bands.splitList().joinToString(", ")
            val meta = listOf(
                s.operator,
                tech,
                if (bands.isNotEmpty()) "$bands MHz" else "",
                s.mastType,
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium)

            if (s.address.isNotBlank()) {
                Text(s.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.latitude != null && s.longitude != null) {
                Text(
                    String.format(Locale.US, "%.5f, %.5f", s.latitude, s.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val photoCount = s.photos.splitList('|').size
            if (photoCount > 0) {
                Text(
                    if (photoCount == 1) "1 Foto" else "$photoCount Fotos",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            maintenanceNote(s.nextMaintenance)?.let { (text, overdue) ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onNew: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.SettingsInputAntenna,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("Noch keine Basisstation erfasst", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            "Lege den ersten Standort an. Vor Ort übernimmst du GPS-Position und Zelldaten direkt vom Handy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onNew) { Text("Ersten Standort anlegen") }
    }
}
