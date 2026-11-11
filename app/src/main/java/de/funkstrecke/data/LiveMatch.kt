package de.funkstrecke.data

import de.funkstrecke.radio.CellRecord

/**
 * Station im aktuellen Scan: verbunden oder als Nachbarzelle gemessen, mit der stärksten passenden Zelle
 * und allen dieser Station zugeordneten Nachbarzellen (bei der verbundenen Station: weitere Sektoren).
 */
data class LiveMatch(val serving: Boolean, val cell: CellRecord, val neighbourCells: List<CellRecord> = emptyList())

/** Ergebnis des Abgleichs: Treffer je Stationsschlüssel und die Zahl der gehörten Zellen. */
data class LiveMatches(
    val byStation: Map<String, LiveMatch>,
    val servingHeard: Int,
    val neighboursHeard: Int,
) {
    val servingShown: Int get() = byStation.values.count { it.serving }
    val neighboursShown: Int get() = byStation.size - servingShown

    companion object {
        val EMPTY = LiveMatches(emptyMap(), 0, 0)
    }
}

/** „In der Nähe“: Höchstabstand der Station zur eigenen Position (ersatzweise zur verbundenen Station). */
const val NEIGHBOUR_MATCH_MAX_M = 10_000.0

/**
 * Ordnet die Zellen eines Live-Scans den verorteten Stationen zu.
 *
 * 1. Zellen mit Cell-ID (in der Regel die verbundene): gleiche Zellkennung.
 * 2. Nachbarzellen kommen meist ohne Cell-ID, nur mit Träger (ARFCN) und PCI. Sie werden einer Station
 *    zugeordnet, die
 *    - als *zuverlässig* oder *Kandidat* bewertet ist,
 *    - eine Zelle mit gleicher Technik, gleichem Band, gleichem ARFCN und gleicher PCI hat und
 *    - höchstens [NEIGHBOUR_MATCH_MAX_M] von [here] (eigene Position, sonst verbundene Station) entfernt ist.
 *    Bei mehreren Treffern (PCI-Wiederholung) gewinnt die nächste; Stationen, die diese Zelle mit
 *    Cell-ID kennen, gehen vor. Ohne jeden Bezugspunkt gewinnt die zuletzt gesehene.
 *    Mögliche Artefakte werden nie als Nachbar markiert.
 */
fun matchLiveCells(located: List<BaseStation>, live: List<CellRecord>, here: Pair<Double, Double>? = null): LiveMatches {
    if (live.isEmpty()) return LiveMatches.EMPTY
    val servingKeys = live.filter { it.registered }.map { it.key }.toSet()
    val neighbourKeys = live.filter { !it.registered }.map { it.key }.toSet() - servingKeys

    val byId = HashMap<String, BaseStation>()
    val byRadio = HashMap<String, MutableList<Pair<BaseStation, CellEntity>>>()
    for (s in located) {
        val rated = s.assessment.reliability != Reliability.ARTEFACT
        for (c in s.allCellEntities) {
            if (c.cellId != null) byId.putIfAbsent(c.id, s)
            if (rated && c.pci != null && c.arfcn != null) {
                byRadio.getOrPut("${c.rat}|${c.arfcn}|${c.pci}") { mutableListOf() } += s to c
            }
        }
    }

    val stationOf = HashMap<CellRecord, BaseStation>()
    for (r in live) if (r.cellId != null) byId[r.key]?.let { stationOf[r] = it }
    val reference = here ?: live.filter { it.registered }.firstNotNullOfOrNull { stationOf[it] }?.estimate
        ?.let { it.latitude to it.longitude }

    for (r in live) {
        if (r in stationOf || r.pci == null || r.arfcn == null) continue
        val band = r.band?.label
        val candidates = byRadio["${r.rat}|${r.arfcn}|${r.pci}"].orEmpty().filter { (s, c) ->
            // Band und Netz nur vergleichen, wenn beide Seiten sie kennen.
            (band == null || c.band == null || band == c.band) &&
                (r.mcc == null || r.mnc == null || s.mcc == null || s.mnc == null || (r.mcc == s.mcc && r.mnc == s.mnc))
        }
        if (candidates.isEmpty()) continue
        fun pick(list: List<BaseStation>): BaseStation? = if (reference != null) {
            list.mapNotNull { st -> st.estimate?.let { e -> st to distanceM(reference.first, reference.second, e.latitude, e.longitude) } }
                .filter { it.second <= NEIGHBOUR_MATCH_MAX_M }
                .minByOrNull { it.second }?.first
        } else {
            list.maxByOrNull { it.lastSeen }
        }
        val identified = candidates.filter { (_, c) -> c.cellId != null }.map { it.first }.distinctBy { it.key }
        val others = candidates.filter { (_, c) -> c.cellId == null }.map { it.first }.distinctBy { it.key }
        val choice = pick(identified) ?: pick(others)
        if (choice != null) stationOf[r] = choice
    }

    val result = HashMap<String, LiveMatch>()
    for ((s, records) in stationOf.entries.groupBy({ it.value }, { it.key })) {
        val serving = records.any { it.registered }
        val best = records.filter { it.registered == serving }.maxByOrNull { it.dbm ?: Int.MIN_VALUE } ?: continue
        result[s.key] = LiveMatch(serving, best, records.filter { !it.registered && it.key !in servingKeys }.distinctBy { it.key })
    }
    return LiveMatches(result, servingKeys.size, neighbourKeys.size)
}
