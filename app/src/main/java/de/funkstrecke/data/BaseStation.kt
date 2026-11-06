package de.funkstrecke.data

import de.funkstrecke.radio.Operators
import de.funkstrecke.util.formatDistance
import de.funkstrecke.util.tr
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/*
 * PCI-Struktur (LTE und NR): PCI = 3 · N_ID1 + N_ID2.
 *   N_ID2 (0–2) kommt aus der primären Synchronisationsfolge (PSS),
 *   N_ID1 aus der sekundären (SSS): LTE 0–167, NR 0–335.
 * Übliche Netzplanung: alle Sektoren eines Standorts teilen dieselbe SSS-Gruppe N_ID1 und
 * unterscheiden sich in N_ID2. Andere Träger desselben Sektors haben oft sogar dieselbe PCI.
 * Das ist eine Planungskonvention, keine Norm, und PCIs werden in einigen Kilometern Abstand
 * wiederverwendet. Zuordnungen über die PCI-Gruppe sind deshalb immer Vermutungen und werden
 * zusätzlich über die Messorte geprüft.
 */
fun pciGroup(pci: Int): Int = pci / 3
fun pciPss(pci: Int): Int = pci % 3
fun supportsPciGroups(rat: String): Boolean = rat == "4G" || rat == "5G"

/** Mittelpunkt der Messorte einer Zelle. */
data class CellCentroid(
    val cellKey: String,
    val latitude: Double,
    val longitude: Double,
    val n: Int,
)

enum class LinkConfidence {
    LIKELY,
    POSSIBLE,
    ;

    val label: String get() = if (this == LIKELY) tr("wahrscheinlich", "likely") else tr("möglich", "possible")
}

/** Zelle ohne Cell-ID, die über ihre PCI-Gruppe einer bekannten Station zugeordnet wurde. */
data class LinkedCell(
    val cell: CellEntity,
    val confidence: LinkConfidence,
    /** Abstand zwischen den Messorten der Zelle und der Station, falls bekannt */
    val distanceM: Double?,
    /** Bekannte Zelle mit derselben PCI: gleicher Sektor, anderer Träger */
    val sameSectorAs: CellEntity?,
)

/**
 * Zwei verschiedene Zellen mit derselben PCI auf demselben Träger (also im gleichen Band) in der Nähe.
 * Handys können sie dann verwechseln (PCI-Konfusion), Übergaben schlagen fehl.
 * Gleiche PCIs auf anderen Bändern oder anderen Trägern sind unkritisch und werden nicht gemeldet.
 */
data class PciConflict(
    val cell: CellEntity,
    val other: CellEntity,
    val otherStationKey: String,
    val otherTitle: String,
    val distanceM: Double,
)

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
    /** Zellen ohne Cell-ID, die vermutlich zu dieser Station gehören */
    val linked: List<LinkedCell> = emptyList(),
    /** Nur aus PCI-Gruppe und Messorten zusammengestellt, ohne Cell-ID */
    val inferred: Boolean = false,
    /** NR-Zellen, die als 5G-NSA-Zweig (EN-DC) mit dieser LTE-Station gemessen wurden */
    val nrLegs: List<CellEntity> = emptyList(),
    /** Gleiche PCI auf gleichem Träger bei einer nahen anderen Station */
    val pciConflicts: List<PciConflict> = emptyList(),
    /** OpenCelliD-Ergebnisse der Zellen dieser Station */
    val ocid: List<OcidEntity> = emptyList(),
    /** Nächster OSM-Funkmast (höchstens 1 km von der Schätzung) */
    val osmMast: OsmMastEntity? = null,
    val osmMastDistanceM: Double? = null,
    /** OSM-Masten rund um die Schätzung wurden schon geladen */
    val osmChecked: Boolean = false,
) {
    /** Bewertung: zuverlässig, Kandidat oder mögliches Artefakt (beste Zelle zählt) */
    val assessment: Assessment get() = assessStation(this)

    /**
     * Grund, warum ein OpenCelliD-Treffer unplausibel ist, sonst null: weiter entfernt als die von
     * OpenCelliD angegebene Reichweite (mindestens [OCID_IMPLAUSIBLE_MIN_M]) oder auf weniger als
     * [OCID_FEW_SAMPLES] Messungen beruhend und über [OCID_FEW_SAMPLES_MAX_M] entfernt.
     */
    fun ocidImplausibleReason(o: OcidEntity): String? {
        val e = estimate ?: return null
        if (!o.found || o.latitude == null || o.longitude == null) return null
        val d = distanceM(e.latitude, e.longitude, o.latitude, o.longitude)
        val limit = maxOf(OCID_IMPLAUSIBLE_MIN_M, (o.rangeM ?: 0).toDouble())
        val samples = o.samples
        return when {
            d > limit -> tr(
                "${formatDistance(d)} entfernt, mehr als die Reichweite laut OpenCelliD",
                "${formatDistance(d)} away, more than the range given by OpenCelliD",
            )
            samples != null && samples < OCID_FEW_SAMPLES && d > OCID_FEW_SAMPLES_MAX_M -> tr(
                "${formatDistance(d)} entfernt, nur $samples ${if (samples == 1) "Messung" else "Messungen"}",
                "${formatDistance(d)} away, only $samples ${if (samples == 1) "measurement" else "measurements"}",
            )
            else -> null
        }
    }

    /** In OpenCelliD gefundene Zellen mit Position, ohne unplausible Treffer */
    val ocidPlausible: List<OcidEntity>
        get() = ocid.filter { it.found && it.latitude != null && it.longitude != null && ocidImplausibleReason(it) == null }

    /** Gefundene, aber unplausible Treffer */
    val ocidImplausible: List<OcidEntity>
        get() = ocid.filter { it.found && it.latitude != null && it.longitude != null && ocidImplausibleReason(it) != null }

    /** Mittel der OpenCelliD-Positionen aller plausiblen Treffer */
    val ocidPosition: Pair<Double, Double>?
        get() {
            val f = ocidPlausible
            if (f.isEmpty()) return null
            return f.map { it.latitude!! }.average() to f.map { it.longitude!! }.average()
        }

    /** Alle abgeglichenen Zellen fehlen in OpenCelliD */
    val ocidUnknown: Boolean get() = ocid.isNotEmpty() && ocid.none { it.found } && ocid.all { it.status == OcidEntity.STATUS_NOT_FOUND }

    /** Abstand zwischen eigener Schätzung und OpenCelliD-Position */
    val ocidDistanceM: Double?
        get() {
            val e = estimate ?: return null
            val (lat, lon) = ocidPosition ?: return null
            return distanceM(e.latitude, e.longitude, lat, lon)
        }

    /** EN-DC war über diese Station verbunden */
    val endcConnected: Boolean get() = cells.any { it.endcConnectedSamples > 0 }
    /** EN-DC war über diese Station verfügbar (angekündigt oder verbunden) */
    val endcAvailable: Boolean get() = cells.any { it.endcAvailableSamples > 0 }
    val nrBands: List<String> get() = nrLegs.mapNotNull { it.band }.distinct()

    val operatorName: String? get() = Operators.name(mcc, mnc)
    val plmn: String? get() = if (mcc != null && mnc != null) "$mcc-$mnc" else null
    val identified: Boolean get() = cells.any { it.cellId != null }
    private val allCells: List<CellEntity> get() = cells + linked.map { it.cell }
    val lastSeen: Long get() = allCells.maxOf { it.lastSeen }
    val firstSeen: Long get() = allCells.minOf { it.firstSeen }
    val bestDbm: Int? get() = allCells.mapNotNull { it.bestDbm }.maxOrNull()
    val samples: Int get() = cells.sumOf { it.samples }
    val area: Int? get() = cells.firstNotNullOfOrNull { it.area }
    val bands: List<String> get() = allCells.mapNotNull { it.band }.distinct()
    val minDistanceM: Int? get() = cells.filter { it.servingSamples > 0 }.mapNotNull { it.minDistanceM }.filter { it <= 50_000 }.minOrNull()
    val wasServing: Boolean get() = cells.any { it.servingSamples > 0 }

    /** SSS-Gruppen (N_ID1) der Zellen dieser Station */
    val pciGroups: List<Int>
        get() = if (supportsPciGroups(rat)) allCells.mapNotNull { it.pci }.map(::pciGroup).distinct().sorted() else emptyList()

    /** Gruppenschlüssel aller Messungen, die zu dieser Station gezeigt werden */
    val measurementKeys: List<String> get() = allCells.map { it.groupKey }.distinct()

    val title: String
        get() {
            val first = cells.first()
            return when {
                inferred -> tr("PCI-Gruppe ", "PCI group ") + pciGroups.joinToString("/")
                nodeId != null -> "eNB $nodeId"
                first.cellId != null && rat == "5G" -> "NCI ${first.cellId}"
                first.cellId != null -> "CID ${first.cellId}"
                else -> "PCI ${first.pci ?: "?"} · ARFCN ${first.arfcn ?: "?"}"
            }
        }
}

private const val LINK_MAX_DISTANCE_M = 3_000.0
private const val LINK_LIKELY_DISTANCE_M = 1_500.0
private const val CLUSTER_DISTANCE_M = 1_500.0
/** Bis zu diesem Abstand gilt gleiche PCI auf gleichem Träger als Konflikt */
private const val PCI_CONFLICT_DISTANCE_M = 5_000.0

internal fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val kx = 111_320.0 * cos((aLat + bLat) / 2 * PI / 180.0)
    return hypot((aLon - bLon) * kx, (aLat - bLat) * 110_540.0)
}

private fun makeStation(key: String, list: List<CellEntity>, estimate: EstimateEntity?): BaseStation {
    val first = list.first()
    return BaseStation(
        key = key,
        rat = first.rat,
        mcc = first.mcc,
        mnc = first.mnc,
        plmnInferred = list.all { it.plmnInferred },
        nodeId = if (first.rat == "4G") first.nodeId else null,
        cells = list.sortedWith(compareBy<CellEntity> { it.sector ?: Int.MAX_VALUE }.thenBy { it.pci ?: 0 }.thenBy { it.arfcn ?: 0 }),
        estimate = estimate,
    )
}

fun buildStations(
    cells: List<CellEntity>,
    estimates: List<EstimateEntity>,
    centroids: List<CellCentroid> = emptyList(),
    ocid: List<OcidEntity> = emptyList(),
    osm: OsmIndex = OsmIndex.EMPTY,
): List<BaseStation> {
    val ocidOf = ocid.associateBy { it.cellKey }
    return buildStationsCore(cells, estimates, centroids).map { s ->
        val results = s.cells.mapNotNull { ocidOf[it.id] }
        var out = if (results.isEmpty()) s else s.copy(ocid = results)
        val e = s.estimate
        if (e != null) {
            val near = osm.nearest(e.latitude, e.longitude)
            out = out.copy(osmMast = near?.first, osmMastDistanceM = near?.second, osmChecked = osm.covered(e.latitude, e.longitude))
        }
        out
    }
}

private fun buildStationsCore(
    cells: List<CellEntity>,
    estimates: List<EstimateEntity>,
    centroids: List<CellCentroid>,
): List<BaseStation> {
    val estimateOf = estimates.associateBy { it.groupKey }
    val centroidOf = centroids.associateBy { it.cellKey }

    // 0) NR-Zellen ohne NCI, die als EN-DC-Zweig einer bekannten LTE-Station gemessen wurden,
    //    gehören zu dieser Station und erscheinen nicht als eigene Einträge.
    val identifiedGroups = cells.filter { it.cellId != null }.map { it.groupKey }.toSet()
    val nrLegsOf = cells
        .filter { it.rat == "5G" && it.cellId == null && it.nrAnchorKey != null && it.nrAnchorKey in identifiedGroups }
        .groupBy { it.nrAnchorKey!! }
    val legIds = nrLegsOf.values.flatten().map { it.id }.toSet()

    // 1) Stationen mit Cell-ID (bei 4G gruppiert nach eNodeB) sind die Anker.
    //    Zellen ohne Cell-ID (Nachbarzellen mit nur PCI/ARFCN) sind „lose“.
    val anchors = mutableListOf<BaseStation>()
    val loose = mutableListOf<CellEntity>()
    for ((key, list) in cells.filter { it.id !in legIds }.groupBy { it.groupKey }) {
        if (list.any { it.cellId != null }) {
            anchors += makeStation(key, list, estimateOf[key]).copy(
                nrLegs = nrLegsOf[key].orEmpty().sortedWith(compareBy<CellEntity> { it.arfcn ?: 0 }.thenBy { it.pci ?: 0 }),
            )
        } else {
            loose += list
        }
    }

    fun positionOf(s: BaseStation): Pair<Double, Double>? {
        s.estimate?.let { return it.latitude to it.longitude }
        val points = s.cells.mapNotNull { centroidOf[it.id] }
        if (points.isEmpty()) return null
        return points.map { it.latitude }.average() to points.map { it.longitude }.average()
    }
    val anchorPosition = anchors.associate { it.key to positionOf(it) }

    // 2) Lose Zellen über die PCI-Gruppe einem Anker zuordnen, geprüft über die Messorte.
    val linkedTo = mutableMapOf<String, MutableList<LinkedCell>>()
    val unlinked = mutableListOf<CellEntity>()
    for (c in loose) {
        val pci = c.pci
        if (pci == null || !supportsPciGroups(c.rat)) {
            unlinked += c
            continue
        }
        val group = pciGroup(pci)
        val candidates = anchors.filter { s ->
            s.rat == c.rat && s.mcc == c.mcc && s.mnc == c.mnc && s.cells.any { it.pci != null && pciGroup(it.pci) == group }
        }
        if (candidates.isEmpty()) {
            unlinked += c
            continue
        }
        val own = centroidOf[c.id]
        val scored = candidates.map { s ->
            val ap = anchorPosition[s.key]
            s to if (own != null && ap != null) distanceM(own.latitude, own.longitude, ap.first, ap.second) else null
        }
        val choice: Pair<BaseStation, Double?>?
        val confidence: LinkConfidence
        val near = scored.filter { it.second != null && it.second!! <= LINK_MAX_DISTANCE_M }.sortedBy { it.second }
        when {
            near.size == 1 -> {
                choice = near[0]
                confidence = if (near[0].second!! <= LINK_LIKELY_DISTANCE_M) LinkConfidence.LIKELY else LinkConfidence.POSSIBLE
            }
            // Mehrere Kandidaten in Reichweite: nur, wenn einer klar näher liegt.
            near.size > 1 && near[1].second!! > 2 * near[0].second!! + 500 -> {
                choice = near[0]
                confidence = LinkConfidence.POSSIBLE
            }
            // Ohne Messorte: nur bei genau einem Kandidaten mit dieser Gruppe.
            near.isEmpty() && candidates.size == 1 && scored[0].second == null -> {
                choice = scored[0]
                confidence = LinkConfidence.POSSIBLE
            }
            else -> {
                choice = null
                confidence = LinkConfidence.POSSIBLE
            }
        }
        if (choice == null) {
            unlinked += c
            continue
        }
        val station = choice.first
        linkedTo.getOrPut(station.key) { mutableListOf() } += LinkedCell(
            cell = c,
            confidence = confidence,
            distanceM = choice.second,
            sameSectorAs = station.cells.firstOrNull { it.pci == pci && it.cellId != null },
        )
    }

    // PCI-Konflikte: gleiche Technik, gleiches Netz, gleicher Träger (ARFCN, damit auch gleiches Band),
    // gleiche PCI, aber verschiedene Stationen, deren Standorte höchstens PCI_CONFLICT_DISTANCE_M auseinanderliegen.
    val stationOf = anchors.associateBy { it.key }
    val conflicts = mutableMapOf<String, MutableList<PciConflict>>()
    anchors.flatMap { s -> s.cells.filter { it.cellId != null && it.pci != null && it.arfcn != null }.map { s to it } }
        .groupBy { (_, c) -> listOf(c.rat, c.mcc, c.mnc, c.arfcn, c.pci) }
        .values
        .filter { list -> list.map { it.first.key }.distinct().size > 1 }
        .forEach { list ->
            for ((s, c) in list) {
                for ((o, oc) in list) {
                    if (o.key == s.key) continue
                    val a = anchorPosition[s.key] ?: continue
                    val b = anchorPosition[o.key] ?: continue
                    val d = distanceM(a.first, a.second, b.first, b.second)
                    if (d <= PCI_CONFLICT_DISTANCE_M) {
                        conflicts.getOrPut(s.key) { mutableListOf() } += PciConflict(c, oc, o.key, stationOf.getValue(o.key).title, d)
                    }
                }
            }
        }

    val result = anchors.map { s ->
        val links = linkedTo[s.key]
        val withLinks = if (links == null) s else s.copy(linked = links.sortedWith(compareBy<LinkedCell> { it.cell.pci ?: 0 }.thenBy { it.cell.arfcn ?: 0 }))
        conflicts[s.key]?.let { withLinks.copy(pciConflicts = it.sortedBy { c -> c.distanceM }) } ?: withLinks
    }.toMutableList()

    // 3) Übrige lose Zellen mit gleicher PCI-Gruppe und nahen Messorten zu vermuteten Stationen bündeln.
    val buckets = unlinked.groupBy { c ->
        if (c.pci != null && supportsPciGroups(c.rat)) "${c.rat}|${c.mcc}-${c.mnc}|${pciGroup(c.pci)}" else "single|${c.id}"
    }
    for ((bucketKey, members) in buckets) {
        if (bucketKey.startsWith("single|") || members.size == 1) {
            members.forEach { c -> result += makeStation(c.groupKey, listOf(c), estimateOf[c.groupKey]) }
            continue
        }
        val clusters = mutableListOf<MutableList<CellEntity>>()
        val noPosition = mutableListOf<CellEntity>()
        for (c in members.sortedByDescending { it.bestDbm ?: -200 }) {
            val p = centroidOf[c.id]
            if (p == null) {
                noPosition += c
                continue
            }
            val target = clusters.firstOrNull { cl ->
                cl.any { o -> centroidOf[o.id]?.let { distanceM(p.latitude, p.longitude, it.latitude, it.longitude) <= CLUSTER_DISTANCE_M } == true }
            }
            if (target != null) target += c else clusters += mutableListOf(c)
        }
        // Zellen ohne Messort nur bündeln, wenn es eindeutig ist.
        when {
            clusters.isEmpty() -> clusters += noPosition
            clusters.size == 1 -> clusters[0] += noPosition
            else -> noPosition.forEach { clusters += mutableListOf(it) }
        }
        for (cl in clusters) {
            if (cl.size == 1) {
                val c = cl[0]
                result += makeStation(c.groupKey, listOf(c), estimateOf[c.groupKey])
            } else {
                val bestEstimate = cl.mapNotNull { estimateOf[it.groupKey] }.minByOrNull { it.radiusM }
                result += makeStation("guess|$bucketKey|${cl.first().id}", cl, bestEstimate).copy(inferred = true)
            }
        }
    }
    return result.sortedByDescending { it.lastSeen }
}
