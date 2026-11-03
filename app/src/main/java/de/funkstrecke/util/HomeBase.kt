package de.funkstrecke.util

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fester Ort mit Radius: Verlässt du ihn, startet die Aufzeichnung. */
data class HomeBase(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusM: Int = 300,
    /** Aufzeichnung beim Verlassen automatisch starten */
    val enabled: Boolean = false,
    /** Aufzeichnung bei der Rückkehr automatisch stoppen */
    val stopOnReturn: Boolean = true,
) {
    val hasPoint: Boolean get() = latitude != null && longitude != null
}

object HomeStore {
    private const val FILE = "home_base"
    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(HomeBase())
    val flow: StateFlow<HomeBase> = state.asStateFlow()
    val current: HomeBase get() = state.value

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val hasPoint = p.contains("lat") && p.contains("lon")
        state.value = HomeBase(
            latitude = if (hasPoint) Double.fromBits(p.getLong("lat", 0)) else null,
            longitude = if (hasPoint) Double.fromBits(p.getLong("lon", 0)) else null,
            radiusM = p.getInt("radiusM", 300),
            enabled = p.getBoolean("enabled", false),
            stopOnReturn = p.getBoolean("stopOnReturn", true),
        )
    }

    fun update(transform: (HomeBase) -> HomeBase) {
        val next = transform(state.value)
        state.value = next
        val e = prefs?.edit() ?: return
        if (next.latitude != null && next.longitude != null) {
            e.putLong("lat", next.latitude.toRawBits()).putLong("lon", next.longitude.toRawBits())
        } else {
            e.remove("lat").remove("lon")
        }
        e.putInt("radiusM", next.radiusM)
            .putBoolean("enabled", next.enabled)
            .putBoolean("stopOnReturn", next.stopOnReturn)
            .apply()
    }

    /** Ab Android 10 braucht das Aufwecken im Hintergrund „Standort: Immer zulassen“. */
    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
