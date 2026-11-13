package de.funkstrecke.locate

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Pegelmodell eines Bandes: Pegel = P0 − 10 · [exponent] · log10(Abstand / 1 m).
 * [p0] und [p0Sigma] sind die Vorgabe für den Pegel in 1 m, [sigmaDb] die Streuung einzelner Messungen.
 */
data class BandModel(
    val exponent: Double,
    val p0: Double,
    val p0Sigma: Double,
    val sigmaDb: Double,
    /** Zellen und Messorte, aus denen gelernt wurde (0 = Vorgabe) */
    val cells: Int = 0,
    val points: Int = 0,
) {
    /** Erwarteter Pegel in [distanceM] */
    fun levelAt(distanceM: Double): Double = p0 - 10 * exponent * kotlin.math.log10(distanceM)
}

/** Korrektur des Timing-Advance-Abstands dieses Geräts: wahrer Abstand ≈ TA-Abstand + [offsetM]. */
data class TaModel(val offsetM: Double, val sigmaM: Double, val samples: Int = 0)

/**
 * Signalmodell für die Standortschätzung. Ohne gelernte Werte gilt für alle Bänder dieselbe Vorgabe
 * (Exponent 3, etwa −65 dBm in 100 m). Gelernt wird an Stationen mit gut bekanntem Standort,
 * siehe de.funkstrecke.data.SignalLearning.
 */
data class SignalModel(
    /** Schlüssel „Technik/Band“ (z. B. „4G/B3“) oder „Technik/ *“ für alle Bänder einer Technik */
    val bands: Map<String, BandModel> = emptyMap(),
    /** Schlüssel = Technik */
    val ta: Map<String, TaModel> = emptyMap(),
    val learnedAt: Long = 0,
    val references: Int = 0,
) {
    val isDefault: Boolean get() = bands.isEmpty() && ta.isEmpty()

    fun band(rat: String, band: String?): BandModel =
        (band?.let { bands["$rat/$it"] }) ?: bands["$rat/*"] ?: DEFAULT_BAND

    fun ta(rat: String): TaModel = ta[rat] ?: if (rat == "2G") DEFAULT_TA_GSM else DEFAULT_TA_LTE

    fun toJson(): String = JSONObject().apply {
        put("learnedAt", learnedAt)
        put("references", references)
        put(
            "bands",
            JSONObject().also { o ->
                bands.forEach { (k, b) ->
                    o.put(
                        k,
                        JSONObject().put("n", b.exponent).put("p0", b.p0).put("p0s", b.p0Sigma).put("s", b.sigmaDb)
                            .put("cells", b.cells).put("points", b.points),
                    )
                }
            },
        )
        put(
            "ta",
            JSONObject().also { o ->
                ta.forEach { (k, t) -> o.put(k, JSONObject().put("off", t.offsetM).put("s", t.sigmaM).put("n", t.samples)) }
            },
        )
    }.toString()

    companion object {
        val DEFAULT_BAND = BandModel(exponent = 3.0, p0 = -5.0, p0Sigma = 10.0, sigmaDb = 6.0)
        val DEFAULT_TA_LTE = TaModel(0.0, 80.0)
        val DEFAULT_TA_GSM = TaModel(0.0, 550.0)
        val DEFAULT = SignalModel()

        fun fromJson(text: String): SignalModel? = runCatching {
            val j = JSONObject(text)
            val bands = j.getJSONObject("bands").let { o ->
                o.keys().asSequence().associateWith { k ->
                    val b = o.getJSONObject(k)
                    BandModel(b.getDouble("n"), b.getDouble("p0"), b.getDouble("p0s"), b.getDouble("s"), b.optInt("cells"), b.optInt("points"))
                }
            }
            val ta = j.getJSONObject("ta").let { o ->
                o.keys().asSequence().associateWith { k ->
                    val t = o.getJSONObject(k)
                    TaModel(t.getDouble("off"), t.getDouble("s"), t.optInt("n"))
                }
            }
            SignalModel(bands, ta, j.optLong("learnedAt"), j.optInt("references"))
        }.getOrNull()
    }
}

/** Ergebnis der Prüfung des gelernten Modells an Stationen mit OSM-Mast (Kreuzvalidierung). */
data class ModelCheck(
    val stations: Int,
    val medianDefaultM: Double?,
    val medianLearnedM: Double?,
    val improved: Int,
    val worse: Int,
) {
    /** Genug Prüfstationen und im Median nicht schlechter als die Vorgabe */
    val passed: Boolean
        get() = stations >= MIN_STATIONS && medianDefaultM != null && medianLearnedM != null && medianLearnedM <= medianDefaultM

    companion object {
        const val MIN_STATIONS = 6
    }
}

/** Gelerntes Signalmodell: gespeichert, ein-/ausschaltbar und nur aktiv, wenn die Prüfung bestanden ist. */
object SignalModelStore {
    private const val PREFS = "signal_model"
    private const val KEY_MODEL = "model"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CHECK = "check"

    data class State(val enabled: Boolean, val learned: SignalModel?, val check: ModelCheck?) {
        /** Das gelernte Modell wird nur verwendet, wenn es eingeschaltet und geprüft besser ist. */
        val applied: Boolean get() = enabled && learned != null && !learned.isDefault && check?.passed == true
        val active: SignalModel get() = if (applied) learned!! else SignalModel.DEFAULT
    }

    private var context: Context? = null
    private val _state = MutableStateFlow(State(true, null, null))
    val state: StateFlow<State> = _state.asStateFlow()

    val active: SignalModel get() = _state.value.active

    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val check = p.getString(KEY_CHECK, null)?.let { text ->
            runCatching {
                val j = JSONObject(text)
                ModelCheck(
                    j.getInt("n"),
                    if (j.has("d")) j.getDouble("d") else null,
                    if (j.has("l")) j.getDouble("l") else null,
                    j.optInt("better"),
                    j.optInt("worse"),
                )
            }.getOrNull()
        }
        _state.value = State(p.getBoolean(KEY_ENABLED, true), p.getString(KEY_MODEL, null)?.let { SignalModel.fromJson(it) }, check)
    }

    fun setEnabled(value: Boolean) {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        _state.value = _state.value.copy(enabled = value)
    }

    fun save(model: SignalModel, check: ModelCheck) {
        val c = JSONObject().put("n", check.stations).put("better", check.improved).put("worse", check.worse)
        check.medianDefaultM?.let { c.put("d", it) }
        check.medianLearnedM?.let { c.put("l", it) }
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY_MODEL, model.toJson())?.putString(KEY_CHECK, c.toString())?.apply()
        _state.value = _state.value.copy(learned = model, check = check)
    }
}
