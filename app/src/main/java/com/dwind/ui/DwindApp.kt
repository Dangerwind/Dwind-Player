package com.dwind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dwind.R
import com.dwind.ui.components.AppOverflowMenu
import com.dwind.ui.components.MiniPlayer
import com.dwind.ui.help.HelpGuide
import com.dwind.ui.help.HelpStep
import com.dwind.ui.help.HelpTargetRegistry
import com.dwind.ui.help.LocalHelpTargets
import com.dwind.ui.help.helpTarget
import com.dwind.ui.screens.folders.FoldersScreen
import com.dwind.ui.screens.playlists.PlaylistsScreen
import com.dwind.ui.theme.HeaderGradientEnd
import com.dwind.ui.theme.HeaderGradientStart
import com.dwind.ui.viewmodel.PlayerViewModel

@Composable
fun DwindApp() {
    // rememberSaveable: выбранная вкладка сохраняется при повороте экрана и
    // пересоздании активности — пользователь остаётся там, где был.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.tab_folders), stringResource(R.string.tab_playlists))
    val playerViewModel: PlayerViewModel = viewModel()
    val playerState by playerViewModel.uiState.collectAsState()
    val window = rememberWindowSizeInfo()

    val showMiniPlayer = playerState.currentTitle.isNotEmpty()
    val compactPlayer = window.isNarrowWidth

    // Точную высоту мини-плеера измеряем на месте: статическая оценка в
    // компактном режиме завышала отступ, и кнопка «+» уезжала слишком высоко.
    // Пока размер не измерен — используем оценку.
    var playerHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val playerInset = when {
        !showMiniPlayer -> 0.dp
        playerHeightPx > 0 -> with(density) { playerHeightPx.toDp() } + 8.dp
        else -> MiniPlayerMetrics.inset(compactPlayer)
    }

    var guideActive by remember { mutableStateOf(false) }
    val helpRegistry = remember { HelpTargetRegistry() }

    CompositionLocalProvider(LocalHelpTargets provides helpRegistry) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    AppHeader(
                        selectedTab = selectedTab,
                        tabs = tabs,
                        onTabSelected = { selectedTab = it },
                        compact = window.isShortHeight,
                        onHelp = { guideActive = true }
                    )
                }
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    when (selectedTab) {
                        0 -> FoldersScreen(
                            modifier = Modifier.fillMaxSize(),
                            playerInset = playerInset,
                            compactWidth = compactPlayer,
                            compactHeight = window.isShortHeight
                        )
                        1 -> PlaylistsScreen(
                            modifier = Modifier.fillMaxSize(),
                            playerInset = playerInset
                        )
                    }
                    if (showMiniPlayer) {
                        MiniPlayer(
                            playerViewModel = playerViewModel,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .onSizeChanged { playerHeightPx = it.height }
                                .helpTarget("player"),
                            compact = compactPlayer
                        )
                    }
                }
            }

            if (guideActive) {
                fun has(id: String) = helpRegistry.bounds.containsKey(id)

                val activeSteps = buildHelpSteps(::has)
                val missingNotes = buildList {
                    if (!has("bpmText") && !has("bpmDetect") && !has("bpmButton") && !has("playlistBpm")) {
                        add(stringResource(R.string.help_missing_bpm))
                    }
                    if (!has("sortByName") && !has("playlistItem")) {
                        add(stringResource(R.string.help_missing_sort))
                    }
                    if (!has("createPlaylist") && !has("playlistItem") && !has("addTracks")) {
                        add(stringResource(R.string.help_missing_playlists))
                    }
                    if (!has("player")) {
                        add(stringResource(R.string.help_missing_player))
                    }
                }
                val recap = buildString {
                    append(stringResource(R.string.help_recap))
                    if (missingNotes.isNotEmpty()) {
                        append(stringResource(R.string.help_recap_missing_prefix))
                        append(missingNotes.joinToString("; "))
                        append(".")
                    }
                }

                HelpGuide(
                    steps = activeSteps + HelpStep(
                        targetId = null,
                        title = stringResource(R.string.help_more_title),
                        text = recap
                    ),
                    registry = helpRegistry,
                    onFinish = { guideActive = false }
                )
            }
        }
    }
}

/**
 * Набор шагов гайда, адаптированный под текущий экран: берутся только те
 * элементы, которые реально есть в композиции (проверяется через [has]).
 * Так «Помощь» не указывает на то, чего на экране нет.
 */
@Composable
private fun buildHelpSteps(has: (String) -> Boolean): List<HelpStep> {
    val steps = mutableListOf<HelpStep>()
    steps += HelpStep("header", stringResource(R.string.help_header_title), stringResource(R.string.help_header_text))
    steps += HelpStep("tabs", stringResource(R.string.help_tabs_title), stringResource(R.string.help_tabs_text))
    steps += HelpStep("menu", stringResource(R.string.help_menu_title), stringResource(R.string.help_menu_text))

    if (has("sortByName")) {
        steps += HelpStep("sortByName", stringResource(R.string.help_sort_name_title), stringResource(R.string.help_sort_name_text))
    }
    if (has("sortByTitle")) {
        steps += HelpStep("sortByTitle", stringResource(R.string.help_sort_title_title), stringResource(R.string.help_sort_title_text))
    }
    if (has("sortByBpm")) {
        steps += HelpStep("sortByBpm", stringResource(R.string.help_sort_bpm_title), stringResource(R.string.help_sort_bpm_text))
    }
    if (has("playlistItem")) {
        steps += HelpStep(
            "playlistItem", stringResource(R.string.help_playlist_item_title),
            stringResource(R.string.help_playlist_item_text)
        )
    }
    if (has("createPlaylist")) {
        steps += HelpStep("createPlaylist", stringResource(R.string.help_create_playlist_title), stringResource(R.string.help_create_playlist_text))
    }
    if (has("addTracks")) {
        steps += HelpStep("addTracks", stringResource(R.string.help_add_tracks_title), stringResource(R.string.help_add_tracks_text))
    }
    if (has("list")) {
        val text = if (has("bpmText") || has("bpmDetect")) {
            stringResource(R.string.help_list_text_tracks)
        } else {
            stringResource(R.string.help_list_text_folders)
        }
        steps += HelpStep("list", stringResource(R.string.help_list_title), text)
    }
    if (has("bpmText")) {
        steps += HelpStep("bpmText", stringResource(R.string.help_bpm_text_title), stringResource(R.string.help_bpm_text_text))
    }
    if (has("bpmDetect")) {
        steps += HelpStep("bpmDetect", stringResource(R.string.help_bpm_detect_title), stringResource(R.string.help_bpm_detect_text))
    }
    if (has("bpmButton")) {
        steps += HelpStep("bpmButton", stringResource(R.string.help_bpm_button_title), stringResource(R.string.help_bpm_button_text))
    }
    if (has("playlistBpm")) {
        steps += HelpStep("playlistBpm", stringResource(R.string.help_bpm_button_title), stringResource(R.string.help_playlist_bpm_text))
    }
    if (has("player")) {
        steps += HelpStep("player", stringResource(R.string.help_player_title), stringResource(R.string.help_player_text))
    }
    if (has("addToPlaylist")) {
        steps += HelpStep("addToPlaylist", stringResource(R.string.help_add_to_playlist_title), stringResource(R.string.help_add_to_playlist_text))
    }
    return steps
}

@Composable
private fun AppHeader(
    selectedTab: Int,
    tabs: List<String>,
    onTabSelected: (Int) -> Unit,
    compact: Boolean,
    onHelp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(Brush.linearGradient(listOf(HeaderGradientStart, HeaderGradientEnd)))
            // В ландшафте системная навигация (кнопки «назад/домой») стоит сбоку и
            // перекрывает меню-троеточие у правого края — сдвигаем содержимое внутрь.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .statusBarsPadding()
            .padding(
                start = if (compact) 16.dp else 20.dp,
                end = if (compact) 6.dp else 8.dp,
                top = if (compact) 4.dp else 10.dp,
                bottom = if (compact) 8.dp else 16.dp
            )
            .helpTarget("header")
    ) {
        if (compact) {
            // Низкий экран (телефон в ландшафте): всё в одну строку, без подзаголовка,
            // чтобы шапка не съедала место у списка.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TabSwitcher(
                    selectedTab = selectedTab,
                    tabs = tabs,
                    onTabSelected = onTabSelected,
                    compact = true,
                    modifier = Modifier.helpTarget("tabs")
                )
                AppOverflowMenu(
                    iconTint = Color.White,
                    modifier = Modifier.helpTarget("menu"),
                    onHelp = onHelp
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.header_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
                AppOverflowMenu(
                    iconTint = Color.White,
                    modifier = Modifier.helpTarget("menu"),
                    onHelp = onHelp
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            TabSwitcher(
                selectedTab = selectedTab,
                tabs = tabs,
                onTabSelected = onTabSelected,
                compact = false,
                modifier = Modifier.helpTarget("tabs")
            )
        }
    }
}

/**
 * Переключатель «Папки / Плейлисты». В полной раскладке занимает всю ширину,
 * в компактной — сжимается до содержимого, чтобы уместиться в строку.
 */
@Composable
private fun TabSwitcher(
    selectedTab: Int,
    tabs: List<String>,
    onTabSelected: (Int) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(4.dp)
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = selectedTab == index
            Box(
                modifier = Modifier
                    .then(if (compact) Modifier else Modifier.weight(1f))
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onTabSelected(index) }
                    .padding(horizontal = if (compact) 14.dp else 0.dp, vertical = if (compact) 7.dp else 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.White
                )
            }
        }
    }
}
