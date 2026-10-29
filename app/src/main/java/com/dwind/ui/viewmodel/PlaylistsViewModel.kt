package com.dwind.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dwind.data.local.AppDatabase
import com.dwind.data.local.entity.PlaylistEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class PlaylistsUiState(
    val playlists: List<PlaylistEntity> = emptyList(),
    val isLoading: Boolean = false
)

class PlaylistsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    
    private val _uiState = MutableStateFlow(PlaylistsUiState())
    val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()
    
    init {
        loadPlaylists()
    }
    
    private fun loadPlaylists() {
        viewModelScope.launch {
            db.playlistDao().getPlaylists().collect { playlists ->
                _uiState.value = PlaylistsUiState(playlists = playlists.toList())
            }
        }
    }
    
    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val playlist = PlaylistEntity(
                id = UUID.randomUUID().toString(),
                name = name
            )
            db.playlistDao().insertPlaylist(playlist)
        }
    }
    
    fun renamePlaylist(playlistId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            db.playlistDao().renamePlaylist(playlistId, newName)
        }
    }
    
    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            db.playlistDao().deletePlaylistById(playlist.id)
        }
    }
}
