package de.funkstrecke.locate

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.abs
import kotlin.math.floor

/**
 * Geländehöhen aus dem Copernicus-Höhenmodell GLO-90 (90-m-Raster, © DLR/Airbus, frei nutzbar),
 * liegend als Cloud Optimized GeoTIFF im offenen AWS-Bucket „copernicus-dem-90m“.
 *
 * Eine Datei deckt 1° × 1° ab (in Deutschland 1200 × 1200 bzw. nördlich von 50° 800 × 1200 Punkte)
 * und ist ein einziger komprimierter Block von 3–4 MB; kleinere Ausschnitte lassen sich daraus nicht
 * laden. Heruntergeladen wird deshalb einmal je Grad-Kachel und dauerhaft als 16-Bit-Raster gespeichert
 * (gzip, etwa 1–2 MB). Kacheln über dem Meer gibt es nicht; dort gilt 0 m.
 *
 * Es ist ein Oberflächenmodell: Wald und größere Gebäude sind grob enthalten.
 */
object Terrain {
    private const val TAG = "Terrain"
    private const val BUCKET = "https://copernicus-dem-90m.s3.amazonaws.com"
    private const val PREFS = "terrain"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val MEMORY_TILES = 4

    private class Grid(val west: Int, val north: Int, val width: Int, val height: Int, val h: ShortArray?) {
        /** Bilinear interpoliert; null-Raster = Meer */
        fun at(lat: Double, lon: Double): Double {
            if (h == null) return 0.0
            val fx = ((lon - west) * width - 0.5).coerceIn(0.0, width - 1.0)
            val fy = ((north - lat) * height - 0.5).coerceIn(0.0, height - 1.0)
            val x0 = floor(fx).toInt().coerceAtMost(width - 2)
            val y0 = floor(fy).toInt().coerceAtMost(height - 2)
            val tx = fx - x0
            val ty = fy - y0
            fun v(x: Int, y: Int) = h[y * width + x].toDouble()
            return (v(x0, y0) * (1 - tx) + v(x0 + 1, y0) * tx) * (1 - ty) +
                (v(x0, y0 + 1) * (1 - tx) + v(x0 + 1, y0 + 1) * tx) * ty
        }
    }

    private var dir: File? = null
    private var appContext: Context? = null
    private val memory = object : LinkedHashMap<String, Grid>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Grid>?) = size > MEMORY_TILES
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pending = mutableSetOf<String>()
    private val failed = mutableMapOf<String, Long>()

    private val _ready = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Meldet jede neu geladene Kachel (Schätzungen können dann neu berechnet werden). */
    val ready: SharedFlow<String> = _ready.asSharedFlow()

    data class Status(val enabled: Boolean, val wifiOnly: Boolean, val tiles: Int, val bytes: Long, val loading: Int)

    private val _status = MutableStateFlow(Status(true, true, 0, 0, 0))
    val status: StateFlow<Status> = _status.asStateFlow()

    val enabled: Boolean get() = _status.value.enabled

    fun init(context: Context) {
        if (dir != null) return
        appContext = context.applicationContext
        dir = File(context.applicationContext.filesDir, "terrain").apply { mkdirs() }
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _status.value = _status.value.copy(enabled = p.getBoolean(KEY_ENABLED, true), wifiOnly = p.getBoolean(KEY_WIFI_ONLY, true))
        refreshStatus()
    }

    fun setEnabled(value: Boolean) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        _status.value = _status.value.copy(enabled = value)
    }

    fun setWifiOnly(value: Boolean) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_WIFI_ONLY, value)?.apply()
        _status.value = _status.value.copy(wifiOnly = value)
    }

    fun clear() {
        synchronized(memory) { memory.clear() }
        dir?.listFiles()?.forEach { it.delete() }
        failed.clear()
        refreshStatus()
    }

    private fun refreshStatus() {
        val files = dir?.listFiles()?.filter { it.name.endsWith(".dem") || it.name.endsWith(".sea") }.orEmpty()
        _status.value = _status.value.copy(tiles = files.size, bytes = files.sumOf { it.length() }, loading = pending.size)
    }

    private fun key(latFloor: Int, lonFloor: Int): String {
        val ns = if (latFloor >= 0) "N%02d".format(latFloor) else "S%02d".format(-latFloor)
        val ew = if (lonFloor >= 0) "E%03d".format(lonFloor) else "W%03d".format(-lonFloor)
        return "${ns}_00_${ew}_00"
    }

    private fun grid(latFloor: Int, lonFloor: Int): Grid? {
        val k = key(latFloor, lonFloor)
        synchronized(memory) { memory[k]?.let { return it } }
        val d = dir ?: return null
        val g = when {
            File(d, "$k.sea").exists() -> Grid(lonFloor, latFloor + 1, 1, 1, null)
            File(d, "$k.dem").exists() -> runCatching {
                DataInputStream(BufferedInputStream(GZIPInputStream(File(d, "$k.dem").inputStream()))).use { input ->
                    val w = input.readInt()
                    val h = input.readInt()
                    val bytes = ByteArray(w * h * 2)
                    input.readFully(bytes)
                    val arr = ShortArray(w * h)
                    ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(arr)
                    Grid(lonFloor, latFloor + 1, w, h, arr)
                }
            }.getOrNull()
            else -> null
        } ?: return null
        synchronized(memory) { memory[k] = g }
        return g
    }

    /** Geländehöhe in m, null wenn die Kachel nicht geladen ist (oder das Modell ausgeschaltet). */
    fun elevation(lat: Double, lon: Double): Double? {
        if (!enabled) return null
        return grid(floor(lat).toInt(), floor(lon).toInt())?.at(lat, lon)
    }

    /** Sind die Kacheln für diese Punkte vorhanden? Fehlende werden im Hintergrund angefordert. */
    fun request(points: List<Pair<Double, Double>>) {
        if (!enabled || dir == null) return
        val tiles = points.map { (lat, lon) -> floor(lat).toInt() to floor(lon).toInt() }.distinct()
        for ((la, lo) in tiles) {
            val k = key(la, lo)
            val d = dir ?: return
            if (File(d, "$k.dem").exists() || File(d, "$k.sea").exists()) continue
            if ((failed[k] ?: 0L) > System.currentTimeMillis() - 30 * 60_000) continue
            val added = synchronized(pending) { pending.add(k) }
            if (!added) continue
            refreshStatus()
            scope.launch {
                try {
                    mutex.withLock { if (allowedNetwork()) download(la, lo) else failed[k] = System.currentTimeMillis() }
                } finally {
                    synchronized(pending) { pending.remove(k) }
                    refreshStatus()
                }
            }
        }
    }

    private fun allowedNetwork(): Boolean {
        val cm = appContext?.getSystemService(ConnectivityManager::class.java) ?: return false
        if (cm.activeNetwork == null) return false
        return !_status.value.wifiOnly || !cm.isActiveNetworkMetered
    }

    private fun open(url: String, from: Long, to: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Range", "bytes=$from-$to")
            setRequestProperty("User-Agent", "Funkstrecke-Android (github.com/FHeiCode/Funkstrecke)")
        }

    private suspend fun download(latFloor: Int, lonFloor: Int) {
        val k = key(latFloor, lonFloor)
        val d = dir ?: return
        val name = "Copernicus_DSM_COG_30_$k" + "_DEM"
        val url = "$BUCKET/$name/$name.tif"
        try {
            // Kopf mit dem ersten Verzeichnis (IFD) lesen.
            val head = open(url, 0, 16_383).let { c ->
                try {
                    if (c.responseCode == 404 || c.responseCode == 403) {
                        File(d, "$k.sea").writeText("")
                        _ready.emit(k)
                        return
                    }
                    if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                    c.inputStream.use { it.readBytes() }
                } finally {
                    c.disconnect()
                }
            }
            val tiff = Tiff.parse(head)
            val block = open(url, tiff.offset, tiff.offset + tiff.byteCount - 1)
            try {
                if (block.responseCode !in 200..299) error("HTTP ${block.responseCode}")
                val grid = block.inputStream.use { Tiff.decode(it, tiff) }
                val tmp = File(d, "$k.tmp")
                DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream()))).use { out ->
                    out.writeInt(tiff.width)
                    out.writeInt(tiff.height)
                    val bytes = ByteBuffer.allocate(grid.size * 2).order(ByteOrder.BIG_ENDIAN)
                    bytes.asShortBuffer().put(grid)
                    out.write(bytes.array())
                }
                tmp.renameTo(File(d, "$k.dem"))
            } finally {
                block.disconnect()
            }
            _ready.emit(k)
        } catch (e: Exception) {
            Log.w(TAG, "Kachel $k nicht geladen: ${e.message}")
            failed[k] = System.currentTimeMillis()
        }
    }

    /** Minimaler Leser für die Copernicus-COGs: Float32, DEFLATE, Prädiktor 3, ein Block. */
    internal object Tiff {
        class Info(val width: Int, val height: Int, val tileWidth: Int, val tileHeight: Int, val offset: Long, val byteCount: Long, val predictor: Int)

        fun parse(b: ByteArray): Info {
            val buf = ByteBuffer.wrap(b)
            buf.order(if (b[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            require(buf.getShort(2).toInt() == 42) { "kein TIFF" }
            val ifd = buf.getInt(4)
            val n = buf.getShort(ifd).toInt() and 0xffff
            val tags = HashMap<Int, Long>()
            for (i in 0 until n) {
                val e = ifd + 2 + i * 12
                val tag = buf.getShort(e).toInt() and 0xffff
                val type = buf.getShort(e + 2).toInt() and 0xffff
                val count = buf.getInt(e + 4)
                if (count != 1) continue
                tags[tag] = when (type) {
                    3 -> (buf.getShort(e + 8).toLong() and 0xffff)
                    4 -> (buf.getInt(e + 8).toLong() and 0xffffffffL)
                    16 -> buf.getLong(e + 8)
                    else -> continue
                }
            }
            fun t(tag: Int) = tags[tag] ?: error("TIFF-Tag $tag fehlt")
            require(t(259) == 8L) { "Kompression ${t(259)}" }
            require((tags[339] ?: 3L) == 3L && (tags[258] ?: 32L) == 32L) { "kein Float32" }
            return Info(
                width = t(256).toInt(),
                height = t(257).toInt(),
                tileWidth = t(322).toInt(),
                tileHeight = t(323).toInt(),
                offset = t(324),
                byteCount = t(325),
                predictor = (tags[317] ?: 1L).toInt(),
            )
        }

        /** Entpackt den Block zeilenweise und gibt die Höhen (m, gerundet) als width × height zurück. */
        fun decode(input: InputStream, info: Info): ShortArray {
            require(info.width <= info.tileWidth && info.height <= info.tileHeight) { "mehrere Blöcke" }
            val inflater = InflaterInputStream(BufferedInputStream(input, 65_536))
            val tw = info.tileWidth
            val row = ByteArray(tw * 4)
            val out = ShortArray(info.width * info.height)
            for (y in 0 until info.height) {
                var read = 0
                while (read < row.size) {
                    val n = inflater.read(row, read, row.size - read)
                    if (n < 0) error("Block zu kurz")
                    read += n
                }
                if (info.predictor == 3) {
                    for (i in 1 until row.size) row[i] = (row[i] + row[i - 1]).toByte()
                }
                for (x in 0 until info.width) {
                    val bits = if (info.predictor == 3) {
                        ((row[x].toInt() and 0xff) shl 24) or ((row[tw + x].toInt() and 0xff) shl 16) or
                            ((row[2 * tw + x].toInt() and 0xff) shl 8) or (row[3 * tw + x].toInt() and 0xff)
                    } else {
                        ((row[4 * x + 3].toInt() and 0xff) shl 24) or ((row[4 * x + 2].toInt() and 0xff) shl 16) or
                            ((row[4 * x + 1].toInt() and 0xff) shl 8) or (row[4 * x].toInt() and 0xff)
                    }
                    val v = Float.fromBits(bits)
                    out[y * info.width + x] = if (v.isNaN() || abs(v) > 9_000f) 0 else Math.round(v).toShort()
                }
            }
            return out
        }
    }
}
