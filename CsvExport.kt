package de.funkstrecke.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.funkstrecke.data.CellEntity
import de.funkstrecke.data.EstimateEntity
import de.funkstrecke.data.MeasurementEntity
import de.funkstrecke.data.pciGroup
import de.funkstrecke.data.pciPss
import de.funkstrecke.data.supportsPciGroups
import de.funkstrecke.radio.Operators
import java.io.File
import java.time.LocalDate

/** CSV mit Semikolon und Dezimalkomma, damit Excel in deutscher Einstellung es direkt öffnet. */
object CsvExport {

    private fun escape(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun num(value: Number?): String = value?.toString()?.replace('.', ',') ?: ""
    private fun str(value: Any?): String = value?.toString() ?: ""

    fun cellsCsv(cells: List<CellEntity>, estimates: List<EstimateEntity>): String {
        val byGroup = estimates.associateBy { it.groupKey }
        val header = listOf(
            "Technik", "Betreiber", "MCC", "MNC", "MCC/MNC abgeleitet", "TAC/LAC", "Cell-ID", "eNB-ID", "Sektor",
            "PCI/PSC/BSIC", "PCI-Gruppe (SSS)", "PSS", "ARFCN", "Band", "Band (MHz)", "Downlink (MHz)", "Bester Pegel (dBm)",
            "Letzter Pegel (dBm)", "Letzte Qualität (dB)", "Letztes SINR (dB)", "Min. Abstand TA (m)", "Scans",
            "Davon verbunden", "Station", "Geschätzte Breite", "Geschätzte Länge", "Radius (m)", "Methode",
            "Erstmals gesehen", "Zuletzt gesehen",
        )
        val rows = cells.sortedWith(compareBy({ it.groupKey }, { it.sector ?: 0 })).map { c ->
            val e = byGroup[c.groupKey]
            listOf(
                c.rat, Operators.name(c.mcc, c.mnc) ?: "", str(c.mcc), str(c.mnc), if (c.plmnInferred) "ja" else "nein",
                str(c.area), str(c.cellId), str(c.nodeId), str(c.sector), str(c.pci),
                if (supportsPciGroups(c.rat)) str(c.pci?.let(::pciGroup)) else "",
                if (supportsPciGroups(c.rat)) str(c.pci?.let(::pciPss)) else "",
                str(c.arfcn), str(c.band),
                str(c.bandMhz), num(c.freqMhz), str(c.bestDbm), str(c.lastDbm), str(c.lastQuality), str(c.lastSinr),
                str(c.minDistanceM), str(c.samples), str(c.servingSamples), c.groupKey,
                num(e?.latitude), num(e?.longitude), num(e?.radiusM?.toInt()), e?.method ?: "",
                formatDateTimeSeconds(c.firstSeen), formatDateTimeSeconds(c.lastSeen),
            )
        }
        return build(header, rows)
    }

    fun measurementsCsv(measurements: List<MeasurementEntity>, cells: List<CellEntity>): String {
        val byKey = cells.associateBy { it.id }
        val header = listOf(
            "Zeit", "Technik", "Betreiber", "MCC", "MNC", "TAC/LAC", "Cell-ID", "eNB-ID", "Sektor", "PCI", "ARFCN",
            "Breite", "Länge", "GPS-Genauigkeit (m)", "Pegel (dBm)", "Qualität (dB)", "SINR (dB)",
            "Timing Advance", "Verbunden",
        )
        val rows = measurements.map { m ->
            val c = byKey[m.cellKey]
            listOf(
                formatDateTimeSeconds(m.time), c?.rat ?: "", Operators.name(c?.mcc, c?.mnc) ?: "", str(c?.mcc),
                str(c?.mnc), str(c?.area), str(c?.cellId), str(c?.nodeId), str(c?.sector), str(c?.pci),
                str(c?.arfcn), num(m.latitude), num(m.longitude), num(m.accuracyM), str(m.dbm), str(m.quality),
                str(m.sinr), str(m.timingAdvance), if (m.serving) "ja" else "nein",
            )
        }
        return build(header, rows)
    }

    private fun build(header: List<String>, rows: List<List<String>>): String = buildString {
        append('﻿')
        append(header.joinToString(";") { escape(it) }).append("\r\n")
        rows.forEach { append(it.joinToString(";") { v -> escape(v) }).append("\r\n") }
    }

    fun share(context: Context, baseName: String, content: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${baseName}_${LocalDate.now()}.csv")
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Funkstrecke – $baseName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Exportieren"))
    }
}
