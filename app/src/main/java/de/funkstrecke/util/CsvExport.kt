package de.funkstrecke.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.funkstrecke.data.Station
import de.funkstrecke.data.splitList
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val GERMAN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Datum aus UTC-Millisekunden (DatePicker-Format). */
fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().format(GERMAN_DATE)

fun localDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** CSV mit Semikolon und Dezimalkomma, damit Excel in deutscher Einstellung es direkt öffnet. */
object CsvExport {

    private val header = listOf(
        "ID", "Name", "Standort-ID", "Betreiber", "Status", "Adresse", "Breite", "Länge",
        "Genauigkeit (m)", "Technologien", "Bänder (MHz)", "MCC", "MNC", "TAC/LAC", "eNB/gNB-ID",
        "Cell-IDs", "PCI", "Sektoren", "Azimute", "Masttyp", "Gesamthöhe (m)", "Antennenhöhe (m)",
        "BNetzA-Standortbescheinigung", "Inbetriebnahme", "Letzte Begehung", "Nächste Wartung",
        "Fotos", "Notizen", "Zuletzt geändert",
    )

    private fun escape(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun decimal(value: Number?): String = value?.toString()?.replace('.', ',') ?: ""

    private fun row(s: Station): List<String> = listOf(
        s.id.toString(), s.name, s.siteId, s.operator, s.status, s.address,
        decimal(s.latitude), decimal(s.longitude), decimal(s.accuracyM),
        s.technologies.replace(",", " / "), s.bands.replace(",", " / "),
        s.mcc, s.mnc, s.tac, s.enbId, s.cellIds, s.pcis, s.sectors?.toString() ?: "", s.azimuths,
        s.mastType, decimal(s.heightTotal), decimal(s.heightAntenna), s.bnetzaNr,
        s.commissioning?.let(::formatDate) ?: "",
        s.lastVisit?.let(::formatDate) ?: "",
        s.nextMaintenance?.let(::formatDate) ?: "",
        s.photos.splitList('|').size.toString(),
        s.notes,
        formatDate(s.updatedAt),
    )

    fun share(context: Context, stations: List<Station>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "funkstrecke_${LocalDate.now()}.csv")
        val text = buildString {
            append('﻿')
            append(header.joinToString(";") { escape(it) }).append("\r\n")
            stations.forEach { append(row(it).joinToString(";") { v -> escape(v) }).append("\r\n") }
        }
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Funkstrecke")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Standorte exportieren"))
    }
}
