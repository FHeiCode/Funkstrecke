package de.funkstrecke.locate

import de.funkstrecke.data.MeasurementEntity
import de.funkstrecke.radio.plausibleTa
import de.funkstrecke.radio.timingDistance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Schätzt den Standort einer Basisstation aus GPS-markierten Messungen.
 *
 * Modell: Pegel = P0(Sektor) − 10·n·log10(Abstand / 1 m) mit n = 3.
 * P0 bekommt eine schwache Vorgabe (≈ −65 dBm in 100 m), sonst kann die Lösung
 * beliebig weit wegdriften. Timing-Advance-Werte gehen als Abstandsmessungen ein.
 * Gelöst wird mit Levenberg-Marquardt von mehreren Startpunkten aus. Passen zwei
 * Lösungen etwa gleich gut (typisch: alle Messungen auf einer Straße → Spiegelung),
 * deckt der Radius beide ab. Mit jeder neuen Blickrichtung schrumpft er.
 *
 * Mit Geländemodell ([elevation]) wird danach für jede Messung geprüft, ob zwischen dem geschätzten
 * Mast (Antenne [ANTENNA_HEIGHT_M] über Grund) und dem Messort ein Hügel liegt. Abgeschattete
 * Messungen sind viel leiser, als das Modell erwartet; sie bekommen eine große Unsicherheit und
 * ziehen die Schätzung nicht mehr nach außen. Das wird zweimal wiederholt, weil sich mit dem Mast
 * auch die Abschattung verschiebt.
 *
 * Exponent, P0-Vorgabe und Streuung kommen je Band aus dem [SignalModel]; ohne gelerntes Modell gilt
 * die feste Vorgabe. Der Timing-Advance-Abstand wird um den gelernten Versatz des Geräts korrigiert.
 */
object LocationEstimator {

    data class Result(
        val latitude: Double,
        val longitude: Double,
        val radiusM: Double,
        val method: String,
        val spots: Int,
        val measurements: Int,
    )

    private const val MIN_DISTANCE = 15.0
    private const val BIN_SIZE = 20.0
    /**
     * Messpunkte bis zu dieser GPS-Ungenauigkeit gehen ein (Zug, Stadt). Ungenaue Punkte werden
     * nicht verworfen, sondern schwächer gewichtet: Ihr Positionsfehler wird in einen Pegelfehler
     * umgerechnet, der mit dem Abstand zum Mast kleiner wird.
     */
    const val MAX_GPS_ERROR = 100f
    /** Für Punkte ohne Genauigkeitsangabe angenommen */
    private const val DEFAULT_ACCURACY_M = 30.0
    private const val MIN_RADIUS = 50.0
    /** Zuschlag für Modellfehler (Gebäude, Antennendiagramm) */
    private const val RADIUS_FACTOR = 1.5
    /** Lösungen innerhalb dieses χ²-Abstands gelten als gleich gut (≈ 2 σ) */
    private const val AMBIGUITY_CHI2 = 4.0

    /** Angenommene Antennenhöhe über Grund */
    const val ANTENNA_HEIGHT_M = 30.0
    /** Höhe des Handys über Grund */
    private const val HANDSET_HEIGHT_M = 1.5
    /** Gelände muss die Sichtlinie um so viel überragen (90-m-Raster, Wald und Gebäude grob enthalten) */
    private const val CLEARANCE_M = 10.0
    /** Zusätzliche Pegelunsicherheit für abgeschattete Messungen */
    private const val SHADOW_SIGMA_DB = 15.0
    /** Abstand der Stützpunkte im Geländeprofil */
    private const val PROFILE_STEP_M = 45.0
    /** Wirksamer Erdradius (k = 4/3) für die Erdkrümmung */
    private const val EFFECTIVE_EARTH_RADIUS_M = 6_371_000.0 * 4 / 3

    private class Obs(
        val x: Double,
        val y: Double,
        val cell: Int,
        val rss: Double,
        val taDistance: Double?,
        /** Mittlere GPS-Ungenauigkeit der Messpunkte in diesem Rasterfeld */
        val accuracy: Double,
    ) {
        /** Zusätzliche Pegelunsicherheit (Abschattung durch Gelände) */
        var extraSigmaDb: Double = 0.0
    }

    private class Solution(val x: Double, val y: Double, val cost: Double, val radius: Double)

    /**
     * @param elevation Geländehöhe in m für eine Position, null wenn unbekannt; ohne Funktion oder
     *   bei fehlenden Höhen wird ohne Gelände gerechnet.
     * @param model Signalmodell (Vorgabe oder gelernt)
     * @param bandOf Band je Zellkennung, für die Wahl des Bandmodells
     */
    fun estimate(
        rat: String,
        measurements: List<MeasurementEntity>,
        elevation: ((Double, Double) -> Double?)? = null,
        model: SignalModel = SignalModel.DEFAULT,
        bandOf: Map<String, String?> = emptyMap(),
    ): Result? {
        val usable = measurements.filter { it.dbm != null && (it.accuracyM ?: Float.MAX_VALUE) <= MAX_GPS_ERROR }
        if (usable.isEmpty()) return null

        // Lokale Ebene in Metern um den Mittelpunkt der Messungen.
        val lat0 = usable.map { it.latitude }.average()
        val lon0 = usable.map { it.longitude }.average()
        val kx = 111_320.0 * cos(lat0 * PI / 180.0)
        val ky = 110_540.0
        fun toX(lon: Double) = (lon - lon0) * kx
        fun toY(lat: Double) = (lat - lat0) * ky

        // Auf 20-m-Raster pro Zelle zusammenfassen, damit langes Stehen an einem Ort nicht dominiert.
        val cellIndex = usable.map { it.cellKey }.distinct().withIndex().associate { (i, k) -> k to i }
        val params = Array(cellIndex.size) { SignalModel.DEFAULT_BAND }
        cellIndex.forEach { (k, i) -> params[i] = model.band(rat, bandOf[k]) }
        val taModel = model.ta(rat)
        val obs = usable
            .groupBy { Triple(it.cellKey, floor(toX(it.longitude) / BIN_SIZE), floor(toY(it.latitude) / BIN_SIZE)) }
            .map { (k, list) ->
                val tas = list.mapNotNull { m -> plausibleTa(rat, m.timingAdvance, m.serving) }.sorted()
                Obs(
                    x = list.map { toX(it.longitude) }.average(),
                    y = list.map { toY(it.latitude) }.average(),
                    cell = cellIndex.getValue(k.first),
                    rss = list.map { it.dbm!!.toDouble() }.average(),
                    taDistance = if (tas.isEmpty()) null else timingDistance(rat, tas[tas.size / 2])?.let { max(0.0, it + taModel.offsetM) },
                    accuracy = list.map { it.accuracyM?.toDouble() ?: DEFAULT_ACCURACY_M }.average(),
                )
            }
        val spots = obs.map { floor(it.x / BIN_SIZE) to floor(it.y / BIN_SIZE) }.distinct().size

        // Pegelgewichteter Schwerpunkt: Startwert und Rückfallebene.
        val weights = obs.map { 10.0.pow((it.rss + 140.0) / 20.0) }
        val wSum = weights.sum()
        val cx = obs.indices.sumOf { obs[it].x * weights[it] } / wSum
        val cy = obs.indices.sumOf { obs[it].y * weights[it] } / wSum
        val spread = obs.maxOf { hypot(it.x - cx, it.y - cy) }

        fun result(x: Double, y: Double, radius: Double, method: String) = Result(
            latitude = lat0 + y / ky,
            longitude = lon0 + x / kx,
            radiusM = radius,
            method = method,
            spots = spots,
            measurements = usable.size,
        )

        val fallback = result(cx, cy, max(300.0, spread * 1.5), "Schwerpunkt (grob)")
        if (spots < 4 || spread < 60.0) return fallback

        val problem = Problem(obs, params, taModel.sigmaM)

        // Startpunkte: Schwerpunkt, beidseits quer zur Hauptachse der Messorte, Spiegelpunkt.
        val mx = obs.map { it.x }.average()
        val my = obs.map { it.y }.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (o in obs) {
            sxx += (o.x - mx) * (o.x - mx); syy += (o.y - my) * (o.y - my); sxy += (o.x - mx) * (o.y - my)
        }
        val angle = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
        val ax = cos(angle); val ay = kotlin.math.sin(angle) // Hauptachse
        val px = -ay; val py = ax // quer dazu
        val offset = max(150.0, spread / 2)
        val vx = cx - mx; val vy = cy - my
        val along = vx * ax + vy * ay
        val starts = listOf(
            cx to cy,
            cx + px * offset to cy + py * offset,
            cx - px * offset to cy - py * offset,
            (mx + 2 * along * ax - vx) to (my + 2 * along * ay - vy),
        )
        fun solveAll() = starts.mapNotNull { (sx, sy) -> problem.solve(sx, sy) }
            .filter { it.x.isFinite() && it.y.isFinite() && hypot(it.x - cx, it.y - cy) < 3 * spread + 2_000 }
        var solutions = solveAll()
        var best = solutions.minByOrNull { it.cost } ?: return fallback

        // Gelände: abgeschattete Messungen abwerten und neu lösen.
        var terrainNote = ""
        if (elevation != null) {
            fun h(x: Double, y: Double) = elevation(lat0 + y / ky, lon0 + x / kx)
            val obsGround = obs.map { h(it.x, it.y) }
            if (obsGround.all { it != null }) {
                var shadowed = 0
                repeat(2) {
                    val mastGround = h(best.x, best.y) ?: return@repeat
                    shadowed = 0
                    obs.forEachIndexed { i, o ->
                        val blocked = blocked(best.x, best.y, mastGround + ANTENNA_HEIGHT_M, o.x, o.y, obsGround[i]!! + HANDSET_HEIGHT_M, ::h)
                        o.extraSigmaDb = if (blocked) SHADOW_SIGMA_DB else 0.0
                        if (blocked) shadowed++
                    }
                    val again = solveAll()
                    again.minByOrNull { it.cost }?.let {
                        solutions = again
                        best = it
                    }
                }
                terrainNote = ", Gelände: $shadowed/${obs.size} abgeschattet"
            }
        }

        val similar = solutions.filter { it.cost <= best.cost + AMBIGUITY_CHI2 }
        val farthest = similar.maxOf { hypot(it.x - best.x, it.y - best.y) }
        val usesTa = obs.any { it.taDistance != null }
        val learnedNote = if (params.any { it.cells > 0 } || (usesTa && taModel.samples > 0)) ", gelerntes Modell" else ""
        val method = (if (usesTa) "Multilateration (Pegel + TA)" else "Multilateration (Pegel)") + terrainNote + learnedNote

        if (farthest <= best.radius) {
            if (best.radius > 20_000) return fallback
            return result(best.x, best.y, best.radius, method)
        }
        // Mehrdeutig: Kreis um alle gleich guten Lösungen.
        val minX = similar.minOf { it.x }; val maxX = similar.maxOf { it.x }
        val minY = similar.minOf { it.y }; val maxY = similar.maxOf { it.y }
        val x = (minX + maxX) / 2
        val y = (minY + maxY) / 2
        val radius = similar.maxOf { hypot(it.x - x, it.y - y) + it.radius }
        return result(x, y, min(radius, 20_000.0), "$method, mehrdeutig – aus anderer Richtung messen")
    }

    /**
     * Liegt Gelände zwischen Mast und Messort über der Sichtlinie (plus [CLEARANCE_M])?
     * Erdkrümmung mit wirksamem Erdradius; die Enden des Profils bleiben unberücksichtigt.
     */
    private fun blocked(
        mx: Double,
        my: Double,
        mastZ: Double,
        ox: Double,
        oy: Double,
        obsZ: Double,
        h: (Double, Double) -> Double?,
    ): Boolean {
        val d = hypot(ox - mx, oy - my)
        if (d < 3 * PROFILE_STEP_M) return false
        val steps = (d / PROFILE_STEP_M).toInt().coerceAtMost(400)
        for (k in 2..steps - 2) {
            val t = k.toDouble() / steps
            val ground = h(mx + (ox - mx) * t, my + (oy - my) * t) ?: continue
            val d1 = d * t
            val bulge = d1 * (d - d1) / (2 * EFFECTIVE_EARTH_RADIUS_M)
            val line = mastZ + (obsZ - mastZ) * t
            if (ground + bulge > line + CLEARANCE_M) return true
        }
        return false
    }

    /** Nichtlineares Ausgleichsproblem: Parameter x, y, P0 je Zelle. */
    private class Problem(val obs: List<Obs>, val params: Array<BandModel>, val taSigma: Double) {
        private val cells = params.size
        private val taObs = obs.filter { it.taDistance != null }
        private val nPar = 2 + cells
        private val nRes = obs.size + taObs.size + cells

        private fun residuals(q: DoubleArray, jac: Array<DoubleArray>?): DoubleArray {
            val r = DoubleArray(nRes)
            var i = 0
            for (o in obs) {
                val dx = q[0] - o.x
                val dy = q[1] - o.y
                val d = max(hypot(dx, dy), MIN_DISTANCE)
                val band = params[o.cell]
                val predicted = q[2 + o.cell] - 10 * band.exponent * log10(d)
                // Positionsfehler als zusätzlicher Pegelfehler (10 · n / ln 10 dB je relativem Abstandsfehler):
                // nah am Mast groß, weit weg klein.
                val positionDb = 10 * band.exponent / ln(10.0) * o.accuracy / d
                val sigma = sqrt(band.sigmaDb * band.sigmaDb + positionDb * positionDb + o.extraSigmaDb * o.extraSigmaDb)
                r[i] = (o.rss - predicted) / sigma
                if (jac != null) {
                    jac[i].fill(0.0)
                    val g = 10 * band.exponent / (ln(10.0) * d * d * sigma)
                    jac[i][0] = g * dx
                    jac[i][1] = g * dy
                    jac[i][2 + o.cell] = -1.0 / sigma
                }
                i++
            }
            for (o in taObs) {
                val dx = q[0] - o.x
                val dy = q[1] - o.y
                val d = max(hypot(dx, dy), 1.0)
                val sigma = sqrt(taSigma * taSigma + o.accuracy * o.accuracy)
                r[i] = (d - o.taDistance!!) / sigma
                if (jac != null) {
                    jac[i].fill(0.0)
                    jac[i][0] = dx / (d * sigma)
                    jac[i][1] = dy / (d * sigma)
                }
                i++
            }
            for (c in 0 until cells) {
                r[i] = (q[2 + c] - params[c].p0) / params[c].p0Sigma
                if (jac != null) {
                    jac[i].fill(0.0)
                    jac[i][2 + c] = 1.0 / params[c].p0Sigma
                }
                i++
            }
            return r
        }

        private fun normal(jac: Array<DoubleArray>): Array<DoubleArray> {
            val jtj = Array(nPar) { DoubleArray(nPar) }
            for (row in jac) {
                for (a in 0 until nPar) {
                    val ra = row[a]
                    if (ra == 0.0) continue
                    for (b in 0 until nPar) jtj[a][b] += ra * row[b]
                }
            }
            return jtj
        }

        fun solve(startX: Double, startY: Double): Solution? {
            val p = DoubleArray(nPar)
            p[0] = startX
            p[1] = startY
            for (c in 0 until cells) {
                val own = obs.filter { it.cell == c }
                p[2 + c] = if (own.isEmpty()) params[c].p0 else own.map {
                    it.rss + 10 * params[c].exponent * log10(max(hypot(it.x - startX, it.y - startY), MIN_DISTANCE))
                }.average()
            }
            val jac = Array(nRes) { DoubleArray(nPar) }
            var r = residuals(p, jac)
            var cost = r.sumOf { it * it }
            var lambda = 1e-2
            for (iteration in 0 until 80) {
                val jtj = normal(jac)
                val jtr = DoubleArray(nPar)
                for (k in 0 until nRes) for (a in 0 until nPar) jtr[a] += jac[k][a] * r[k]
                for (a in 0 until nPar) jtj[a][a] += lambda * max(jtj[a][a], 1e-9)
                val step = Linear.solve(jtj, DoubleArray(nPar) { -jtr[it] }) ?: break
                val candidate = DoubleArray(nPar) { p[it] + step[it] }
                val candidateCost = residuals(candidate, null).sumOf { it * it }
                if (candidateCost < cost) {
                    candidate.copyInto(p)
                    r = residuals(p, jac)
                    cost = candidateCost
                    lambda = max(lambda / 3, 1e-7)
                    if (hypot(step[0], step[1]) < 0.5) break
                } else {
                    lambda *= 4
                    if (lambda > 1e7) break
                }
            }
            // Unsicherheit: Kovarianz = (JᵀJ)⁻¹ · max(1, reduziertes χ²)
            val covariance = Linear.invert(normal(jac)) ?: return null
            val dof = nRes - nPar
            val scale = max(1.0, if (dof > 0) cost / dof else 1.0)
            val vxx = covariance[0][0] * scale
            val vyy = covariance[1][1] * scale
            val vxy = covariance[0][1] * scale
            val trace = vxx + vyy
            val det = vxx * vyy - vxy * vxy
            val largest = trace / 2 + sqrt(max(trace * trace / 4 - det, 0.0))
            // 95 %-Radius einer 2D-Normalverteilung ≈ 2,45 σ, plus Zuschlag für Modellfehler
            val radius = max(MIN_RADIUS, RADIUS_FACTOR * 2.45 * sqrt(max(largest, 0.0)))
            return Solution(p[0], p[1], cost, radius)
        }
    }
}

/** Kleine dichte Lineare Algebra für die Normalgleichungen (wenige Parameter). */
internal object Linear {

    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (row in 0 until n) {
                if (row == col) continue
                val f = m[row][col] / m[col][col]
                if (f == 0.0) continue
                for (k in col..n) m[row][k] -= f * m[col][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    fun invert(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val m = Array(n) { i -> DoubleArray(2 * n) { k -> if (k < n) a[i][k] else if (k - n == i) 1.0 else 0.0 } }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val div = m[col][col]
            for (k in 0 until 2 * n) m[col][k] /= div
            for (row in 0 until n) {
                if (row == col) continue
                val f = m[row][col]
                if (f == 0.0) continue
                for (k in 0 until 2 * n) m[row][k] -= f * m[col][k]
            }
        }
        return Array(n) { i -> DoubleArray(n) { k -> m[i][n + k] } }
    }
}

/** Die Methode wird deutsch gespeichert; für die Anzeige in die eingestellte Sprache übersetzen. */
fun localizeMethod(method: String): String =
    if (!de.funkstrecke.util.Lang.english) {
        method
    } else {
        method
            .replace("Schwerpunkt (grob)", "centroid (rough)")
            .replace("(Pegel + TA)", "(signal + TA)")
            .replace("(Pegel)", "(signal)")
            .replace("mehrdeutig – aus anderer Richtung messen", "ambiguous – measure from another direction")
            .replace(Regex("Gelände: (\\d+)/(\\d+) abgeschattet"), "terrain: $1/$2 shadowed")
            .replace("gelerntes Modell", "learned model")
            .replace(Regex("\\+(\\d+) Nachbarmessungen"), "+$1 neighbour measurements")
    }
