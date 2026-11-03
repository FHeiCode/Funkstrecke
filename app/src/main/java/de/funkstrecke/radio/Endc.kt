package de.funkstrecke.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellSignalStrengthNr
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** NR-Zustand der LTE-Ankerzelle, wie Android ihn im ServiceState führt. */
enum class NrState(val label: String, val rank: Int) {
    UNKNOWN("unbekannt", -1),
    NONE("kein NR", 0),
    RESTRICTED("NR gesperrt", 1),
    NOT_RESTRICTED("NR verfügbar, nicht verbunden", 2),
    CONNECTED("NR verbunden", 3),
}

/** Zusammengefasster 5G-Zustand an einem Ort. Der Name wird so in der Datenbank gespeichert. */
enum class EndcMode(val label: String, val rank: Int) {
    NONE("Kein 5G", 0),
    RESTRICTED("5G gesperrt", 1),
    AVAILABLE("5G NSA verfügbar", 2),
    CONNECTED("5G NSA aktiv", 3),
    SA("5G SA", 4),
    ;

    companion object {
        fun of(name: String?): EndcMode? = entries.firstOrNull { it.name == name }
    }
}

/** Was das Handy anzeigt (Statusleiste), ab Android 11. */
data class DisplayInfo(val networkType: Int, val overrideType: Int) {
    val label: String?
        get() = when {
            networkType == TelephonyManager.NETWORK_TYPE_NR -> "5G SA"
            overrideType == OVERRIDE_NR_ADVANCED -> "5G+ (NR Advanced)"
            overrideType == OVERRIDE_NR_NSA_MMWAVE -> "5G NSA mmWave"
            overrideType == OVERRIDE_NR_NSA -> "5G NSA"
            overrideType == OVERRIDE_LTE_ADVANCED_PRO -> "LTE Advanced Pro"
            overrideType == OVERRIDE_LTE_CA -> "4G+ (LTE-CA)"
            else -> null
        }
    val shows5g: Boolean get() = overrideType in setOf(OVERRIDE_NR_NSA, OVERRIDE_NR_NSA_MMWAVE, OVERRIDE_NR_ADVANCED)

    companion object {
        // Werte aus TelephonyDisplayInfo (als Zahlen, damit es auch vor Android 11 kompiliert und läuft).
        const val OVERRIDE_LTE_CA = 1
        const val OVERRIDE_LTE_ADVANCED_PRO = 2
        const val OVERRIDE_NR_NSA = 3
        const val OVERRIDE_NR_NSA_MMWAVE = 4
        const val OVERRIDE_NR_ADVANCED = 5
    }
}

/** EN-DC-Lage zum Zeitpunkt eines Scans. */
data class EndcSnapshot(
    /** null = nicht bestimmbar (z. B. keine LTE-Verbindung oder fehlende Freigabe „Telefon“) */
    val mode: EndcMode?,
    val nrState: NrState,
    /** LTE-Zelle unterstützt EN-DC (DataSpecificRegistrationInfo.isEnDcAvailable) */
    val enDcAvailable: Boolean?,
    /** LTE-Zelle kündigt NR an (SIB2 upperLayerIndication) */
    val nrAvailable: Boolean?,
    /** EN-DC für dieses Gerät/Abo gesperrt */
    val dcNrRestricted: Boolean?,
    val display: DisplayInfo?,
    /** Verbundene LTE-Zelle (Anker) */
    val anchor: CellRecord?,
    /** Gemessene NR-Zellen während einer LTE-Verbindung */
    val nrLegs: List<CellRecord>,
    /** NR-Messwerte aus der Signalstärke (falls keine NR-Zelle gemeldet wird) */
    val nrRsrp: Int?,
    val nrRsrq: Int?,
    val nrSinr: Int?,
    /** Ob der Registrierungsstatus gelesen werden konnte (Freigabe „Telefon“) */
    val serviceStateReadable: Boolean,
)

/**
 * Erkennt EN-DC (5G NSA: LTE-Anker plus NR-Zweig) aus drei öffentlichen Quellen:
 * Anzeige-Status (TelephonyDisplayInfo), Registrierungsstatus (ServiceState, als Text ausgewertet,
 * weil Android die Felder nicht als öffentliche Methoden anbietet) und NR-Messwerten.
 */
object EndcMonitor {

    private val _display = MutableStateFlow<DisplayInfo?>(null)
    val display: StateFlow<DisplayInfo?> = _display.asStateFlow()
    private var started = false

    private fun hasPhoneState(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Telephony der mobilen Daten-SIM (dort läuft EN-DC). */
    private fun dataTelephony(context: Context): TelephonyManager? {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return null
        val sub = SubscriptionManager.getDefaultDataSubscriptionId()
        return if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(sub) else base
    }

    /** Anzeige-Status beobachten. Muss auf dem Hauptthread aufgerufen werden; wiederholte Aufrufe sind harmlos. */
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (started || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        val tm = dataTelephony(app) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tm.registerTelephonyCallback(app.mainExecutor, DisplayCallback())
            } else {
                if (!hasPhoneState(app)) return
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                        _display.value = DisplayInfo(info.networkType, info.overrideNetworkType)
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
            }
            started = true
        } catch (_: SecurityException) {
            // Ohne Freigabe „Telefon“ gibt es den Anzeige-Status nicht; die übrigen Quellen reichen meist.
        } catch (_: IllegalStateException) {
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class DisplayCallback : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
            _display.value = DisplayInfo(info.networkType, info.overrideNetworkType)
        }
    }

    /** EN-DC-Lage passend zu einem gerade gemachten Zell-Scan. */
    @SuppressLint("MissingPermission")
    fun snapshot(context: Context, cells: List<CellRecord>): EndcSnapshot {
        val tm = dataTelephony(context)
        val serviceText: String? = if (tm != null && hasPhoneState(context)) {
            runCatching { tm.serviceState?.toString() }.getOrNull()
        } else {
            null
        }
        val parsed = ServiceStateText.parse(serviceText)

        var nrRsrp: Int? = null
        var nrRsrq: Int? = null
        var nrSinr: Int? = null
        if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val nr = runCatching { tm.signalStrength?.getCellSignalStrengths(CellSignalStrengthNr::class.java)?.firstOrNull() }.getOrNull()
            if (nr != null) {
                nrRsrp = nr.ssRsrp.takeIf { it != Int.MAX_VALUE }?.let { if (it > 0) -it else it }?.takeIf { it in -156..-31 }
                nrRsrq = nr.ssRsrq.takeIf { it != Int.MAX_VALUE }?.let { if (it > 0) -it else it }?.takeIf { it in -43..20 }
                nrSinr = nr.ssSinr.takeIf { it != Int.MAX_VALUE }?.takeIf { it in -23..40 }
            }
        }
        return derive(cells, parsed, _display.value, nrRsrp, nrRsrq, nrSinr, serviceText != null)
    }

    /** Leitet den Modus ab; getrennt von Android, damit die Regeln nachvollziehbar bleiben. */
    fun derive(
        cells: List<CellRecord>,
        parsed: ServiceStateText.Parsed,
        display: DisplayInfo?,
        nrRsrp: Int?,
        nrRsrq: Int?,
        nrSinr: Int?,
        serviceStateReadable: Boolean,
    ): EndcSnapshot {
        val serving = cells.filter { it.registered }
        val anchor = serving.firstOrNull { it.rat == "4G" }
        val nrServing = serving.firstOrNull { it.rat == "5G" }
        // NR-Zellen zählen nur als NR-Zweig, solange eine LTE-Verbindung besteht und das Netz passt.
        val legs = if (anchor != null) {
            cells.filter { it.rat == "5G" && (it.mcc == null || anchor.mcc == null || it.mcc == anchor.mcc && it.mnc == anchor.mnc) }
        } else {
            emptyList()
        }
        val nrMeasured = legs.any { it.dbm != null } || nrRsrp != null
        val nr = parsed.nrState
        val mode: EndcMode? = when {
            display?.networkType == android.telephony.TelephonyManager.NETWORK_TYPE_NR || (nrServing != null && anchor == null) -> EndcMode.SA
            anchor == null -> null
            nr == NrState.CONNECTED || (nrMeasured && nr != NrState.RESTRICTED) -> EndcMode.CONNECTED
            nr == NrState.RESTRICTED || parsed.dcNrRestricted == true -> EndcMode.RESTRICTED
            nr == NrState.NOT_RESTRICTED || parsed.enDcAvailable == true || parsed.nrAvailable == true ||
                display?.shows5g == true -> EndcMode.AVAILABLE
            nr == NrState.NONE || parsed.enDcAvailable == false -> EndcMode.NONE
            else -> null
        }
        return EndcSnapshot(
            mode = mode,
            nrState = nr,
            enDcAvailable = parsed.enDcAvailable,
            nrAvailable = parsed.nrAvailable,
            dcNrRestricted = parsed.dcNrRestricted,
            display = display,
            anchor = anchor,
            nrLegs = legs,
            nrRsrp = nrRsrp,
            nrRsrq = nrRsrq,
            nrSinr = nrSinr,
            serviceStateReadable = serviceStateReadable,
        )
    }
}

/**
 * Liest die EN-DC-Felder aus dem Text von ServiceState.toString(). Die Schreibweise
 * unterscheidet sich je Android-Version („nrState=CONNECTED“, „isEnDcAvailable = true“,
 * „mIsEnDcAvailable=true“); bei mehreren Registrierungen gilt der beste Wert.
 */
object ServiceStateText {
    data class Parsed(
        val nrState: NrState = NrState.UNKNOWN,
        val enDcAvailable: Boolean? = null,
        val nrAvailable: Boolean? = null,
        val dcNrRestricted: Boolean? = null,
    )

    private val nrStateRegex = Regex("""nrState\s*[=:]\s*([A-Za-z_]+)""", RegexOption.IGNORE_CASE)

    private fun flag(text: String, name: String): Boolean? {
        val values = Regex("""$name\s*[=:]\s*(true|false)""", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1].equals("true", ignoreCase = true) }.toList()
        return if (values.isEmpty()) null else values.any { it }
    }

    fun parse(text: String?): Parsed {
        if (text.isNullOrEmpty()) return Parsed()
        val state = nrStateRegex.findAll(text)
            .mapNotNull {
                when (it.groupValues[1].uppercase()) {
                    "CONNECTED" -> NrState.CONNECTED
                    "NOT_RESTRICTED" -> NrState.NOT_RESTRICTED
                    "RESTRICTED" -> NrState.RESTRICTED
                    "NONE" -> NrState.NONE
                    else -> null
                }
            }
            .maxByOrNull { it.rank } ?: NrState.UNKNOWN
        return Parsed(
            nrState = state,
            enDcAvailable = flag(text, "isEnDcAvailable"),
            nrAvailable = flag(text, "isNrAvailable"),
            dcNrRestricted = flag(text, "isDcNrRestricted"),
        )
    }
}
