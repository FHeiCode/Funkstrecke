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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.funkstrecke.data.BaseStation
import de.funkstrecke.data.CellEntity
import de.funkstrecke.data.LinkConfidence
import de.funkstrecke.data.MeasurementEntity
import de.funkstrecke.data.OsmMastEntity
import de.funkstrecke.data.OsmMasts
import de.funkstrecke.data.distanceM
import de.funkstrecke.data.ocidLineWorthy
import de.funkstrecke.data.ocidMarker
import de.funkstrecke.data.pciGroup
import de.funkstrecke.data.pciPss
import de.funkstrecke.data.supportsPciGroups
import de.funkstrecke.locate.LocationEstimator
import de.funkstrecke.locate.localizeMethod
import de.funkstrecke.ui.theme.operatorColorRaw
import de.funkstrecke.ui.theme.signalColorRaw
import de.funkstrecke.ui.theme.signalLevel
import de.funkstrecke.util.formatCoordinates
import de.funkstrecke.util.formatDateTime
import de.funkstrecke.util.formatDistance
import de.funkstrecke.util.formatTime
import de.funkstrecke.util.tr
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
    val measurementKeys = station?.measurementKeys ?: listOf(stationKey)
    val measurementsFlow = remember(measurementKeys) { vm.measurementsFor(measurementKeys) }
    val measurements by measurementsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val osmAll by vm.osmMasts.collectAsStateWithLifecycle()
    val est = station?.estimate
    val osmNear = remember(osmAll, est) {
        if (est == null) emptyList() else osmAll.filter { distanceM(est.latitude, est.longitude, it.latitude, it.longitude) <= OsmMasts.SEARCH_M }
    }
    // Masten rund um die Station automatisch nachladen (aus dem Zwischenspeicher, sonst einmal von Overpass).
    LaunchedEffect(est?.latitude, est?.longitude) {
        if (est != null) vm.loadOsmAround(est.latitude, est.longitude)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(station?.title ?: "Station", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Zurück", "Back")) }
                },
                actions = {
                    if (station != null && measurements.isNotEmpty()) {
                        IconButton(onClick = { vm.recompute(station) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = tr("Standort neu berechnen", "Recalculate location"))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (station == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(tr("Station nicht gefunden.", "Station not found."))
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
                Text(station.operatorName ?: tr("Unbekannter Betreiber", "Unknown operator"), style = MaterialTheme.typography.titleMedium)
            }
            val assessment = station.assessment
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReliabilityPill(assessment.reliability)
                Spacer(Modifier.width(8.dp))
                Text(assessment.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section(tr("Kennung", "Identity")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    KeyValue("MCC", station.mcc ?: "–")
                    KeyValue("MNC", station.mnc ?: "–")
                    KeyValue(if (station.rat == "4G" || station.rat == "5G") "TAC" else "LAC", station.area?.toString() ?: "–")
                    station.nodeId?.let { KeyValue(tr("eNodeB-ID", "eNodeB ID"), it.toString()) }
                    if (station.pciGroups.isNotEmpty()) KeyValue(tr("PCI-Gruppe (SSS)", "PCI group (SSS)"), station.pciGroups.joinToString(", "))
                    KeyValue(tr("Technik", "Technology"), station.rat)
                    KeyValue(tr("Bänder", "Bands"), station.bands.joinToString(", ").ifEmpty { "–" })
                    station.minDistanceM?.let { KeyValue(tr("Min. Abstand (TA)", "Min. distance (TA)"), formatDistance(it.toDouble())) }
                }
                if (station.inferred) {
                    Text(
                        tr("Vermutete Station: Diese Zellen melden keine Cell-ID. Sie wurden zusammengefasst, weil sie dieselbe ", "Presumed station: these cells report no cell ID. They were grouped because they share the same ") +
                            tr("PCI-Gruppe haben und an nahe beieinander liegenden Orten gemessen wurden.", "PCI group and were measured at locations close to each other."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (station.plmnInferred) {
                    Text(
                        tr("MCC/MNC wurden nicht von der Zelle gemeldet, sondern von der verbundenen Zelle übernommen.", "MCC/MNC were not reported by the cell but taken from the serving cell."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    tr("Erstmals ${formatDateTime(station.firstSeen)} · zuletzt ${formatDateTime(station.lastSeen)} · ${station.samples} Scans", "First ${formatDateTime(station.firstSeen)} · last ${formatDateTime(station.lastSeen)} · ${station.samples} scans"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(tr("Geschätzter Standort", "Estimated location")) {
                val e = station.estimate
                if (e == null) {
                    Text(
                        if (measurements.isEmpty()) {
                            tr("Noch keine Messung mit GPS. Starte im Live-Tab die Aufzeichnung und bewege dich in der Umgebung dieser Station.", "No GPS measurement yet. Start recording in the Live tab and move around near this station.")
                        } else {
                            tr("Zu wenige brauchbare Messungen (GPS-Genauigkeit besser als 30 m nötig).", "Too few usable measurements (GPS accuracy better than 30 m required).")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    EstimateMap(station, measurements, osmNear)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KeyValue("Position", formatCoordinates(e.latitude, e.longitude))
                        KeyValue(tr("Unsicherheit (95 %)", "Uncertainty (95 %)"), "±${formatDistance(e.radiusM)}")
                        KeyValue(tr("Messorte", "Measurement spots"), e.spots.toString())
                        KeyValue(tr("Messwerte", "Measurements"), e.measurements.toString())
                    }
                    Text(localizeMethod(e.method), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text(
                        tr("Je mehr Richtungen deine Messungen abdecken, desto kleiner wird der Kreis. ", "The more directions your measurements cover, the smaller the circle. ") +
                            tr("Am besten die Station einmal umrunden. Gebäude und Antennenausrichtung verfälschen die Pegel; ", "Ideally circle the station once. Buildings and antenna orientation distort the signal levels; ") +
                            tr("die Schätzung ist ein Suchgebiet, kein Kataster.", "the estimate is a search area, not a land register."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { openInMaps(context, e.latitude, e.longitude, station.title) }) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("In Karten-App öffnen", "Open in maps app"))
                    }
                }
            }

            if (station.pciConflicts.isNotEmpty()) {
                Section(tr("PCI-Konflikt", "PCI conflict")) {
                    station.pciConflicts.forEach { c ->
                        Text(
                            tr("Sektor ", "Sector ") + "${c.cell.sector ?: c.cell.cellId} (PCI ${c.cell.pci}, ${c.cell.band ?: "?"}, ARFCN ${c.cell.arfcn}) " +
                                "↔ ${c.otherTitle} " + tr("Sektor", "sector") + " ${c.other.sector ?: c.other.cellId} · " +
                                tr("${formatDistance(c.distanceM)} entfernt", "${formatDistance(c.distanceM)} apart"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        tr("Zwei verschiedene Zellen nutzen dieselbe PCI auf demselben Träger und liegen nahe beieinander. ", "Two different cells use the same PCI on the same carrier and are close to each other. ") +
                            tr("Handys können sie verwechseln; Übergaben zwischen ihnen können fehlschlagen. ", "Phones may confuse them; handovers between them may fail. ") +
                            tr("Gleiche PCIs auf anderen Bändern oder Trägern sind dagegen üblich und werden nicht gemeldet. ", "Identical PCIs on other bands or carriers, by contrast, are common and not reported. ") +
                            tr("Grundlage sind die geschätzten Standorte; bei großen Unsicherheitskreisen ist die Warnung entsprechend unsicher.", "It is based on the estimated locations; with large uncertainty circles the warning is correspondingly uncertain."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (station.rat == "4G" && (station.endcAvailable || station.nrLegs.isNotEmpty())) {
                Section("5G NSA (EN-DC)") {
                    EndcSection(station)
                }
            }

            Section("OpenCelliD") {
                OcidSection(station, vm)
            }

            Section("OpenStreetMap") {
                OsmSection(station, vm)
            }

            Section(if (station.cells.size == 1) tr("Zelle", "Cell") else tr("Zellen (${station.cells.size})", "Cells (${station.cells.size})")) {
                CellTable(station.rat, station.cells, station)
            }

            if (station.linked.isNotEmpty()) {
                Section(tr("Vermutlich zugehörig (${station.linked.size})", "Probably belonging (${station.linked.size})")) {
                    LinkedCells(station)
                }
            }

            if (measurements.isNotEmpty()) {
                Section(tr("Letzte Messungen", "Latest measurements")) {
                    measurements.take(15).forEach { m -> MeasurementRow(station, m) }
                    if (measurements.size > 15) {
                        Text(
                            tr("Alle Messwerte über „Messprotokoll als CSV exportieren“ in der Stationsliste.", "All measurements via \"Export measurement log as CSV\" in the station list."),
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
private fun EstimateMap(station: BaseStation, measurements: List<MeasurementEntity>, osmNear: List<OsmMastEntity>) {
    val e = station.estimate ?: return
    val color = operatorColorRaw(station.operatorName)
    val mastDots = remember(osmNear) {
        osmNear.map { m ->
            MapDot(m.latitude, m.longitude, OsmMastColor, sizeDp = 15, title = "OpenStreetMap · ${m.description}", snippet = m.name, mast = true)
        }
    }
    val dots = remember(measurements, e, station.ocid) {
        // Ein Punkt je ~20-m-Raster, stärkster Pegel zählt.
        val spots = measurements
            .filter { it.dbm != null && (it.accuracyM ?: Float.MAX_VALUE) <= LocationEstimator.MAX_GPS_ERROR }
            .groupBy { floor(it.latitude / 0.00018) to floor(it.longitude / 0.00028) }
            .map { (_, list) -> list.maxBy { it.dbm!! } }
        spots.map { m ->
            MapDot(m.latitude, m.longitude, signalColorRaw(signalLevel(station.rat, m.dbm)), sizeDp = 9)
        } + MapDot(e.latitude, e.longitude, color, sizeDp = 18, title = station.title, snippet = "±${formatDistance(e.radiusM)}") +
            listOfNotNull(
                ocidMarker(station)?.let { m ->
                    MapDot(
                        m.latitude, m.longitude, if (m.plausible) OcidColor else OcidImplausibleGrey, sizeDp = 13,
                        title = tr("OpenCelliD-Schwerpunkt", "OpenCelliD centroid") + if (m.plausible) "" else tr(" · unplausibel", " · implausible"),
                        snippet = tr("${m.cells} Zellen, ${m.samples} Messungen", "${m.cells} cells, ${m.samples} measurements"),
                    )
                },
            )
    }
    val circles = remember(e) { listOf(MapCircle(e.latitude, e.longitude, e.radiusM, color)) }
    val ocidLines = remember(station, e) {
        listOfNotNull(
            ocidMarker(station)?.takeIf { ocidLineWorthy(station, it) }?.let { m ->
                CoverageLine(e.latitude, e.longitude, m.latitude, m.longitude, (if (m.plausible) OcidColor else OcidImplausibleGrey).toArgb(), 1.5f, dashed = true)
            },
        )
    }
    OsmMap(
        dots = mastDots + dots,
        circles = circles,
        lines = ocidLines,
        focus = MapFocus.Around(e.latitude, e.longitude, radiusM = 1_000.0),
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(12.dp)),
    )
    Text(
        tr("Punkte: deine Messorte, grün = starker, rot = schwacher Pegel. Großer Punkt und Kreis: geschätzter Mast.", "Dots: your measurement spots, green = strong, red = weak signal. Large dot and circle: estimated mast.") +
            if (ocidLines.isNotEmpty()) tr(" Lila, gestrichelt: OpenCelliD-Schwerpunkt der Station (grau = unplausibel).", " Purple, dashed: OpenCelliD centroid of the station (grey = implausible).") else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CellTable(rat: String, cells: List<CellEntity>, station: BaseStation) {
    val groups = supportsPciGroups(rat)
    val headers = listOf(
        if (rat == "4G") tr("Sektor", "Sector") else tr("Nr.", "No."),
        if (rat == "5G") "NCI" else if (rat == "4G") "ECI" else "CID",
        if (rat == "3G") "PSC" else if (rat == "2G") "BSIC" else "PCI",
        if (groups) "SSS/PSS" else "",
        "Band", "ARFCN", "max dBm", "Scans", tr("Bewertung", "Rating"),
    )
    val rows = cells.mapIndexed { i, c ->
        listOf(
            c.sector?.toString() ?: (i + 1).toString(),
            c.cellId?.toString() ?: "–",
            c.pci?.toString() ?: "–",
            if (groups) c.pci?.let { "${pciGroup(it)}/${pciPss(it)}" } ?: "–" else "",
            listOfNotNull(c.band, c.bandMhz?.let { "$it MHz" }).joinToString(" ").ifEmpty { "–" },
            c.arfcn?.toString() ?: "–",
            c.bestDbm?.toString() ?: "–",
            if (c.servingSamples > 0) tr("${c.samples} (${c.servingSamples}×v)", "${c.samples} (${c.servingSamples}×s)") else c.samples.toString(),
            de.funkstrecke.data.assessCell(station, c).reliability.short,
        )
    }
    val widths = listOf(56, 110, 56, if (groups) 72 else 0, 130, 72, 72, 96, 72)
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
                    if (widths[i] > 0) Text(
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
                        if (widths[i] > 0) Text(
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
        if (groups) {
            tr("v = als verbundene Zelle gesehen · SSS/PSS = PCI-Gruppe N_ID1 / Index N_ID2 (PCI = 3·SSS + PSS)", "s = seen as serving cell · SSS/PSS = PCI group N_ID1 / index N_ID2 (PCI = 3·SSS + PSS)")
        } else {
            tr("v = als verbundene Zelle gesehen", "s = seen as serving cell")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MeasurementRow(station: BaseStation, m: MeasurementEntity) {
    val cell = station.cells.firstOrNull { it.id == m.cellKey }
        ?: station.linked.firstOrNull { it.cell.id == m.cellKey }?.cell
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

/** Nachbarzellen ohne Cell-ID, die über die PCI-Gruppe dieser Station zugeordnet wurden. */
@Composable
private fun LinkedCells(station: BaseStation) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        station.linked.forEach { l ->
            val c = l.cell
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append("PCI ${c.pci ?: "?"}")
                                c.pci?.let { append("  (${pciGroup(it)}/${pciPss(it)})") }
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            l.confidence.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (l.confidence == LinkConfidence.LIKELY) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                        )
                    }
                    Text(
                        listOfNotNull(
                            listOfNotNull(c.band, c.bandMhz?.let { "$it MHz" }).joinToString(" ").ifEmpty { null },
                            c.arfcn?.let { "ARFCN $it" },
                            c.bestDbm?.let { "max $it dBm" },
                            "${c.samples} Scans",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    val reason = buildString {
                        val same = l.sameSectorAs
                        if (same != null) {
                            append(
                                tr(
                                    "Gleiche PCI wie Sektor ${same.sector ?: same.cellId}: vermutlich derselbe Sektor auf einem anderen Träger.",
                                    "Same PCI as sector ${same.sector ?: same.cellId}: probably the same sector on another carrier.",
                                ),
                            )
                        } else {
                            append(tr("Gleiche PCI-Gruppe, anderer PSS-Index: vermutlich ein weiterer Sektor.", "Same PCI group, different PSS index: probably another sector."))
                        }
                        l.distanceM?.let { append(tr(" Messorte ${formatDistance(it)} vom Standort entfernt.", " Measurement spots ${formatDistance(it)} from the location.")) }
                            ?: append(tr(" Noch ohne GPS-Messung, nur über die PCI-Gruppe zugeordnet.", " No GPS measurement yet, assigned via PCI group only."))
                    }
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            tr("Diese Zellen melden keine Cell-ID. Zugeordnet nach der üblichen Netzplanung, bei der alle Sektoren ", "These cells report no cell ID. Assigned according to usual network planning, where all sectors ") +
                tr("eines Standorts dieselbe PCI-Gruppe (SSS) nutzen, und geprüft über den Abstand der Messorte. ", "of a site use the same PCI group (SSS), and checked via the distance of the measurement spots. ") +
                tr("PCIs werden in einigen Kilometern Entfernung wiederverwendet; die Zuordnung ist deshalb eine Vermutung.", "PCIs are reused a few kilometres away; the assignment is therefore an assumption."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** EN-DC-Nutzung je Sektor und die gemessenen NR-Zweige dieser LTE-Station. */
@Composable
private fun EndcSection(station: BaseStation) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when {
                station.endcConnected -> tr("Diese Station diente als LTE-Anker für 5G NSA: Das Handy war über sie mit einer NR-Zelle verbunden.", "This station served as LTE anchor for 5G NSA: the phone was connected to an NR cell through it.")
                else -> tr("Diese Station kündigt 5G NSA an, eine NR-Verbindung wurde aber noch nicht gemessen.", "This station announces 5G NSA, but no NR connection has been measured yet.")
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        station.cells.filter { it.endcAvailableSamples > 0 }.forEach { c ->
            Text(
                tr("Sektor", "Sector") + " ${c.sector ?: c.cellId ?: "?"} · ${c.band ?: ""}: " +
                    tr("verbunden in ${c.endcConnectedSamples}, ", "connected in ${c.endcConnectedSamples}, ") +
                    tr("verfügbar in ${c.endcAvailableSamples} von ${c.servingSamples} Scans als verbundene Zelle", "available in ${c.endcAvailableSamples} of ${c.servingSamples} scans as serving cell"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (station.nrLegs.isNotEmpty()) {
            Text(tr("NR-Zweige", "NR legs"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    station.nrLegs.forEach { l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOfNotNull(
                                    listOfNotNull(l.band, l.bandMhz?.let { "$it MHz" }).joinToString(" ").ifEmpty { "NR" },
                                    l.pci?.let { "PCI $it (${pciGroup(it)}/${pciPss(it)})" },
                                    l.arfcn?.let { "ARFCN $it" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                listOfNotNull(l.bestDbm?.let { "max $it dBm" }, "${l.samples}×").joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            Text(
                tr("Der NR-Zweig steht meist am selben Mast, kann aber auch von einem anderen Standort kommen. ", "The NR leg is usually on the same mast but may also come from another site. ") +
                    tr("Er fließt deshalb nicht in die Standortschätzung dieser Station ein.", "It is therefore not used in this station's location estimate."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Farbe der OpenCelliD-Positionen auf der Karte */
private val OcidColor = androidx.compose.ui.graphics.Color(0xFF7B3FB0)
private val OcidImplausibleGrey = androidx.compose.ui.graphics.Color(0xFF8A8F96)
private val OsmMastColor = androidx.compose.ui.graphics.Color(0xFF3D4650)

/** Nächster Funkmast aus OpenStreetMap. */
@Composable
private fun OsmSection(station: BaseStation, vm: ScannerViewModel) {
    val progress by vm.osmProgress.collectAsStateWithLifecycle()
    val e = station.estimate
    val mast = station.osmMast
    val d = station.osmMastDistanceM
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when {
                e == null -> tr("Ohne Standortschätzung lässt sich kein Mast zuordnen.", "No mast can be matched without a location estimate.")
                mast != null && d != null -> tr("Nächster Funkmast: ${formatDistance(d)} entfernt", "Nearest radio mast: ${formatDistance(d)} away") +
                    "\n" + mast.description + (mast.name?.let { " · $it" } ?: "") +
                    when {
                        station.osmConfirmed && !station.osmUnambiguous -> tr(
                            "\nBestätigt die Schätzung (≤ 100 m). Achtung: Der nächste andere Maststandort liegt nur ${formatDistance(station.osmOtherSiteDistanceM ?: 0.0)} von der Schätzung entfernt; die Nähe kann Zufall sein.",
                            "\nConfirms the estimate (≤ 100 m). Note: the next other mast site is only ${formatDistance(station.osmOtherSiteDistanceM ?: 0.0)} from the estimate; the proximity may be coincidence.",
                        )
                        station.osmConfirmed -> tr("\nBestätigt die Schätzung (≤ 100 m, kein anderer Maststandort im Umkreis von 300 m).", "\nConfirms the estimate (≤ 100 m, no other mast site within 300 m).")
                        station.osmMastNear -> tr(
                            "\nNur ein Hinweis, keine Bestätigung: Der nächste andere Maststandort liegt ${formatDistance(station.osmOtherSiteDistanceM ?: 0.0)} von der Schätzung entfernt. Bei so dichten Masten kann die Nähe Zufall sein.",
                            "\nOnly a hint, not a confirmation: the next other mast site is ${formatDistance(station.osmOtherSiteDistanceM ?: 0.0)} from the estimate. With masts this dense, the proximity may be coincidence.",
                        )
                        else -> ""
                    }
                progress?.running == true -> tr("Lade Funkmasten aus OpenStreetMap …", "Loading radio masts from OpenStreetMap …")
                progress?.error != null && !station.osmChecked -> tr("Nicht geladen: ", "Not loaded: ") + progress?.error
                station.osmChecked -> tr(
                    "Kein Funkmast in OpenStreetMap im Umkreis von 1 km. Das spricht nicht gegen die Station: Dachstandorte sind dort selten eingetragen.",
                    "No radio mast in OpenStreetMap within 1 km. That does not count against the station: rooftop sites are rarely mapped there.",
                )
                else -> tr("Noch nicht geladen.", "Not loaded yet.")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            tr(
                "Funkmasten und -türme, die Freiwillige in OpenStreetMap eingetragen haben (Dreiecke auf der Karte). " +
                    "Die Positionen sind echte Maststandorte, aber ohne Cell-ID und unvollständig. Daten: © OpenStreetMap-Mitwirkende, ODbL.",
                "Radio masts and towers mapped by volunteers in OpenStreetMap (triangles on the map). " +
                    "The positions are real mast locations, but without cell IDs and incomplete. Data: © OpenStreetMap contributors, ODbL.",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (e != null && progress?.running != true) {
            OutlinedButton(onClick = { vm.loadOsmAround(e.latitude, e.longitude) }) {
                Text(if (station.osmChecked) tr("Erneut prüfen", "Check again") else tr("Masten laden", "Load masts"))
            }
        }
    }
}

/** Vergleich mit OpenCelliD: je Zelle gefunden/unbekannt, Position, Abstand zur eigenen Schätzung. */
@Composable
private fun OcidSection(station: BaseStation, vm: ScannerViewModel) {
    val key by de.funkstrecke.util.SettingsStore.openCellIdKey.collectAsStateWithLifecycle()
    val progress by vm.ocidProgress.collectAsStateWithLifecycle()
    val lookupable = station.cells.filter { de.funkstrecke.data.OpenCellId.canLookup(it) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            lookupable.isEmpty() -> Text(
                tr("Diese Station hat keine Zelle mit vollständiger Kennung (MCC, MNC, TAC, Cell-ID). OpenCelliD kann sie nicht nachschlagen.", "This station has no cell with a complete identity (MCC, MNC, TAC, cell ID). OpenCelliD cannot look it up."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            station.ocid.isEmpty() -> Text(
                tr("Noch nicht abgeglichen.", "Not compared yet."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val distance = station.ocidDistanceM
                Text(
                    when {
                        station.ocidPosition == null && station.ocidImplausible.isNotEmpty() -> tr(
                            "Die OpenCelliD-Treffer dieser Station sind unplausibel (zu weit von deiner Schätzung) und werden nicht berücksichtigt.",
                            "This station's OpenCelliD matches are implausible (too far from your estimate) and are not used.",
                        )
                        station.ocidUnknown -> tr("Keine Zelle dieser Station ist in OpenCelliD verzeichnet. Sie ist neu oder wurde dort noch nicht erfasst.", "No cell of this station is listed in OpenCelliD. It is new or has not been recorded there yet.")
                        distance != null -> tr("Der OpenCelliD-Schwerpunkt (${station.ocidPlausible.size} Zellen, ${station.ocidSamples} Messungen, nach Messungen gewichtet) liegt ${formatDistance(distance)} von deiner Schätzung entfernt.", "The OpenCelliD centroid (${station.ocidPlausible.size} cells, ${station.ocidSamples} measurements, weighted by measurements) is ${formatDistance(distance)} from your estimate.")
                        else -> tr("In OpenCelliD verzeichnet. Für einen Vergleich fehlt noch deine Standortschätzung.", "Listed in OpenCelliD. Your location estimate is still missing for a comparison.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                station.ocid.forEach { o ->
                    val cell = station.cells.firstOrNull { it.id == o.cellKey }
                    Text(
                        tr("Sektor", "Sector") + " ${cell?.sector ?: cell?.cellId ?: "?"}: " + when (o.status) {
                            de.funkstrecke.data.OcidEntity.STATUS_FOUND -> listOfNotNull(
                                formatCoordinates(o.latitude ?: 0.0, o.longitude ?: 0.0),
                                o.samples?.let { tr("$it Messungen", "$it measurements") },
                                o.rangeM?.let { tr("Reichweite ${formatDistance(it.toDouble())}", "range ${formatDistance(it.toDouble())}") },
                            ).joinToString(" · ")
                            de.funkstrecke.data.OcidEntity.STATUS_NOT_FOUND -> tr("nicht in OpenCelliD", "not in OpenCelliD")
                            else -> tr("Fehler: ", "Error: ") + (o.message ?: tr("unbekannt", "unknown"))
                        } + " (${formatDateTime(o.fetchedAt)})",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    station.ocidImplausibleReason(o)?.let { reason ->
                        Text(
                            tr("  ↳ unplausibel: ", "  ↳ implausible: ") + reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    tr("OpenCelliD mittelt die Orte, an denen Nutzer die Zelle gemessen haben. Das ist meist nicht der Mast, ", "OpenCelliD averages the places where users measured the cell. That is usually not the mast, ") +
                        tr("sondern liegt in Richtung der Hauptversorgung. Abweichungen von einigen hundert Metern sind normal. ", "but lies in the main coverage direction. Deviations of a few hundred metres are normal. ") +
                        tr("Daten: OpenCelliD, CC BY-SA 4.0.", "Data: OpenCelliD, CC BY-SA 4.0."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (lookupable.isNotEmpty()) {
            if (key.isBlank()) {
                Text(
                    tr("Für den Abgleich einen OpenCelliD-Schlüssel in den Einstellungen eintragen.", "Enter an OpenCelliD key in settings for the comparison."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                OutlinedButton(
                    onClick = { vm.compareWithOpenCellId(station) },
                    enabled = progress?.running != true,
                ) { Text(if (station.ocid.isEmpty()) tr("Mit OpenCelliD abgleichen", "Compare with OpenCelliD") else tr("Erneut abgleichen", "Compare again")) }
            }
        }
    }
}
