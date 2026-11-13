package de.funkstrecke.data

import de.funkstrecke.locate.BandModel
import de.funkstrecke.locate.LocationEstimator
import de.funkstrecke.locate.ModelCheck
import de.funkstrecke.locate.SignalModel
import de.funkstrecke.locate.TaModel
import de.funkstrecke.locate.Terrain
import de.funkstrecke.radio.plausibleTa
import de.funkstrecke.radio.timingDistance
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Lernt das Signalmodell an Stationen, deren Standort gut bekannt ist:
 *  - OSM-Funkmast höchstens 100 m neben der Schätzung → der Mast gilt als wahrer Standort,
 *  - sonst eigene Multilateration mit Radius ≤ 150 m → die Schätzung gilt als wahrer Standort.
 *
 * Je Band wird der Pegelabfall mit dem Abstand (Exponent n) aus den Messungen aller Zellen bestimmt,
 * jede Zelle mit eigenem Grundpegel; daraus folgen Mittel und Streuung des Grundpegels P0 und die
 * Streuung einzelner Messungen. Wenige Daten ziehen n zur Vorgabe 3. Aus den Timing-Advance-Werten
 * der verbundenen Zelle folgt der Abstandsversatz des Geräts.
 */
object SignalLearning {

    /** Station mit bekanntem Standort */
    data class Reference(val key: String, val rat: String, val latitude: Double, val longitude: Double, val fromOsm: Boolean)

    private const val OSM_TRUTH_M = 100.0
    private const val OWN_TRUTH_RADIUS_M = 150.0
    /** Näher am Mast verfälscht der Standortfehler den Abstand zu stark */
    private const val MIN_DISTANCE_M = 150.0
    private const val MAX_DISTANCE_M = 20_000.0
    private const val BIN_DEG = 0.0002 // etwa 20 m
    private const val MIN_CELLS = 4
    private const val MIN_POINTS = 80
    /** Gewicht der Vorgabe n = 3, in Messorten */
    private const val PRIOR_POINTS = 150.0
    private const val MIN_TA_SAMPLES = 60

    fun references(stations: List<BaseStation>): List<Reference> = stations.mapNotNull { s ->
        val e = s.estimate ?: return@mapNotNull null
        if (s.inferred) return@mapNotNull null
        val mast = s.osmMast
        val d = s.osmMastDistanceM
        when {
            mast != null && d != null && d <= OSM_TRUTH_M -> Reference(s.key, s.rat, mast.latitude, mast.longitude, true)
            e.radiusM <= OWN_TRUTH_RADIUS_M && e.method.startsWith("Multilateration") && !e.method.contains("mehrdeutig") ->
                Reference(s.key, s.rat, e.latitude, e.longitude, false)
            else -> null
        }
    }

    private class Point(val logD: Double, val rss: Double)

    private fun median(v: List<Double>): Double = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    /** Robuste Streuung: 1,4826 · Median der absoluten Abweichungen */
    private fun robustSigma(v: List<Double>): Double {
        val m = median(v)
        return 1.4826 * median(v.map { abs(it - m) })
    }

    suspend fun learn(db: AppDatabase, references: List<Reference>, now: Long = System.currentTimeMillis()): SignalModel {
        // Messorte je Zelle, gruppiert nach „Technik/Band“.
        val byBand = HashMap<String, MutableList<List<Point>>>()
        val taDiffs = HashMap<String, MutableList<Double>>()
        for (ref in references) {
            val input = Recorder.measurementsForEstimate(db, ref.key)
            val usable = input.measurements.filter { it.dbm != null && (it.accuracyM ?: 30f) <= 50f }
            for ((cellKey, list) in usable.groupBy { it.cellKey }) {
                val points = list.groupBy { (it.latitude / BIN_DEG).toInt() to (it.longitude / BIN_DEG).toInt() }.values.mapNotNull { bin ->
                    val d = distanceM(ref.latitude, ref.longitude, bin.map { it.latitude }.average(), bin.map { it.longitude }.average())
                    if (d < MIN_DISTANCE_M || d > MAX_DISTANCE_M) null else Point(log10(d), bin.map { it.dbm!!.toDouble() }.average())
                }
                if (points.size >= 3) {
                    val band = input.bandOf[cellKey] ?: "?"
                    byBand.getOrPut("${ref.rat}/$band") { mutableListOf() } += points
                    byBand.getOrPut("${ref.rat}/*") { mutableListOf() } += points
                }
            }
            for (m in usable) {
                val ta = plausibleTa(ref.rat, m.timingAdvance, m.serving) ?: continue
                val d = distanceM(ref.latitude, ref.longitude, m.latitude, m.longitude)
                if (d > MAX_DISTANCE_M) continue
                val taDistance = timingDistance(ref.rat, ta) ?: continue
                taDiffs.getOrPut(ref.rat) { mutableListOf() } += d - taDistance
            }
        }

        val bands = HashMap<String, BandModel>()
        for ((key, cells) in byBand) {
            if (key.endsWith("/?")) continue
            fitBand(cells)?.let { bands[key] = it }
        }
        val ta = HashMap<String, TaModel>()
        for ((rat, diffs) in taDiffs) {
            if (diffs.size < MIN_TA_SAMPLES) continue
            val default = SignalModel.DEFAULT.ta(rat)
            ta[rat] = TaModel(
                offsetM = median(diffs).coerceIn(-400.0, 400.0),
                sigmaM = robustSigma(diffs).coerceIn(default.sigmaM * 0.75, default.sigmaM * 4),
                samples = diffs.size,
            )
        }
        return SignalModel(bands, ta, now, references.size)
    }

    private fun fitBand(cells: List<List<Point>>): BandModel? {
        // Steigung nur aus Zellen mit genug Messorten über mindestens Faktor 2 im Abstand.
        val slopeCells = cells.filter { c -> c.size >= 5 && c.maxOf { it.logD } - c.minOf { it.logD } >= 0.3 }
        val n = slopeCells.sumOf { it.size }
        if (slopeCells.size < MIN_CELLS || n < MIN_POINTS) return null
        var sxx = 0.0
        var sxy = 0.0
        for (c in slopeCells) {
            val mx = c.map { it.logD }.average()
            val my = c.map { it.rss }.average()
            for (p in c) {
                sxx += (p.logD - mx) * (p.logD - mx)
                sxy += (p.logD - mx) * (p.rss - my)
            }
        }
        if (sxx <= 0.0) return null
        val fitted = -sxy / (10 * sxx)
        val default = SignalModel.DEFAULT_BAND
        val exponent = ((n * fitted + PRIOR_POINTS * default.exponent) / (n + PRIOR_POINTS)).coerceIn(2.0, 4.5)
        // Grundpegel je Zelle mit diesem Exponenten.
        val p0s = cells.map { c -> c.map { it.rss + 10 * exponent * it.logD }.average() }
        val p0 = median(p0s)
        val residuals = cells.flatMapIndexed { i, c -> c.map { it.rss - (p0s[i] - 10 * exponent * it.logD) } }
        val sigma = sqrt(residuals.sumOf { it * it } / residuals.size.coerceAtLeast(1))
        return BandModel(
            exponent = exponent,
            p0 = p0,
            p0Sigma = robustSigma(p0s).coerceIn(4.0, 12.0),
            sigmaDb = sigma.coerceIn(4.0, 10.0),
            cells = cells.size,
            points = cells.sumOf { it.size },
        )
    }

    /**
     * Prüft das Lernen an Stationen mit OSM-Mast (höchstens 300 m neben der Schätzung), ohne dass sie sich
     * selbst bestätigen: Die Referenzen werden in zwei Hälften geteilt, gelernt wird jeweils auf der einen,
     * gemessen auf der anderen. Verglichen wird der Abstand der Schätzung zum Mast mit Vorgabe und gelerntem Modell.
     */
    suspend fun check(db: AppDatabase, stations: List<BaseStation>, references: List<Reference>): ModelCheck {
        fun fold(key: String) = (key.hashCode() and 0x7fffffff) % 2
        val targets = stations.filter { s ->
            !s.inferred && s.estimate != null && s.osmMast != null && (s.osmMastDistanceM ?: Double.MAX_VALUE) <= 300.0
        }
        val elevation: ((Double, Double) -> Double?)? = if (Terrain.enabled) Terrain::elevation else null
        val pairs = ArrayList<Pair<Double, Double>>()
        for (f in 0..1) {
            val model = learn(db, references.filter { fold(it.key) != f })
            if (model.isDefault) continue
            for (s in targets.filter { fold(it.key) == f }) {
                val mast = s.osmMast!!
                val input = Recorder.measurementsForEstimate(db, s.key)
                val a = LocationEstimator.estimate(s.rat, input.measurements, elevation) ?: continue
                val b = LocationEstimator.estimate(s.rat, input.measurements, elevation, model, input.bandOf) ?: continue
                if (!a.method.startsWith("Multilateration") || !b.method.startsWith("Multilateration")) continue
                pairs += distanceM(a.latitude, a.longitude, mast.latitude, mast.longitude) to
                    distanceM(b.latitude, b.longitude, mast.latitude, mast.longitude)
            }
        }
        return ModelCheck(
            stations = pairs.size,
            medianDefaultM = if (pairs.isEmpty()) null else median(pairs.map { it.first }),
            medianLearnedM = if (pairs.isEmpty()) null else median(pairs.map { it.second }),
            improved = pairs.count { it.second < it.first - 10 },
            worse = pairs.count { it.second > it.first + 10 },
        )
    }
}
