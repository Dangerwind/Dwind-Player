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
    /** Пути накопителей, чтобы заголовок и корень показывали их названия. */
    val volumePaths: Set<String> = emptySet(),
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

    /** Все каталоги с музыкой, включая промежуточные, где файлов нет. */
    private var directories: Set<String> = emptySet()

    /** Прямые подкаталоги: родитель → список путей, уже отсортированный по имени. */
    private var childIndex: Map<String, List<String>> = emptyMap()

    /** Сколько файлов лежит непосредственно в каталоге. */
    private var directCount: Map<String, Int> = emptyMap()

    /** Сколько файлов лежит в каталоге и во всех вложенных. */
    private var subtreeCount: Map<String, Int> = emptyMap()

    /** Корни накопителей: внутренняя память, SD-карта и т. п. */
    private var volumePaths: Set<String> = emptySet()

    /** Первичная загрузка уже была — при возврате в приложение можно обновлять молча. */
    private var loadedOnce = false
    private var refreshJob: Job? = null

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
        if (!granted) {
            _state.value = _state.value.copy(hasPermission = false)
            return
        }
        _state.value = _state.value.copy(hasPermission = true)
        if (allItems.isEmpty() && !loadedOnce) loadRoot() else refresh()
    }

    /** Первичная загрузка библиотеки — с индикатором. */
    fun loadRoot() {
        if (refreshJob?.isActive == true) return
        refreshJob = startLoad(showSpinner = true)
    }

    /**
     * Молчаливое обновление: перечитывает MediaStore и перестраивает дерево,
     * не прерывая воспроизведение и не показывая спиннер.
     * Нужно, чтобы музыка, скопированная в другую папку, появлялась сразу.
     */
    fun refresh() {
        if (!_state.value.hasPermission) return
        if (refreshJob?.isActive == true) return
        refreshJob = startLoad(showSpinner = false)
    }

    /** Обновление при возврате в приложение — только после первой загрузки. */
    fun refreshIfNeeded() {
        if (loadedOnce) refresh()
    }

    private fun startLoad(showSpinner: Boolean): Job = viewModelScope.launch {
        if (showSpinner) _state.value = _state.value.copy(isLoading = true)
        try {
            val items = repo.getAllAudio()
            allItems = items
            storeTracks(items)
            rebuildIndex()
            restoreCurrentView()
            loadedOnce = true
        } finally {
            if (showSpinner) _state.value = _state.value.copy(isLoading = false)
        }
    }

    /** Сохраняем треки в Room, чтобы плейлисты могли делать JOIN и искать треки. */
    private suspend fun storeTracks(items: List<AudioItem>) {
        if (items.isEmpty()) return
        val trackEntities = items.map { item ->
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
    }

    /**
     * Строит дерево каталогов по путям файлов из MediaStore.
     * Промежуточные папки без музыки тоже попадают в дерево — по ним можно ходить,
     * как в обычном файловом менеджере.
     */
    private fun rebuildIndex() {
        val dirs = LinkedHashSet<String>()
        val volumes = LinkedHashSet<String>()
        val direct = HashMap<String, Int>()

        for (item in allItems) {
            val parent = item.parentPath.ifEmpty { "/" }
            direct[parent] = (direct[parent] ?: 0) + 1
            val volume = volumeRootOf(parent)
            if (volume != null) volumes.add(volume)
            var dir = parent
            while (true) {
                if (dir != "/") dirs.add(dir)
                if (volume != null && dir == volume) break
                val up = parentDirOf(dir) ?: break
                // Корень файловой системы не показываем — на верхнем уровне
                // выводим накопители или их каталоги.
                if (up == "/") break
                dir = up
            }
        }
        dirs.addAll(volumes)

        // Счётчики: сначала прямые файлы, затем снизу вверх суммируем вложенные.
        val subtree = HashMap<String, Int>()
        for (dir in dirs) subtree[dir] = direct[dir] ?: 0
        for (dir in dirs.sortedByDescending { it.count { c -> c == '/' } }) {
            val up = parentDirOf(dir) ?: continue
            if (up in dirs) subtree[up] = (subtree[up] ?: 0) + (subtree[dir] ?: 0)
        }

        val children = HashMap<String, MutableList<String>>()
        for (dir in dirs) {
            // Накопители — корни дерева, а не вложенные каталоги /storage.
            if (dir in volumes) continue
            val up = parentDirOf(dir) ?: continue
            if (up in dirs) children.getOrPut(up) { mutableListOf() }.add(dir)
        }
        for (list in children.values) list.sortBy { File(it).name.lowercase() }

        directories = dirs
        volumePaths = volumes
        directCount = direct
        subtreeCount = subtree
        childIndex = children
    }

    /** Корень накопителя для пути из MediaStore (например, /storage/emulated/0). */
    private fun volumeRootOf(path: String): String? {
        if (!path.startsWith("/")) return null
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated") {
            return "/storage/emulated/${parts[2]}"
        }
        if (parts.size >= 2 && parts[0] == "storage") {
            return "/storage/${parts[1]}"
        }
        return null
    }

    private fun parentDirOf(path: String): String? {
        if (path.isEmpty() || path == "/") return null
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return when {
            index < 0 -> null
            index == 0 -> "/"
            else -> trimmed.substring(0, index)
        }
    }

    private fun canOpen(path: String): Boolean = path.isEmpty() || path in directories

    private fun folderNode(path: String): FolderNode = FolderNode(
        path = path,
        name = if (path in volumePaths) "" else File(path).name.ifEmpty { path },
        isDirectory = true,
        trackCount = subtreeCount[path] ?: 0,
        isVolume = path in volumePaths
    )

    private fun childFolders(path: String): List<FolderNode> =
        childIndex[path].orEmpty().map { folderNode(it) }

    private fun buildRoot() {
        val volumes = volumePaths.sortedWith(
            compareBy<String> { if (it.startsWith("/storage/emulated/")) 0 else 1 }.thenBy { it }
        )
        // Каталоги вне /storage (например, /sdcard у некоторых прошивок) — отдельные корни.
        val detached = directories
            .filter { it !in volumePaths && volumeRootOf(it) == null }
            .filter { parentDirOf(it) !in directories }
            .sorted()

        val nodes = when {
            volumes.isEmpty() -> emptyList()
            // Один накопитель: сразу показываем его верхние папки, без лишнего шага.
            volumes.size == 1 -> {
                val volume = volumes.first()
                val kids = childFolders(volume)
                if ((directCount[volume] ?: 0) > 0) listOf(folderNode(volume)) + kids else kids
            }
            else -> volumes.map { folderNode(it) }
        } + detached.map { folderNode(it) }

        currentFiles = emptyList()
        _state.value = _state.value.copy(
            currentPath = "",
            folders = nodes,
            files = emptyList(),
            pathStack = emptyList(),
            volumePaths = volumePaths
        )
        savedStateHandle["currentPath"] = ""
        savedStateHandle["pathStack"] = ArrayList<String>()
    }

    /** Возвращает вид к сохранённому пути, если он ещё существует, иначе к корню. */
    private fun restoreCurrentView() {
        val path = _state.value.currentPath
        if (!canOpen(path) || path.isEmpty()) {
            buildRoot()
            return
        }
        val stack = _state.value.pathStack.filter { canOpen(it) }
        _state.value = _state.value.copy(pathStack = stack)
        savedStateHandle["pathStack"] = ArrayList(stack)
        openFolderDirect(path)
    }

    fun openFolder(path: String) {
        val stack = _state.value.pathStack + _state.value.currentPath
        _state.value = _state.value.copy(pathStack = stack)
        openFolderDirect(path)
        savedStateHandle["currentPath"] = _state.value.currentPath
        savedStateHandle["pathStack"] = ArrayList(_state.value.pathStack)
    }

    fun goBack(): Boolean {
        val stack = _state.value.pathStack
        if (stack.isEmpty()) {
            buildRoot()
            return true
        }
        val prev = stack.last()
        val newStack = stack.dropLast(1)
        if (prev.isEmpty()) {
            buildRoot()
            _state.value = _state.value.copy(pathStack = newStack)
            savedStateHandle["pathStack"] = ArrayList(newStack)
            return true
        }
        if (!canOpen(prev)) {
            // Папка исчезла, пока приложение было свёрнуто — показываем корень.
            buildRoot()
            return true
        }
        _state.value = _state.value.copy(pathStack = newStack)
        openFolderDirect(prev)
        savedStateHandle["currentPath"] = _state.value.currentPath
        savedStateHandle["pathStack"] = ArrayList(newStack)
        return true
    }

    private fun openFolderDirect(path: String) {
        if (!canOpen(path) || path.isEmpty()) {
            buildRoot()
            return
        }
        val folderItems = allItems.filter { it.parentPath.ifEmpty { "/" } == path }
        currentFiles = folderItems
        val sorted = applySort(folderItems, _state.value.sortMode, _state.value.sortOrder)
        _state.value = _state.value.copy(
            currentPath = path,
            folders = childFolders(path),
            files = sorted,
            volumePaths = volumePaths
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
