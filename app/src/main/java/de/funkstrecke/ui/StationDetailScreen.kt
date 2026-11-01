package de.funkstrecke.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.funkstrecke.data.BaseStation
import de.funkstrecke.data.CellEntity
import de.funkstrecke.data.MeasurementEntity
import de.funkstrecke.ui.theme.operatorColorRaw
import de.funkstrecke.ui.theme.signalColorRaw
import de.funkstrecke.ui.theme.signalLevel
import de.funkstrecke.util.formatCoordinates
import de.funkstrecke.util.formatDateTime
import de.funkstrecke.util.formatDistance
import de.funkstrecke.util.formatTime
import java.util.Locale
import kotlin.math.floor

private fun openInMaps(context: Context, lat: Double, lon: Double, label: String) {
    val la = String.format(Locale.US, "%.6f", lat)
    val lo = String.format(Locale.US, "%.6f", lon)
    val uri = Uri.parse("geo:$la,$lo?q=$la,$lo(${Uri.encode(label)})")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StationDetailScreen(vm: ScannerViewModel, stationKey: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val stations by vm.stations.collectAsStateWithLifecycle()
    val station = stations.firstOrNull { it.key == stationKey }
    val measurementsFlow = remember(stationKey) { vm.measurementsFor(stationKey) }
    val measurements by measurementsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(station?.title ?: "Station", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
                actions = {
                    if (station != null && measurements.isNotEmpty()) {
                        IconButton(onClick = { vm.recompute(station) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Standort neu berechnen")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (station == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Station nicht gefunden.")
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RatBadge(station.rat)
                Spacer(Modifier.width(10.dp))
                OperatorDot(station.operatorName)
                Spacer(Modifier.width(6.dp))
                Text(station.operatorName ?: "Unbekannter Betreiber", style = MaterialTheme.typography.titleMedium)
            }

            Section("Kennung") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    KeyValue("MCC", station.mcc ?: "–")
                    KeyValue("MNC", station.mnc ?: "–")
                    KeyValue(if (station.rat == "4G" || station.rat == "5G") "TAC" else "LAC", station.area?.toString() ?: "–")
                    station.nodeId?.let { KeyValue("eNodeB-ID", it.toString()) }
                    KeyValue("Technik", station.rat)
                    KeyValue("Bänder", station.bands.joinToString(", ").ifEmpty { "–" })
                    station.minDistanceM?.let { KeyValue("Min. Abstand (TA)", formatDistance(it.toDouble())) }
                }
                if (station.plmnInferred) {
                    Text(
                        "MCC/MNC wurden nicht von der Zelle gemeldet, sondern von der verbundenen Zelle übernommen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Erstmals ${formatDateTime(station.firstSeen)} · zuletzt ${formatDateTime(station.lastSeen)} · ${station.samples} Scans",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Geschätzter Standort") {
                val e = station.estimate
                if (e == null) {
                    Text(
                        if (measurements.isEmpty()) {
                            "Noch keine Messung mit GPS. Starte im Live-Tab die Aufzeichnung und bewege dich in der Umgebung dieser Station."
                        } else {
                            "Zu wenige brauchbare Messungen (GPS-Genauigkeit besser als 30 m nötig)."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    EstimateMap(station, measurements)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KeyValue("Position", formatCoordinates(e.latitude, e.longitude))
                        KeyValue("Unsicherheit (95 %)", "±${formatDistance(e.radiusM)}")
                        KeyValue("Messorte", e.spots.toString())
                        KeyValue("Messwerte", e.measurements.toString())
                    }
                    Text(e.method, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text(
                        "Je mehr Richtungen deine Messungen abdecken, desto kleiner wird der Kreis. " +
                            "Am besten die Station einmal umrunden. Gebäude und Antennenausrichtung verfälschen die Pegel; " +
                            "die Schätzung ist ein Suchgebiet, kein Kataster.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { openInMaps(context, e.latitude, e.longitude, station.title) }) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("In Karten-App öffnen")
                    }
                }
            }

            Section(if (station.cells.size == 1) "Zelle" else "Zellen (${station.cells.size})") {
                CellTable(station.rat, station.cells)
            }

            if (measurements.isNotEmpty()) {
                Section("Letzte Messungen") {
                    measurements.take(15).forEach { m -> MeasurementRow(station, m) }
                    if (measurements.size > 15) {
                        Text(
                            "Alle Messwerte über „Messprotokoll als CSV exportieren“ in der Stationsliste.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Karte mit Messorten (gefärbt nach Pegel) und Unsicherheitskreis der Schätzung. */
@Composable
private fun EstimateMap(station: BaseStation, measurements: List<MeasurementEntity>) {
    val e = station.estimate ?: return
    val color = operatorColorRaw(station.operatorName)
    val dots = remember(measurements, e) {
        // Ein Punkt je ~20-m-Raster, stärkster Pegel zählt.
        val spots = measurements
            .filter { it.dbm != null }
            .groupBy { floor(it.latitude / 0.00018) to floor(it.longitude / 0.00028) }
            .map { (_, list) -> list.maxBy { it.dbm!! } }
        spots.map { m ->
            MapDot(m.latitude, m.longitude, signalColorRaw(signalLevel(station.rat, m.dbm)), sizeDp = 9)
        } + MapDot(e.latitude, e.longitude, color, sizeDp = 18, title = station.title, snippet = "±${formatDistance(e.radiusM)}")
    }
    val circles = remember(e) { listOf(MapCircle(e.latitude, e.longitude, e.radiusM, color)) }
    OsmMap(
        dots = dots,
        circles = circles,
        focus = MapFocus.Around(e.latitude, e.longitude, radiusM = 1_000.0),
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(12.dp)),
    )
    Text(
        "Punkte: deine Messorte, grün = starker, rot = schwacher Pegel. Großer Punkt und Kreis: geschätzter Mast.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CellTable(rat: String, cells: List<CellEntity>) {
    val headers = listOf(
        if (rat == "4G") "Sektor" else "Nr.",
        if (rat == "5G") "NCI" else if (rat == "4G") "ECI" else "CID",
        if (rat == "3G") "PSC" else if (rat == "2G") "BSIC" else "PCI",
        "Band", "ARFCN", "max dBm", "Scans",
    )
    val rows = cells.mapIndexed { i, c ->
        listOf(
            c.sector?.toString() ?: (i + 1).toString(),
            c.cellId?.toString() ?: "–",
            c.pci?.toString() ?: "–",
            listOfNotNull(c.band, c.bandMhz?.let { "$it MHz" }).joinToString(" ").ifEmpty { "–" },
            c.arfcn?.toString() ?: "–",
            c.bestDbm?.toString() ?: "–",
            if (c.servingSamples > 0) "${c.samples} (${c.servingSamples}×v)" else c.samples.toString(),
        )
    }
    val widths = listOf(56, 110, 56, 130, 72, 72, 96)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                headers.forEachIndexed { i, h ->
                    Text(
                        h,
                        modifier = Modifier.width(widths[i].dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            rows.forEach { row ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    row.forEachIndexed { i, v ->
                        Text(
                            v,
                            modifier = Modifier.width(widths[i].dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
    Text(
        "v = als verbundene Zelle gesehen",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MeasurementRow(station: BaseStation, m: MeasurementEntity) {
    val cell = station.cells.firstOrNull { it.id == m.cellKey }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(m.time), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.width(72.dp))
        Text(
            listOfNotNull(
                cell?.sector?.let { "S$it" } ?: cell?.pci?.let { "PCI $it" },
                m.timingAdvance?.let { "TA $it" },
                m.accuracyM?.let { "GPS ±${it.toInt()} m" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            m.dbm?.let { "$it dBm" } ?: "–",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = androidx.compose.ui.graphics.lerp(
                signalColorRaw(signalLevel(station.rat, m.dbm)),
                MaterialTheme.colorScheme.onSurface,
                0.2f,
            ),
        )
    }
}
