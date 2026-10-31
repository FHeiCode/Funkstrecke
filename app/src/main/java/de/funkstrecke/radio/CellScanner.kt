package de.funkstrecke.radio

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Eine vom Handy empfangene Funkzelle. */
data class CellRecord(
    val tech: String,
    val registered: Boolean,
    val mcc: String?,
    val mnc: String?,
    /** TAC (4G/5G) bzw. LAC (2G/3G) */
    val area: Int?,
    val areaLabel: String,
    /** ECI (4G), NCI (5G) oder CID (2G/3G) */
    val cellId: Long?,
    /** eNodeB-ID, nur bei 4G aus der ECI ableitbar */
    val enbId: Long?,
    val sector: Int?,
    /** PCI (4G/5G), PSC (3G), BSIC (2G) */
    val pci: Int?,
    val arfcn: Int?,
    val band: BandInfo?,
    val dbm: Int?,
) {
    val operatorName: String? get() = Operators.name(mcc, mnc)
}

object CellScanner {

    private fun Int.orNull(): Int? = if (this == Int.MAX_VALUE || this == Int.MIN_VALUE) null else this
    private fun Long.orNull(): Long? = if (this == Long.MAX_VALUE) null else this

    @SuppressLint("MissingPermission")
    suspend fun scan(context: Context): List<CellRecord> {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val infos: List<CellInfo> = try {
            val fresh: List<CellInfo>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                withTimeoutOrNull(8_000) { requestFresh(context, tm) }
            } else {
                null
            }
            if (fresh.isNullOrEmpty()) tm.allCellInfo.orEmpty() else fresh
        } catch (e: SecurityException) {
            emptyList()
        }
        return infos.mapNotNull { map(it) }
            .sortedWith(compareByDescending<CellRecord> { it.registered }.thenByDescending { it.dbm ?: Int.MIN_VALUE })
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
        val id: CellIdentityLte = info.cellIdentity
        val (mcc, mnc) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mccString to id.mncString
        } else {
            id.mcc.orNull()?.toString() to id.mnc.orNull()?.toString()?.padStart(2, '0')
        }
        val eci = id.ci.orNull()?.toLong()
        val earfcn = id.earfcn.orNull()
        val reportedBand = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.firstOrNull() else null
        return CellRecord(
            tech = "4G",
            registered = info.isRegistered,
            mcc = mcc, mnc = mnc,
            area = id.tac.orNull(), areaLabel = "TAC",
            cellId = eci,
            enbId = eci?.shr(8),
            sector = eci?.and(0xFF)?.toInt(),
            pci = id.pci.orNull(),
            arfcn = earfcn,
            band = Bands.lte(earfcn, reportedBand),
            dbm = info.cellSignalStrength.dbm.orNull(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mapNr(info: CellInfo): CellRecord? {
        if (info !is CellInfoNr) return null
        val id = info.cellIdentity as? CellIdentityNr ?: return null
        val signal = info.cellSignalStrength as? CellSignalStrengthNr
        val arfcn = id.nrarfcn.orNull()
        val reportedBand = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.firstOrNull() else null
        return CellRecord(
            tech = "5G",
            registered = info.isRegistered,
            mcc = id.mccString, mnc = id.mncString,
            area = id.tac.orNull(), areaLabel = "TAC",
            cellId = id.nci.orNull(),
            enbId = null,
            sector = null,
            pci = id.pci.orNull(),
            arfcn = arfcn,
            band = Bands.nr(arfcn, reportedBand),
            dbm = signal?.dbm?.orNull(),
        )
    }

    @Suppress("DEPRECATION")
    private fun mapGsm(info: CellInfoGsm): CellRecord {
        val id = info.cellIdentity
        val (mcc, mnc) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mccString to id.mncString
        } else {
            id.mcc.orNull()?.toString() to id.mnc.orNull()?.toString()?.padStart(2, '0')
        }
        val arfcn = id.arfcn.orNull()
        return CellRecord(
            tech = "2G",
            registered = info.isRegistered,
            mcc = mcc, mnc = mnc,
            area = id.lac.orNull(), areaLabel = "LAC",
            cellId = id.cid.orNull()?.toLong(),
            enbId = null, sector = null,
            pci = id.bsic.orNull(),
            arfcn = arfcn,
            band = Bands.gsm(arfcn),
            dbm = info.cellSignalStrength.dbm.orNull(),
        )
    }

    @Suppress("DEPRECATION")
    private fun mapWcdma(info: CellInfoWcdma): CellRecord {
        val id = info.cellIdentity
        val (mcc, mnc) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mccString to id.mncString
        } else {
            id.mcc.orNull()?.toString() to id.mnc.orNull()?.toString()?.padStart(2, '0')
        }
        return CellRecord(
            tech = "3G",
            registered = info.isRegistered,
            mcc = mcc, mnc = mnc,
            area = id.lac.orNull(), areaLabel = "LAC",
            cellId = id.cid.orNull()?.toLong(),
            enbId = null, sector = null,
            pci = id.psc.orNull(),
            arfcn = id.uarfcn.orNull(),
            band = null,
            dbm = info.cellSignalStrength.dbm.orNull(),
        )
    }
}
