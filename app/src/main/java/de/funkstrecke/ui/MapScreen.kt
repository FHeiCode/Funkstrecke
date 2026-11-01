package de.funkstrecke.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.funkstrecke.ui.theme.operatorColorRaw
import de.funkstrecke.util.LocationHelper
import de.funkstrecke.util.formatDistance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(vm: ScannerViewModel, onOpen: (String) -> Unit) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val located = remember(stations) { stations.filter { it.estimate != null } }
    val context = LocalContext.current
    val canLocate = remember { LocationHelper.hasPermission(context) }
    var recenterKey by rememberSaveable { mutableIntStateOf(0) }

    // Kreise nur, wenn sie aussagekräftig sind; sehr grobe Schätzungen würden die Karte zudecken.
    val circles = remember(located) {
        located.mapNotNull { s ->
            val e = s.estimate!!
            if (e.radiusM > 2_000) null else MapCircle(e.latitude, e.longitude, e.radiusM, operatorColorRaw(s.operatorName))
        }
    }
    val dots = remember(located) {
        located.map { s ->
            val e = s.estimate!!
            MapDot(
                latitude = e.latitude,
                longitude = e.longitude,
                color = operatorColorRaw(s.operatorName),
                sizeDp = 16,
                title = s.title,
                snippet = "${s.operatorName ?: "Unbekannt"} · ±${formatDistance(e.radiusM)}",
                onClick = { onOpen(s.key) },
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text("Karte")
                        Text(
                            "${located.size} von ${stations.size} Stationen verortet",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OsmMap(
                dots = dots,
                circles = circles,
                modifier = Modifier.fillMaxSize(),
                focus = MapFocus.OwnPosition(radiusM = 1_000.0),
                recenterKey = recenterKey,
            )
            if (canLocate) {
                SmallFloatingActionButton(
                    onClick = { recenterKey++ },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Auf meine Position zentrieren")
                }
            }
            if (located.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        "Noch keine Station verortet. Starte im Live-Tab die Aufzeichnung und bewege dich durch die Umgebung.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
