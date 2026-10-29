package com.dwind.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.dwind.data.local.AppDatabase
import com.dwind.data.local.entity.TrackEntity
import com.dwind.data.model.AudioItem
import com.dwind.data.model.BpmInfo
import com.dwind.data.repo.AudioRepository
import com.dwind.data.repo.BpmRepository
import com.dwind.ui.model.FolderNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class SortMode {
    FILE_NAME,
    TRACK_TITLE,
    BPM
}

enum class SortOrder {
    ASC,
    DESC
}

data class BrowserState(
    val currentPath: String = "",
    val folders: List<FolderNode> = emptyList(),
    val files: List<AudioItem> = emptyList(),
    val pathStack: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    val sortMode: SortMode = SortMode.FILE_NAME,
    val sortOrder: SortOrder = SortOrder.ASC,
    val bpmByTrackId: Map<String, Double> = emptyMap(),
    val bpmInfoByTrackId: Map<String, BpmInfo> = emptyMap(),
    val bpmBusyIds: Set<String> = emptySet(),
    val bpmRunning: Boolean = false,
    val bpmTotal: Int = 0,
    val bpmDone: Int = 0
)

class FoldersBrowserViewModel(app: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = AudioRepository(app)
    private val bpmRepo = BpmRepository(app)
    private val db = AppDatabase.getInstance(app)
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private var bpmJob: Job? = null

    init {
        // BPM-кэш живёт в Room; подписываемся, чтобы значения и сортировка обновлялись сразу
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
                if (_state.value.sortMode == SortMode.BPM) resortCurrent()
            }
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

    fun detectBpm(item: AudioItem, force: Boolean = false) {
        viewModelScope.launch(Dispatchers.Default) {
            val id = item.id.toString()
            markBusy(id, true)
            try {
                bpmRepo.ensureBpm(
                    trackId = id,
                    uri = item.uri,
                    fileName = item.fileName,
                    force = force
                )
            } finally {
                markBusy(id, false)
            }
        }
    }

    /** Пакетный анализ: столько дорожек считаются одновременно, сколько ядер CPU. */
    fun analyzeBpmForCurrentFolder(force: Boolean = false) {
        if (bpmJob?.isActive == true) return
        val items = _state.value.files
        if (items.isEmpty()) return
        val total = items.size
        val workerCount = minOf(cores, total)
        _state.update { it.copy(bpmRunning = true, bpmTotal = total, bpmDone = 0) }
        val cursor = java.util.concurrent.atomic.AtomicInteger(0)
        bpmJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                if (force) {
                    for (item in items) {
                        bpmRepo.clearBpm(item.id.toString())
                    }
                }
                coroutineScope {
                    repeat(workerCount) {
                        launch(Dispatchers.Default) {
                            while (isActive) {
                                val i = cursor.getAndIncrement()
                                if (i >= total) break
                                val item = items[i]
                                val id = item.id.toString()
                                markBusy(id, true)
                                try {
                                    bpmRepo.ensureBpm(
                                        trackId = id,
                                        uri = item.uri,
                                        fileName = item.fileName,
                                        force = force,
                                        isCancelled = { !isActive }
                                    )
                                } finally {
                                    markBusy(id, false)
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

    fun hasUnanalyzedInCurrentFolder(): Boolean {
        val items = _state.value.files
        val map = _state.value.bpmByTrackId
        return items.any { map[it.id.toString()] == null }
    }

    fun allAnalyzedInCurrentFolder(): Boolean {
        val items = _state.value.files
        if (items.isEmpty()) return true
        val map = _state.value.bpmByTrackId
        return items.all { map[it.id.toString()] != null }
    }

    fun clearAndReanalyzeCurrentFolder() {
        analyzeBpmForCurrentFolder(force = true)
    }

    fun cancelBpmAnalysis() {
        bpmJob?.cancel()
        bpmJob = null
        _state.update { it.copy(bpmRunning = false, bpmTotal = 0, bpmDone = 0, bpmBusyIds = emptySet()) }
    }

    private fun resortCurrent() {
        val files = _state.value.files
        if (files.isEmpty()) return
        _state.value = _state.value.copy(files = applySort(files, _state.value.sortMode, _state.value.sortOrder))
    }
    
    private var allItems: List<AudioItem> = emptyList()
    private var currentFiles: List<AudioItem> = emptyList()

    init {
        val savedPath = savedStateHandle.get<String>("currentPath") ?: ""
        val savedStack = savedStateHandle.get<ArrayList<String>>("pathStack") ?: arrayListOf()
        val savedSort = savedStateHandle.get<String>("sortMode")
        val savedOrder = savedStateHandle.get<String>("sortOrder")
        if (savedPath.isNotEmpty() || savedStack.isNotEmpty()) {
            _state.value = _state.value.copy(
                currentPath = savedPath, 
                pathStack = savedStack,
                sortMode = savedSort?.let { SortMode.valueOf(it) } ?: SortMode.FILE_NAME,
                sortOrder = savedOrder?.let { SortOrder.valueOf(it) } ?: SortOrder.ASC
            )
        }
    }

    fun setPermission(granted: Boolean) {
        _state.value = _state.value.copy(hasPermission = granted)
        if (granted && allItems.isEmpty()) loadRoot()
        else if (granted && allItems.isNotEmpty()) {
            if (_state.value.currentPath.isEmpty() && _state.value.pathStack.isEmpty()) {
                buildRoot()
            } else {
                openFolderDirect(_state.value.currentPath)
            }
        }
    }

    fun loadRoot() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            allItems = repo.getAllAudio()
            // Сохраняем треки в Room, чтобы плейлисты могли делать JOIN и искать треки
            val trackEntities = allItems.map { item ->
                TrackEntity(
                    id = item.id.toString(),
                    uri = item.uri,
                    fileName = item.fileName,
                    title = item.title.ifBlank { null },
                    artist = item.artist,
                    durationMs = item.duration,
                    folderPath = item.parentPath
                )
            }
            db.trackDao().upsertTracks(trackEntities)
            if (_state.value.currentPath.isEmpty() && _state.value.pathStack.isEmpty()) {
                buildRoot()
            } else {
                openFolderDirect(_state.value.currentPath)
            }
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    private fun buildRoot() {
        val rootMap = mutableMapOf<String, MutableList<AudioItem>>()
        for (item in allItems) {
            val parent = item.parentPath.ifEmpty { "/" }
            rootMap.getOrPut(parent) { mutableListOf() }.add(item)
        }
        val folders = rootMap.keys.sorted().map { path ->
            val name = if (path == "/") "Все файлы" else File(path).name.ifEmpty { path }
            FolderNode(path = path, name = name, isDirectory = true, trackCount = rootMap[path]?.size ?: 0)
        }
        currentFiles = emptyList()
        _state.value = _state.value.copy(
            currentPath = "",
            folders = folders,
            files = emptyList(),
            pathStack = emptyList()
        )
        savedStateHandle["currentPath"] = ""
        savedStateHandle["pathStack"] = ArrayList<String>()
    }

    fun openFolder(path: String) {
        val stack = _state.value.pathStack + _state.value.currentPath
        _state.value = _state.value.copy(pathStack = stack)
        openFolderDirect(path)
        savedStateHandle["currentPath"] = _state.value.currentPath
        savedStateHandle["pathStack"] = ArrayList(_state.value.pathStack)
    }

    fun goBack(): Boolean {
        if (_state.value.pathStack.isEmpty()) {
            buildRoot()
            return true
        }
        val prev = _state.value.pathStack.last()
        val newStack = _state.value.pathStack.dropLast(1)
        if (prev.isEmpty()) {
            buildRoot()
            _state.value = _state.value.copy(pathStack = newStack)
            savedStateHandle["pathStack"] = ArrayList(newStack)
            return true
        }
        _state.value = _state.value.copy(pathStack = newStack)
        openFolderDirect(prev)
        savedStateHandle["currentPath"] = _state.value.currentPath
        savedStateHandle["pathStack"] = ArrayList(newStack)
        return true
    }
    
    private fun openFolderDirect(path: String) {
        val folderItems = allItems.filter { it.parentPath == path }
        val subfolders = allItems
            .filter { it.parentPath.startsWith("$path/") && it.parentPath.count { c -> c == '/' } == path.count { c -> c == '/' } + 1 }
            .map { it.parentPath }
            .toSet()
            .sorted()
            .map { sp ->
                val name = File(sp).name
                val count = allItems.count { it.parentPath == sp }
                FolderNode(path = sp, name = name, isDirectory = true, trackCount = count)
            }
        currentFiles = folderItems
        val sorted = applySort(folderItems, _state.value.sortMode, _state.value.sortOrder)
        _state.value = _state.value.copy(
            currentPath = path,
            folders = subfolders,
            files = sorted
        )
    }
    
    fun setSortMode(mode: SortMode) {
        if (_state.value.sortMode == mode) {
            val newOrder = if (_state.value.sortOrder == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC
            _state.value = _state.value.copy(sortOrder = newOrder)
            savedStateHandle["sortOrder"] = newOrder.name
        } else {
            _state.value = _state.value.copy(sortMode = mode, sortOrder = SortOrder.ASC)
            savedStateHandle["sortMode"] = mode.name
            savedStateHandle["sortOrder"] = SortOrder.ASC.name
        }
        if (currentFiles.isNotEmpty()) {
            val sorted = applySort(currentFiles, _state.value.sortMode, _state.value.sortOrder)
            _state.value = _state.value.copy(files = sorted)
        }
    }
    
    private fun applySort(items: List<AudioItem>, mode: SortMode, order: SortOrder): List<AudioItem> {
        val sorted = when (mode) {
            SortMode.FILE_NAME -> {
                val list = items.sortedBy { it.fileName.lowercase() }
                if (order == SortOrder.DESC) list.reversed() else list
            }
            SortMode.TRACK_TITLE -> {
                val list = items.sortedBy { 
                    if (it.title.isNotBlank()) it.title.lowercase() else it.fileName.lowercase() 
                }
                if (order == SortOrder.DESC) list.reversed() else list
            }
            SortMode.BPM -> {
                val map = _state.value.bpmByTrackId
                val comparator = java.util.Comparator<AudioItem> { a, b ->
                    val ba = map[a.id.toString()]
                    val bb = map[b.id.toString()]
                    when {
                        ba == null && bb == null -> a.fileName.lowercase().compareTo(b.fileName.lowercase())
                        ba == null -> 1    // без BPM — всегда в конце
                        bb == null -> -1
                        else -> if (order == SortOrder.ASC) ba.compareTo(bb) else bb.compareTo(ba)
                    }
                }
                items.sortedWith(comparator)
            }
        }
        return sorted
    }
}
