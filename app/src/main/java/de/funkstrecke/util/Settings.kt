package de.funkstrecke.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Wie oft und wie gründlich gescannt wird. Beeinflusst den Akkuverbrauch am stärksten. */
data class ScanSettings(
    /** Aufzeichnung: frühestens alle n Sekunden ein Scan */
    val recordIntervalS: Int = 10,
    /** Aufzeichnung: erst nach so vielen Metern Bewegung erneut scannen (0 = immer) */
    val recordMinDistanceM: Int = 15,
    /** Live-Ansicht: Aktualisierung alle n Sekunden */
    val liveIntervalS: Int = 5,
    /** Modem um eine frische Messung bitten statt den letzten Stand zu lesen */
    val forceRefresh: Boolean = true,
)

enum class ScanPreset(private val de: String, private val en: String, private val descDe: String, private val descEn: String, val settings: ScanSettings) {
    PRECISE(
        "Genau",
        "Precise",
        "Scan alle 3 s, auch im Stand. Höchster Verbrauch.",
        "Scan every 3 s, even when standing still. Highest consumption.",
        ScanSettings(recordIntervalS = 3, recordMinDistanceM = 0, liveIntervalS = 3, forceRefresh = true),
    ),
    BALANCED(
        "Ausgewogen",
        "Balanced",
        "Scan alle 10 s, nur wenn du dich 15 m bewegt hast.",
        "Scan every 10 s, only after you have moved 15 m.",
        ScanSettings(recordIntervalS = 10, recordMinDistanceM = 15, liveIntervalS = 5, forceRefresh = true),
    ),
    SAVER(
        "Sparsam",
        "Saver",
        "Scan alle 30 s nach 50 m Bewegung, Zellwerte aus dem Zwischenspeicher des Modems.",
        "Scan every 30 s after 50 m of movement, cell values from the modem cache.",
        ScanSettings(recordIntervalS = 30, recordMinDistanceM = 50, liveIntervalS = 15, forceRefresh = false),
    ),
    ;

    val label: String get() = tr(de, en)
    val description: String get() = tr(descDe, descEn)

    companion object {
        fun matching(settings: ScanSettings): ScanPreset? = entries.firstOrNull { it.settings == settings }
    }
}

object SettingsStore {
    private const val FILE = "settings"
    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(ScanPreset.BALANCED.settings)
    val flow: StateFlow<ScanSettings> = state.asStateFlow()
    val current: ScanSettings get() = state.value

    private val callState = MutableStateFlow(CallBehavior.PAUSE)
    val callBehavior: StateFlow<CallBehavior> = callState.asStateFlow()

    fun setCallBehavior(value: CallBehavior) {
        callState.value = value
        prefs?.edit()?.putString("callBehavior", value.name)?.apply()
    }

    private val ocidKeyState = MutableStateFlow("")
    /** API-Schlüssel für OpenCelliD (leer = Abgleich aus) */
    val openCellIdKey: StateFlow<String> = ocidKeyState.asStateFlow()

    fun setOpenCellIdKey(value: String) {
        ocidKeyState.value = value.trim()
        prefs?.edit()?.putString("openCellIdKey", value.trim())?.apply()
    }

    private val strictOsmState = MutableStateFlow(true)
    /**
     * true: Ein OSM-Mast bestätigt eine Station nur, wenn er eindeutig ist (kein anderer Maststandort im Umkreis
     * von 300 m). false: wie bis Version 2.24 jeder Mast höchstens 100 m neben der Schätzung.
     */
    val strictOsm: StateFlow<Boolean> = strictOsmState.asStateFlow()

    fun setStrictOsm(value: Boolean) {
        strictOsmState.value = value
        prefs?.edit()?.putBoolean("strictOsm", value)?.apply()
    }

    private val dataState = MutableStateFlow(CallBehavior.PASSIVE)
    val dataBehavior: StateFlow<CallBehavior> = dataState.asStateFlow()

    fun setDataBehavior(value: CallBehavior) {
        dataState.value = value
        prefs?.edit()?.putString("dataBehavior", value.name)?.apply()
    }

    /** Einmal beim Start von App oder Dienst aufrufen; weitere Aufrufe sind wirkungslos. */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        Lang.init(context)
        de.funkstrecke.locate.Terrain.init(context)
        de.funkstrecke.locate.SignalModelStore.init(context)
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val defaults = ScanPreset.BALANCED.settings
        state.value = ScanSettings(
            recordIntervalS = p.getInt("recordIntervalS", defaults.recordIntervalS),
            recordMinDistanceM = p.getInt("recordMinDistanceM", defaults.recordMinDistanceM),
            liveIntervalS = p.getInt("liveIntervalS", defaults.liveIntervalS),
            forceRefresh = p.getBoolean("forceRefresh", defaults.forceRefresh),
        )
        callState.value = CallBehavior.entries.firstOrNull { it.name == p.getString("callBehavior", null) } ?: CallBehavior.PAUSE
        dataState.value = CallBehavior.entries.firstOrNull { it.name == p.getString("dataBehavior", null) } ?: CallBehavior.PASSIVE
        ocidKeyState.value = p.getString("openCellIdKey", "").orEmpty()
        strictOsmState.value = p.getBoolean("strictOsm", true)
    }

    fun update(transform: (ScanSettings) -> ScanSettings) {
        val next = transform(state.value)
        state.value = next
        prefs?.edit()
            ?.putInt("recordIntervalS", next.recordIntervalS)
            ?.putInt("recordMinDistanceM", next.recordMinDistanceM)
            ?.putInt("liveIntervalS", next.liveIntervalS)
            ?.putBoolean("forceRefresh", next.forceRefresh)
            ?.apply()
    }
}
