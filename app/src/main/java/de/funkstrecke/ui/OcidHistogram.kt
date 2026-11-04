package de.funkstrecke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.funkstrecke.data.BaseStation
import de.funkstrecke.data.distanceM
import de.funkstrecke.util.formatDistance

/** Abweichung einer Zelle: eigene Mast-Schätzung der Station gegen OpenCelliD-Position der Zelle. */
data class OcidDeviation(
    val stationKey: String,
    val cellKey: String,
    val distanceM: Double,
    /** OpenCelliD-Position liegt innerhalb des eigenen 95-%-Unsicherheitskreises */
    val withinRadius: Boolean,
)

/** Klassen des Histogramms: Obergrenzen in m (die letzte ist offen). */
private val binEdges = listOf(50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, Double.MAX_VALUE)
private val binLabels = listOf("<50", "50–\n100", "100–\n200", "200–\n500", "0,5–\n1 km", "1–\n2 km", ">2 km")

fun ocidBinOf(distanceM: Double): Int = binEdges.indexOfFirst { distanceM < it }.coerceAtLeast(0)

fun ocidDeviations(stations: List<BaseStation>): List<OcidDeviation> = stations.flatMap { s ->
    val e = s.estimate ?: return@flatMap emptyList()
    s.ocid.filter { it.found && it.latitude != null && it.longitude != null }.map { o ->
        val d = distanceM(e.latitude, e.longitude, o.latitude!!, o.longitude!!)
        OcidDeviation(s.key, o.cellKey, d, d <= e.radiusM)
    }
}

/**
 * Histogramm der Abweichungen zu OpenCelliD. Ein Tipp auf einen Balken wählt die Klasse aus
 * ([onSelectBin]), ein zweiter Tipp hebt die Auswahl auf.
 */
@Composable
fun OcidHistogramCard(
    deviations: List<OcidDeviation>,
    notFoundCells: Int,
    selectedBin: Int?,
    onSelectBin: (Int?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val counts = IntArray(binEdges.size).also { arr -> deviations.forEach { arr[ocidBinOf(it.distanceM)]++ } }
    val max = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    val sorted = deviations.map { it.distanceM }.sorted()
    val median = if (sorted.isEmpty()) null else if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    val within = deviations.count { it.withinRadius }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Abweichung zu OpenCelliD", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append("${deviations.size} Zellen")
                            median?.let { append(" · Median ${formatDistance(it)}") }
                            if (deviations.isNotEmpty()) append(" · ${within * 100 / deviations.size} % im eigenen Unsicherheitskreis")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Einklappen" else "Ausklappen",
                )
            }
            if (!expanded) return@Column

            if (deviations.isEmpty()) {
                Text(
                    "Noch keine Vergleichswerte: Dafür braucht eine Station deine Standortschätzung und mindestens eine in OpenCelliD gefundene Zelle.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    counts.forEachIndexed { i, n ->
                        val selected = selectedBin == i
                        val dimmed = selectedBin != null && !selected
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = n > 0 || selected) { onSelectBin(if (selected) null else i) }
                                .padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (n > 0) n.toString() else "",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(2.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth(0.8f)
                                    .height((4 + 96f * n / max).dp)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(
                                        when {
                                            n == 0 -> MaterialTheme.colorScheme.surfaceVariant
                                            dimmed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                    ),
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    binLabels.forEachIndexed { i, label ->
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            fontWeight = if (selectedBin == i) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedBin == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    if (selectedBin != null) {
                        "Liste zeigt nur Stationen mit einer Zelle in dieser Klasse. Erneut tippen zum Aufheben."
                    } else {
                        "Abstand in m zwischen deiner Mast-Schätzung und der OpenCelliD-Position je Zelle. Balken antippen, um die Liste zu filtern."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (notFoundCells > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(10.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$notFoundCells ${if (notFoundCells == 1) "Zelle" else "Zellen"} nicht in OpenCelliD",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(
                "OpenCelliD mittelt Nutzer-Messorte und liegt deshalb meist in Richtung der Hauptversorgung, nicht am Mast. " +
                    "Einige hundert Meter Abweichung sind normal.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
