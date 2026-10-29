package com.dwind.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dwind.data.local.AppDatabase
import com.dwind.data.local.entity.PlaylistEntity
import com.dwind.data.local.entity.PlaylistTrackCrossRef
import com.dwind.data.local.entity.TrackEntity
import com.dwind.data.model.BpmInfo
import com.dwind.data.repo.BpmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaylistDetailState(
    val playlist: PlaylistEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val bpmByTrackId: Map<String, Double> = emptyMap(),
    val bpmInfoByTrackId: Map<String, BpmInfo> = emptyMap(),
    val bpmBusyIds: Set<String> = emptySet(),
    val bpmRunning: Boolean = false,
    val bpmTotal: Int = 0,
    val bpmDone: Int = 0
)

class PlaylistDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val bpmRepo = BpmRepository(app)

    private val _state = MutableStateFlow(PlaylistDetailState())
    val state: StateFlow<PlaylistDetailState> = _state.asStateFlow()

    private val _allTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val allTracks: StateFlow<List<TrackEntity>> = _allTracks.asStateFlow()

    private var currentPlaylistId: String? = null
    private var bpmJob: Job? = null

    init {
        viewModelScope.launch {
            bpmRepo.watchAll().collect { entries ->
                val map = entries.mapNotNull { e -> e.bpm?.let { e.trackId to it } }.toMap()
                val info = entries.mapNotNull { e ->
                    e.bpm?.let {
                        e.trackId to BpmInfo(
                            bpm = it,
                            confidence = e.confidence,
                            genre = e.genre,
                            manual = e.source == "manual"
                        )
                    }
                }.toMap()
                _state.value = _state.value.copy(bpmByTrackId = map, bpmInfoByTrackId = info)
            }
        }
    }
    
    fun loadPlaylist(playlistId: String) {
        if (currentPlaylistId == playlistId) return
        currentPlaylistId = playlistId
        _state.value = _state.value.copy(playlist = null, tracks = emptyList())
        viewModelScope.launch {
            combine(
                db.playlistDao().getPlaylists(),
                db.playlistDao().getTracksForPlaylist(playlistId)
            ) { playlists, tracks ->
                if (currentPlaylistId == playlistId) {
                    val playlist = playlists.find { it.id == playlistId }
                    PlaylistDetailState(playlist = playlist, tracks = tracks)
                } else {
                    null
                }
            }.collect { newState ->
                if (newState != null) {
                    _state.value = _state.value.copy(playlist = newState.playlist, tracks = newState.tracks)
                }
            }
        }
        loadAllTracks()
    }
    
    fun loadAllTracks() {
        viewModelScope.launch {
            db.trackDao().getAllTracks().collect { tracks ->
                _allTracks.value = tracks
            }
        }
    }
    
    fun addTrack(playlistId: String, trackId: String, position: Int) {
        viewModelScope.launch {
            db.playlistDao().addTrackToPlaylist(
                PlaylistTrackCrossRef(
                    playlistId = playlistId, 
                    trackId = trackId, 
                    position = position
                )
            )
        }
    }
    
    fun addTracks(playlistId: String, items: List<com.dwind.data.model.AudioItem>) {
        viewModelScope.launch {
            val existing = db.playlistDao().getTracksInPlaylist(playlistId).first()
            val existingIds = existing.map { it.trackId }.toSet()
            var position = existing.size
            items.map { item ->
                TrackEntity(
                    id = item.id.toString(),
                    uri = item.uri,
                    fileName = item.fileName,
                    title = item.title.ifBlank { null },
                    artist = item.artist,
                    durationMs = item.duration,
                    folderPath = item.parentPath
                )
            }.let { db.trackDao().upsertTracks(it) }
            items.map { it.id.toString() }
                .filter { !existingIds.contains(it) }
                .forEach { trackId ->
                    db.playlistDao().addTrackToPlaylist(
                        PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = position++)
                    )
                }
        }
    }
    
    fun removeTrack(playlistId: String, trackId: String) {
        viewModelScope.launch {
            db.playlistDao().removeTrackFromPlaylist(playlistId, trackId)
        }
    }
    
    fun renamePlaylist(playlistId: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            db.playlistDao().renamePlaylist(playlistId, name)
        }
    }
    
    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            db.playlistDao().deletePlaylistById(playlist.id)
        }
    }

    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private fun markBusy(trackId: String, busy: Boolean) {
        _state.update {
            it.copy(bpmBusyIds = if (busy) it.bpmBusyIds + trackId else it.bpmBusyIds - trackId)
        }
    }

    fun setManualBpm(trackId: String, uri: String, fileName: String, bpm: Double, genre: String? = null) {
        viewModelScope.launch(Dispatchers.Default) {
            bpmRepo.saveManual(trackId, uri, fileName, bpm, genre)
        }
    }

    fun detectBpm(track: TrackEntity, force: Boolean = false) {
        viewModelScope.launch(Dispatchers.Default) {
            markBusy(track.id, true)
            try {
                bpmRepo.ensureBpm(
                    trackId = track.id,
                    uri = track.uri,
                    fileName = track.fileName,
                    force = force
                )
            } finally {
                markBusy(track.id, false)
            }
        }
    }

    /** Пакетный анализ плейлиста: столько дорожек одновременно, сколько ядер CPU. */
    fun analyzeBpmForPlaylist(force: Boolean = false) {
        if (bpmJob?.isActive == true) return
        val items = _state.value.tracks
        if (items.isEmpty()) return
        val total = items.size
        val workerCount = minOf(cores, total)
        _state.update { it.copy(bpmRunning = true, bpmTotal = total, bpmDone = 0) }
        val cursor = java.util.concurrent.atomic.AtomicInteger(0)
        bpmJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                if (force) {
                    for (track in items) {
                        bpmRepo.clearBpm(track.id)
                    }
                }
                coroutineScope {
                    repeat(workerCount) {
                        launch(Dispatchers.Default) {
                            while (isActive) {
                                val i = cursor.getAndIncrement()
                                if (i >= total) break
                                val track = items[i]
                                markBusy(track.id, true)
                                try {
                                    bpmRepo.ensureBpm(
                                        trackId = track.id,
                                        uri = track.uri,
                                        fileName = track.fileName,
                                        force = force,
                                        isCancelled = { !isActive }
                                    )
                                } finally {
                                    markBusy(track.id, false)
                                    _state.update { it.copy(bpmDone = it.bpmDone + 1) }
                                }
                            }
                        }
                    }
                }
            } finally {
                _state.update { it.copy(bpmRunning = false, bpmTotal = 0, bpmDone = 0, bpmBusyIds = emptySet()) }
            }
        }
    }

    fun allAnalyzedInPlaylist(): Boolean {
        val items = _state.value.tracks
        if (items.isEmpty()) return true
        val map = _state.value.bpmByTrackId
        return items.all { map[it.id] != null }
    }

    fun hasUnanalyzedInPlaylist(): Boolean {
        val items = _state.value.tracks
        val map = _state.value.bpmByTrackId
        return items.any { map[it.id] == null }
    }

    fun cancelBpmAnalysis() {
        bpmJob?.cancel()
        bpmJob = null
        _state.update { it.copy(bpmRunning = false, bpmTotal = 0, bpmDone = 0, bpmBusyIds = emptySet()) }
    }
}
