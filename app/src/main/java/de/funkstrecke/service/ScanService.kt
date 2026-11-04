package de.funkstrecke.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.funkstrecke.MainActivity
import de.funkstrecke.R
import de.funkstrecke.data.AppDatabase
import de.funkstrecke.data.Recorder
import de.funkstrecke.radio.CellScanner
import de.funkstrecke.radio.EndcMonitor
import de.funkstrecke.radio.LiveScan
import de.funkstrecke.util.Busy
import de.funkstrecke.util.CallBehavior
import de.funkstrecke.util.CallGuard
import de.funkstrecke.util.LocationHelper
import de.funkstrecke.util.PauseStore
import de.funkstrecke.util.SettingsStore
import de.funkstrecke.util.formatDistance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.os.SystemClock
import de.funkstrecke.radio.CellRecord
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt

/** In welcher Lage sich die Aufzeichnung gerade befindet. */
enum class RecordPhase {
    /** Noch keine Position seit dem Start */
    WAITING,

    /** Genaue Position, in Bewegung: Scan mit Messpunkt */
    MOVING,

    /** Genaue Position, aber kaum Bewegung: nur gelegentlich Zellen auffrischen */
    STATIONARY,

    /** Nur grobe Position (Zug, Tunnel, Gebäude): Zellen weiter erfassen, Messpunkte zählen nicht für die Schätzung */
    COARSE,

    /** Vom Nutzer pausiert: GPS aus, keine Scans */
    PAUSED,
}

/** Zustand der Hintergrund-Aufzeichnung, für die Oberfläche beobachtbar. */
data class LogState(
    val running: Boolean = false,
    val startedAt: Long? = null,
    val scans: Int = 0,
    val measurements: Int = 0,
    val lastFixAt: Long? = null,
    val lastAccuracyM: Float? = null,
    val waitingForGps: Boolean = false,
    /** Von der Heimatbasis gestartet statt von Hand */
    val autoStarted: Boolean = false,
    val phase: RecordPhase = RecordPhase.WAITING,
    val phaseSince: Long? = null,
    /** Letzter Fix mit ≤ 30 m Genauigkeit */
    val lastPreciseFixAt: Long? = null,
    /** Ortungsquelle: Google-Standortdienst (GPS + WLAN + Mobilfunk) oder nur Android */
    val fused: Boolean = false,
    /** Telefonat oder starker Datenverkehr; Scans werden nach den Einstellungen angepasst */
    val busy: Busy = Busy.NONE,
)

/**
 * Vordergrunddienst: nimmt bei Positionsmeldungen Zell-Scans auf.
 *
 * - In Bewegung mit genauer Position: Scan mit Messpunkt (Grundlage der Standortschätzung),
 *   frühestens nach Intervall und Mindestabstand aus den Einstellungen.
 * - Im Stand: einmal pro Minute nur die Zellen auffrischen, ohne Messpunkt.
 * - Ohne genaue Position (Zug, Tunnel): Zellen im normalen Takt weiter erfassen; die Messpunkte
 *   tragen ihre grobe Genauigkeit und werden von der Schätzung ignoriert.
 *
 * Ortung bevorzugt über den Google-Standortdienst (schnellerer Fix, Rückfall auf WLAN/Mobilfunk),
 * sonst GPS und Netzwerk von Android parallel.
 */
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tracking = false
    private var fusedClient: FusedLocationProviderClient? = null
    private var fusedCallback: LocationCallback? = null
    private val lmListeners = mutableListOf<LocationListener>()

    private var lastScanAt = 0L
    /** Ort des letzten Messpunkts mit genauer Position */
    private var lastMeasuredLocation: Location? = null
    /** Letzte genaue Position, um GPS-Zittern im Stand von Bewegung zu unterscheiden */
    private var lastPreciseLocation: Location? = null
    private var scanJob: Job? = null
    private var settingsJob: Job? = null
    private var autoStarted = false
    private var started = false
    private var pauseJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, -1)
                PauseStore.pause(this, if (minutes > 0) minutes else null)
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                PauseStore.resume(this)
                return START_NOT_STICKY
            }
        }
        if (started) return START_NOT_STICKY
        SettingsStore.init(this)
        EndcMonitor.start(this)
        autoStarted = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        if (!LocationHelper.hasPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Warte auf erste Position …"), type)
        } catch (e: Exception) {
            // Z. B. fehlt „Standort: Immer zulassen“ für einen Start aus dem Hintergrund.
            if (autoStarted) Geofencer.notifyStartFailed(this)
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        val now = System.currentTimeMillis()
        _state.value = LogState(
            running = true,
            startedAt = now,
            waitingForGps = true,
            autoStarted = autoStarted,
            phase = RecordPhase.WAITING,
            phaseSince = now,
        )
        // Pause beachten: Während einer Pause sind GPS und Scans aus, danach geht es von selbst weiter.
        PauseStore.init(this)
        pauseJob = scope.launch {
            PauseStore.pausedUntil.collect { until ->
                if (until > System.currentTimeMillis()) {
                    stopLocationUpdates()
                    setPhase(RecordPhase.PAUSED, System.currentTimeMillis())
                } else {
                    if (_state.value.phase == RecordPhase.PAUSED) setPhase(RecordPhase.WAITING, System.currentTimeMillis())
                    if (!tracking && !startLocationUpdates()) updateNotification("Standort ist ausgeschaltet")
                }
                refreshNotification()
            }
        }
        // Geänderte Einstellungen sofort übernehmen.
        settingsJob = scope.launch {
            SettingsStore.flow.drop(1).collect {
                if (PauseStore.isPaused()) return@collect
                stopLocationUpdates()
                startLocationUpdates()
            }
        }
        return START_NOT_STICKY
    }

    private fun playServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS

    /** Positionsintervall: so oft wie gescannt wird, aber nie seltener als alle 10 s, damit Stillstand und Weiterfahrt schnell erkannt werden. */
    private fun locationIntervalMs(): Long =
        (SettingsStore.current.recordIntervalS * 1000L).coerceIn(2_000L, 10_000L)

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(): Boolean {
        val interval = locationIntervalMs()
        if (playServicesAvailable()) {
            val client = LocationServices.getFusedLocationProviderClient(this)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                .setMinUpdateIntervalMillis(1_000L)
                .setWaitForAccurateLocation(false)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let(::onFix)
                }
            }
            return try {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                fusedClient = client
                fusedCallback = callback
                tracking = true
                _state.update { it.copy(fused = true) }
                true
            } catch (e: SecurityException) {
                false
            }
        }
        // Ohne Google-Dienste: GPS und Netzwerk parallel, damit im Zug wenigstens grobe Positionen kommen.
        val lm = getSystemService(LocationManager::class.java) ?: return false
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return false
        for (provider in providers) {
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) = onFix(location)

                // Auf Android 8–9 sind diese Methoden noch abstrakt und müssen existieren.
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            try {
                lm.requestLocationUpdates(provider, interval, 0f, l, Looper.getMainLooper())
                lmListeners += l
            } catch (_: SecurityException) {
            }
        }
        tracking = lmListeners.isNotEmpty()
        _state.update { it.copy(fused = false) }
        return tracking
    }

    private fun stopLocationUpdates() {
        fusedCallback?.let { cb -> fusedClient?.removeLocationUpdates(cb) }
        fusedCallback = null
        fusedClient = null
        val lm = getSystemService(LocationManager::class.java)
        lmListeners.forEach { lm?.removeUpdates(it) }
        lmListeners.clear()
        tracking = false
    }

    private fun setPhase(phase: RecordPhase, now: Long) {
        _state.update { if (it.phase == phase) it else it.copy(phase = phase, phaseSince = now) }
    }

    private fun onFix(location: Location) {
        if (PauseStore.isPaused()) return
        val now = System.currentTimeMillis()
        val settings = SettingsStore.current
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        val precise = accuracy <= PRECISE_ACCURACY_M

        // Eine grobe Netzwerkposition darf eine frische GPS-Position nicht überschreiben (beide Quellen liefern parallel).
        if (!precise && now - (_state.value.lastPreciseFixAt ?: 0L) < 15_000) return

        _state.update {
            it.copy(
                waitingForGps = false,
                lastAccuracyM = if (location.hasAccuracy()) location.accuracy else null,
                lastPreciseFixAt = if (precise) now else it.lastPreciseFixAt,
            )
        }
        if (scanJob?.isActive == true) return
        if (now - lastScanAt < settings.recordIntervalS * 1000L - 500) return

        if (precise) {
            // Bewegung nur zählen, wenn sie größer ist als Mindestabstand UND Ungenauigkeit beider Positionen,
            // sonst löst GPS-Zittern im Stand Messpunkte aus.
            val previous = lastMeasuredLocation
            val jitter = accuracy + (previous?.takeIf { it.hasAccuracy() }?.accuracy ?: 0f)
            val threshold = max(settings.recordMinDistanceM.toFloat(), jitter)
            val moved = previous == null || location.distanceTo(previous) >= threshold
            lastPreciseLocation = location
            if (moved) {
                setPhase(RecordPhase.MOVING, now)
                lastMeasuredLocation = location
                scan(location, now, withMeasurement = true)
            } else {
                setPhase(RecordPhase.STATIONARY, now)
                // Im Stand trotzdem regelmäßig die Zellen auffrischen (neue Nachbarn, Zellwechsel).
                if (now - lastScanAt >= STATIONARY_REFRESH_MS) scan(location, now, withMeasurement = false)
                else refreshNotification()
            }
        } else {
            // Grobe Position: Zellen im normalen Takt weiter erfassen. Der Messpunkt trägt die grobe
            // Genauigkeit und wird von der Standortschätzung (≤ 30 m) automatisch ausgelassen.
            setPhase(RecordPhase.COARSE, now)
            scan(location, now, withMeasurement = location.hasAccuracy() && accuracy <= COARSE_STORE_ACCURACY_M)
        }
    }

    private fun scan(location: Location, now: Long, withMeasurement: Boolean) {
        // Während eines Telefonats das Modem nicht stören (Einstellung „Telefonate“).
        val decision = CallGuard.decide(this, SettingsStore.current.forceRefresh, lastScanAt, now)
        if (decision.busy != _state.value.busy) {
            _state.update { it.copy(busy = decision.busy) }
            refreshNotification()
        }
        val forceRefresh = decision.forceRefresh ?: return
        lastScanAt = now
        scanJob = scope.launch {
            val cells = CellScanner.scan(this@ScanService, forceRefresh)
            val endc = EndcMonitor.snapshot(this@ScanService, cells)
            _lastScan.value = LiveScan(cells, now, endc)
            val position = if (withMeasurement) positionAtMeasurement(location, cells) else null
            Recorder.record(AppDatabase.get(this@ScanService), cells, position, now, endc)
            val measured = if (withMeasurement) cells.count { it.dbm != null } else 0
            _state.update {
                it.copy(
                    scans = it.scans + 1,
                    measurements = it.measurements + measured,
                    lastFixAt = now,
                )
            }
            refreshNotification(cells.size)
        }
    }

    /**
     * Rechnet die Position mit Geschwindigkeit und Fahrtrichtung auf den Zeitpunkt der Zellmessung um.
     * Zwischen GPS-Fix und Messung liegen oft 1–6 s; bei 200 km/h sind das bis zu 300 m.
     */
    private fun positionAtMeasurement(fix: Location, cells: List<CellRecord>): Location {
        if (!fix.hasSpeed() || fix.speed < MIN_EXTRAPOLATION_SPEED) return fix
        val fixAt = fix.elapsedRealtimeNanos / 1_000_000
        val nowElapsed = SystemClock.elapsedRealtime()
        // Zeitstempel der verbundenen Zelle bevorzugen; unplausible Werte durch das Scan-Ende ersetzen.
        val stamps = (cells.filter { it.registered }.mapNotNull { it.measuredAtMs }.ifEmpty { cells.mapNotNull { it.measuredAtMs } })
            .filter { it in (fixAt - 15_000)..(nowElapsed + 1_000) }
            .sorted()
        val cellAt = stamps.getOrNull(stamps.size / 2) ?: nowElapsed
        val dt = ((cellAt - fixAt) / 1000.0).coerceIn(-MAX_EXTRAPOLATION_S, MAX_EXTRAPOLATION_S)
        if (abs(dt) < 0.3) return fix
        val distance = fix.speed * dt
        val out = Location(fix)
        if (fix.hasBearing()) {
            val bearing = Math.toRadians(fix.bearing.toDouble())
            out.latitude = fix.latitude + distance * cos(bearing) / 110_540.0
            out.longitude = fix.longitude + distance * sin(bearing) / (111_320.0 * cos(Math.toRadians(fix.latitude)))
            // Unsicherheit von Geschwindigkeit und Richtung: etwa 10 % der hochgerechneten Strecke.
            out.accuracy = fix.accuracy + abs(distance).toFloat() * 0.1f
        } else {
            // Ohne Fahrtrichtung bleibt der Ort, die Ungenauigkeit wächst um die gefahrene Strecke.
            out.accuracy = fix.accuracy + abs(distance).toFloat()
        }
        return out
    }

    private var lastCellCount = 0

    private fun refreshNotification(cellCount: Int = lastCellCount) {
        lastCellCount = cellCount
        val s = _state.value
        val now = System.currentTimeMillis()
        val minutes = s.phaseSince?.let { ((now - it) / 60_000).toInt() } ?: 0
        val acc = s.lastAccuracyM?.let { "±${formatDistance(it.toDouble())}" } ?: "?"
        val situation = when (s.phase) {
            RecordPhase.WAITING -> "Warte auf erste Position"
            RecordPhase.MOVING -> "In Bewegung · GPS $acc"
            RecordPhase.STATIONARY -> "Stillstand${if (minutes > 0) " seit $minutes min" else ""} · Zellen jede Minute"
            RecordPhase.COARSE -> "Ungenaues GPS${if (minutes > 0) " seit $minutes min" else ""} ($acc) · Zellen werden weiter erfasst"
            RecordPhase.PAUSED -> "Pausiert ${PauseStore.describe(PauseStore.pausedUntil.value)} · GPS aus"
        }
        val reason = when (s.busy) {
            Busy.CALL -> "Telefonat" to SettingsStore.callBehavior.value
            Busy.DATA -> "Datenverkehr" to SettingsStore.dataBehavior.value
            Busy.NONE -> null
        }
        val call = if (reason == null) {
            ""
        } else {
            when (reason.second) {
                CallBehavior.PAUSE -> "${reason.first}: Scans pausiert · "
                CallBehavior.PASSIVE -> "${reason.first}: nur Mitlesen · "
                CallBehavior.NORMAL -> ""
            }
        }
        updateNotification("$call$situation · ${s.scans} Scans · ${s.measurements} Messwerte · $cellCount Zellen")
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Aufzeichnung", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Hinweis, solange Funkstrecke Zellen mit GPS aufzeichnet"
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle(if (autoStarted) "Heimatbasis verlassen: Aufzeichnung läuft" else "Funkstrecke zeichnet auf")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply {
                if (PauseStore.isPaused()) {
                    addAction(0, "Fortsetzen", serviceAction(ACTION_RESUME, 2))
                } else {
                    addAction(0, "Pause 30 min", serviceAction(ACTION_PAUSE, 3) { putExtra(EXTRA_MINUTES, 30) })
                }
            }
            .addAction(0, "Stopp", stop)
            .build()
    }

    private fun serviceAction(action: String, requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, ScanService::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        stopLocationUpdates()
        settingsJob?.cancel()
        pauseJob?.cancel()
        started = false
        scope.cancel()
        _state.update { it.copy(running = false, waitingForGps = false) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "logging"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "de.funkstrecke.STOP"
        private const val ACTION_PAUSE = "de.funkstrecke.PAUSE"
        private const val ACTION_RESUME = "de.funkstrecke.RESUME"
        private const val EXTRA_MINUTES = "minutes"

        /** Ab dieser Genauigkeit zählt eine Position als genau (Unterscheidung Bewegung/Stillstand). */
        const val PRECISE_ACCURACY_M = 30f

        /** Unterhalb dieser Geschwindigkeit (m/s ≈ 11 km/h) lohnt keine Hochrechnung */
        private const val MIN_EXTRAPOLATION_SPEED = 3f
        private const val MAX_EXTRAPOLATION_S = 10.0

        /** Grobe Messpunkte bis zu dieser Genauigkeit speichern (für das Protokoll; die Schätzung nutzt sie nicht). */
        private const val COARSE_STORE_ACCURACY_M = 500f

        /** Im Stand die Zellen so oft auffrischen */
        private const val STATIONARY_REFRESH_MS = 60_000L

        private val _state = MutableStateFlow(LogState())
        val state: StateFlow<LogState> = _state.asStateFlow()

        /** Letzter Scan der Aufzeichnung; die Live-Ansicht zeigt ihn, statt selbst zu scannen. */
        private val _lastScan = MutableStateFlow(LiveScan())
        val lastScan: StateFlow<LiveScan> = _lastScan.asStateFlow()

        private const val EXTRA_AUTO = "auto"

        /** [auto] = von der Heimatbasis ausgelöst (Hinweistext in der Benachrichtigung) */
        fun start(context: Context, auto: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScanService::class.java).putExtra(EXTRA_AUTO, auto),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScanService::class.java))
        }
    }
}
