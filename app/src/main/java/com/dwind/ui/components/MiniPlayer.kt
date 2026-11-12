package com.dwind.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.dwind.R
import com.dwind.settings.AppSettings
import com.dwind.settings.SettingsState
import com.dwind.ui.genreLabel
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

    // Метки фрагмента в долях шкалы — для отрисовки поверх прогресса.
    fun fractionOf(ms: Long?): Float? =
        ms?.let { if (duration > 0) (it.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else null }
    val markerAFraction = fractionOf(uiState.markerA)
    val markerBFraction = fractionOf(uiState.markerB)
    val markerColor = MaterialTheme.colorScheme.tertiary
    val markerTextColor = MaterialTheme.colorScheme.onTertiary
    val segmentColor = if (uiState.abLoopEnabled) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    } else {
        markerColor.copy(alpha = 0.22f)
    }
    val textMeasurer = rememberTextMeasurer()

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
                    // Подсветка выбранного фрагмента A–B.
                    if (markerAFraction != null && markerBFraction != null &&
                        markerBFraction > markerAFraction
                    ) {
                        val xa = startX + (endX - startX) * markerAFraction
                        val xb = startX + (endX - startX) * markerBFraction
                        val bandHeight = with(density) { 10.dp.toPx() }
                        drawRoundRect(
                            color = segmentColor,
                            topLeft = Offset(xa, cy - bandHeight / 2f),
                            size = Size((xb - xa).coerceAtLeast(0f), bandHeight),
                            cornerRadius = CornerRadius(bandHeight / 2f)
                        )
                    }
                    // Флажки меток с буквами A и B.
                    fun drawMarkerBadge(fraction: Float, label: String) {
                        val bx = startX + (endX - startX) * fraction
                        val half = with(density) { 6.dp.toPx() }
                        drawRoundRect(
                            color = markerColor,
                            topLeft = Offset(bx - half, cy - half),
                            size = Size(half * 2f, half * 2f),
                            cornerRadius = CornerRadius(with(density) { 3.dp.toPx() })
                        )
                        val layout = textMeasurer.measure(
                            text = AnnotatedString(label),
                            style = TextStyle(
                                color = markerTextColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(
                                bx - layout.size.width / 2f,
                                cy - layout.size.height / 2f
                            )
                        )
                    }
                    markerAFraction?.let { drawMarkerBadge(it, "A") }
                    markerBFraction?.let { drawMarkerBadge(it, "B") }
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
            if (uiState.abPanelExpanded) {
                AbPanel(
                    uiState = uiState,
                    onSetA = { playerViewModel.setMarkerA() },
                    onSetB = { playerViewModel.setMarkerB() },
                    onJumpA = { playerViewModel.jumpToMarkerA() },
                    onJumpB = { playerViewModel.jumpToMarkerB() },
                    onPlayFromA = { playerViewModel.playFromMarkerA() },
                    onToggleLoop = { playerViewModel.toggleAbLoop() },
                    onClear = { playerViewModel.clearAbMarkers() }
                )
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
                        settings = settings,
                        abExpanded = uiState.abPanelExpanded,
                        onToggleAb = {
                            playerViewModel.setAbPanelExpanded(!uiState.abPanelExpanded)
                        },
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
                        settings = settings,
                        abExpanded = uiState.abPanelExpanded,
                        onToggleAb = {
                            playerViewModel.setAbPanelExpanded(!uiState.abPanelExpanded)
                        },
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
    settings: SettingsState,
    abExpanded: Boolean,
    onToggleAb: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showGenre = settings.showGenre
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
                    if (showGenre) uiState.currentGenre?.let { g -> " · ${genreLabel(g)}" } ?: "" else ""
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
            VoiceCueQuickToggle(settings)
            AbToggleChip(
                active = uiState.markerA != null || uiState.markerB != null ||
                    uiState.abLoopEnabled,
                expanded = abExpanded,
                onClick = onToggleAb
            )
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
                text = stringResource(
                    R.string.in_playlist,
                    uiState.currentTrackInPlaylists.joinToString(", ") { it.name }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Быстрое включение голосовых подсказок прямо из мини-плеера. */
@Composable
private fun VoiceCueQuickToggle(settings: SettingsState) {
    val hasLines = settings.voiceCueFixedLines.isNotEmpty() ||
        settings.voiceCueRandomLines.isNotEmpty()
    IconButton(
        onClick = { AppSettings.setVoiceCuesEnabled(!settings.voiceCuesEnabled) },
        enabled = hasLines,
        modifier = Modifier
            .size(30.dp)
            .helpTarget("playerVoiceCues")
    ) {
        Icon(
            Icons.Filled.RecordVoiceOver,
            contentDescription = stringResource(R.string.cd_voice_cues),
            modifier = Modifier.size(18.dp),
            tint = when {
                settings.voiceCuesEnabled -> MaterialTheme.colorScheme.primary
                hasLines -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                else -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f)
            }
        )
    }
}

/**
 * Компактная кнопка «A–B» в строке названия. Раскрывает панель фрагмента;
 * залита цветом, когда метки заданы или включён цикл.
 */
@Composable
private fun AbToggleChip(
    active: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val highlighted = active || expanded
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f),
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onPrimaryContainer,
        border = if (highlighted) null else BorderStroke(
            1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .padding(start = 4.dp)
            .height(26.dp)
            .helpTarget("abSection")
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.ab_chip_label),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/** Панель фрагмента: метки A/B, запуск с A, цикл и сброс. */
@Composable
private fun AbPanel(
    uiState: PlayerUiState,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onJumpA: () -> Unit,
    onJumpB: () -> Unit,
    onPlayFromA: () -> Unit,
    onToggleLoop: () -> Unit,
    onClear: () -> Unit
) {
    val a = uiState.markerA
    val b = uiState.markerB
    val validSection = a != null && b != null && b - a >= 1_000L

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AbMarkerChip(
            label = "A",
            timeMs = a,
            contentDescription = stringResource(R.string.cd_ab_marker_a),
            onSet = onSetA,
            onJump = onJumpA
        )
        AbMarkerChip(
            label = "B",
            timeMs = b,
            contentDescription = stringResource(R.string.cd_ab_marker_b),
            onSet = onSetB,
            onJump = onJumpB
        )
        Spacer(Modifier.weight(1f))
        AbActionChip(
            icon = Icons.Filled.PlayArrow,
            label = "A",
            contentDescription = stringResource(R.string.cd_ab_play_from_a),
            enabled = a != null,
            onClick = onPlayFromA
        )
        AbActionChip(
            icon = Icons.Filled.Repeat,
            contentDescription = stringResource(R.string.cd_ab_loop),
            enabled = validSection,
            active = uiState.abLoopEnabled,
            onClick = onToggleLoop
        )
        if (a != null || b != null) {
            AbActionChip(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.cd_ab_clear),
                onClick = onClear
            )
        }
    }
}

/** Чип метки: тап ставит метку в текущую позицию, долгое нажатие — переход к ней. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AbMarkerChip(
    label: String,
    timeMs: Long?,
    contentDescription: String,
    onSet: () -> Unit,
    onJump: () -> Unit
) {
    val set = timeMs != null
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (set) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
        contentColor = if (set) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
        modifier = Modifier
            .height(30.dp)
            .semantics { this.contentDescription = contentDescription }
            .combinedClickable(onClick = onSet, onLongClick = onJump)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (timeMs != null) formatTime(timeMs) else "--:--",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/** Компактная кнопка действия панели фрагмента. */
@Composable
private fun AbActionChip(
    icon: ImageVector,
    contentDescription: String,
    label: String? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.30f)
        active -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = contentColor,
        border = if (active) null else BorderStroke(
            1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f)
        ),
        modifier = Modifier.height(30.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (label == null) 8.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp)
            )
            if (label != null) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
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
                    contentDescription = stringResource(R.string.cd_add_to_playlist),
                    tint = if (uiState.currentTrackInPlaylists.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            DropdownMenu(
                expanded = showPlaylistMenu,
                onDismissRequest = { onShowPlaylistMenuChange(false) }
            ) {
                if (uiState.playlists.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.no_playlists)) },
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
            contentDescription = stringResource(R.string.cd_shuffle)
        )
        IconButton(onClick = { playerViewModel.previous() }) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.cd_previous))
        }
        IconButton(onClick = { playerViewModel.playPause() }) {
            Icon(
                if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.cd_play_pause)
            )
        }
        IconButton(onClick = { playerViewModel.next() }) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.cd_next))
        }
        MiniToggleButton(
            active = uiState.repeatMode != Player.REPEAT_MODE_OFF,
            onClick = { playerViewModel.setRepeatMode() },
            icon = when (uiState.repeatMode) {
                Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                else -> Icons.Filled.Repeat
            },
            contentDescription = stringResource(R.string.cd_repeat)
        )
    }
}
