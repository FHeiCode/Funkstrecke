package de.funkstrecke.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ergebnis des OpenCelliD-Abgleichs für eine Zelle (Zwischenspeicher). */
@Entity(tableName = "opencellid")
data class OcidEntity(
    @PrimaryKey val cellKey: String,
    /** FOUND, NOT_FOUND oder ERROR */
    val status: String,
    val latitude: Double?,
    val longitude: Double?,
    /** Geschätzter Versorgungsradius laut OpenCelliD in m */
    val rangeM: Int?,
    /** Anzahl der Messungen, aus denen OpenCelliD die Position mittelt */
    val samples: Int?,
    val fetchedAt: Long,
    val message: String?,
) {
    val found: Boolean get() = status == STATUS_FOUND

    companion object {
        const val STATUS_FOUND = "FOUND"
        const val STATUS_NOT_FOUND = "NOT_FOUND"
        const val STATUS_ERROR = "ERROR"
    }
}

/** Fortschritt eines laufenden Abgleichs für die Oberfläche. */
data class OcidProgress(
    val running: Boolean,
    val done: Int,
    val total: Int,
    val found: Int,
    val notFound: Int,
    /** Grund für einen vorzeitigen Abbruch (Schlüssel ungültig, Tageslimit …) */
    val stopReason: String? = null,
)

/**
 * Abfrage der OpenCelliD-Datenbank (opencellid.org, Daten unter CC BY-SA 4.0).
 * Schnittstelle: https://opencellid.org/cell/get?key=…&mcc=…&mnc=…&lac=…&cellid=…&radio=…&format=json
 * Fehlercodes der API: 1 nicht gefunden, 2 Schlüssel ungültig, 3 ungültige Eingabe,
 * 4 Schlüssel nicht freigeschaltet, 5 Serverfehler, 6 zu viele Anfragen, 7 Tageslimit erreicht.
 */
object OpenCellId {

    /** Ergebnisse so lange wiederverwenden */
    const val CACHE_DAYS = 30L
    /** Abstand zwischen zwei Anfragen, um die API nicht zu überlasten */
    private const val REQUEST_SPACING_MS = 1_100L

    sealed interface Lookup {
        data class Found(val lat: Double, val lon: Double, val range: Int?, val samples: Int?) : Lookup
        data object NotFound : Lookup
        /** [fatal]: weitere Anfragen sind sinnlos (Schlüssel, Tageslimit) */
        data class Failed(val message: String, val fatal: Boolean, val retryLater: Boolean = false) : Lookup
    }

    /** Nur Zellen mit vollständiger Kennung lassen sich nachschlagen. */
    fun canLookup(c: CellEntity): Boolean =
        c.cellId != null && c.area != null && c.mcc != null && c.mnc != null && !c.plmnInferred

    private fun radioOf(rat: String): String = when (rat) {
        "4G" -> "LTE"
        "5G" -> "NR"
        "3G" -> "UMTS"
        else -> "GSM"
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    suspend fun lookup(apiKey: String, c: CellEntity): Lookup = withContext(Dispatchers.IO) {
        val url = "https://opencellid.org/cell/get?key=${enc(apiKey)}&mcc=${c.mcc}&mnc=${c.mnc?.toIntOrNull() ?: c.mnc}" +
            "&lac=${c.area}&cellid=${c.cellId}&radio=${radioOf(c.rat)}&format=json"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Funkstrecke-Android")
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 429) return@withContext Lookup.Failed("Zu viele Anfragen", fatal = false, retryLater = true)
            if (code >= 500) return@withContext Lookup.Failed("Serverfehler (HTTP $code)", fatal = false)
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: return@withContext Lookup.Failed("Unerwartete Antwort (HTTP $code)", fatal = false)
            val apiCode = json.optInt("code", 0)
            if (json.has("error") || apiCode != 0) {
                return@withContext when (apiCode) {
                    1 -> Lookup.NotFound
                    2 -> Lookup.Failed("API-Schlüssel ungültig", fatal = true)
                    3 -> Lookup.Failed("OpenCelliD lehnt die Kennung ab", fatal = false)
                    4 -> Lookup.Failed("API-Schlüssel noch nicht freigeschaltet", fatal = true)
                    6 -> Lookup.Failed("Zu viele Anfragen", fatal = false, retryLater = true)
                    7 -> Lookup.Failed("Tageslimit von OpenCelliD erreicht", fatal = true)
                    else -> if (json.optString("error").contains("not found", ignoreCase = true)) {
                        Lookup.NotFound
                    } else {
                        Lookup.Failed(json.optString("error").ifBlank { "Fehler $apiCode" }, fatal = false)
                    }
                }
            }
            if (!json.has("lat") || !json.has("lon")) return@withContext Lookup.NotFound
            Lookup.Found(
                lat = json.getDouble("lat"),
                lon = json.getDouble("lon"),
                range = json.optInt("range", -1).takeIf { it >= 0 },
                samples = json.optInt("samples", -1).takeIf { it >= 0 },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Lookup.Failed("Keine Verbindung zu OpenCelliD", fatal = false)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Gleicht [cells] ab. Bereits geprüfte Zellen werden übersprungen, solange das Ergebnis jünger als
     * [CACHE_DAYS] ist (außer bei [force]). Bricht bei ungültigem Schlüssel oder Tageslimit ab.
     */
    suspend fun compare(
        db: AppDatabase,
        apiKey: String,
        cells: List<CellEntity>,
        force: Boolean,
        onProgress: (OcidProgress) -> Unit,
    ): OcidProgress {
        val dao = db.cellDao()
        val now = System.currentTimeMillis()
        val maxAge = CACHE_DAYS * 24 * 3600 * 1000
        val todo = cells.filter { canLookup(it) }.filter { c ->
            if (force) return@filter true
            val cached = dao.getOcid(c.id)
            cached == null || cached.status == OcidEntity.STATUS_ERROR || now - cached.fetchedAt > maxAge
        }
        var p = OcidProgress(running = true, done = 0, total = todo.size, found = 0, notFound = 0)
        onProgress(p)
        for (c in todo) {
            var result = lookup(apiKey, c)
            if (result is Lookup.Failed && result.retryLater) {
                delay(5_000)
                result = lookup(apiKey, c)
            }
            val entity = when (result) {
                is Lookup.Found -> OcidEntity(c.id, OcidEntity.STATUS_FOUND, result.lat, result.lon, result.range, result.samples, now, null)
                Lookup.NotFound -> OcidEntity(c.id, OcidEntity.STATUS_NOT_FOUND, null, null, null, null, now, null)
                is Lookup.Failed -> OcidEntity(c.id, OcidEntity.STATUS_ERROR, null, null, null, null, now, result.message)
            }
            dao.upsertOcid(entity)
            p = p.copy(
                done = p.done + 1,
                found = p.found + if (result is Lookup.Found) 1 else 0,
                notFound = p.notFound + if (result == Lookup.NotFound) 1 else 0,
            )
            if (result is Lookup.Failed && result.fatal) {
                p = p.copy(running = false, stopReason = result.message)
                onProgress(p)
                return p
            }
            onProgress(p)
            delay(REQUEST_SPACING_MS)
        }
        p = p.copy(running = false)
        onProgress(p)
        return p
    }
}
