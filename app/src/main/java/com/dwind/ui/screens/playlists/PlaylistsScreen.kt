package com.dwind.ui.screens.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dwind.data.local.entity.PlaylistEntity
import com.dwind.ui.components.EmptyState
import com.dwind.ui.components.IconBadge
import com.dwind.ui.help.helpTarget
import com.dwind.ui.viewmodel.PlaylistsViewModel

@Composable
fun PlaylistsScreen(
    modifier: Modifier = Modifier,
    playerInset: Dp = 0.dp,
    viewModel: PlaylistsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var selectedPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<PlaylistEntity?>(null) }

    val selectedPlaylist = selectedPlaylistId?.let { id -> uiState.playlists.find { it.id == id } }

    if (selectedPlaylist != null) {
        PlaylistDetailScreen(
            playlistId = selectedPlaylist.id,
            initialPlaylistName = selectedPlaylist.name,
            playerInset = playerInset,
            onBack = {
                selectedPlaylistId = null
            }
        )
        return
    } else if (selectedPlaylistId != null) {
        // Плейлист был удален, возвращаемся в список
        selectedPlaylistId = null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // Внешний Scaffold (DwindApp) уже учитывает системные отступы — здесь их
        // обнуляем, иначе они применяются дважды и кнопка «+» уезжает вверх.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .padding(bottom = playerInset)
                    .helpTarget("createPlaylist")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Создать плейлист")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = playerInset)
        ) {
            Text(
                text = "Мои плейлисты",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)
            )
            if (uiState.playlists.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    title = "Плейлистов нет",
                    subtitle = "Создайте плейлист, чтобы собирать треки под настроение.",
                    modifier = Modifier.fillMaxSize(),
                    action = {
                        Button(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier.helpTarget("createPlaylist")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("  Создать плейлист")
                        }
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(uiState.playlists, key = { it.id }) { playlist ->
                        val itemAnchor =
                            if (playlist.id == uiState.playlists.firstOrNull()?.id)
                                Modifier.helpTarget("playlistItem")
                            else Modifier
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .then(itemAnchor)
                                .clickable {
                                    selectedPlaylistId = playlist.id
                                },
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
                                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 14.dp).weight(1f),
                                    maxLines = 1
                                )
                                IconButton(onClick = {
                                    renameTarget = playlist
                                    renameName = playlist.name
                                }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Переименовать")
                                }
                                IconButton(onClick = { deleteTarget = playlist }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Удалить",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false; newPlaylistName = "" },
            title = { Text("Создать плейлист") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Название плейлиста") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createPlaylist(newPlaylistName)
                    showCreateDialog = false
                    newPlaylistName = ""
                }) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newPlaylistName = "" }) {
                    Text("Отмена")
                }
            }
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Переименовать плейлист") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    label = { Text("Название") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameName.isNotBlank()) {
                        viewModel.renamePlaylist(target.id, renameName)
                    }
                    renameTarget = null
                }) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить плейлист") },
            text = { Text("Удалить плейлист \"${target.name}\"? Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist(target)
                    deleteTarget = null
                }) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}
