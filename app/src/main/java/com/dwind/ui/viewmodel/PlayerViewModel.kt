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
import com.dwind.settings.AppSettings
import com.dwind.settings.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    val currentGenre: String? = null,
    /** Метка начала фрагмента, мс; null — не задана. */
    val markerA: Long? = null,
    /** Метка конца фрагмента, мс; null — не задана. */
    val markerB: Long? = null,
    /** Включён ли цикл A→B с секундной паузой перед повтором. */
    val abLoopEnabled: Boolean = false,
    /** Раскрыта ли панель фрагмента под шкалой времени. */
    val abPanelExpanded: Boolean = false
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
                            val previousId = _uiState.value.currentTrackId
                            val newId = it.mediaId ?: ""
                            // Метки заданы в секундах текущего трека: на другом
                            // треке они бессмысленны, поэтому сбрасываем.
                            if (previousId.isNotEmpty() && previousId != newId) {
                                clearAbMarkers()
                            }
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

                            // Фильтр мог измениться, пока играл трек: не даём
                            // перейти на трек вне диапазона.
                            val id = it.mediaId ?: ""
                            if (AppSettings.state.value.bpmFilterEnabled) {
                                if (playableMediaIds.isEmpty()) {
                                    // Подходящих треков нет — останавливаемся.
                                    ctrl.pause()
                                } else if (id !in playableMediaIds && id != preservedNonPlayableId) {
                                    val target = findPlayableIndex(ctrl, forward = true)
                                    if (target != null) ctrl.seekTo(target, 0) else ctrl.pause()
                                } else if (id != preservedNonPlayableId) {
                                    preservedNonPlayableId = null
                                }
                            } else {
                                preservedNonPlayableId = null
                            }
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
        // Диапазон фильтра может измениться прямо во время воспроизведения:
        // пересобираем очередь, чтобы дальше играли только подходящие треки.
        viewModelScope.launch {
            AppSettings.state
                .map { Triple(it.bpmFilterEnabled, it.filterBpmMin, it.filterBpmMax) }
                .distinctUntilChanged()
                .collect { (enabled, min, max) -> applyFilterChange(enabled, min, max) }
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
    private var filterJob: Job? = null
    private var abLoopJob: Job? = null

    /** Исходный список, из которого собрана текущая очередь — нужен для пересборки при смене фильтра. */
    private sealed interface QueueSource {
        data class Audio(val items: List<AudioItem>) : QueueSource
        data class Tracks(val items: List<TrackEntity>) : QueueSource
    }

    private var activeSource: QueueSource? = null

    /** mediaId треков в текущей очереди (в порядке воспроизведения). */
    private var currentMediaIds: List<String> = emptyList()

    /** mediaId треков, которые разрешены текущим фильтром BPM. */
    private var playableMediaIds: Set<String> = emptySet()

    /**
     * Трек, который продолжаем играть после смены фильтра, хотя он уже не
     * попадает в диапазон. Ему даём доиграть и только потом переходим к
     * подходящим трекам.
     */
    private var preservedNonPlayableId: String? = null

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

    private fun buildQueue(source: QueueSource): MediaQueue = when (source) {
        is QueueSource.Audio -> buildAudioQueue(source.items)
        is QueueSource.Tracks -> buildTrackQueue(source.items)
    }

    /**
     * Пересобирает очередь при изменении фильтра BPM.
     *
     * Текущий трек продолжает играть, даже если он не попадает в новый диапазон:
     * он временно остаётся первым в очереди. Дальше играют только подходящие
     * треки. Если подходящих нет — после текущего трека воспроизведение
     * останавливается (в очереди остаётся только он).
     */
    private fun applyFilterChange(enabled: Boolean, min: Int, max: Int) {
        val source = activeSource ?: return
        val ctrl = controller.value ?: return
        val currentId = ctrl.currentMediaItem?.mediaId
            ?: _uiState.value.currentTrackId.takeIf { it.isNotEmpty() }
            ?: return
        val position = ctrl.currentPosition.coerceAtLeast(0L)
        // playWhenReady хранит намерение пользователя: false и во время буферизации.
        val wasPlaying = ctrl.playWhenReady

        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val full = withContext(Dispatchers.Default) { buildQueue(source) }
            if (!isActive) return@launch

            val filter = SettingsState(
                bpmFilterEnabled = enabled,
                filterBpmMin = min,
                filterBpmMax = max
            )
            val plan = planBpmQueue(
                allIds = full.items.map { it.mediaId },
                bpmById = bpmMap,
                currentId = currentId,
                settings = filter
            )
            playableMediaIds = plan.playableIds
            preservedNonPlayableId = plan.preservedId

            // null — подходящих треков нет: текущий доигрывает, очередь не трогаем.
            val desiredIds = plan.queueIds ?: return@launch
            if (desiredIds.isEmpty()) {
                ctrl.pause()
                return@launch
            }
            if (desiredIds == currentMediaIds) return@launch

            val itemsById = full.items.associateBy { it.mediaId }
            val desired = desiredIds.map { itemsById.getValue(it) }
            val index = desiredIds.indexOf(currentId).takeIf { it >= 0 } ?: 0
            metaByMediaId = full.meta
            currentMediaIds = desiredIds
            val startPosition = if (currentId in desiredIds) position else 0L
            ctrl.setMediaItems(desired, index, startPosition)
            ctrl.prepare()
            if (wasPlaying) ctrl.play()
        }
    }

    fun playTrack(item: AudioItem, playlist: List<AudioItem>) {
        val ctrl = controller.value ?: return
        if (playlist.isEmpty()) return
        val settings = AppSettings.state.value
        val filterEnabled = settings.bpmFilterEnabled
        // При включённом фильтре в очередь попадают только треки в диапазоне BPM.
        val playable = if (filterEnabled) {
            playlist.filter { settings.isBpmAllowed(bpmMap[it.id.toString()]) }
        } else {
            playlist
        }
        if (playable.none { it.id == item.id }) return
        activeSource = QueueSource.Audio(playlist)
        preservedNonPlayableId = null
        filterJob?.cancel()
        playJob?.cancel()
        playJob = viewModelScope.launch {
            // Повторный тап по тому же списку переиспользует уже собранную очередь,
            // но только когда фильтр выключен: иначе состав очереди зависит от диапазона.
            val queue = if (filterEnabled) {
                withContext(Dispatchers.Default) { buildAudioQueue(playable) }
            } else {
                cachedQueue?.takeIf { it.source === playlist }
                    ?: withContext(Dispatchers.Default) { buildAudioQueue(playlist) }
            }
            if (!isActive) return@launch
            if (!filterEnabled) cachedQueue = queue
            metaByMediaId = queue.meta
            currentMediaIds = queue.items.map { it.mediaId }
            playableMediaIds = playable.map { it.id.toString() }.toSet()
            ctrl.setMediaItems(queue.items)
            val index = playable.indexOfFirst { it.id == item.id }
            if (index >= 0) ctrl.seekTo(index, 0)
            ctrl.prepare()
            ctrl.play()
        }
    }

    fun playTrackList(trackId: String, tracks: List<TrackEntity>) {
        val ctrl = controller.value ?: return
        if (tracks.isEmpty()) return
        val settings = AppSettings.state.value
        val filterEnabled = settings.bpmFilterEnabled
        val playable = if (filterEnabled) {
            tracks.filter { settings.isBpmAllowed(bpmMap[it.id]) }
        } else {
            tracks
        }
        if (playable.none { it.id == trackId }) return
        activeSource = QueueSource.Tracks(tracks)
        preservedNonPlayableId = null
        filterJob?.cancel()
        playJob?.cancel()
        playJob = viewModelScope.launch {
            val queue = if (filterEnabled) {
                withContext(Dispatchers.Default) { buildTrackQueue(playable) }
            } else {
                cachedQueue?.takeIf { it.source === tracks }
                    ?: withContext(Dispatchers.Default) { buildTrackQueue(tracks) }
            }
            if (!isActive) return@launch
            if (!filterEnabled) cachedQueue = queue
            metaByMediaId = queue.meta
            currentMediaIds = queue.items.map { it.mediaId }
            playableMediaIds = playable.map { it.id }.toSet()
            ctrl.setMediaItems(queue.items)
            val index = playable.indexOfFirst { it.id == trackId }
            if (index >= 0) ctrl.seekTo(index, 0)
            ctrl.prepare()
            ctrl.play()
        }
    }

    fun playPause() {
        val ctrl = controller.value ?: return
        if (ctrl.isPlaying) {
            ctrl.pause()
            return
        }
        // В режиме цикла возобновление вне фрагмента начинаем с метки A.
        if (_uiState.value.abLoopEnabled) {
            val section = abSection()
            val pos = ctrl.currentPosition
            if (section != null && (pos < section.first || pos >= section.second)) {
                ctrl.seekTo(section.first)
            }
            startAbLoopDriver()
        }
        ctrl.play()
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

    // --- Фрагмент A–B -------------------------------------------------------

    /** Раскрывает/сворачивает панель фрагмента под шкалой времени. */
    fun setAbPanelExpanded(expanded: Boolean) {
        _uiState.value = _uiState.value.copy(abPanelExpanded = expanded)
    }

    /** Ставит метку начала в текущую позицию воспроизведения. */
    fun setMarkerA() {
        val ctrl = controller.value ?: return
        val pos = ctrl.currentPosition.coerceAtLeast(0L)
        _uiState.value = _uiState.value.copy(markerA = pos)
        if (_uiState.value.abLoopEnabled && abSection() == null) stopAbLoop()
    }

    /** Ставит метку конца в текущую позицию воспроизведения. */
    fun setMarkerB() {
        val ctrl = controller.value ?: return
        val pos = ctrl.currentPosition.coerceAtLeast(0L)
        _uiState.value = _uiState.value.copy(markerB = pos)
        if (_uiState.value.abLoopEnabled && abSection() == null) stopAbLoop()
    }

    /** Переход к метке A без запуска воспроизведения. */
    fun jumpToMarkerA() {
        _uiState.value.markerA?.let { seekTo(it) }
    }

    /** Переход к метке B без запуска воспроизведения. */
    fun jumpToMarkerB() {
        _uiState.value.markerB?.let { seekTo(it) }
    }

    /** Играть с метки A (без зацикливания). */
    fun playFromMarkerA() {
        val ctrl = controller.value ?: return
        val a = _uiState.value.markerA ?: return
        ctrl.seekTo(a.coerceIn(0L, ctrl.duration.coerceAtLeast(0L)))
        if (_uiState.value.abLoopEnabled) startAbLoopDriver()
        ctrl.play()
    }

    /** Включает/выключает цикл A→B. */
    fun toggleAbLoop() {
        val ctrl = controller.value ?: return
        if (_uiState.value.abLoopEnabled) {
            stopAbLoop()
            return
        }
        val section = abSection() ?: return
        _uiState.value = _uiState.value.copy(abLoopEnabled = true)
        ctrl.seekTo(section.first)
        ctrl.play()
        startAbLoopDriver()
    }

    /** Сбрасывает обе метки и выключает цикл. */
    fun clearAbMarkers() {
        abLoopJob?.cancel()
        abLoopJob = null
        _uiState.value = _uiState.value.copy(
            markerA = null,
            markerB = null,
            abLoopEnabled = false
        )
    }

    private fun stopAbLoop() {
        abLoopJob?.cancel()
        abLoopJob = null
        _uiState.value = _uiState.value.copy(abLoopEnabled = false)
    }

    /** Валидный фрагмент: обе метки заданы и между ними не меньше секунды. */
    private fun abSection(): Pair<Long, Long>? {
        val st = _uiState.value
        val a = st.markerA ?: return null
        val b = st.markerB ?: return null
        if (b - a < AB_MIN_SECTION_MS) return null
        return a to b
    }

    /**
     * Ведёт цикл A→B: доводит воспроизведение до метки B, ставит паузу на
     * секунду, возвращает на A и повторяет. Работает, пока цикл включён;
     * ручная пауза просто приостанавливает драйвер, не выключая режим.
     */
    private fun startAbLoopDriver() {
        abLoopJob?.cancel()
        abLoopJob = viewModelScope.launch {
            val ctrl = controller.value ?: return@launch
            while (isActive) {
                val section = abSection()
                if (!_uiState.value.abLoopEnabled || section == null) break
                val (a, b) = section
                if (!ctrl.playWhenReady) {
                    // Пауза пользователя или буферизация — ждём, режим сохраняем.
                    delay(150)
                    continue
                }
                val pos = ctrl.currentPosition
                if (pos >= b) {
                    ctrl.pause()
                    delay(AB_LOOP_GAP_MS)
                    if (!isActive || !_uiState.value.abLoopEnabled) break
                    ctrl.seekTo(a)
                    ctrl.play()
                    // Даём плееру тронуться, чтобы не поймать старую позицию.
                    delay(120)
                    continue
                }
                delay((b - pos).coerceIn(20L, 200L))
            }
        }
    }
    
    fun next() {
        val ctrl = controller.value ?: return
        if (!AppSettings.state.value.bpmFilterEnabled) {
            ctrl.seekToNext()
            return
        }
        val target = findPlayableIndex(ctrl, forward = true)
        if (target != null) {
            ctrl.seekTo(target, 0)
        } else {
            // Подходящих треков дальше нет — останавливаем воспроизведение.
            ctrl.pause()
        }
    }
    
    fun previous() {
        val ctrl = controller.value ?: return
        if (!AppSettings.state.value.bpmFilterEnabled) {
            ctrl.seekToPrevious()
            return
        }
        val target = findPlayableIndex(ctrl, forward = false)
        if (target != null) {
            ctrl.seekTo(target, 0)
        } else {
            // Раньше подходящих треков нет — начинаем текущий заново.
            val current = ctrl.currentMediaItemIndex
            if (current >= 0) ctrl.seekTo(current, 0)
        }
    }

    /** Ближайший трек в очереди, разрешённый текущим фильтром BPM. */
    private fun findPlayableIndex(ctrl: Player, forward: Boolean): Int? {
        val count = ctrl.mediaItemCount
        if (count == 0) return null
        val allowed = playableMediaIds
        if (allowed.isEmpty()) return null
        val current = ctrl.currentMediaItemIndex
        if (forward) {
            for (i in current + 1 until count) {
                if (ctrl.getMediaItemAt(i).mediaId in allowed) return i
            }
            if (ctrl.repeatMode == Player.REPEAT_MODE_ALL) {
                for (i in 0 until count) {
                    if (i != current && ctrl.getMediaItemAt(i).mediaId in allowed) return i
                }
            }
        } else {
            for (i in current - 1 downTo 0) {
                if (ctrl.getMediaItemAt(i).mediaId in allowed) return i
            }
            if (ctrl.repeatMode == Player.REPEAT_MODE_ALL) {
                for (i in count - 1 downTo 0) {
                    if (i != current && ctrl.getMediaItemAt(i).mediaId in allowed) return i
                }
            }
        }
        return null
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
        abLoopJob?.cancel()
        super.onCleared()
        connection.release()
    }

    private companion object {
        /** Минимальная длина фрагмента: короче секунды цикл бессмысленен. */
        const val AB_MIN_SECTION_MS = 1_000L
        /** Пауза между повторами фрагмента. */
        const val AB_LOOP_GAP_MS = 1_000L
    }
}
