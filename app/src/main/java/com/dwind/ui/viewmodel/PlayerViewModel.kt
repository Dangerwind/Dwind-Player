package com.dwind.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.dwind.data.local.AppDatabase
import com.dwind.data.local.entity.PlaylistEntity
import com.dwind.data.local.entity.PlaylistTrackCrossRef
import com.dwind.data.local.entity.TrackEntity
import com.dwind.data.model.AudioItem
import com.dwind.playback.PlaybackConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val currentTitle: String = "",
    val currentArtist: String = "",
    val currentUri: String = "",
    val currentTrackId: String = "",
    val currentFileName: String = "",
    val position: Long = 0L,
    val duration: Long = 0L,
    val shuffleModeEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val playlists: List<PlaylistEntity> = emptyList(),
    val currentTrackInPlaylists: List<PlaylistEntity> = emptyList(),
    val currentBpm: Double? = null,
    val currentBpmConfidence: Double? = null,
    val currentBpmManual: Boolean = false,
    val currentGenre: String? = null
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val connection = PlaybackConnection(app)
    private val db = AppDatabase.getInstance(app)
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    val controller = connection.controller
    
    init {
        viewModelScope.launch {
            controller.collect { ctrl ->
                ctrl?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        mediaItem?.let {
                            val uri = it.localConfiguration?.uri?.toString() ?: ""
                            val meta = metaByMediaId[it.mediaId]
                            _uiState.value = _uiState.value.copy(
                                currentTitle = it.mediaMetadata.title?.toString() ?: meta?.title ?: "",
                                currentArtist = it.mediaMetadata.artist?.toString() ?: meta?.artist ?: "",
                                currentUri = uri,
                                currentTrackId = it.mediaId ?: "",
                                currentFileName = meta?.fileName ?: ""
                            )
                            checkCurrentTrackInPlaylists(uri)
                            refreshCurrentBpm()
                        }
                    }
                    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                        _uiState.value = _uiState.value.copy(shuffleModeEnabled = shuffleModeEnabled)
                    }
                    override fun onRepeatModeChanged(repeatMode: Int) {
                        _uiState.value = _uiState.value.copy(repeatMode = repeatMode)
                    }
                })
            }
        }
        viewModelScope.launch {
            db.playlistDao().getPlaylists().collect { playlists ->
                _uiState.value = _uiState.value.copy(playlists = playlists)
                if (_uiState.value.currentUri.isNotEmpty()) {
                    checkCurrentTrackInPlaylists(_uiState.value.currentUri)
                }
            }
        }
        // Периодически опрашиваем плеер, чтобы прогресс-бар двигался
        viewModelScope.launch {
            while (true) {
                val ctrl = controller.value
                if (ctrl != null) {
                    val dur = ctrl.duration
                    val pos = ctrl.currentPosition
                    _uiState.value = _uiState.value.copy(
                        duration = if (dur > 0) dur else 0L,
                        position = if (pos > 0) pos else 0L
                    )
                }
                delay(500)
            }
        }
        // Подписка на BPM-кэш: BPM играющего трека виден в плеере сразу после расчёта
        viewModelScope.launch {
            db.bpmDao().getAll().collect { entries ->
                bpmMap = entries.mapNotNull { e -> e.bpm?.let { e.trackId to it } }.toMap()
                bpmInfoMap = entries.mapNotNull { e ->
                    e.bpm?.let { e.trackId to com.dwind.data.model.BpmInfo(it, e.confidence, e.genre, e.source == "manual") }
                }.toMap()
                refreshCurrentBpm()
            }
        }
    }

    private var bpmMap: Map<String, Double> = emptyMap()
    private var bpmInfoMap: Map<String, com.dwind.data.model.BpmInfo> = emptyMap()

    /** Метаданные треков текущей очереди: mediaId -> (title, artist, fileName). */
    private data class TrackMeta(val title: String, val artist: String?, val fileName: String)

    private data class MediaQueue(
        val source: Any,
        val items: List<MediaItem>,
        val meta: Map<String, TrackMeta>
    )

    private var metaByMediaId: Map<String, TrackMeta> = emptyMap()
    private var cachedQueue: MediaQueue? = null
    private var playJob: Job? = null

    private fun refreshCurrentBpm() {
        val id = currentTrackId()
        val info = id?.let { bpmInfoMap[it] }
        _uiState.value = _uiState.value.copy(
            currentBpm = id?.let { bpmMap[it] },
            currentBpmConfidence = info?.confidence,
            currentBpmManual = info?.manual == true,
            currentGenre = info?.genre
        )
    }
    
    private fun currentTrackId(): String? {
        val st = _uiState.value
        val fromState = st.currentTrackId
        if (fromState.isNotEmpty()) return fromState
        val derived = st.currentUri.substringAfterLast("/").substringBefore("?")
        return derived.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
    }

    private fun checkCurrentTrackInPlaylists(uri: String) {
        viewModelScope.launch {
            val trackId = currentTrackId()
            if (trackId == null) {
                _uiState.value = _uiState.value.copy(currentTrackInPlaylists = emptyList())
                return@launch
            }
            val ids = db.playlistDao().getPlaylistIdsContainingTrack(trackId).toSet()
            val inPlaylists = _uiState.value.playlists.filter { it.id in ids }
            _uiState.value = _uiState.value.copy(currentTrackInPlaylists = inPlaylists)
        }
    }

    fun addCurrentTrackToPlaylist(playlistId: String) {
        viewModelScope.launch {
            val trackId = currentTrackId() ?: return@launch
            val st = _uiState.value
            // Если трека ещё нет в таблице tracks — создаём из метаинформации играющего трека
            val existingTrack = db.trackDao().getTrackById(trackId)
                ?: st.currentUri.takeIf { it.isNotEmpty() }?.let { db.trackDao().getTrackByUri(it) }
            if (existingTrack == null) {
                db.trackDao().upsertTrack(
                    TrackEntity(
                        id = trackId,
                        uri = st.currentUri,
                        fileName = st.currentFileName.ifBlank { st.currentTitle },
                        title = st.currentTitle.ifBlank { null },
                        artist = st.currentArtist.ifBlank { null },
                        durationMs = st.duration.takeIf { it > 0 },
                        folderPath = null
                    )
                )
            }
            if (db.playlistDao().countTrackInPlaylist(playlistId, trackId) == 0) {
                val position = db.playlistDao().countTracksInPlaylist(playlistId)
                db.playlistDao().addTrackToPlaylist(
                    PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = position)
                )
            }
            checkCurrentTrackInPlaylists(st.currentUri)
        }
    }

    /** Собирает очередь для ExoPlayer вне главного потока. */
    private fun buildAudioQueue(playlist: List<AudioItem>): MediaQueue {
        val meta = HashMap<String, TrackMeta>(playlist.size)
        val items = ArrayList<MediaItem>(playlist.size)
        for (a in playlist) {
            val id = a.id.toString()
            val title = a.title.ifBlank { a.fileName }
            meta[id] = TrackMeta(title, a.artist, a.fileName)
            items += MediaItem.Builder()
                .setMediaId(id)
                .setUri(Uri.parse(a.uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(a.artist).build())
                .build()
        }
        return MediaQueue(playlist, items, meta)
    }

    private fun buildTrackQueue(tracks: List<TrackEntity>): MediaQueue {
        val meta = HashMap<String, TrackMeta>(tracks.size)
        val items = ArrayList<MediaItem>(tracks.size)
        for (t in tracks) {
            val title = t.title?.takeIf { it.isNotBlank() } ?: t.fileName
            meta[t.id] = TrackMeta(title, t.artist, t.fileName)
            items += MediaItem.Builder()
                .setMediaId(t.id)
                .setUri(Uri.parse(t.uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(t.artist).build())
                .build()
        }
        return MediaQueue(tracks, items, meta)
    }

    fun playTrack(item: AudioItem, playlist: List<AudioItem>) {
        val ctrl = controller.value ?: return
        if (playlist.isEmpty()) return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            // Повторный тап по тому же списку переиспользует уже собранную очередь.
            val queue = cachedQueue?.takeIf { it.source === playlist }
                ?: withContext(Dispatchers.Default) { buildAudioQueue(playlist) }
            if (!isActive) return@launch
            cachedQueue = queue
            metaByMediaId = queue.meta
            ctrl.setMediaItems(queue.items)
            val index = playlist.indexOfFirst { it.id == item.id }
            if (index >= 0) ctrl.seekTo(index, 0)
            ctrl.prepare()
            ctrl.play()
        }
    }

    fun playTrackList(trackId: String, tracks: List<TrackEntity>) {
        val ctrl = controller.value ?: return
        if (tracks.isEmpty()) return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            val queue = cachedQueue?.takeIf { it.source === tracks }
                ?: withContext(Dispatchers.Default) { buildTrackQueue(tracks) }
            if (!isActive) return@launch
            cachedQueue = queue
            metaByMediaId = queue.meta
            ctrl.setMediaItems(queue.items)
            val index = tracks.indexOfFirst { it.id == trackId }
            if (index >= 0) ctrl.seekTo(index, 0)
            ctrl.prepare()
            ctrl.play()
        }
    }

    fun playPause() {
        val ctrl = controller.value ?: return
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
    }

    /** Играет ли плеер прямо сейчас (для запоминания состояния перед ручным определением BPM). */
    fun isPlayingNow(): Boolean = controller.value?.isPlaying == true

    /** Гарантирует воспроизведение: если трек на паузе — запускает его. */
    fun ensurePlaying() {
        val ctrl = controller.value ?: return
        if (!ctrl.isPlaying) ctrl.play()
    }

    /** Ставит текущий трек на паузу (без переключения). */
    fun pause() {
        controller.value?.pause()
    }

    fun seekTo(positionMs: Long) {
        val ctrl = controller.value ?: return
        if (ctrl.duration > 0) {
            ctrl.seekTo(positionMs.coerceIn(0L, ctrl.duration))
            _uiState.value = _uiState.value.copy(position = positionMs)
        }
    }
    
    fun next() {
        controller.value?.seekToNext()
    }
    
    fun previous() {
        controller.value?.seekToPrevious()
    }
    
    fun toggleShuffle() {
        val ctrl = controller.value ?: return
        ctrl.shuffleModeEnabled = !ctrl.shuffleModeEnabled
    }
    
    fun setRepeatMode() {
        val ctrl = controller.value ?: return
        ctrl.repeatMode = when (ctrl.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        connection.release()
    }
}
