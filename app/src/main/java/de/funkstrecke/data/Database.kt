package de.funkstrecke.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Eine erkannte Funkzelle mit zusammengefassten Messwerten. */
@Entity(tableName = "cells")
data class CellEntity(
    /** Zellkennung, siehe CellRecord.key */
    @PrimaryKey val id: String,
    /** Basisstation, zu der die Zelle gehört (4G: eNodeB, sonst die Zelle selbst) */
    val groupKey: String,
    val rat: String,
    val mcc: String?,
    val mnc: String?,
    val plmnInferred: Boolean,
    val area: Int?,
    val cellId: Long?,
    val nodeId: Long?,
    val sector: Int?,
    val pci: Int?,
    val arfcn: Int?,
    val band: String?,
    val bandMhz: String?,
    val freqMhz: Double?,
    val firstSeen: Long,
    val lastSeen: Long,
    val samples: Int,
    val servingSamples: Int,
    val bestDbm: Int?,
    val lastDbm: Int?,
    val lastQuality: Int?,
    val lastSinr: Int?,
    val minDistanceM: Int?,
    /** LTE-Ankerzelle: Scans, in denen EN-DC (5G NSA) verbunden war */
    @ColumnInfo(defaultValue = "0") val endcConnectedSamples: Int = 0,
    /** LTE-Ankerzelle: Scans, in denen EN-DC verfügbar oder verbunden war */
    @ColumnInfo(defaultValue = "0") val endcAvailableSamples: Int = 0,
    /** NR-Zelle: Station der LTE-Ankerzelle, mit der sie zuletzt als NR-Zweig gemessen wurde */
    val nrAnchorKey: String? = null,
)

/** Eine einzelne Messung mit GPS-Position, Grundlage der Standortschätzung. */
@Entity(
    tableName = "measurements",
    indices = [Index("groupKey"), Index("cellKey")],
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cellKey: String,
    val groupKey: String,
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val dbm: Int?,
    val quality: Int?,
    val sinr: Int?,
    val timingAdvance: Int?,
    val serving: Boolean,
    /** EN-DC-Zustand beim Scan (Name von EndcMode), null = unbekannt */
    val endc: String? = null,
)

/** Aktuelle Standortschätzung einer Basisstation. */
@Entity(tableName = "estimates")
data class EstimateEntity(
    @PrimaryKey val groupKey: String,
    val latitude: Double,
    val longitude: Double,
    /** Radius, in dem der Mast mit etwa 95 % Wahrscheinlichkeit steht */
    val radiusM: Double,
    val method: String,
    /** Anzahl der Messorte (nach Zusammenfassen auf ein 20-m-Raster) */
    val spots: Int,
    val measurements: Int,
    val updatedAt: Long,
)

@Dao
interface CellDao {
    @Query("SELECT * FROM cells ORDER BY lastSeen DESC")
    fun observeCells(): Flow<List<CellEntity>>

    @Query("SELECT * FROM cells WHERE id = :id")
    suspend fun getCell(id: String): CellEntity?

    @Query("SELECT * FROM cells")
    suspend fun allCells(): List<CellEntity>

    @Upsert
    suspend fun upsertCell(cell: CellEntity)

    @Insert
    suspend fun insertMeasurement(measurement: MeasurementEntity)

    @Query("SELECT COUNT(*) FROM measurements")
    fun observeMeasurementCount(): Flow<Int>

    @Query("SELECT * FROM measurements ORDER BY time")
    suspend fun allMeasurements(): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE groupKey = :groupKey ORDER BY time DESC LIMIT :limit")
    suspend fun measurementsForGroup(groupKey: String, limit: Int): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE groupKey = :groupKey ORDER BY time DESC LIMIT :limit")
    fun observeMeasurementsForGroup(groupKey: String, limit: Int): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM estimates")
    fun observeEstimates(): Flow<List<EstimateEntity>>

    /** Mittelpunkt der Messorte je Zelle, für die Zuordnung über die PCI-Gruppe */
    @Query(
        "SELECT cellKey, AVG(latitude) AS latitude, AVG(longitude) AS longitude, COUNT(*) AS n " +
            "FROM measurements WHERE accuracyM IS NULL OR accuracyM <= 100 GROUP BY cellKey",
    )
    fun observeCentroids(): Flow<List<CellCentroid>>

    /**
     * Abdeckung: genaue Messpunkte je ~50-m-Feld, Betreiber und Technik zusammengefasst.
     * Rastergröße wie COVERAGE_LAT_STEP / COVERAGE_LON_STEP (Room erlaubt hier keine Konstanten-Ausdrücke).
     */
    @Query(
        "SELECT c.mcc AS mcc, c.mnc AS mnc, c.rat AS rat, " +
            "CAST(m.latitude / 0.00045 AS INTEGER) AS latIdx, " +
            "CAST(m.longitude / 0.0007 AS INTEGER) AS lonIdx, " +
            "MAX(m.dbm) AS bestDbm, AVG(m.dbm) AS avgDbm, COUNT(*) AS n, MAX(m.time) AS lastTime " +
            "FROM measurements m JOIN cells c ON c.id = m.cellKey " +
            "WHERE m.dbm IS NOT NULL AND m.accuracyM IS NOT NULL AND m.accuracyM <= 100 " +
            "GROUP BY c.mcc, c.mnc, c.rat, latIdx, lonIdx",
    )
    fun observeCoverage(): Flow<List<CoverageTile>>

    /** Messpunkte der verbundenen Zelle mit TAC/LAC, zeitlich sortiert (für die Tracking-Area-Karte). */
    @Query(
        "SELECT m.time AS time, m.latitude AS latitude, m.longitude AS longitude, m.accuracyM AS accuracyM, " +
            "c.area AS area, c.rat AS rat, c.mcc AS mcc, c.mnc AS mnc " +
            "FROM measurements m JOIN cells c ON c.id = m.cellKey " +
            "WHERE m.serving = 1 AND c.area IS NOT NULL AND m.accuracyM IS NOT NULL AND m.accuracyM <= 100 " +
            "ORDER BY m.time",
    )
    suspend fun servingTaPoints(): List<TaPoint>

    /** 5G-NSA-Abdeckung: bester EN-DC-Zustand je ~50-m-Feld und Betreiber der verbundenen Zelle. */
    @Query(
        "SELECT c.mcc AS mcc, c.mnc AS mnc, " +
            "CAST(m.latitude / 0.00045 AS INTEGER) AS latIdx, " +
            "CAST(m.longitude / 0.0007 AS INTEGER) AS lonIdx, " +
            "MAX(CASE m.endc WHEN 'SA' THEN 4 WHEN 'CONNECTED' THEN 3 WHEN 'AVAILABLE' THEN 2 " +
            "WHEN 'RESTRICTED' THEN 1 ELSE 0 END) AS endcRank, COUNT(*) AS n " +
            "FROM measurements m JOIN cells c ON c.id = m.cellKey " +
            "WHERE m.serving = 1 AND m.endc IS NOT NULL AND m.accuracyM IS NOT NULL AND m.accuracyM <= 100 " +
            "GROUP BY c.mcc, c.mnc, latIdx, lonIdx",
    )
    fun observeEndcCoverage(): Flow<List<EndcTile>>

    @Query("SELECT * FROM measurements WHERE groupKey IN (:groupKeys) ORDER BY time DESC LIMIT :limit")
    fun observeMeasurementsForGroups(groupKeys: List<String>, limit: Int): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM estimates")
    suspend fun allEstimatesOnce(): List<EstimateEntity>

    /** Alle Stationen mit Messpunkten, für eine vollständige Neuberechnung der Schätzungen. */
    @Query("SELECT DISTINCT m.groupKey AS groupKey, c.rat AS rat FROM measurements m JOIN cells c ON c.id = m.cellKey")
    suspend fun measuredGroups(): List<GroupRat>

    @Upsert
    suspend fun upsertEstimate(estimate: EstimateEntity)

    @Query("DELETE FROM estimates WHERE groupKey = :groupKey")
    suspend fun deleteEstimate(groupKey: String)

    @Query("DELETE FROM cells")
    suspend fun clearCells()

    @Query("DELETE FROM measurements")
    suspend fun clearMeasurements()

    @Query("DELETE FROM estimates")
    suspend fun clearEstimates()
}

@Database(
    entities = [CellEntity::class, MeasurementEntity::class, EstimateEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cellDao(): CellDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * 3 → 4: Timing-Advance-Werte von Nachbarzellen und unrealistische Werte entfernen
         * (Modem-Platzhalter wie 1242 ≈ 97 km). Die Schätzungen werden danach neu berechnet.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE measurements SET timingAdvance = NULL WHERE serving = 0 OR timingAdvance > 640 OR timingAdvance < 0")
                db.execSQL("UPDATE cells SET minDistanceM = NULL WHERE servingSamples = 0 OR minDistanceM > 50000")
            }
        }

        /** 2 → 3: EN-DC-Zähler an Zellen, NR-Anker, EN-DC-Zustand je Messpunkt. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cells ADD COLUMN endcConnectedSamples INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cells ADD COLUMN endcAvailableSamples INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cells ADD COLUMN nrAnchorKey TEXT")
                db.execSQL("ALTER TABLE measurements ADD COLUMN endc TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "funkstrecke.db",
                )
                    // Messdaten bleiben bei Updates erhalten: jede Schemaänderung bekommt eine Migration.
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // Nur Version 1 (altes Erfassungsformular) wird verworfen.
                    .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                    .build()
                    .also { instance = it }
            }
    }
}

/** Station und Funktechnik, für die Neuberechnung. */
data class GroupRat(val groupKey: String, val rat: String)
