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
            mast != null && d != null && s.osmUnambiguous -> Reference(s.key, s.rat, mast.latitude, mast.longitude, true)
            e.radiusM <= OWN_TRUTH_RADIUS_M && e.method.startsWith("Multilateration") && !e.method.contains("mehrdeutig") ->
                Reference(s.key, s.rat, e.latitude, e.longitude, false)
            else -> null
        }
    }

    /** [neighbour]: An diesem Messort wurde die Zelle (auch) als Nachbarzelle gemessen. */
    private class Point(val logD: Double, val rss: Double, val neighbour: Boolean)

    /** So viele Messorte als Nachbarzelle braucht eine Zelle, damit ihr Pegelabfall zählt */
    private const val MIN_NEIGHBOUR_POINTS = 5

    /** Zahl der Zellen je Band, die für den Pegelabfall schon genug Nachbarmessungen haben (für die Anzeige) */
    data class Progress(val cellsWithNeighbours: Int, val needed: Int)

    @Volatile
    var lastProgress: Progress = Progress(0, MIN_CELLS)
        private set

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
                    if (d < MIN_DISTANCE_M || d > MAX_DISTANCE_M) null else Point(log10(d), bin.map { it.dbm!!.toDouble() }.average(), bin.any { !it.serving })
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

        lastProgress = Progress(
            byBand.filterKeys { it.endsWith("/*") }.values.sumOf { cells -> cells.count { usableForSlope(it) } },
            MIN_CELLS,
        )
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
                // Untergrenze nahe der Stufenbreite: LTE 78 m je Stufe ≈ ±23 m, GSM 554 m ≈ ±160 m.
                sigmaM = robustSigma(diffs).coerceIn(if (rat == "2G") 200.0 else 30.0, default.sigmaM * 4),
                samples = diffs.size,
            )
        }
        return SignalModel(bands, ta, now, references.size)
    }

    /**
     * Für den Pegelabfall zählen nur Zellen mit genug Messorten über mindestens Faktor 2 im Abstand, die auch
     * als Nachbarzelle gemessen wurden. Als verbundene Zelle wird eine Zelle nur dort gemessen, wo sie stark
     * ist: In der Ferne fehlen die schwachen Orte, und der Pegel scheint langsamer zu fallen, als er es tut
     * (gelernt wurde so n ≈ 2 statt 3–4). Nachbarmessungen füllen diese Lücke.
     */
    private fun usableForSlope(c: List<Point>): Boolean =
        c.size >= 5 && c.maxOf { it.logD } - c.minOf { it.logD } >= 0.3 && c.count { it.neighbour } >= MIN_NEIGHBOUR_POINTS

    private fun fitBand(all: List<List<Point>>): BandModel? {
        val slopeCells = all.filter { usableForSlope(it) }
        // Auch Grundpegel und Streuung nur aus diesen Zellen, damit beides zum Exponenten passt.
        val cells = slopeCells
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

    /** Prüfergebnisse der beiden Teile des gelernten Modells */
    class Checks(val ta: ModelCheck, val bands: ModelCheck)

    /**
     * Prüft das Lernen an Stationen mit OSM-Mast (höchstens 300 m neben der Schätzung), ohne dass sie sich
     * selbst bestätigen: Die Referenzen werden in zwei Hälften geteilt, gelernt wird jeweils auf der einen,
     * gemessen auf der anderen. Verglichen wird der Abstand der Schätzung zum Mast mit der Vorgabe und
     * – getrennt – mit nur der gelernten Timing-Advance-Korrektur und mit nur dem gelernten Pegelmodell.
     */
    suspend fun check(db: AppDatabase, stations: List<BaseStation>, references: List<Reference>): Checks {
        fun fold(key: String) = (key.hashCode() and 0x7fffffff) % 2
        val targets = stations.filter { s ->
            // Nur eindeutige Masten: Der „nächste Mast“ in dichter Bebauung ist Zufall und bevorzugt die Vorgabe.
            !s.inferred && s.estimate != null && s.osmMast != null && s.osmIsolated
        }
        val elevation: ((Double, Double) -> Double?)? = if (Terrain.enabled) Terrain::elevation else null
        val taPairs = ArrayList<Pair<Double, Double>>()
        val bandPairs = ArrayList<Pair<Double, Double>>()
        for (f in 0..1) {
            val model = learn(db, references.filter { fold(it.key) != f })
            if (model.isDefault) continue
            val taOnly = model.copy(bands = emptyMap())
            val bandsOnly = model.copy(ta = emptyMap())
            for (s in targets.filter { fold(it.key) == f }) {
                val mast = s.osmMast!!
                val input = Recorder.measurementsForEstimate(db, s.key)
                fun error(m: SignalModel): Double? =
                    LocationEstimator.estimate(s.rat, input.measurements, elevation, m, input.bandOf)
                        ?.takeIf { it.method.startsWith("Multilateration") }
                        ?.let { distanceM(it.latitude, it.longitude, mast.latitude, mast.longitude) }
                val base = error(SignalModel.DEFAULT) ?: continue
                if (taOnly.ta.isNotEmpty()) error(taOnly)?.let { taPairs += base to it }
                if (bandsOnly.bands.isNotEmpty()) error(bandsOnly)?.let { bandPairs += base to it }
            }
        }
        fun result(pairs: List<Pair<Double, Double>>) = ModelCheck(
            stations = pairs.size,
            medianDefaultM = if (pairs.isEmpty()) null else median(pairs.map { it.first }),
            medianLearnedM = if (pairs.isEmpty()) null else median(pairs.map { it.second }),
            improved = pairs.count { it.second < it.first - 10 },
            worse = pairs.count { it.second > it.first + 10 },
        )
        return Checks(result(taPairs), result(bandPairs))
    }
}
