package de.funkstrecke.data

import de.funkstrecke.radio.CellRecord

/** Station im aktuellen Scan: verbunden oder als Nachbarzelle gemessen, mit der stärksten passenden Zelle. */
data class LiveMatch(val serving: Boolean, val cell: CellRecord)

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

/** Höchstabstand zur verbundenen Station, wenn eine Nachbarzelle nur über ARFCN/PCI zugeordnet wird. */
private const val RADIO_MATCH_MAX_M = 30_000.0

/**
 * Ordnet die Zellen eines Live-Scans den verorteten Stationen zu.
 *
 * 1. Gleiche Zellkennung (CellRecord.key = CellEntity.id).
 * 2. Sonst gleiche Technik, gleicher Träger (ARFCN) und gleiche PCI. Nötig, weil Nachbarzellen meist
 *    ohne Cell-ID gemeldet werden und unter einer eigenen Kennung („nb|ARFCN|PCI“) gespeichert sind.
 *    Diese Einträge haben oft keine eigene Schätzung, dieselbe Zelle ist aber als verbundene Zelle
 *    (mit ECI) einer verorteten Station bekannt. Gibt es mehrere Stationen mit diesem Träger und
 *    dieser PCI (PCI-Wiederholung), gewinnt die nächste zur verbundenen Station, sonst die zuletzt gesehene.
 */
fun matchLiveCells(located: List<BaseStation>, live: List<CellRecord>): LiveMatches {
    if (live.isEmpty()) return LiveMatches.EMPTY
    val servingKeys = live.filter { it.registered }.map { it.key }.toSet()
    val neighbourKeys = live.filter { !it.registered }.map { it.key }.toSet() - servingKeys

    val byId = HashMap<String, BaseStation>()
    val byRadio = HashMap<String, MutableList<Pair<BaseStation, CellEntity>>>()
    for (s in located) {
        for (c in s.allCellEntities) {
            byId.putIfAbsent(c.id, s)
            if (c.pci != null && c.arfcn != null) {
                byRadio.getOrPut("${c.rat}|${c.arfcn}|${c.pci}") { mutableListOf() } += s to c
            }
        }
    }

    // Zuerst die exakten Treffer; die verbundene Station dient dann als Bezugspunkt.
    val stationOf = HashMap<CellRecord, BaseStation>()
    for (r in live) byId[r.key]?.let { stationOf[r] = it }
    val anchor = live.filter { it.registered }.firstNotNullOfOrNull { stationOf[it] }?.estimate

    for (r in live) {
        if (r in stationOf || r.pci == null || r.arfcn == null) continue
        val candidates = byRadio["${r.rat}|${r.arfcn}|${r.pci}"].orEmpty().filter { (s, _) ->
            // Netz nur vergleichen, wenn beide Seiten es kennen.
            r.mcc == null || r.mnc == null || s.mcc == null || s.mnc == null || (r.mcc == s.mcc && r.mnc == s.mnc)
        }.map { it.first }.distinctBy { it.key }
        if (candidates.isEmpty()) continue
        val choice = if (anchor != null) {
            candidates
                .mapNotNull { st -> st.estimate?.let { e -> st to distanceM(anchor.latitude, anchor.longitude, e.latitude, e.longitude) } }
                .filter { it.second <= RADIO_MATCH_MAX_M }
                .minByOrNull { it.second }?.first
        } else {
            candidates.maxByOrNull { it.lastSeen }
        }
        if (choice != null) stationOf[r] = choice
    }

    val result = HashMap<String, LiveMatch>()
    for ((s, records) in stationOf.entries.groupBy({ it.value }, { it.key })) {
        val serving = records.any { it.registered }
        val best = records.filter { it.registered == serving }.maxByOrNull { it.dbm ?: Int.MIN_VALUE } ?: continue
        result[s.key] = LiveMatch(serving, best)
    }
    return LiveMatches(result, servingKeys.size, neighbourKeys.size)
}
