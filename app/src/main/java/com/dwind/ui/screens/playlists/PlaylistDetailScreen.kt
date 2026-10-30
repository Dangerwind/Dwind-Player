package com.dwind.ui.screens.playlists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dwind.R
import com.dwind.settings.AppSettings
import com.dwind.ui.components.BpmEditTarget
import com.dwind.ui.components.EmptyState
import com.dwind.ui.components.IconBadge
import com.dwind.ui.genreLabel
import com.dwind.ui.help.helpTarget
import com.dwind.ui.viewmodel.PlaylistDetailViewModel
import com.dwind.ui.viewmodel.PlayerViewModel

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    initialPlaylistName: String = "",
    playerInset: Dp = 0.dp,
    onBack: () -> Unit,
    viewModel: PlaylistDetailViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val playerViewModel: PlayerViewModel = viewModel()
    val playerState by playerViewModel.uiState.collectAsState()
    val settings by AppSettings.state.collectAsState()
    var showTrackPicker by rememberSaveable { mutableStateOf(false) }
    var bpmEditor by remember { mutableStateOf<com.dwind.ui.components.BpmEditTarget?>(null) }
    var bpmWasPlaying by remember { mutableStateOf(false) }
    var showReanalyzeDialog by remember { mutableStateOf(false) }
    var currentDisplayName by remember(playlistId) { mutableStateOf(initialPlaylistName) }
    
    LaunchedEffect(playlistId) {
        viewModel.loadPlaylist(playlistId)
    }
    
    LaunchedEffect(state.playlist) {
        state.playlist?.let { playlist ->
            if (playlist.id == playlistId) {
                currentDisplayName = playlist.name
            }
        }
    }
    
    val displayName = if (state.playlist?.id == playlistId) {
        state.playlist?.name ?: currentDisplayName
    } else {
        currentDisplayName
    }
    
    if (showTrackPicker) {
        PlaylistTrackPickerScreen(
            modifier = Modifier.fillMaxSize(),
            playerInset = playerInset,
            onBack = { showTrackPicker = false },
            onTracksSelected = { selectedItems ->
                viewModel.addTracks(playlistId, selectedItems)
                showTrackPicker = false
            }
        )
        return
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // Внешний Scaffold (DwindApp) уже учитывает системные отступы — здесь их
        // обнуляем, иначе они применяются дважды и кнопка «+» уезжает вверх.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { 
                    showTrackPicker = true 
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .padding(bottom = playerInset)
                    .helpTarget("addTracks")
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_tracks))
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    maxLines = 1
                )
                if (state.tracks.isNotEmpty()) {
                    if (state.bpmRunning) {
                        Button(
                            onClick = { viewModel.cancelBpmAnalysis() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(15.dp))
                            Text(" ${state.bpmDone}/${state.bpmTotal}", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Button(
                            onClick = {
                                if (state.tracks.isEmpty()) return@Button
                                if (viewModel.allAnalyzedInPlaylist()) {
                                    showReanalyzeDialog = true
                                } else {
                                    viewModel.analyzeBpmForPlaylist(force = false)
                                }
                            },
                            modifier = Modifier.helpTarget("playlistBpm"),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(15.dp))
                            Text(" BPM", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            
            if (state.tracks.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    title = stringResource(R.string.playlist_empty_title),
                    subtitle = stringResource(R.string.playlist_empty_subtitle),
                    modifier = Modifier.fillMaxSize(),
                    action = {
                        Button(
                            onClick = {
                                showTrackPicker = true
                            },
                            modifier = Modifier.helpTarget("addTracks")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("  ${stringResource(R.string.add_tracks)}")
                        }
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().helpTarget("list"),
                    contentPadding = PaddingValues(start = 8.dp, top = 4.dp, end = 8.dp, bottom = if (playerInset > 0.dp) playerInset + 56.dp else 88.dp)
                ) {
                    items(state.tracks) { track ->
                        val isCurrent = playerState.currentTrackId == track.id
                        val isFirstRow = track.id == state.tracks.firstOrNull()?.id
                        val bpmAnchor = if (isFirstRow) Modifier.helpTarget("bpmText") else Modifier
                        val detectAnchor = if (isFirstRow) Modifier.helpTarget("bpmDetect") else Modifier
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 3.dp)
                                .clickable { playerViewModel.playTrackList(track.id, state.tracks) },
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
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
                                    icon = if (isCurrent) Icons.Filled.PlayArrow else Icons.Filled.MusicNote,
                                    tint = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.primary,
                                    container = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                )
                                Column(
                                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                                ) {
                                    Text(
                                        text = track.title ?: track.fileName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        color = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    track.artist?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                                val bpm = state.bpmByTrackId[track.id]
                                val info = state.bpmInfoByTrackId[track.id]
                                val busy = state.bpmBusyIds.contains(track.id)
                                if (busy) {
                                    Text(
                                        text = stringResource(R.string.calculating_bpm),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else if (bpm != null) {
                                    val bpmColor = com.dwind.ui.components.bpmConfidenceColor(
                                        info?.confidence, info?.manual == true
                                    )
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        modifier = Modifier
                                            .then(bpmAnchor)
                                            .clickable {
                                            bpmWasPlaying = playerViewModel.isPlayingNow()
                                            if (!isCurrent) {
                                                playerViewModel.playTrackList(track.id, state.tracks)
                                            } else {
                                                playerViewModel.ensurePlaying()
                                            }
                                            bpmEditor = BpmEditTarget(
                                                trackId = track.id,
                                                uri = track.uri,
                                                fileName = track.fileName,
                                                title = track.title ?: track.fileName,
                                                currentBpm = bpm
                                            )
                                        }
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
                                            .then(bpmAnchor)
                                            .clickable {
                                            bpmWasPlaying = playerViewModel.isPlayingNow()
                                            if (!isCurrent) {
                                                playerViewModel.playTrackList(track.id, state.tracks)
                                            } else {
                                                playerViewModel.ensurePlaying()
                                            }
                                            bpmEditor = BpmEditTarget(
                                                trackId = track.id,
                                                uri = track.uri,
                                                fileName = track.fileName,
                                                title = track.title ?: track.fileName,
                                                currentBpm = null
                                            )
                                        }
                                    )
                                }
                                if (busy) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(
                                        onClick = { viewModel.detectBpm(track, force = bpm != null) },
                                        modifier = detectAnchor
                                    ) {
                                        Icon(Icons.Filled.Speed, contentDescription = stringResource(R.string.cd_detect_bpm))
                                    }
                                }
                                IconButton(
                                    onClick = { viewModel.removeTrack(playlistId, track.id) }
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_remove_from_playlist))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    bpmEditor?.let { target ->
        com.dwind.ui.components.BpmEditDialog(
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
            text = { Text(stringResource(R.string.reanalyze_playlist_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showReanalyzeDialog = false
                    viewModel.analyzeBpmForPlaylist(force = true)
                }) { Text(stringResource(R.string.action_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showReanalyzeDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
