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
import de.funkstrecke.MainActivity
import de.funkstrecke.R
import de.funkstrecke.data.AppDatabase
import de.funkstrecke.data.Recorder
import de.funkstrecke.radio.CellScanner
import de.funkstrecke.util.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Zustand der Hintergrund-Aufzeichnung, für die Oberfläche beobachtbar. */
data class LogState(
    val running: Boolean = false,
    val startedAt: Long? = null,
    val scans: Int = 0,
    val measurements: Int = 0,
    val lastFixAt: Long? = null,
    val lastAccuracyM: Float? = null,
    val waitingForGps: Boolean = false,
)

/**
 * Vordergrunddienst: nimmt bei jeder GPS-Position (höchstens alle 3 s) einen Zell-Scan
 * auf und speichert ihn als Messpunkte. Läuft weiter, wenn der Bildschirm aus ist.
 */
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listener: LocationListener? = null
    private var lastScanAt = 0L
    private var scanJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (listener != null) return START_NOT_STICKY
        if (!LocationHelper.hasPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Warte auf GPS …"), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        _state.value = LogState(running = true, startedAt = System.currentTimeMillis(), waitingForGps = true)
        if (!startLocationUpdates()) {
            _state.update { it.copy(waitingForGps = true) }
            updateNotification("Standort ist ausgeschaltet")
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(): Boolean {
        val lm = getSystemService(LocationManager::class.java) ?: return false
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return false
        }
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location)

            // Auf Android 8–9 sind diese Methoden noch abstrakt und müssen existieren.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        return try {
            lm.requestLocationUpdates(provider, 2_000L, 0f, l, Looper.getMainLooper())
            listener = l
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun onFix(location: Location) {
        val now = System.currentTimeMillis()
        if (now - lastScanAt < SCAN_INTERVAL_MS || scanJob?.isActive == true) return
        lastScanAt = now
        scanJob = scope.launch {
            val cells = CellScanner.scan(this@ScanService)
            Recorder.record(AppDatabase.get(this@ScanService), cells, location, now)
            val measured = cells.count { it.dbm != null }
            _state.update {
                it.copy(
                    scans = it.scans + 1,
                    measurements = it.measurements + measured,
                    lastFixAt = now,
                    lastAccuracyM = if (location.hasAccuracy()) location.accuracy else null,
                    waitingForGps = false,
                )
            }
            val s = _state.value
            val accuracy = s.lastAccuracyM?.let { " · GPS ±${it.roundToInt()} m" } ?: ""
            updateNotification("${s.scans} Scans · ${s.measurements} Messwerte · ${cells.size} Zellen hörbar$accuracy")
        }
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
            .setContentTitle("Funkstrecke zeichnet auf")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stopp", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        listener?.let { l -> getSystemService(LocationManager::class.java)?.removeUpdates(l) }
        listener = null
        scope.cancel()
        _state.update { it.copy(running = false, waitingForGps = false) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "logging"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "de.funkstrecke.STOP"
        private const val SCAN_INTERVAL_MS = 3_000L

        private val _state = MutableStateFlow(LogState())
        val state: StateFlow<LogState> = _state.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScanService::class.java))
        }
    }
}
