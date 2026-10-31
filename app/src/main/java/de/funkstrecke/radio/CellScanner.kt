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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

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
    val timingAdvance: Int?,
) {
    val operatorName: String? get() = Operators.name(mcc, mnc)
    val plmn: String? get() = if (mcc != null && mnc != null) "$mcc-$mnc" else null
    val distanceM: Int? get() = timingDistance(rat, timingAdvance)?.roundToInt()

    /** Kennung zum Erkennen derselben Zelle über Scans hinweg. */
    val key: String
        get() = if (cellId != null) "$rat|$mcc-$mnc|$cellId" else "$rat|$mcc-$mnc|nb|$arfcn|$pci"
}

/** Entfernung aus dem Timing Advance: 4G 78,12 m pro Schritt, 2G 553,5 m pro Schritt (Mitte des Intervalls). */
fun timingDistance(rat: String, ta: Int?): Double? = when {
    ta == null -> null
    rat == "4G" -> (ta + 0.5) * 78.12
    rat == "2G" -> (ta + 0.5) * 553.5
    else -> null
}

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
    else -> "Qualität"
}

object CellScanner {

    private fun Int.orNull(): Int? = if (this == Int.MAX_VALUE || this == Int.MIN_VALUE) null else this
    private fun Long.orNull(): Long? = if (this == Long.MAX_VALUE) null else this

    /** Scannt alle aktiven SIMs (Dual-SIM, falls "Telefonstatus" erlaubt ist). */
    suspend fun scan(context: Context): List<CellRecord> {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val all = subscriptionManagers(context, base).flatMap { scanOne(context, it) }
        return all
            .distinctBy { Triple(it.key, it.registered, it.arfcn) }
            .sortedWith(
                compareByDescending<CellRecord> { it.registered }
                    .thenByDescending { it.dbm ?: Int.MIN_VALUE },
            )
    }

    @SuppressLint("MissingPermission")
    private fun subscriptionManagers(context: Context, base: TelephonyManager): List<TelephonyManager> {
        val canReadState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!canReadState) return listOf(base)
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return listOf(base)
        val ids = runCatching { sm.activeSubscriptionInfoList?.map { it.subscriptionId } }.getOrNull().orEmpty()
        return if (ids.size <= 1) listOf(base) else ids.map { base.createForSubscriptionId(it) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanOne(context: Context, tm: TelephonyManager): List<CellRecord> {
        val infos: List<CellInfo> = try {
            val fresh: List<CellInfo>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                withTimeoutOrNull(6_000) { requestFresh(context, tm) }
            } else {
                null
            }
            if (fresh.isNullOrEmpty()) tm.allCellInfo.orEmpty() else fresh
        } catch (e: SecurityException) {
            emptyList()
        }
        val records = infos.mapNotNull { map(it) }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mapNr(info)?.let { return it }
        }
        return when (info) {
            is CellInfoLte -> mapLte(info)
            is CellInfoGsm -> mapGsm(info)
            is CellInfoWcdma -> mapWcdma(info)
            else -> null
        }
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
        val eci = id.ci.orNull()?.toLong()
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
            timingAdvance = ss.timingAdvance.orNull()?.takeIf { it in 0..1282 },
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
            cellId = id.nci.orNull(),
            nodeId = null,
            sector = null,
            pci = id.pci.orNull(),
            arfcn = arfcn,
            band = Bands.nr(arfcn, reportedBand),
            dbm = rsrp,
            quality = ss?.ssRsrq?.orNull()?.let { if (it > 0) -it else it }?.takeIf { it in -43..20 },
            sinr = ss?.ssSinr?.orNull()?.takeIf { it in -23..40 },
            timingAdvance = null,
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
            cellId = id.cid.orNull()?.toLong(),
            nodeId = null, sector = null,
            pci = id.bsic.orNull(),
            arfcn = arfcn,
            band = Bands.gsm(arfcn),
            dbm = ss.dbm.orNull()?.takeIf { it in -120..-20 },
            quality = null,
            sinr = null,
            timingAdvance = ss.timingAdvance.orNull()?.takeIf { it in 0..219 },
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
            cellId = id.cid.orNull()?.toLong(),
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
