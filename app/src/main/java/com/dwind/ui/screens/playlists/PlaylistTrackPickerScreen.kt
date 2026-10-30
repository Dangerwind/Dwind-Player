package com.dwind.ui.screens.playlists

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dwind.R
import com.dwind.data.model.AudioItem
import com.dwind.ui.components.EmptyState
import com.dwind.ui.components.IconBadge
import com.dwind.ui.rememberWindowSizeInfo
import com.dwind.ui.viewmodel.FoldersBrowserViewModel
import com.dwind.ui.viewmodel.SortMode
import com.dwind.ui.viewmodel.SortOrder
import java.io.File

@Composable
fun PlaylistTrackPickerScreen(
    modifier: Modifier = Modifier,
    playerInset: Dp = 0.dp,
    onBack: () -> Unit,
    onTracksSelected: (List<AudioItem>) -> Unit,
    viewModel: FoldersBrowserViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val compactHeight = rememberWindowSizeInfo().isShortHeight
    // Выбор храним как карту id -> элемент, чтобы он сохранялся при переходе между папками.
    // mutableStateMapOf — snapshot-коллекция: изменения сразу видны Compose
    val selectedItems = remember { mutableStateMapOf<Long, AudioItem>() }

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

    val addSelectedAndClose: () -> Unit = {
        if (selectedItems.isNotEmpty()) {
            onTracksSelected(selectedItems.values.toList())
        } else {
            onBack()
        }
    }

    val pickerHasItems = state.files.isNotEmpty() || (state.currentPath.isNotEmpty() && state.folders.isEmpty())

    Column(modifier = modifier.fillMaxSize()) {
        // Заголовок (в ландшафте телефона сливается со строкой сортировки в одну невысокую).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (compactHeight) 8.dp else 8.dp,
                    end = if (compactHeight) 4.dp else 8.dp,
                    top = if (compactHeight) 2.dp else 8.dp,
                    bottom = if (compactHeight) 2.dp else 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (compactHeight) Arrangement.spacedBy(6.dp) else Arrangement.Start
        ) {
            IconButton(
                onClick = {
                    if (state.currentPath.isNotEmpty() || state.pathStack.isNotEmpty()) {
                        viewModel.goBack()
                    } else {
                        addSelectedAndClose()
                    }
                },
                modifier = if (compactHeight) Modifier.size(36.dp) else Modifier
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    modifier = if (compactHeight) Modifier.size(20.dp) else Modifier
                )
            }
            Text(
                text = if (state.currentPath.isEmpty()) stringResource(R.string.select_tracks)
                    else File(state.currentPath).name.ifEmpty { state.currentPath },
                style = if (compactHeight) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
                maxLines = 1
            )
            if (compactHeight && pickerHasItems) {
                // В ландшафте чипы сортировки переносим в строку заголовка.
                PickerSortChip(stringResource(R.string.sort_name), state.sortMode == SortMode.FILE_NAME, state.sortOrder, compact = true) {
                    viewModel.setSortMode(SortMode.FILE_NAME)
                }
                PickerSortChip(stringResource(R.string.sort_title), state.sortMode == SortMode.TRACK_TITLE, state.sortOrder, compact = true) {
                    viewModel.setSortMode(SortMode.TRACK_TITLE)
                }
                PickerSortChip(stringResource(R.string.sort_bpm), state.sortMode == SortMode.BPM, state.sortOrder, compact = true) {
                    viewModel.setSortMode(SortMode.BPM)
                }
            }
            TextButton(onClick = {
                val currentFolder = state.files
                val allCurrentSelected = currentFolder.isNotEmpty() && currentFolder.all { selectedItems.containsKey(it.id) }
                if (allCurrentSelected) {
                    currentFolder.forEach { selectedItems.remove(it.id) }
                } else {
                    currentFolder.forEach { selectedItems.put(it.id, it) }
                }
            }) {
                Text(
                    text = if (state.files.isNotEmpty() && state.files.all { selectedItems.containsKey(it.id) })
                        stringResource(R.string.deselect_all) else stringResource(R.string.select_all),
                    style = if (compactHeight) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
                )
            }
        }

        // Сортировка (в ландшафте уже перенесена в заголовок)
        if (!compactHeight && pickerHasItems) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PickerSortChip(stringResource(R.string.sort_name), state.sortMode == SortMode.FILE_NAME, state.sortOrder) {
                    viewModel.setSortMode(SortMode.FILE_NAME)
                }
                PickerSortChip(stringResource(R.string.sort_title), state.sortMode == SortMode.TRACK_TITLE, state.sortOrder) {
                    viewModel.setSortMode(SortMode.TRACK_TITLE)
                }
                PickerSortChip(stringResource(R.string.sort_bpm), state.sortMode == SortMode.BPM, state.sortOrder) {
                    viewModel.setSortMode(SortMode.BPM)
                }
            }
        }

        // Список
        if (state.isLoading) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (!state.hasPermission) {
            EmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = stringResource(R.string.need_music_access_title),
                subtitle = stringResource(R.string.need_music_access_picker_subtitle),
                modifier = Modifier.weight(1f)
            )
        } else if (state.folders.isEmpty() && state.files.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Folder,
                title = stringResource(R.string.empty_here_title),
                subtitle = stringResource(R.string.empty_here_subtitle),
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 6.dp, bottom = 8.dp)
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
                items(state.files, key = { "file_${it.id}" }) { item ->
                    val isSelected = selectedItems.containsKey(item.id)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clickable {
                                if (selectedItems.containsKey(item.id)) {
                                    selectedItems.remove(item.id)
                                } else {
                                    selectedItems.put(item.id, item)
                                }
                            },
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconBadge(
                                icon = if (isSelected) Icons.Filled.Check else Icons.Filled.MusicNote,
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.primary,
                                container = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                                val displayText = when (state.sortMode) {
                                    SortMode.FILE_NAME -> item.fileName
                                    SortMode.TRACK_TITLE -> if (item.title.isNotBlank()) item.title else item.fileName
                                    SortMode.BPM -> if (item.title.isNotBlank()) item.title else item.fileName
                                }
                                Text(
                                    text = displayText,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1
                                )
                                if ((state.sortMode == SortMode.TRACK_TITLE || state.sortMode == SortMode.BPM) && !item.artist.isNullOrBlank()) {
                                    Text(
                                        text = item.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Нижняя панель с кнопкой добавления
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (playerInset > 0.dp) playerInset + 8.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (selectedItems.isEmpty()) stringResource(R.string.mark_tracks)
                        else stringResource(R.string.selected_count, selectedItems.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { addSelectedAndClose() },
                    enabled = selectedItems.isNotEmpty()
                ) {
                    Text(stringResource(R.string.add_selected, selectedItems.size))
                }
            }
        }
    }
}

@Composable
private fun PickerSortChip(
    label: String,
    selected: Boolean,
    order: SortOrder,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val text = if (selected) "$label ${if (order == SortOrder.ASC) "↑" else "↓"}" else label
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = if (compact) Modifier.height(30.dp) else Modifier,
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
