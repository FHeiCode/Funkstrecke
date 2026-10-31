package de.funkstrecke.data

import de.funkstrecke.radio.Operators

/** Eine Basisstation aus Sicht der App: ein eNodeB mit seinen Sektoren, sonst eine einzelne Zelle. */
data class BaseStation(
    val key: String,
    val rat: String,
    val mcc: String?,
    val mnc: String?,
    val plmnInferred: Boolean,
    val nodeId: Long?,
    val cells: List<CellEntity>,
    val estimate: EstimateEntity?,
) {
    val operatorName: String? get() = Operators.name(mcc, mnc)
    val plmn: String? get() = if (mcc != null && mnc != null) "$mcc-$mnc" else null
    val identified: Boolean get() = cells.any { it.cellId != null }
    val lastSeen: Long get() = cells.maxOf { it.lastSeen }
    val firstSeen: Long get() = cells.minOf { it.firstSeen }
    val bestDbm: Int? get() = cells.mapNotNull { it.bestDbm }.maxOrNull()
    val samples: Int get() = cells.sumOf { it.samples }
    val area: Int? get() = cells.firstNotNullOfOrNull { it.area }
    val bands: List<String> get() = cells.mapNotNull { it.band }.distinct()
    val minDistanceM: Int? get() = cells.mapNotNull { it.minDistanceM }.minOrNull()
    val wasServing: Boolean get() = cells.any { it.servingSamples > 0 }

    val title: String
        get() {
            val first = cells.first()
            return when {
                nodeId != null -> "eNB $nodeId"
                first.cellId != null && rat == "5G" -> "NCI ${first.cellId}"
                first.cellId != null -> "CID ${first.cellId}"
                else -> "PCI ${first.pci ?: "?"} · ARFCN ${first.arfcn ?: "?"}"
            }
        }
}

fun buildStations(cells: List<CellEntity>, estimates: List<EstimateEntity>): List<BaseStation> {
    val byGroup = estimates.associateBy { it.groupKey }
    return cells.groupBy { it.groupKey }
        .map { (key, list) ->
            val first = list.first()
            BaseStation(
                key = key,
                rat = first.rat,
                mcc = first.mcc,
                mnc = first.mnc,
                plmnInferred = list.all { it.plmnInferred },
                nodeId = if (first.rat == "4G") first.nodeId else null,
                cells = list.sortedWith(compareBy<CellEntity> { it.sector ?: Int.MAX_VALUE }.thenBy { it.arfcn ?: 0 }),
                estimate = byGroup[key],
            )
        }
        .sortedByDescending { it.lastSeen }
}
