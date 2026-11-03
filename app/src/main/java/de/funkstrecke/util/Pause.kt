package de.funkstrecke.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Zeitweilige Pause aller Scans: Aufzeichnung (GPS aus, keine Zell-Scans), Live-Ansicht und
 * automatischer Start durch die Heimatbasis. Endet von selbst zum gewählten Zeitpunkt.
 */
object PauseStore {
    /** Wert für „bis ich fortsetze“ */
    const val UNTIL_RESUMED = Long.MAX_VALUE

    /** Angebotene Pausen in Minuten; null = bis zum Fortsetzen */
    val choices: List<Pair<String, Int?>> = listOf(
        "15 Minuten" to 15,
        "30 Minuten" to 30,
        "1 Stunde" to 60,
        "2 Stunden" to 120,
        "Bis ich fortsetze" to null,
    )

    private const val FILE = "pause"
    private var prefs: SharedPreferences? = null

    /** Pausiert bis zu diesem Zeitpunkt (System.currentTimeMillis), 0 = keine Pause */
    private val state = MutableStateFlow(0L)
    val pausedUntil: StateFlow<Long> = state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val until = p.getLong("until", 0L)
        state.value = if (until > System.currentTimeMillis()) until else 0L
    }

    fun isPaused(now: Long = System.currentTimeMillis()): Boolean = state.value > now

    /** [minutes] = null: bis zum Fortsetzen */
    fun pause(context: Context, minutes: Int?) {
        init(context)
        val until = if (minutes == null) UNTIL_RESUMED else System.currentTimeMillis() + minutes * 60_000L
        set(until)
        val pi = resumeIntent(context)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(pi)
        if (minutes != null) {
            // Ungenauer Wecker, auch im Ruhemodus; braucht keine Sonderberechtigung (Abweichung höchstens Minuten).
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + minutes * 60_000L,
                pi,
            )
        }
    }

    fun resume(context: Context) {
        init(context)
        set(0L)
        context.getSystemService(AlarmManager::class.java)?.cancel(resumeIntent(context))
    }

    private fun set(until: Long) {
        state.value = until
        prefs?.edit()?.putLong("until", until)?.apply()
    }

    private fun resumeIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 11,
            Intent(context, ResumeReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** „bis 14:30“ oder „bis du fortsetzt“ */
    fun describe(until: Long): String =
        if (until == UNTIL_RESUMED) "bis du fortsetzt" else "bis ${formatTime(until).substring(0, 5)}"
}

/** Beendet eine zeitlich begrenzte Pause. */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PauseStore.resume(context)
    }
}
