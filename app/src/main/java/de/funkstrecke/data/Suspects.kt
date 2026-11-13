package de.funkstrecke.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlin.math.cos

/**
 * Als verdächtig markierte Station. Gespeichert wird je Messgruppe (bei einer vermuteten Station mehrere),
 * zusammen mit der Position der Schätzung beim Markieren.
 */
@Entity(tableName = "suspects")
data class SuspectEntity(
    @PrimaryKey val groupKey: String,
    /** Schlüssel der Station in der Liste (für die Anzeige) */
    val stationKey: String,
    val markedAt: Long,
    val latitude: Double,
    val longitude: Double,
)

@Dao
interface SuspectDao {
    @Query("SELECT * FROM suspects")
    fun observe(): Flow<List<SuspectEntity>>

    @Query("SELECT * FROM suspects")
    suspend fun all(): List<SuspectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<SuspectEntity>)

    @Query("DELETE FROM suspects WHERE groupKey IN (:groupKeys)")
    suspend fun delete(groupKeys: List<String>)

    /** Scans (verschiedene Zeitpunkte) seit [since] in einem Rechteck um die Station */
    @Query(
        "SELECT COUNT(DISTINCT time) FROM measurements WHERE time > :since " +
            "AND latitude BETWEEN :south AND :north AND longitude BETWEEN :west AND :east",
    )
    suspend fun scansNear(since: Long, south: Double, north: Double, west: Double, east: Double): Int

    @Query("DELETE FROM measurements WHERE groupKey = :groupKey")
    suspend fun deleteMeasurements(groupKey: String)

    @Query("DELETE FROM opencellid WHERE cellKey IN (SELECT id FROM cells WHERE groupKey = :groupKey)")
    suspend fun deleteOcid(groupKey: String)

    @Query("DELETE FROM estimates WHERE groupKey = :groupKey")
    suspend fun deleteEstimate(groupKey: String)

    @Query("DELETE FROM cells WHERE groupKey = :groupKey")
    suspend fun deleteCells(groupKey: String)
}

/**
 * Verdächtige Stationen: Wer eine Station für ein Artefakt hält, markiert sie. Zeichnest du danach wieder in
 * ihrer Nähe auf ([NEAR_M], mindestens [NEAR_MIN_SCANS] Scans) und wird sie dabei seltener als die eingestellte
 * Mindestzahl neu gemessen, wird sie mit allen Zellen und Messungen gelöscht. Wird sie oft genug gemessen,
 * ist sie bestätigt, und die Markierung verschwindet.
 */
object Suspects {
    /** Umkreis um die Schätzung, in dem eine Aufzeichnung „in der Nähe“ zählt */
    const val NEAR_M = 1_000.0
    /** So viele Scans in der Nähe braucht es, bevor entschieden wird (bei 10 s Takt gut eineinhalb Minuten) */
    const val NEAR_MIN_SCANS = 10
    private const val CHECK_INTERVAL_MS = 60_000L

    @Volatile
    private var lastCheck = 0L

    /** Stand einer markierten Station */
    data class Status(val markedAt: Long, val newMeasurements: Int, val scansNear: Int)

    suspend fun mark(db: AppDatabase, station: BaseStation, now: Long = System.currentTimeMillis()) {
        // Ohne Schätzung lässt sich „in der Nähe“ nicht bestimmen.
        val e = station.estimate ?: return
        val lat = e.latitude
        val lon = e.longitude
        val rows = station.cells.map { it.groupKey }.distinct().map { SuspectEntity(it, station.key, now, lat, lon) }
        db.suspectDao().insert(rows)
    }

    suspend fun unmark(db: AppDatabase, station: BaseStation) {
        db.suspectDao().delete(station.cells.map { it.groupKey }.distinct())
    }

    /** Neue Messungen der Gruppe seit dem Markieren, einschließlich passender Nachbarmessungen */
    private suspend fun newMeasurements(db: AppDatabase, groupKey: String, since: Long): Int =
        Recorder.measurementsForEstimate(db, groupKey).measurements.count { it.time > since }

    private suspend fun scansNear(db: AppDatabase, s: SuspectEntity): Int {
        val dLat = NEAR_M / 110_540.0
        val dLon = NEAR_M / (111_320.0 * cos(Math.toRadians(s.latitude)))
        return db.suspectDao().scansNear(s.markedAt, s.latitude - dLat, s.latitude + dLat, s.longitude - dLon, s.longitude + dLon)
    }

    suspend fun status(db: AppDatabase, groupKeys: List<String>): Status? {
        val rows = db.suspectDao().all().filter { it.groupKey in groupKeys }
        if (rows.isEmpty()) return null
        val first = rows.minBy { it.markedAt }
        return Status(first.markedAt, rows.sumOf { newMeasurements(db, it.groupKey, it.markedAt) }, scansNear(db, first))
    }

    /**
     * Entscheidet über alle markierten Stationen. Höchstens einmal pro Minute (wird nach jedem Aufzeichnen
     * aufgerufen). Gibt die Zahl der gelöschten Stationen zurück.
     */
    suspend fun check(db: AppDatabase, minMeasurements: Int, now: Long = System.currentTimeMillis(), force: Boolean = false): Int {
        if (!force && now - lastCheck < CHECK_INTERVAL_MS) return 0
        lastCheck = now
        val dao = db.suspectDao()
        val rows = dao.all()
        if (rows.isEmpty()) return 0
        var removed = 0
        for ((_, group) in rows.groupBy { it.stationKey }) {
            val first = group.minBy { it.markedAt }
            val measured = group.sumOf { newMeasurements(db, it.groupKey, it.markedAt) }
            when {
                // Oft genug wieder gemessen: Die Station gibt es wirklich.
                measured >= minMeasurements -> dao.delete(group.map { it.groupKey })
                // In der Nähe aufgezeichnet, aber nicht (oft genug) gehört: löschen.
                scansNear(db, first) >= NEAR_MIN_SCANS -> {
                    db.withTransaction {
                        for (s in group) {
                            dao.deleteMeasurements(s.groupKey)
                            dao.deleteOcid(s.groupKey)
                            dao.deleteEstimate(s.groupKey)
                            dao.deleteCells(s.groupKey)
                        }
                        dao.delete(group.map { it.groupKey })
                    }
                    removed++
                }
            }
        }
        return removed
    }
}
