package de.funkstrecke.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import de.funkstrecke.util.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.floor

/**
 * Funkmasten aus OpenStreetMap (© OpenStreetMap-Mitwirkende, ODbL), über die Overpass-API.
 * Gesucht wird nach `man_made=mast|tower` mit `tower:type=communication`, `man_made=communications_tower`
 * und allem mit `communication:mobile_phone=yes`. Die Positionen sind echte Maststandorte, aber
 * unvollständig: Dachstandorte und Kleinzellen fehlen meist.
 *
 * Abgefragt wird in Kacheln von 0,05° × 0,075° (etwa 5,5 × 5,5 km); jede Kachel wird höchstens alle
 * [CACHE_DAYS] Tage neu geladen.
 */
@Entity(tableName = "osm_masts", indices = [Index("tile")])
data class OsmMastEntity(
    /** OSM-Objekt, z. B. "n123456" oder "w987" */
    @PrimaryKey val id: String,
    val latitude: Double,
    val longitude: Double,
    /** mast, tower, communications_tower, antenna … */
    val kind: String?,
    val operator: String?,
    val heightM: Double?,
    val name: String?,
    val tile: String,
) {
    val label: String
        get() = when (kind) {
            "tower", "communications_tower" -> tr("Funkturm", "Radio tower")
            "antenna" -> tr("Antenne", "Antenna")
            else -> tr("Funkmast", "Radio mast")
        }

    /** Kurzbeschreibung: Art, Betreiber, Höhe */
    val description: String
        get() = listOfNotNull(
            label,
            operator,
            heightM?.let { tr("${it.toInt()} m hoch", "${it.toInt()} m high") },
        ).joinToString(" · ")
}

/** Wann eine Kachel zuletzt geladen wurde. */
@Entity(tableName = "osm_tiles")
data class OsmTileEntity(
    @PrimaryKey val tile: String,
    val fetchedAt: Long,
)

@Dao
interface OsmDao {
    @Query("SELECT * FROM osm_masts")
    fun observeMasts(): Flow<List<OsmMastEntity>>

    @Query("SELECT * FROM osm_masts")
    suspend fun allMasts(): List<OsmMastEntity>

    @Query("SELECT tile FROM osm_tiles WHERE fetchedAt >= :since")
    fun observeFreshTiles(since: Long): Flow<List<String>>

    @Query("SELECT tile FROM osm_tiles WHERE fetchedAt >= :since")
    suspend fun freshTiles(since: Long): List<String>

    @Query("DELETE FROM osm_masts WHERE tile = :tile")
    suspend fun deleteMastsInTile(tile: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMasts(masts: List<OsmMastEntity>)

    @Upsert
    suspend fun upsertTile(tile: OsmTileEntity)
}

/** Fortschritt beim Laden mehrerer Kacheln. */
data class OsmProgress(
    val running: Boolean,
    val done: Int,
    val total: Int,
    val masts: Int,
    val error: String? = null,
)

object OsmMasts {
    const val CACHE_DAYS = 30L
    private const val TILE_LAT = 0.05
    private const val TILE_LON = 0.075
    /** Ein Mast so nah an der Schätzung bestätigt die Station */
    const val CONFIRM_M = 100.0
    /** Bis zu diesem Abstand wird der nächste Mast angezeigt */
    const val SEARCH_M = 1_000.0
    /** Höchstens so viele Kacheln für einen Kartenausschnitt (sonst zu weit herausgezoomt) */
    const val MAX_VIEW_TILES = 4

    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private const val REQUEST_SPACING_MS = 1_500L

    fun tileOf(lat: Double, lon: Double): String = "${floor(lat / TILE_LAT).toInt()}_${floor(lon / TILE_LON).toInt()}"

    /** Kacheln, sortiert nach Abstand ihrer Mitte zu einem Punkt (Wichtigstes zuerst laden) */
    fun sortedByDistance(tiles: List<String>, lat: Double, lon: Double): List<String> =
        tiles.sortedBy { t ->
            val b = bounds(t)
            distanceM(lat, lon, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
        }

    /** Kacheln, die ein Gebiet abdecken */
    fun tilesFor(south: Double, west: Double, north: Double, east: Double): List<String> {
        val i0 = floor(south / TILE_LAT).toInt()
        val i1 = floor(north / TILE_LAT).toInt()
        val j0 = floor(west / TILE_LON).toInt()
        val j1 = floor(east / TILE_LON).toInt()
        val out = mutableListOf<String>()
        for (i in i0..i1) for (j in j0..j1) out += "${i}_$j"
        return out
    }

    /** Kacheln im Umkreis [radiusM] um einen Punkt */
    fun tilesAround(lat: Double, lon: Double, radiusM: Double): List<String> {
        val dLat = radiusM / 110_540.0
        val dLon = radiusM / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)))
        return tilesFor(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
    }

    private fun bounds(tile: String): DoubleArray {
        val (i, j) = tile.split('_').map { it.toInt() }
        return doubleArrayOf(i * TILE_LAT, j * TILE_LON, (i + 1) * TILE_LAT, (j + 1) * TILE_LON)
    }

    private sealed interface Fetch {
        data class Ok(val masts: List<OsmMastEntity>) : Fetch
        data class Failed(val message: String, val retryLater: Boolean) : Fetch
    }

    private suspend fun fetchTile(tile: String): Fetch = withContext(Dispatchers.IO) {
        val b = bounds(tile)
        val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", b[0], b[1], b[2], b[3])
        val query = """
            [out:json][timeout:90];
            (
              nwr["man_made"~"^(mast|tower)$"]["tower:type"="communication"]($bbox);
              nwr["man_made"="communications_tower"]($bbox);
              nwr["communication:mobile_phone"="yes"]($bbox);
            );
            out center tags;
        """.trimIndent()
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "Funkstrecke-Android (github.com/FHeiCode/Funkstrecke)")
        }
        try {
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            if (code == 429 || code == 504) {
                return@withContext Fetch.Failed(tr("OpenStreetMap ist ausgelastet", "OpenStreetMap is busy"), retryLater = true)
            }
            if (code !in 200..299) return@withContext Fetch.Failed(tr("OpenStreetMap-Fehler (HTTP $code)", "OpenStreetMap error (HTTP $code)"), false)
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val elements = json.optJSONArray("elements") ?: return@withContext Fetch.Ok(emptyList())
            val masts = (0 until elements.length()).mapNotNull { k ->
                val el = elements.getJSONObject(k)
                val center = el.optJSONObject("center")
                val lat = if (el.has("lat")) el.getDouble("lat") else center?.optDouble("lat") ?: return@mapNotNull null
                val lon = if (el.has("lon")) el.getDouble("lon") else center?.optDouble("lon") ?: return@mapNotNull null
                val tags = el.optJSONObject("tags")
                fun tag(name: String) = tags?.optString(name)?.takeIf { it.isNotBlank() }
                OsmMastEntity(
                    id = el.optString("type").take(1) + el.optLong("id"),
                    latitude = lat,
                    longitude = lon,
                    kind = tag("man_made"),
                    operator = tag("operator"),
                    heightM = tag("height")?.replace(",", ".")?.takeWhile { it.isDigit() || it == '.' }?.toDoubleOrNull(),
                    name = tag("name"),
                    tile = tile,
                )
            }
            Fetch.Ok(masts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Fetch.Failed(tr("Keine Verbindung zu OpenStreetMap", "No connection to OpenStreetMap"), retryLater = false)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Lädt fehlende oder veraltete Kacheln nacheinander, mit Pause zwischen den Anfragen
     * (Nutzungsregeln der Overpass-API). Bricht beim ersten Verbindungsfehler ab.
     */
    suspend fun ensure(db: AppDatabase, tiles: Collection<String>, onProgress: (OsmProgress) -> Unit = {}): OsmProgress {
        val dao = db.osmDao()
        val since = System.currentTimeMillis() - CACHE_DAYS * 24 * 3600 * 1000
        val fresh = dao.freshTiles(since).toSet()
        val todo = tiles.distinct().filter { it !in fresh }
        var p = OsmProgress(running = todo.isNotEmpty(), done = 0, total = todo.size, masts = 0)
        onProgress(p)
        for ((n, tile) in todo.withIndex()) {
            if (n > 0) delay(REQUEST_SPACING_MS)
            var result = fetchTile(tile)
            if (result is Fetch.Failed && result.retryLater) {
                delay(10_000)
                result = fetchTile(tile)
            }
            when (result) {
                is Fetch.Ok -> {
                    db.withTransaction {
                        dao.deleteMastsInTile(tile)
                        dao.insertMasts(result.masts)
                        dao.upsertTile(OsmTileEntity(tile, System.currentTimeMillis()))
                    }
                    p = p.copy(done = p.done + 1, masts = p.masts + result.masts.size)
                    onProgress(p)
                }
                is Fetch.Failed -> {
                    p = p.copy(running = false, error = result.message)
                    onProgress(p)
                    return p
                }
            }
        }
        p = p.copy(running = false)
        onProgress(p)
        return p
    }
}

/** Nächster OSM-Mast zu einer Position innerhalb von [OsmMasts.SEARCH_M]. */
class OsmIndex(masts: List<OsmMastEntity>, val freshTiles: Set<String>) {
    private val byTile = masts.groupBy { it.tile }

    /** Kachel der Position wurde schon abgefragt (sonst ist „kein Mast“ nicht aussagekräftig) */
    fun covered(lat: Double, lon: Double): Boolean = OsmMasts.tileOf(lat, lon) in freshTiles

    fun nearest(lat: Double, lon: Double): Pair<OsmMastEntity, Double>? =
        OsmMasts.tilesAround(lat, lon, OsmMasts.SEARCH_M)
            .flatMap { byTile[it].orEmpty() }
            .map { it to distanceM(lat, lon, it.latitude, it.longitude) }
            .filter { it.second <= OsmMasts.SEARCH_M }
            .minByOrNull { it.second }

    fun within(lat: Double, lon: Double, radiusM: Double): List<OsmMastEntity> =
        OsmMasts.tilesAround(lat, lon, radiusM)
            .flatMap { byTile[it].orEmpty() }
            .filter { distanceM(lat, lon, it.latitude, it.longitude) <= radiusM }

    companion object {
        val EMPTY = OsmIndex(emptyList(), emptySet())
    }
}
