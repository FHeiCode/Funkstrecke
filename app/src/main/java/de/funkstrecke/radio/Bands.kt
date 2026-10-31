package de.funkstrecke.radio

/** Band-Ergebnis: Bandbezeichnung, Frequenzklasse in MHz (wie im Formular) und Downlink-Mittenfrequenz. */
data class BandInfo(val label: String, val mhz: String, val downlinkMhz: Double?)

/**
 * Umrechnung von Kanalnummern (EARFCN / NR-ARFCN / ARFCN) in Band und Frequenz
 * für die in Deutschland genutzten Bänder (3GPP TS 36.101 / 38.104 / 45.005).
 */
object Bands {
    private data class LteBand(
        val number: Int, val mhz: String, val fdlLow: Double, val nOffset: Int, val range: IntRange,
    )

    private val lteBands = listOf(
        LteBand(1, "2100", 2110.0, 0, 0..599),
        LteBand(3, "1800", 1805.0, 1200, 1200..1949),
        LteBand(7, "2600", 2620.0, 2750, 2750..3449),
        LteBand(8, "900", 925.0, 3450, 3450..3799),
        LteBand(20, "800", 791.0, 6150, 6150..6449),
        LteBand(28, "700", 758.0, 9210, 9210..9659),
        LteBand(32, "1500", 1452.0, 9920, 9920..10359),
        LteBand(38, "2600", 2570.0, 37750, 37750..38249),
    )

    fun lte(earfcn: Int?, reportedBand: Int?): BandInfo? {
        val band = lteBands.firstOrNull { reportedBand != null && it.number == reportedBand }
            ?: earfcn?.let { e -> lteBands.firstOrNull { e in it.range } }
        if (band == null) return reportedBand?.let { BandInfo("B$it", "", null) }
        val freq = earfcn?.takeIf { it in band.range }?.let { band.fdlLow + 0.1 * (it - band.nOffset) }
        return BandInfo("B${band.number}", band.mhz, freq)
    }

    private data class NrBand(val number: Int, val mhz: String, val dl: ClosedFloatingPointRange<Double>)

    private val nrBands = listOf(
        NrBand(28, "700", 758.0..790.99),
        NrBand(20, "800", 791.0..821.0),
        NrBand(8, "900", 925.0..960.0),
        NrBand(3, "1800", 1805.0..1880.0),
        NrBand(1, "2100", 2110.0..2170.0),
        NrBand(7, "2600", 2620.0..2690.0),
        NrBand(78, "3600", 3300.0..3800.0),
    )

    fun nrFrequency(arfcn: Int): Double? = when {
        arfcn in 0 until 600_000 -> arfcn * 0.005
        arfcn in 600_000..2_016_666 -> 3000.0 + 0.015 * (arfcn - 600_000)
        else -> null
    }

    fun nr(arfcn: Int?, reportedBand: Int?): BandInfo? {
        val freq = arfcn?.let(::nrFrequency)
        val band = nrBands.firstOrNull { reportedBand != null && it.number == reportedBand }
            ?: freq?.let { f -> nrBands.firstOrNull { f in it.dl } }
        if (band == null) return reportedBand?.let { BandInfo("n$it", "", freq) }
        return BandInfo("n${band.number}", band.mhz, freq)
    }

    fun gsm(arfcn: Int?): BandInfo? {
        val n = arfcn ?: return null
        return when (n) {
            in 0..124 -> BandInfo("GSM 900", "900", 935.0 + 0.2 * n)
            in 975..1023 -> BandInfo("E-GSM 900", "900", 935.0 + 0.2 * (n - 1024))
            in 512..885 -> BandInfo("DCS 1800", "1800", 1805.2 + 0.2 * (n - 512))
            else -> null
        }
    }
}

/** Netzbetreiber anhand MCC/MNC (Deutschland: MCC 262). */
object Operators {
    fun name(mcc: String?, mnc: String?): String? {
        if (mcc != "262" || mnc == null) return null
        return when (mnc.padStart(2, '0')) {
            "01", "06" -> "Telekom"
            "02", "04", "09" -> "Vodafone"
            "03", "05", "07", "08", "11" -> "Telefónica (O2)"
            "23" -> "1&1"
            else -> null
        }
    }
}
