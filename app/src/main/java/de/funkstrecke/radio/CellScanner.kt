package de.funkstrecke.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import de.funkstrecke.util.tr
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Ergebnis eines Scans für die Live-Ansicht. */
data class LiveScan(val cells: List<CellRecord> = emptyList(), val at: Long? = null, val endc: EndcSnapshot? = null)

/** Eine vom Handy gehörte Funkzelle mit allen Kennungen und Messwerten. */
data class CellRecord(
    /** "2G", "3G", "4G" oder "5G" */
    val rat: String,
    val registered: Boolean,
    val mcc: String?,
    val mnc: String?,
    /** MCC/MNC nicht von der Zelle gemeldet, sondern von der verbundenen Zelle übernommen */
    val plmnInferred: Boolean,
    /** TAC (4G/5G) bzw. LAC (2G/3G) */
    val area: Int?,
    /** ECI (4G), NCI (5G) oder CID (2G/3G) */
    val cellId: Long?,
    /** eNodeB-ID (nur 4G, ECI ÷ 256) */
    val nodeId: Long?,
    /** Sektor bzw. lokale Zellnummer (nur 4G, ECI mod 256) */
    val sector: Int?,
    /** PCI (4G/5G), PSC (3G), BSIC (2G) */
    val pci: Int?,
    val arfcn: Int?,
    val band: BandInfo?,
    /** RSRP (4G), SS-RSRP (5G), RSCP (3G), RSSI (2G) in dBm */
    val dbm: Int?,
    /** RSRQ (4G), SS-RSRQ (5G), Ec/No (3G) in dB */
    val quality: Int?,
    /** RS-SNR (4G) bzw. SS-SINR (5G) in dB */
    val sinr: Int?,
    /** 4G/2G: TA-Schritte; 5G: Mikrosekunden (Android 14+) */
    val timingAdvance: Int?,
    /** Messzeitpunkt laut Modem (SystemClock.elapsedRealtime in ms), falls gemeldet */
    val measuredAtMs: Long? = null,
    /** Sekundärzelle der Trägerbündelung (Carrier Aggregation): sendet zusammen mit der verbundenen Zelle */
    val secondary: Boolean = false,
    /** Netzname, wie ihn das Netz selbst meldet (meist nur bei der verbundenen Zelle) */
    val operatorAlpha: String? = null,
    /** Von der Daten-SIM gemessen (bei nur einer SIM immer true). Nur sie ist aktiv verbunden. */
    val dataSim: Boolean = true,
) {
    val operatorName: String? get() = Operators.name(mcc, mnc)
    val plmn: String? get() = if (mcc != null && mnc != null) "$mcc-$mnc" else null
    val distanceM: Int? get() = timingDistance(rat, timingAdvance)?.roundToInt()

    /** Kennung zum Erkennen derselben Zelle über Scans hinweg. */
    val key: String
        get() = if (cellId != null) "$rat|$mcc-$mnc|$cellId" else "$rat|$mcc-$mnc|nb|$arfcn|$pci"
}

/**
 * Timing Advance gibt es nur für die verbundene Zelle. Manche Modems melden bei Nachbarzellen
 * Platzhalter oder alte Werte (z. B. 1242 ≈ 97 km); diese werden verworfen, ebenso Werte jenseits
 * realistischer Zellradien (LTE 640 ≈ 50 km, GSM 63 ≈ 35 km).
 */
const val MAX_TA_LTE = 640
const val MAX_TA_GSM = 63
/** 5G: Timing Advance in µs; 340 µs ≈ 51 km */
const val MAX_TA_NR_US = 340

fun plausibleTa(rat: String, ta: Int?, registered: Boolean): Int? = when {
    ta == null || !registered || ta < 0 -> null
    rat == "4G" && ta <= MAX_TA_LTE -> ta
    rat == "2G" && ta <= MAX_TA_GSM -> ta
    rat == "5G" && ta <= MAX_TA_NR_US -> ta
    else -> null
}

/**
 * Entfernung aus dem Timing Advance: 4G 78,12 m pro Schritt, 2G 553,5 m pro Schritt, 5G 149,9 m pro µs
 * (Laufzeit hin und zurück; jeweils Mitte des Intervalls).
 */
fun timingDistance(rat: String, ta: Int?): Double? = when {
    ta == null -> null
    rat == "4G" -> (ta + 0.5) * 78.12
    rat == "2G" -> (ta + 0.5) * 553.5
    rat == "5G" -> (ta + 0.5) * 149.9
    else -> null
}

/** Anzeige des Timing Advance mit Einheit (5G in µs) */
fun taLabel(rat: String, ta: Int): String = if (rat == "5G") "$ta µs" else ta.toString()

fun signalLabel(rat: String) = when (rat) {
    "4G" -> "RSRP"
    "5G" -> "SS-RSRP"
    "3G" -> "RSCP"
    else -> "RSSI"
}

fun qualityLabel(rat: String) = when (rat) {
    "4G" -> "RSRQ"
    "5G" -> "SS-RSRQ"
    "3G" -> "Ec/No"
    else -> tr("Qualität", "Quality")
}

object CellScanner {

    private fun Int.orNull(): Int? = if (this == Int.MAX_VALUE || this == Int.MIN_VALUE) null else this
    private fun Long.orNull(): Long? = if (this == Long.MAX_VALUE) null else this

    /**
     * Manche Modems melden für Nachbarzellen statt „unbekannt“ den höchsten möglichen Wert als Cell-ID
     * (LTE/UMTS: 2^28 − 1, NR: 2^36 − 1, GSM: 65535). Das ist keine echte Zelle: Ohne diese Prüfung
     * landeten alle Nachbarzellen unter einer einzigen Kennung.
     */
    private fun Long.validCellId(rat: String): Long? = when {
        this < 0 -> null
        rat == "2G" && this == 65_535L -> null
        rat != "2G" && (this == INVALID_ECI || this == INVALID_NCI) -> null
        else -> this
    }

    const val INVALID_ECI = 268_435_455L
    const val INVALID_NCI = 68_719_476_735L

    /**
     * Scannt alle aktiven SIMs (Dual-SIM, falls "Telefonstatus" erlaubt ist).
     * Mit [forceRefresh] misst das Modem neu (genauer, braucht mehr Strom),
     * sonst liefert Android den letzten bekannten Stand.
     *
     * Dual-SIM: Nur die Daten-SIM ist aktiv verbunden, die andere ruht im Leerlauf. Deren Pegel stimmen,
     * Timing Advance und Sekundärzellen aber nicht (alt oder fehlend); beides wird bei ihr verworfen.
     * Hören beide SIMs dieselbe Zelle (gleiches Netz), zählt ein Eintrag: verbunden vor Nachbar,
     * dann Daten-SIM vor der anderen. Zellen, die nur die andere SIM hört (anderes Netz oder andere Zelle),
     * bleiben erhalten.
     */
    suspend fun scan(context: Context, forceRefresh: Boolean = true): List<CellRecord> {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val subs = subscriptionManagers(context, base)
        val dataSub = SubscriptionManager.getDefaultDataSubscriptionId()
        val all = subs.flatMap { (subId, tm) ->
            val records = scanOne(context, tm, forceRefresh)
            // Ohne gültige Daten-SIM-Kennung (oder bei nur einer SIM) gelten alle als Daten-SIM.
            val isData = subId == null || dataSub == SubscriptionManager.INVALID_SUBSCRIPTION_ID || subId == dataSub
            if (isData) records else records.map { it.copy(dataSim = false, timingAdvance = null, secondary = false) }
        }
        return all
            .sortedWith(
                compareByDescending<CellRecord> { it.registered }
                    .thenByDescending { it.dataSim }
                    .thenByDescending { it.dbm ?: Int.MIN_VALUE },
            )
            .distinctBy { it.key }
    }

    /** Telephony je aktiver SIM; die Kennung ist null, wenn nur eine SIM (oder keine Berechtigung) da ist. */
    @SuppressLint("MissingPermission")
    private fun subscriptionManagers(context: Context, base: TelephonyManager): List<Pair<Int?, TelephonyManager>> {
        val canReadState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!canReadState) return listOf(null to base)
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return listOf(null to base)
        val ids = runCatching { sm.activeSubscriptionInfoList?.map { it.subscriptionId } }.getOrNull().orEmpty()
        return if (ids.size <= 1) listOf(null to base) else ids.map { it to base.createForSubscriptionId(it) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanOne(context: Context, tm: TelephonyManager, forceRefresh: Boolean): List<CellRecord> {
        val infos: List<CellInfo> = try {
            val fresh: List<CellInfo>? = if (forceRefresh && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                withTimeoutOrNull(6_000) { requestFresh(context, tm) }
            } else {
                null
            }
            if (fresh.isNullOrEmpty()) tm.allCellInfo.orEmpty() else fresh
        } catch (e: SecurityException) {
            emptyList()
        }
        val records = infos.mapNotNull { map(it) }
        // Netznamen merken, damit Betreiber auch ohne eigene Tabelle (andere Länder) einen Namen haben.
        records.forEach { r -> if (r.operatorAlpha != null) Operators.learn(context, r.mcc, r.mnc, r.operatorAlpha) }
        // Nachbarzellen melden oft kein MCC/MNC: von der verbundenen Zelle derselben SIM übernehmen.
        val serving = records.filter { it.registered && it.mcc != null }
        return records.map { r ->
            if (r.mcc != null) {
                r
            } else {
                val source = serving.firstOrNull { it.rat == r.rat } ?: serving.firstOrNull()
                if (source != null) r.copy(mcc = source.mcc, mnc = source.mnc, plmnInferred = true) else r
            }
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun requestFresh(context: Context, tm: TelephonyManager): List<CellInfo> =
        suspendCancellableCoroutine { cont ->
            tm.requestCellInfoUpdate(context.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    if (cont.isActive) cont.resume(cellInfo.toList())
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            })
        }

    private fun map(info: CellInfo): CellRecord? {
        val record = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
            mapNr(info)
        } else {
            when (info) {
                is CellInfoLte -> mapLte(info)
                is CellInfoGsm -> mapGsm(info)
                is CellInfoWcdma -> mapWcdma(info)
                else -> null
            }
        }
        if (record == null) return null
        // Sekundärzellen der Trägerbündelung (ab Android 9). NR-Zellen bei EN-DC (5G NSA) melden sich ebenfalls
        // als sekundär; die werden über EndcMonitor einer LTE-Station zugeordnet.
        val secondary = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            info.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING
        // CellInfo.getCellIdentity() gibt es erst ab Android 11.
        val alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.cellIdentity.operatorAlphaLong?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return record.copy(measuredAtMs = timestampOf(info), secondary = secondary, operatorAlpha = alpha)
    }

    @Suppress("DEPRECATION")
    private fun timestampOf(info: CellInfo): Long? {
        val ms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.timestampMillis else info.timeStamp / 1_000_000
        return ms.takeIf { it > 0 }
    }

    @Suppress("DEPRECATION")
    private fun mapLte(info: CellInfoLte): CellRecord {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        val (mcc, mnc) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mccString to id.mncString
        } else {
            id.mcc.orNull()?.toString() to id.mnc.orNull()?.toString()?.padStart(2, '0')
        }
        val eci = id.ci.orNull()?.toLong()?.validCellId("4G")
        val earfcn = id.earfcn.orNull()
        val reportedBand = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.firstOrNull() else null
        return CellRecord(
            rat = "4G",
            registered = info.isRegistered,
            mcc = mcc, mnc = mnc, plmnInferred = false,
            area = id.tac.orNull(),
            cellId = eci,
            nodeId = eci?.shr(8),
            sector = eci?.and(0xFF)?.toInt(),
            pci = id.pci.orNull(),
            arfcn = earfcn,
            band = Bands.lte(earfcn, reportedBand),
            dbm = ss.rsrp.orNull()?.takeIf { it in -150..-30 },
            quality = ss.rsrq.orNull()?.takeIf { it in -35..5 },
            sinr = ss.rssnr.orNull()?.takeIf { it in -25..40 },
            timingAdvance = plausibleTa("4G", ss.timingAdvance.orNull(), info.isRegistered),
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mapNr(info: CellInfo): CellRecord? {
        if (info !is CellInfoNr) return null
        val id = info.cellIdentity as? CellIdentityNr ?: return null
        val ss = info.cellSignalStrength as? CellSignalStrengthNr
        val arfcn = id.nrarfcn.orNull()
        val reportedBand = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.firstOrNull() else null
        // Manche Geräte melden SS-RSRP positiv statt negativ.
        val rsrp = ss?.ssRsrp?.orNull()?.let { if (it > 0) -it else it }?.takeIf { it in -156..-31 }
        return CellRecord(
            rat = "5G",
            registered = info.isRegistered,
            mcc = id.mccString, mnc = id.mncString, plmnInferred = false,
            area = id.tac.orNull(),
            cellId = id.nci.orNull()?.validCellId("5G"),
            nodeId = null,
            sector = null,
            pci = id.pci.orNull(),
            arfcn = arfcn,
            band = Bands.nr(arfcn, reportedBand),
            dbm = rsrp,
            quality = ss?.ssRsrq?.orNull()?.let { if (it > 0) -it else it }?.takeIf { it in -43..20 },
            sinr = ss?.ssSinr?.orNull()?.takeIf { it in -23..40 },
            // Timing Advance für 5G gibt es erst ab Android 14 und nur, wenn das Modem ihn meldet (meist 5G SA).
            timingAdvance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                plausibleTa("5G", ss?.timingAdvanceMicros?.orNull(), info.isRegistered)
            } else {
                null
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun mapGsm(info: CellInfoGsm): CellRecord {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        val (mcc, mnc) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mccString to id.mncString
        } else {
            id.mcc.orNull()?.toString() to id.mnc.orNull()?.toString()?.padStart(2, '0')
        }
        val arfcn = id.arfcn.orNull()
        return CellRecord(
            rat = "2G",
            registered = info.isRegistered,
            mcc = mcc, mnc = mnc, plmnInferred = false,
            area = id.lac.orNull(),
            cellId = id.cid.orNull()?.toLong()?.validCellId("2G"),
            nodeId = null, sector = null,
            pci = id.bsic.orNull(),
            arfcn = arfcn,
            band = Bands.gsm(arfcn),
            dbm = ss.dbm.orNull()?.takeIf { it in -120..-20 },
            quality = null,
            sinr = null,
            timingAdvance = plausibleTa("2G", ss.timingAdvance.orNull(), info.isRegistered),
        )
    }

    @Suppress("DEPRECATION")
    private fun mapWcdma(info: CellInfoWcdma): CellRecord {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        val (mcc, mnc) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mccString to id.mncString
        } else {
            id.mcc.orNull()?.toString() to id.mnc.orNull()?.toString()?.padStart(2, '0')
        }
        val ecNo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ss.ecNo.orNull()?.takeIf { it in -24..1 } else null
        return CellRecord(
            rat = "3G",
            registered = info.isRegistered,
            mcc = mcc, mnc = mnc, plmnInferred = false,
            area = id.lac.orNull(),
            cellId = id.cid.orNull()?.toLong()?.validCellId("3G"),
            nodeId = null, sector = null,
            pci = id.psc.orNull(),
            arfcn = id.uarfcn.orNull(),
            band = null,
            dbm = ss.dbm.orNull()?.takeIf { it in -125..-20 },
            quality = ecNo,
            sinr = null,
            timingAdvance = null,
        )
    }
}
