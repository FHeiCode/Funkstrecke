package de.funkstrecke.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.funkstrecke.service.Geofencer
import de.funkstrecke.service.HomeStatus
import de.funkstrecke.util.HomeStore
import de.funkstrecke.util.LocationHelper
import de.funkstrecke.util.formatCoordinates
import de.funkstrecke.util.formatDistance
import de.funkstrecke.util.tr
import kotlinx.coroutines.launch

/** Farbe der Heimatbasis auf allen Karten */
// Magenta: hebt sich von Betreiber-, Signal- und Bewertungsfarben ab.
val HomeBaseColor = Color(0xFFD0108A)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeBaseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val home by HomeStore.flow.collectAsStateWithLifecycle()
    val status by Geofencer.status.collectAsStateWithLifecycle()
    var locating by remember { mutableStateOf(false) }
    var recenterKey by remember { mutableIntStateOf(0) }
    // Wird bei Rückkehr aus den Systemeinstellungen neu geprüft
    var hasBackground by remember { mutableStateOf(HomeStore.hasBackgroundLocation(context)) }
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun change(sync: Boolean = true, transform: (de.funkstrecke.util.HomeBase) -> de.funkstrecke.util.HomeBase) {
        HomeStore.update(transform)
        if (sync) Geofencer.sync(context)
    }

    // Nach den Systemeinstellungen (Hintergrund-Standort, Akku) Zustand neu lesen und neu anmelden.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasBackground = HomeStore.hasBackgroundLocation(context)
                batteryExempt = isIgnoringBatteryOptimizations(context)
                Geofencer.sync(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasBackground = granted || HomeStore.hasBackgroundLocation(context)
        if (hasBackground) {
            change { it.copy(enabled = true) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            message(tr("Ohne „Standort: Immer zulassen“ kann Android die App beim Verlassen nicht wecken.", "Without \"Location: Allow all the time\", Android cannot wake the app when you leave."))
        }
    }

    fun enable() {
        if (!home.hasPoint) {
            message(tr("Lege zuerst einen Punkt fest.", "Set a point first."))
            return
        }
        if (!LocationHelper.hasPermission(context)) {
            message(tr("Erteile zuerst im Live-Tab die Standortfreigabe.", "Grant location permission in the Live tab first."))
            return
        }
        if (!HomeStore.hasBackgroundLocation(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Ab Android 11 öffnet das die Einstellungsseite; dort „Immer zulassen“ wählen.
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }
        change { it.copy(enabled = true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun useCurrentPosition() {
        locating = true
        scope.launch {
            val here = LocationHelper.currentLocation(context)
            locating = false
            if (here == null) {
                message(tr("Keine Position ermittelt. Ist der Standort eingeschaltet?", "No position found. Is location turned on?"))
            } else {
                change { it.copy(latitude = here.latitude, longitude = here.longitude) }
                recenterKey++
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(tr("Heimatbasis", "Home base")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Zurück", "Back")) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StatusCard(status, home.enabled)

            Section(tr("Ort", "Location")) {
                val lat = home.latitude
                val lon = home.longitude
                val dots = if (lat != null && lon != null) listOf(MapDot(lat, lon, HomeBaseColor, sizeDp = 14)) else emptyList()
                val circles = if (lat != null && lon != null) {
                    listOf(MapCircle(lat, lon, home.radiusM.toDouble(), HomeBaseColor))
                } else {
                    emptyList()
                }
                OsmMap(
                    dots = dots,
                    circles = circles,
                    focus = if (lat != null && lon != null) {
                        MapFocus.Around(lat, lon, radiusM = maxOf(home.radiusM * 2.0, 500.0))
                    } else {
                        MapFocus.OwnPosition(radiusM = 1_000.0)
                    },
                    recenterKey = recenterKey,
                    onLongPress = { la, lo -> change { it.copy(latitude = la, longitude = lo) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Text(
                    tr("Lange auf die Karte drücken, um die Heimatbasis zu setzen oder zu verschieben.", "Long-press the map to set or move the home base."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { useCurrentPosition() }, enabled = !locating) {
                        if (locating) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        } else {
                            Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (locating) tr("Suche Position …", "Locating …") else tr("Aktuelle Position verwenden", "Use current position"))
                    }
                }
                if (lat != null && lon != null) {
                    KeyValue(tr("Mittelpunkt", "Center"), formatCoordinates(lat, lon))
                }
            }

            Section("Radius") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 200, 300, 500, 1000, 2000).forEach { r ->
                        FilterChip(
                            selected = home.radiusM == r,
                            onClick = { change { it.copy(radiusM = r) } },
                            label = { Text(formatDistance(r.toDouble())) },
                        )
                    }
                }
                Text(
                    tr("Android meldet das Verlassen meist mit 1–3 Minuten Verzögerung, und Kreise unter 150 m ", "Android usually reports leaving with a 1–3 minute delay, and circles under 150 m ") +
                        tr("lösen oft unzuverlässig aus. 200–300 m sind ein guter Kompromiss.", "often trigger unreliably. 200–300 m is a good compromise."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(tr("Automatik", "Automation")) {
                SwitchRow(
                    title = tr("Aufzeichnung beim Verlassen starten", "Start recording when leaving"),
                    description = tr("Startet die normale Aufzeichnung mit deinem Energiemodus, sobald du den Kreis verlässt.", "Starts normal recording with your power mode as soon as you leave the circle."),
                    checked = home.enabled,
                    onChange = { on -> if (on) enable() else change { it.copy(enabled = false) } },
                )
                SwitchRow(
                    title = tr("Bei Rückkehr stoppen", "Stop on return"),
                    description = tr("Beendet die Aufzeichnung, wenn du den Kreis wieder betrittst.", "Ends recording when you re-enter the circle."),
                    checked = home.stopOnReturn,
                    onChange = { on -> change(sync = false) { it.copy(stopOnReturn = on) } },
                )
                if (!hasBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Text(
                        tr("Benötigt „Standort: Immer zulassen“. Beim Einschalten öffnet sich die Einstellung; dort „Immer zulassen“ wählen.", "Requires \"Location: Allow all the time\". Switching on opens the setting; choose \"Allow all the time\" there."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            if (!batteryExempt) {
                Section(tr("Akku-Optimierung", "Battery optimization")) {
                    Text(
                        tr("Einige Hersteller (z. B. Samsung, Xiaomi, Huawei) blockieren das Aufwecken im Hintergrund. ", "Some manufacturers (e.g. Samsung, Xiaomi, Huawei) block background wake-ups. ") +
                            tr("Nimm Funkstrecke von der Akku-Optimierung aus, wenn die Aufzeichnung nicht startet.", "Exclude Funkstrecke from battery optimization if recording does not start."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }.onFailure {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }) { Text(tr("Akku-Einstellungen öffnen", "Open battery settings")) }
                }
            }

            if (home.hasPoint) {
                TextButton(onClick = {
                    change { it.copy(latitude = null, longitude = null, enabled = false) }
                }) { Text(tr("Heimatbasis entfernen", "Remove home base"), color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusCard(status: HomeStatus, enabled: Boolean) {
    val (title, text, ok) = when (status) {
        HomeStatus.Active -> Triple(tr("Aktiv", "Active"), tr("Die Aufzeichnung startet, sobald du den Kreis verlässt.", "Recording starts as soon as you leave the circle."), true)
        HomeStatus.Registering -> Triple(tr("Wird angemeldet …", "Registering …"), tr("Einen Moment.", "One moment."), true)
        HomeStatus.Off -> Triple(tr("Aus", "Off"), tr("Lege einen Ort und einen Radius fest und schalte die Automatik ein.", "Set a location and a radius and switch on the automation."), false)
        HomeStatus.NoPoint -> Triple(tr("Kein Ort festgelegt", "No location set"), tr("Drücke lange auf die Karte oder verwende die aktuelle Position.", "Long-press the map or use the current position."), false)
        HomeStatus.NeedsLocationPermission -> Triple(tr("Standortfreigabe fehlt", "Location permission missing"), tr("Erteile im Live-Tab die Standortfreigabe.", "Grant location permission in the Live tab."), false)
        HomeStatus.NeedsBackgroundPermission -> Triple(
            tr("„Immer zulassen“ fehlt", "\"Allow all the time\" missing"),
            tr("Schalte die Automatik aus und wieder ein und wähle in der Einstellung „Standort: Immer zulassen“.", "Switch the automation off and on again and choose \"Location: Allow all the time\" in the setting."),
            false,
        )
        HomeStatus.NoPlayServices -> Triple(
            tr("Google Play-Dienste fehlen", "Google Play services missing"),
            tr("Die Heimatbasis nutzt das Geofencing der Google-Standortdienste. Auf diesem Gerät sind sie nicht verfügbar.", "The home base uses geofencing from Google location services. They are not available on this device."),
            false,
        )
        is HomeStatus.Failed -> Triple(tr("Nicht aktiv", "Not active"), status.message, false)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                ok && enabled -> MaterialTheme.colorScheme.primaryContainer
                !ok && enabled -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
