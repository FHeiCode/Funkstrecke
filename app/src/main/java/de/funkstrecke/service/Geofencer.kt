package de.funkstrecke.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import de.funkstrecke.MainActivity
import de.funkstrecke.R
import de.funkstrecke.util.HomeStore
import de.funkstrecke.util.LocationHelper
import de.funkstrecke.util.PauseStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Zustand der Heimatbasis-Überwachung, für die Oberfläche. */
sealed interface HomeStatus {
    data object Off : HomeStatus
    data object NoPoint : HomeStatus
    data object NeedsLocationPermission : HomeStatus
    data object NeedsBackgroundPermission : HomeStatus
    data object NoPlayServices : HomeStatus
    data object Registering : HomeStatus
    data object Active : HomeStatus
    data class Failed(val message: String) : HomeStatus
}

/**
 * Meldet die Heimatbasis als Geofence bei den Google-Standortdiensten an.
 * Android weckt dann [GeofenceReceiver], wenn der Kreis verlassen oder wieder betreten wird.
 * Geofences gehen bei Neustart verloren; [BootReceiver] und der App-Start melden sie neu an.
 */
object Geofencer {
    private const val REQUEST_ID = "home"

    private val _status = MutableStateFlow<HomeStatus>(HomeStatus.Off)
    val status: StateFlow<HomeStatus> = _status.asStateFlow()

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        // Die Play-Dienste füllen den Intent; er muss daher veränderbar sein.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 7, intent, flags)
    }

    /** Gleicht die Anmeldung mit den gespeicherten Einstellungen ab. */
    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        val app = context.applicationContext
        HomeStore.init(app)
        val home = HomeStore.current
        val client = LocationServices.getGeofencingClient(app)

        if (!home.enabled) {
            runCatching { client.removeGeofences(pendingIntent(app)) }
            _status.value = HomeStatus.Off
            return
        }
        val lat = home.latitude
        val lon = home.longitude
        if (lat == null || lon == null) {
            _status.value = HomeStatus.NoPoint
            return
        }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
            _status.value = HomeStatus.NoPlayServices
            return
        }
        if (!LocationHelper.hasPermission(app)) {
            _status.value = HomeStatus.NeedsLocationPermission
            return
        }
        if (!HomeStore.hasBackgroundLocation(app)) {
            _status.value = HomeStatus.NeedsBackgroundPermission
            return
        }

        val fence = Geofence.Builder()
            .setRequestId(REQUEST_ID)
            .setCircularRegion(lat, lon, home.radiusM.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_ENTER)
            // Bis zu 30 s Verzögerung erlauben spart Akku; Android meldet ohnehin selten schneller.
            .setNotificationResponsiveness(30_000)
            .build()
        // Kein Auslösen beim Anmelden: nur echte Übergänge zählen, sonst startet jeder App-Start
        // außerhalb der Basis eine Aufzeichnung.
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()

        _status.value = HomeStatus.Registering
        val pi = pendingIntent(app)
        // Alte Anmeldung zuerst entfernen, dann neu anmelden (Ort oder Radius können sich geändert haben).
        client.removeGeofences(pi).addOnCompleteListener {
            try {
                client.addGeofences(request, pi)
                    .addOnSuccessListener { _status.value = HomeStatus.Active }
                    .addOnFailureListener { e -> _status.value = HomeStatus.Failed(describe(e)) }
            } catch (e: SecurityException) {
                _status.value = HomeStatus.NeedsBackgroundPermission
            }
        }
    }

    private fun describe(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
                "Geofencing ist gerade nicht verfügbar. Schalte den Standort ein und in den Standorteinstellungen " +
                    "„Google Standortgenauigkeit“ (Verbesserte Standortgenauigkeit)."
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES, GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS ->
                "Zu viele Geofences angemeldet. Starte das Handy neu und versuche es noch einmal."
            GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION ->
                "Standort „Immer zulassen“ fehlt."
            else -> "Anmeldung fehlgeschlagen (${code ?: e.javaClass.simpleName})."
        }
    }

    /** Hinweis, falls Android den Start aus dem Hintergrund verweigert: Tippen startet die Aufzeichnung. */
    fun notifyStartFailed(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Heimatbasis", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Hinweise, wenn du die Heimatbasis verlässt"
            },
        )
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_START_RECORDING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle("Heimatbasis verlassen")
            .setContentText("Android hat den automatischen Start blockiert. Tippen, um aufzuzeichnen.")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOTIFY_ID, n) }
    }

    private const val CHANNEL_ID = "home_base"
    private const val NOTIFY_ID = 43
}

/** Wird von Android geweckt, wenn die Heimatbasis verlassen oder betreten wird. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        HomeStore.init(context)
        val home = HomeStore.current
        if (!home.enabled) return
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                PauseStore.init(context)
                if (PauseStore.isPaused()) return
                // Geofence-Ereignisse dürfen laut Android einen Vordergrunddienst aus dem Hintergrund starten.
                val started = runCatching { ScanService.start(context, auto = true) }.isSuccess
                if (!started) Geofencer.notifyStartFailed(context)
            }
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                if (home.stopOnReturn) ScanService.stop(context)
            }
        }
    }
}

/** Meldet die Heimatbasis nach einem Neustart oder App-Update neu an. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Geofencer.sync(context)
        }
    }
}
