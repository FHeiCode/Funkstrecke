package de.funkstrecke.data

import android.content.Context
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

    @Query("SELECT * FROM estimates")
    suspend fun allEstimatesOnce(): List<EstimateEntity>

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
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cellDao(): CellDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "funkstrecke.db",
                )
                    // Version 1 war das alte Erfassungsformular; dessen Tabelle wird verworfen.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
