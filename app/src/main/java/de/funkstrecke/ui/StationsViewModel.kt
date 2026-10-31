package de.funkstrecke.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.funkstrecke.data.AppDatabase
import de.funkstrecke.data.Station
import de.funkstrecke.data.splitList
import de.funkstrecke.util.Photos
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StationsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).stationDao()

    val stations: StateFlow<List<Station>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun load(id: Long): Station? = dao.get(id)

    fun save(station: Station) {
        viewModelScope.launch { dao.upsert(station) }
    }

    /** Löscht den Standort samt gespeicherter Fotos und noch nicht gespeicherter Aufnahmen. */
    fun delete(id: Long, unsavedPhotos: List<String>) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val station = dao.get(id)
            if (station != null) {
                dao.delete(station)
                station.photos.splitList('|').forEach { Photos.delete(context, it) }
            }
            unsavedPhotos.forEach { Photos.delete(context, it) }
        }
    }
}
