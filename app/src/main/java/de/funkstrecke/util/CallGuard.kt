package de.funkstrecke.util

import android.content.Context
import android.media.AudioManager
import android.net.TrafficStats
import android.os.SystemClock

/** Was beim Scannen passiert, während telefoniert oder viel übertragen wird. */
enum class CallBehavior(val label: String, val callDescription: String, val dataDescription: String) {
    PAUSE(
        "Pausieren",
        "Keine Zell-Scans während eines Gesprächs. Die Position läuft weiter, nach dem Auflegen geht es normal weiter.",
        "Keine Zell-Scans, solange viel übertragen wird. Achtung: Gerade dann liefert die verbundene Zelle Timing Advance.",
    ),
    PASSIVE(
        "Nur mitlesen",
        "Das Modem wird nicht um neue Messungen gebeten; die App liest nur, was es ohnehin meldet, höchstens alle 30 s.",
        "Scans im normalen Takt, aber ohne zusätzliche Messanforderung an das Modem. Werte können einige Sekunden alt sein.",
    ),
    NORMAL(
        "Weiter scannen",
        "Scans wie sonst. Kann auf manchen Geräten zu Aussetzern im Gespräch führen.",
        "Scans wie sonst. Kann auf manchen Geräten den Datendurchsatz kurz bremsen.",
    ),
}

/** Warum gerade Rücksicht genommen wird. */
enum class Busy { NONE, CALL, DATA }

/** Ergebnis für einen anstehenden Scan: [forceRefresh] = null heißt auslassen. */
data class ScanDecision(val busy: Busy, val forceRefresh: Boolean?)

/**
 * Erkennt Telefonate (über den Audio-Modus, auch VoLTE/WLAN-Call, ohne zusätzliche Berechtigung)
 * und starken mobilen Datenverkehr (über TrafficStats) und entscheidet, wie gescannt wird.
 */
object CallGuard {
    /** Mindestabstand zwischen passiven Scans während eines Gesprächs */
    const val PASSIVE_CALL_INTERVAL_MS = 30_000L

    /** Ab diesem mobilen Durchsatz gilt die Verbindung als ausgelastet: 32 kB/s ≈ 256 kbit/s */
    const val BUSY_BYTES_PER_SECOND = 32_000.0

    /** AudioManager.MODE_CALL_SCREENING (ab Android 10), als Zahl für ältere Versionen */
    private const val MODE_CALL_SCREENING = 4

    fun inCall(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            mode == MODE_CALL_SCREENING
    }

    private var lastBytes = -1L
    private var lastSampleAt = 0L
    private var lastBusy = false

    /** Mobiler Datendurchsatz seit dem letzten Aufruf (mindestens 1 s Fenster) über der Schwelle? */
    @Synchronized
    fun dataBusy(): Boolean {
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return false
        val bytes = rx + tx
        val now = SystemClock.elapsedRealtime()
        if (lastBytes < 0 || bytes < lastBytes) {
            lastBytes = bytes
            lastSampleAt = now
            return false
        }
        val elapsed = now - lastSampleAt
        if (elapsed < 1_000) return lastBusy
        lastBusy = (bytes - lastBytes) * 1000.0 / elapsed >= BUSY_BYTES_PER_SECOND
        lastBytes = bytes
        lastSampleAt = now
        return lastBusy
    }

    fun decide(context: Context, forceRefresh: Boolean, lastScanAt: Long, now: Long): ScanDecision {
        if (inCall(context)) {
            val f = when (SettingsStore.callBehavior.value) {
                CallBehavior.PAUSE -> null
                CallBehavior.PASSIVE -> if (now - lastScanAt >= PASSIVE_CALL_INTERVAL_MS) false else null
                CallBehavior.NORMAL -> forceRefresh
            }
            return ScanDecision(Busy.CALL, f)
        }
        if (dataBusy()) {
            val f = when (SettingsStore.dataBehavior.value) {
                CallBehavior.PAUSE -> null
                CallBehavior.PASSIVE -> false
                CallBehavior.NORMAL -> forceRefresh
            }
            return ScanDecision(Busy.DATA, f)
        }
        return ScanDecision(Busy.NONE, forceRefresh)
    }
}
