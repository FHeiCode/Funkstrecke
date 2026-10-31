package de.funkstrecke.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {
    @Query("SELECT * FROM stations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Station>>

    @Query("SELECT * FROM stations WHERE id = :id")
    suspend fun get(id: Long): Station?

    @Upsert
    suspend fun upsert(station: Station): Long

    @Delete
    suspend fun delete(station: Station)
}

@Database(entities = [Station::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stationDao(): StationDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "funkstrecke.db",
                ).build().also { instance = it }
            }
    }
}
