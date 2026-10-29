package com.dwind.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.dwind.settings.AppSettings
import com.dwind.ui.help.helpTarget
import com.dwind.ui.viewmodel.PlayerUiState
import com.dwind.ui.viewmodel.PlayerViewModel

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Кнопка-переключатель плеера (перемешивание/повтор). Когда режим включён —
 * под иконкой виден цветной круг, так что состояние читается с первого взгляда.
 */
@Composable
private fun MiniToggleButton(
    active: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String
) {
    val activeColor = MaterialTheme.colorScheme.primary
    IconButton(
        onClick = onClick,
        modifier = if (active) {
            Modifier.background(activeColor.copy(alpha = 0.22f), CircleShape)
        } else {
            Modifier
        }
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) activeColor else MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * Мини-плеер у нижнего края.
 *
 * @param compact узкий экран (телефон в портрете): подпись трека выносится
 *   отдельной строкой над кнопками, иначе название не помещается рядом с ними.
 */
@Composable
fun MiniPlayer(
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val uiState by playerViewModel.uiState.collectAsState()
    val settings by AppSettings.state.collectAsState()
    var showPlaylistMenu by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    if (uiState.currentTitle.isEmpty()) return

    val duration = uiState.duration
    val position = uiState.position
    val realFraction = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val shownFraction = if (isDragging) dragFraction else realFraction
    val shownPosition = (shownFraction * duration).toLong().coerceAtLeast(0L)
    val durationState = rememberUpdatedState(duration)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .shadow(10.dp, RoundedCornerShape(18.dp), clip = false),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Компактный интерактивный прогресс-бар: тап или проводка = перемотка
            val barActiveColor = MaterialTheme.colorScheme.primary
            val barTrackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.28f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .pointerInput(playerViewModel) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val inset = 7.dp.toPx()
                                fun fracAt(x: Float): Float =
                                    if (size.width <= 2 * inset) 0f
                                    else ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                                var frac = fracAt(down.position.x)
                                val dur = durationState.value
                                if (dur > 0) {
                                    isDragging = true
                                    dragFraction = frac
                                }
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.pressed) {
                                        frac = fracAt(change.position.x)
                                        if (dur > 0) {
                                            dragFraction = frac
                                            change.consume()
                                        }
                                    } else {
                                        frac = fracAt(change.position.x)
                                        break
                                    }
                                }
                                if (dur > 0) {
                                    playerViewModel.seekTo((frac * dur).toLong())
                                    isDragging = false
                                }
                            }
                        }
                ) {
                    val cy = size.height / 2f
                    val inset = with(density) { 7.dp.toPx() }
                    val startX = inset
                    val endX = (size.width - inset).coerceAtLeast(startX)
                    val trackHeight = with(density) { 3.dp.toPx() }
                    drawRoundRect(
                        color = barTrackColor,
                        topLeft = Offset(startX, cy - trackHeight / 2f),
                        size = Size((endX - startX).coerceAtLeast(0f), trackHeight),
                        cornerRadius = CornerRadius(trackHeight / 2f)
                    )
                    val x = startX + (endX - startX) * shownFraction
                    if (shownFraction > 0f) {
                        drawRoundRect(
                            color = barActiveColor,
                            topLeft = Offset(startX, cy - trackHeight / 2f),
                            size = Size((x - startX).coerceAtLeast(0f), trackHeight),
                            cornerRadius = CornerRadius(trackHeight / 2f)
                        )
                    }
                    drawCircle(color = barActiveColor, radius = if (isDragging) with(density) { 6.dp.toPx() } else with(density) { 4.dp.toPx() }, center = Offset(x, cy))
                }
                if (isDragging) {
                    Text(
                        text = "${formatTime(shownPosition)} / ${formatTime(duration)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            if (compact) {
                // Узкий экран: название сверху на всю ширину, кнопки — отдельной строкой ниже.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    TrackTitleBlock(
                        uiState = uiState,
                        showGenre = settings.showGenre,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    TransportControls(
                        playerViewModel = playerViewModel,
                        uiState = uiState,
                        showPlaylistMenu = showPlaylistMenu,
                        onShowPlaylistMenuChange = { showPlaylistMenu = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                // Широкий экран (планшет/ландшафт): название слева, кнопки справа — как раньше.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackTitleBlock(
                        uiState = uiState,
                        showGenre = settings.showGenre,
                        modifier = Modifier.weight(1f)
                    )
                    TransportControls(
                        playerViewModel = playerViewModel,
                        uiState = uiState,
                        showPlaylistMenu = showPlaylistMenu,
                        onShowPlaylistMenuChange = { showPlaylistMenu = it }
                    )
                }
            }
        }
    }
}

/** Название, BPM-бейдж, исполнитель и информация о плейлистах текущего трека. */
@Composable
private fun TrackTitleBlock(
    uiState: PlayerUiState,
    showGenre: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = uiState.currentTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            uiState.currentBpm?.let { bpm ->
                val genreSuffix =
                    if (showGenre) uiState.currentGenre?.let { g -> " · $g" } ?: "" else ""
                Text(
                    text = " ${bpm.toInt()} BPM$genreSuffix",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    color = bpmConfidenceColor(
                        uiState.currentBpmConfidence, manual = uiState.currentBpmManual
                    ),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
        if (uiState.currentArtist.isNotBlank()) {
            Text(
                text = uiState.currentArtist,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (uiState.currentTrackInPlaylists.isNotEmpty()) {
            Text(
                text = "В плейлисте: ${uiState.currentTrackInPlaylists.joinToString(", ") { it.name }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Ряд кнопок управления: добавление в плейлист, перемешивание, перемотка, повтор. */
@Composable
private fun TransportControls(
    playerViewModel: PlayerViewModel,
    uiState: PlayerUiState,
    showPlaylistMenu: Boolean,
    onShowPlaylistMenuChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Box(modifier = Modifier.helpTarget("addToPlaylist")) {
            IconButton(onClick = { onShowPlaylistMenuChange(true) }) {
                Icon(
                    if (uiState.currentTrackInPlaylists.isNotEmpty()) Icons.Filled.LibraryMusic else Icons.Filled.Add,
                    contentDescription = "Добавить в плейлист",
                    tint = if (uiState.currentTrackInPlaylists.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            DropdownMenu(
                expanded = showPlaylistMenu,
                onDismissRequest = { onShowPlaylistMenuChange(false) }
            ) {
                if (uiState.playlists.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Нет плейлистов") },
                        onClick = { onShowPlaylistMenuChange(false) }
                    )
                } else {
                    uiState.playlists.forEach { playlist ->
                        val isIn = uiState.currentTrackInPlaylists.any { it.id == playlist.id }
                        DropdownMenuItem(
                            text = { Text(if (isIn) "${playlist.name} ✓" else playlist.name) },
                            onClick = {
                                if (!isIn) {
                                    playerViewModel.addCurrentTrackToPlaylist(playlist.id)
                                }
                                onShowPlaylistMenuChange(false)
                            }
                        )
                    }
                }
            }
        }
        MiniToggleButton(
            active = uiState.shuffleModeEnabled,
            onClick = { playerViewModel.toggleShuffle() },
            icon = Icons.Filled.Shuffle,
            contentDescription = "Играть вперемешку"
        )
        IconButton(onClick = { playerViewModel.previous() }) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
        }
        IconButton(onClick = { playerViewModel.playPause() }) {
            Icon(if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Play/Pause")
        }
        IconButton(onClick = { playerViewModel.next() }) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next")
        }
        MiniToggleButton(
            active = uiState.repeatMode != Player.REPEAT_MODE_OFF,
            onClick = { playerViewModel.setRepeatMode() },
            icon = when (uiState.repeatMode) {
                Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                else -> Icons.Filled.Repeat
            },
            contentDescription = "Повтор"
        )
    }
}
