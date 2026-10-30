package com.dwind.ui.screens.folders

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dwind.R
import com.dwind.settings.AppSettings
import com.dwind.ui.components.BpmEditDialog
import com.dwind.ui.components.BpmEditTarget
import com.dwind.ui.components.EmptyState
import com.dwind.ui.components.IconBadge
import com.dwind.ui.genreLabel
import com.dwind.ui.help.helpTarget
import com.dwind.ui.viewmodel.FoldersBrowserViewModel
import com.dwind.ui.viewmodel.PlayerViewModel
import com.dwind.ui.viewmodel.SortMode
import com.dwind.ui.viewmodel.SortOrder
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun FoldersScreen(
    modifier: Modifier = Modifier,
    playerInset: Dp = 0.dp,
    compactWidth: Boolean = false,
    compactHeight: Boolean = false,
    viewModel: FoldersBrowserViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val playerViewModel: PlayerViewModel = viewModel()
    val playerState by playerViewModel.uiState.collectAsState()
    val settings by AppSettings.state.collectAsState()
    var bpmEditor by remember { mutableStateOf<BpmEditTarget?>(null) }
    var bpmWasPlaying by remember { mutableStateOf(false) }
    var showReanalyzeDialog by remember { mutableStateOf(false) }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setPermission(granted)
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        viewModel.setPermission(granted)
        if (!granted) {
            launcher.launch(permission)
        }
    }

    val showBack = state.currentPath.isNotEmpty() || state.pathStack.isNotEmpty()
    val foldersTitle = stringResource(R.string.folders_title)
    val titleText = if (state.currentPath.isEmpty()) foldersTitle
        else File(state.currentPath).name.ifEmpty { state.currentPath }
    val hasItems = state.files.isNotEmpty() || state.folders.isNotEmpty() ||
        (state.currentPath.isNotEmpty() && state.folders.isEmpty())

    Column(modifier = modifier.fillMaxSize()) {
        if (compactHeight) {
            // Ландшафт телефона: заголовок, фильтры и кнопка BPM — в одну невысокую строку.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (showBack) {
                    IconButton(
                        onClick = { viewModel.goBack() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                if (hasItems) {
                    FolderSortChips(state.sortMode, state.sortOrder, compact = true) { viewModel.setSortMode(it) }
                    FolderBpmButton(
                        running = state.bpmRunning,
                        done = state.bpmDone,
                        total = state.bpmTotal,
                        enabled = state.files.isNotEmpty(),
                        modifier = Modifier.helpTarget("bpmButton"),
                        compact = true,
                        onAnalyze = {
                            if (state.files.isEmpty()) return@FolderBpmButton
                            if (viewModel.allAnalyzedInCurrentFolder()) {
                                showReanalyzeDialog = true
                            } else {
                                viewModel.analyzeBpmForCurrentFolder(force = false)
                            }
                        },
                        onCancel = { viewModel.cancelBpmAnalysis() }
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showBack) {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 4.dp).weight(1f),
                    maxLines = 1
                )
            }
        }

        if (!compactHeight && hasItems) {
            if (compactWidth) {
                // Узкий экран: фильтры и кнопка BPM переносятся на новую строку, если не влезают.
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FolderSortChips(state.sortMode, state.sortOrder) { viewModel.setSortMode(it) }
                    FolderBpmButton(
                        running = state.bpmRunning,
                        done = state.bpmDone,
                        total = state.bpmTotal,
                        enabled = state.files.isNotEmpty(),
                        modifier = Modifier.helpTarget("bpmButton"),
                        onAnalyze = {
                            if (state.files.isEmpty()) return@FolderBpmButton
                            if (viewModel.allAnalyzedInCurrentFolder()) {
                                showReanalyzeDialog = true
                            } else {
                                viewModel.analyzeBpmForCurrentFolder(force = false)
                            }
                        },
                        onCancel = { viewModel.cancelBpmAnalysis() }
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FolderSortChips(state.sortMode, state.sortOrder) { viewModel.setSortMode(it) }
                    Spacer(modifier = Modifier.weight(1f))
                    FolderBpmButton(
                        running = state.bpmRunning,
                        done = state.bpmDone,
                        total = state.bpmTotal,
                        enabled = state.files.isNotEmpty(),
                        modifier = Modifier.helpTarget("bpmButton"),
                        onAnalyze = {
                            if (state.files.isEmpty()) return@FolderBpmButton
                            if (viewModel.allAnalyzedInCurrentFolder()) {
                                showReanalyzeDialog = true
                            } else {
                                viewModel.analyzeBpmForCurrentFolder(force = false)
                            }
                        },
                        onCancel = { viewModel.cancelBpmAnalysis() }
                    )
                }
            }
        }

        if (state.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (!state.hasPermission) {
            EmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = stringResource(R.string.need_music_access_title),
                subtitle = stringResource(R.string.need_music_access_subtitle),
                modifier = Modifier.fillMaxSize()
            )
        } else if (state.folders.isEmpty() && state.files.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Folder,
                title = stringResource(R.string.empty_here_title),
                subtitle = stringResource(R.string.empty_here_subtitle),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().helpTarget("list"),
                contentPadding = PaddingValues(top = 6.dp, bottom = playerInset + 12.dp)
            ) {
                items(state.folders, key = { "dir_${it.path}" }) { folder ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clickable { viewModel.openFolder(folder.path) },
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconBadge(
                                icon = Icons.Filled.Folder,
                                tint = MaterialTheme.colorScheme.tertiary,
                                container = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(
                                    text = if (folder.path == "/") stringResource(R.string.all_files) else folder.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1
                                )
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.track_count, folder.trackCount, folder.trackCount
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                items(state.files, key = { it.id }) { item ->
                    val isCurrent = playerState.currentTrackId == item.id.toString()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clickable { playerViewModel.playTrack(item, state.files) },
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = if (compactWidth) 10.dp else 12.dp,
                                    top = if (compactWidth) 8.dp else 12.dp,
                                    end = if (compactWidth) 4.dp else 12.dp,
                                    bottom = if (compactWidth) 8.dp else 12.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconBadge(
                                icon = if (isCurrent) Icons.Filled.PlayArrow else Icons.Filled.MusicNote,
                                tint = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.primary,
                                container = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                size = if (compactWidth) 36 else 44
                            )
                            val bpm = state.bpmByTrackId[item.id.toString()]
                            val info = state.bpmInfoByTrackId[item.id.toString()]
                            val busy = state.bpmBusyIds.contains(item.id.toString())
                            val isFirstRow = item.id == state.files.firstOrNull()?.id
                            val bpmAnchor = if (isFirstRow) Modifier.helpTarget("bpmText") else Modifier
                            val detectAnchor = if (isFirstRow) Modifier.helpTarget("bpmDetect") else Modifier
                            val bpmColor = com.dwind.ui.components.bpmConfidenceColor(
                                info?.confidence, info?.manual == true
                            )
                            // Тап по BPM открывает ручной редактор и запускает воспроизведение.
                            val openBpmEditor: () -> Unit = {
                                bpmWasPlaying = playerViewModel.isPlayingNow()
                                if (playerState.currentTrackId != item.id.toString()) {
                                    playerViewModel.playTrack(item, state.files)
                                } else {
                                    playerViewModel.ensurePlaying()
                                }
                                bpmEditor = BpmEditTarget(
                                    trackId = item.id.toString(),
                                    uri = item.uri,
                                    fileName = item.fileName,
                                    title = item.fileName,
                                    currentBpm = bpm
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = if (compactWidth) 10.dp else 14.dp)
                            ) {
                                val displayText = when (state.sortMode) {
                                    SortMode.FILE_NAME -> item.fileName
                                    SortMode.TRACK_TITLE -> if (item.title.isNotBlank()) item.title else item.fileName
                                    SortMode.BPM -> if (item.title.isNotBlank()) item.title else item.fileName
                                }
                                Text(
                                    text = displayText,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if ((state.sortMode == SortMode.TRACK_TITLE || state.sortMode == SortMode.BPM) && !item.artist.isNullOrBlank()) {
                                    Text(
                                        text = item.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                // В узком портрете жанр переезжает под название, освобождая правый край под кнопку BPM.
                                if (compactWidth && settings.showGenre) {
                                    info?.genre?.let { g ->
                                        Text(
                                            text = genreLabel(g),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (compactWidth) {
                                // Узкий экран: BPM и кнопка авто-BPM — друг под другом, название получает максимум ширины.
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (busy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text(
                                            text = if (bpm != null) "${bpm.toInt()} BPM" else stringResource(R.string.bpm_none),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (bpm != null) bpmColor
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .padding(horizontal = 2.dp)
                                                .clickable { openBpmEditor() }
                                                .then(bpmAnchor)
                                        )
                                        IconButton(
                                            onClick = { viewModel.detectBpm(item, force = bpm != null) },
                                            modifier = Modifier.size(32.dp).then(detectAnchor)
                                        ) {
                                            Icon(
                                                Icons.Filled.Speed,
                                                contentDescription = stringResource(R.string.cd_detect_bpm),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                if (busy) {
                                    Text(
                                        text = stringResource(R.string.calculating_bpm),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else if (bpm != null) {
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        modifier = Modifier.clickable { openBpmEditor() }.then(bpmAnchor)
                                    ) {
                                        Text(
                                            text = "${bpm.toInt()} BPM",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = bpmColor
                                        )
                                        if (settings.showGenre) {
                                            info?.genre?.let { g ->
                                                Text(
                                                    text = genreLabel(g),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = stringResource(R.string.bpm_none),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .padding(horizontal = 6.dp)
                                            .clickable { openBpmEditor() }
                                            .then(bpmAnchor)
                                    )
                                }
                                if (busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    IconButton(
                                        onClick = { viewModel.detectBpm(item, force = bpm != null) },
                                        modifier = detectAnchor
                                    ) {
                                        Icon(Icons.Filled.Speed, contentDescription = stringResource(R.string.cd_detect_bpm))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Диалог ручной установки BPM
    bpmEditor?.let { target ->
        BpmEditDialog(
            target = target,
            onDismiss = {
                bpmEditor = null
                if (!bpmWasPlaying) playerViewModel.pause()
            },
            onSave = { v, genre ->
                viewModel.setManualBpm(target.trackId, target.uri, target.fileName, v, genre)
                bpmEditor = null
                if (!bpmWasPlaying) playerViewModel.pause()
            }
        )
    }

    if (showReanalyzeDialog) {
        AlertDialog(
            onDismissRequest = { showReanalyzeDialog = false },
            title = { Text(stringResource(R.string.reanalyze_title)) },
            text = { Text(stringResource(R.string.reanalyze_folder_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showReanalyzeDialog = false
                    viewModel.clearAndReanalyzeCurrentFolder()
                }) { Text(stringResource(R.string.action_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showReanalyzeDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun SortChip(
    label: String,
    selected: Boolean,
    order: SortOrder,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val text = if (selected) "$label ${if (order == SortOrder.ASC) "↑" else "↓"}" else label
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.then(if (compact) Modifier.height(30.dp) else Modifier),
        label = {
            Text(
                text = text,
                style = if (compact) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
    )
}

/** Три чипа сортировки одним блоком — используются в Row и FlowRow. */
@Composable
private fun FolderSortChips(
    sortMode: SortMode,
    sortOrder: SortOrder,
    compact: Boolean = false,
    onSelect: (SortMode) -> Unit
) {
    SortChip(stringResource(R.string.sort_name), sortMode == SortMode.FILE_NAME, sortOrder, compact, Modifier.helpTarget("sortByName")) { onSelect(SortMode.FILE_NAME) }
    SortChip(stringResource(R.string.sort_title), sortMode == SortMode.TRACK_TITLE, sortOrder, compact, Modifier.helpTarget("sortByTitle")) { onSelect(SortMode.TRACK_TITLE) }
    SortChip(stringResource(R.string.sort_bpm), sortMode == SortMode.BPM, sortOrder, compact, Modifier.helpTarget("sortByBpm")) { onSelect(SortMode.BPM) }
}

/** Кнопка пакетного анализа BPM: прогресс с возможностью отмены либо запуск. */
@Composable
private fun FolderBpmButton(
    running: Boolean,
    done: Int,
    total: Int,
    enabled: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit
) {
    val padding = if (compact) PaddingValues(horizontal = 10.dp, vertical = 2.dp)
    else PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    val textStyle = if (compact) MaterialTheme.typography.labelMedium
    else MaterialTheme.typography.labelLarge
    if (running) {
        Button(
            onClick = onCancel,
            modifier = modifier.then(if (compact) Modifier.height(30.dp) else Modifier),
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(if (compact) 13.dp else 15.dp))
            Text(text = " $done/$total", style = textStyle)
        }
    } else {
        Button(
            onClick = onAnalyze,
            enabled = enabled,
            modifier = modifier.then(if (compact) Modifier.height(30.dp) else Modifier),
            contentPadding = padding
        ) {
            Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(if (compact) 13.dp else 15.dp))
            Text(text = " BPM", style = textStyle)
        }
    }
}
