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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val tabs = listOf("Папки", "Плейлисты")
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
                        add("откройте папку или плейлист с треками — там работа с BPM")
                    }
                    if (!has("sortByName") && !has("playlistItem")) {
                        add("в папке с треками есть сортировка по имени файла, названию трека и BPM")
                    }
                    if (!has("createPlaylist") && !has("playlistItem") && !has("addTracks")) {
                        add("на вкладке «Плейлисты» создаются плейлисты и добавляются треки")
                    }
                    if (!has("player")) {
                        add("включите трек — появится мини-плеер")
                    }
                }
                val recap = buildString {
                    append(
                        "Коротко обо всём: измерение BPM (всей папки, одного трека и вручную тапом), " +
                            "ручной ввод темпа и стиля, плейлисты, сортировка, настройки BPM и темы, " +
                            "мини-плеер с перемешиванием."
                    )
                    if (missingNotes.isNotEmpty()) {
                        append("\n\nНа этом экране видно не всё. Чтобы попробовать — ")
                        append(missingNotes.joinToString("; "))
                        append(".")
                    }
                }

                HelpGuide(
                    steps = activeSteps + HelpStep(
                        targetId = null,
                        title = "Что ещё умеет",
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
private fun buildHelpSteps(has: (String) -> Boolean): List<HelpStep> {
    val steps = mutableListOf<HelpStep>()
    steps += HelpStep("header", "Dwind Player", "Аудиоплеер, который умеет измерять темп музыки (BPM).")
    steps += HelpStep("tabs", "Папки и плейлисты", "Переключение между папками устройства и плейлистами.")
    steps += HelpStep("menu", "Меню", "Настройки: измерение BPM, тема, стиль и справка.")

    if (has("sortByName")) {
        steps += HelpStep("sortByName", "Сортировка по имени", "«Имя» — сортировка по имени файла.")
    }
    if (has("sortByTitle")) {
        steps += HelpStep("sortByTitle", "Сортировка по названию", "«Название» — сортировка по названию трека.")
    }
    if (has("sortByBpm")) {
        steps += HelpStep("sortByBpm", "Сортировка по BPM", "«BPM» — сортировка по темпу.")
    }
    if (has("playlistItem")) {
        steps += HelpStep(
            "playlistItem", "Плейлисты",
            "Тап по плейлисту — открыть его; значки справа — переименовать и удалить."
        )
    }
    if (has("createPlaylist")) {
        steps += HelpStep("createPlaylist", "Создать плейлист", "Собирайте треки в плейлисты под настроение.")
    }
    if (has("addTracks")) {
        steps += HelpStep("addTracks", "Добавить треки", "Добавьте треки из библиотеки в этот плейлист.")
    }
    if (has("list")) {
        val text = if (has("bpmText") || has("bpmDetect")) {
            "Тап по строке — воспроизведение. Цвет цифры BPM показывает уверенность определения."
        } else {
            "Тап по папке — открыть её, тап по треку — воспроизведение."
        }
        steps += HelpStep("list", "Список", text)
    }
    if (has("bpmText")) {
        steps += HelpStep("bpmText", "Цифра BPM", "Тап по цифре BPM — ручной ввод темпа и выбор стиля.")
    }
    if (has("bpmDetect")) {
        steps += HelpStep("bpmDetect", "Определить BPM", "Измеряет BPM только этого трека.")
    }
    if (has("bpmButton")) {
        steps += HelpStep("bpmButton", "Анализ BPM", "Измеряет BPM всех треков в текущей папке.")
    }
    if (has("playlistBpm")) {
        steps += HelpStep("playlistBpm", "Анализ BPM", "Измеряет BPM всех треков плейлиста.")
    }
    if (has("player")) {
        steps += HelpStep("player", "Мини-плеер", "Управление воспроизведением и перемешивание треков.")
    }
    if (has("addToPlaylist")) {
        steps += HelpStep("addToPlaylist", "В плейлист", "Кнопка «+» добавляет текущий трек в плейлист.")
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
                    text = "Dwind Player",
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
                        text = "Dwind Player",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Text(
                        text = "Аудиоплеер для тех, кому важно знать темп музыки",
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
