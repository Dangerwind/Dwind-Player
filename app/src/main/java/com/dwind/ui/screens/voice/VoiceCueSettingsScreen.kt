package com.dwind.ui.screens.voice

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dwind.R
import com.dwind.settings.AppSettings
import com.dwind.settings.SettingsState
import com.dwind.ui.rememberWindowSizeInfo
import com.dwind.voice.VoiceCueLanguage
import com.dwind.voice.VoiceCueSpeechSettings
import com.dwind.voice.VoiceCueSpeaker
import com.dwind.voice.VoiceCueVoice
import com.dwind.voice.toVoiceCueSpeechSettings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Полноэкранные настройки голосовых подсказок.
 *
 * На телефоне карточки идут в одну колонку и прокручиваются, на планшете
 * два режима стоят рядом. Так экран остаётся удобным и на маленьком дисплее.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCueSettingsScreen(onBack: () -> Unit) {
    val settings by AppSettings.state.collectAsState()
    val window = rememberWindowSizeInfo()
    val wide = window.widthDp >= WIDE_SCREEN_DP
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaker = remember { VoiceCueSpeaker(context.applicationContext) }
    val ttsError = stringResource(R.string.voice_cues_tts_error)

    var editor by remember { mutableStateOf<PhraseEditor?>(null) }
    var voiceDialog by remember { mutableStateOf<VoiceCueLanguage?>(null) }

    DisposableEffect(Unit) {
        onDispose { speaker.shutdown() }
    }
    BackHandler(onBack = onBack)

    fun preview(lines: List<String>) {
        val line = lines.randomOrNull() ?: return
        scope.launch {
            if (!speaker.speak(line, settings.toVoiceCueSpeechSettings())) {
                Toast.makeText(context, ttsError, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteLine(fixed: Boolean, index: Int) {
        val lines = if (fixed) settings.voiceCueFixedLines else settings.voiceCueRandomLines
        val updated = lines.toMutableList().also { it.removeAt(index) }
        if (fixed) {
            AppSettings.setVoiceCueFixedLines(updated)
            if (updated.isEmpty()) AppSettings.setVoiceCueFixedEnabled(false)
        } else {
            AppSettings.setVoiceCueRandomLines(updated)
            if (updated.isEmpty()) AppSettings.setVoiceCueRandomEnabled(false)
        }
        val otherLines = if (fixed) settings.voiceCueRandomLines else settings.voiceCueFixedLines
        if (updated.isEmpty() && otherLines.isEmpty()) {
            AppSettings.setVoiceCuesEnabled(false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_cues_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MasterCard(settings)

            VoiceCard(
                settings = settings,
                onPickVoice = { language -> voiceDialog = language }
            )

            val fixedCard: @Composable (Modifier) -> Unit = { modifier ->
                VoiceCueModeCard(
                    title = stringResource(R.string.voice_cues_fixed_title),
                    subtitle = stringResource(R.string.voice_cues_fixed_subtitle),
                    enabled = settings.voiceCueFixedEnabled,
                    hasLines = settings.voiceCueFixedLines.isNotEmpty(),
                    onEnabledChange = { AppSettings.setVoiceCueFixedEnabled(it) },
                    lines = settings.voiceCueFixedLines,
                    onAdd = { editor = PhraseEditor(fixed = true, index = null, text = "") },
                    onEdit = { index ->
                        editor = PhraseEditor(
                            fixed = true,
                            index = index,
                            text = settings.voiceCueFixedLines[index]
                        )
                    },
                    onDelete = { index -> deleteLine(fixed = true, index) },
                    onPreview = { preview(settings.voiceCueFixedLines) },
                    modifier = modifier
                ) {
                    FixedIntervalControl(settings)
                }
            }

            val randomCard: @Composable (Modifier) -> Unit = { modifier ->
                VoiceCueModeCard(
                    title = stringResource(R.string.voice_cues_random_title),
                    subtitle = stringResource(R.string.voice_cues_random_subtitle),
                    enabled = settings.voiceCueRandomEnabled,
                    hasLines = settings.voiceCueRandomLines.isNotEmpty(),
                    onEnabledChange = { AppSettings.setVoiceCueRandomEnabled(it) },
                    lines = settings.voiceCueRandomLines,
                    onAdd = { editor = PhraseEditor(fixed = false, index = null, text = "") },
                    onEdit = { index ->
                        editor = PhraseEditor(
                            fixed = false,
                            index = index,
                            text = settings.voiceCueRandomLines[index]
                        )
                    },
                    onDelete = { index -> deleteLine(fixed = false, index) },
                    onPreview = { preview(settings.voiceCueRandomLines) },
                    modifier = modifier
                ) {
                    RandomIntervalControl(settings)
                }
            }

            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    fixedCard(Modifier.weight(1f))
                    randomCard(Modifier.weight(1f))
                }
            } else {
                fixedCard(Modifier.fillMaxWidth())
                randomCard(Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    editor?.let { current ->
        PhraseDialog(
            editor = current,
            onDismiss = { editor = null },
            onSave = { text ->
                val lines = if (current.fixed) {
                    settings.voiceCueFixedLines
                } else {
                    settings.voiceCueRandomLines
                }
                val updated = if (current.index == null) {
                    lines + text
                } else {
                    lines.toMutableList().also { it[current.index] = text }
                }
                if (current.fixed) {
                    AppSettings.setVoiceCueFixedLines(updated)
                } else {
                    AppSettings.setVoiceCueRandomLines(updated)
                }
                editor = null
            }
        )
    }

    voiceDialog?.let { language ->
        VoicePickerDialog(
            language = language,
            currentVoice = when (language) {
                VoiceCueLanguage.RUSSIAN -> settings.voiceCueRuVoice
                VoiceCueLanguage.ENGLISH -> settings.voiceCueEnVoice
            },
            settings = settings,
            speaker = speaker,
            onSelect = { name ->
                when (language) {
                    VoiceCueLanguage.RUSSIAN -> AppSettings.setVoiceCueRuVoice(name)
                    VoiceCueLanguage.ENGLISH -> AppSettings.setVoiceCueEnVoice(name)
                }
                voiceDialog = null
            },
            onDismiss = { voiceDialog = null }
        )
    }
}

@Composable
private fun MasterCard(settings: SettingsState) {
    val hasAnyLines = settings.voiceCueFixedLines.isNotEmpty() ||
        settings.voiceCueRandomLines.isNotEmpty()
    var duck by remember(settings.voiceCueDuckPercent) {
        mutableFloatStateOf(settings.voiceCueDuckPercent.toFloat())
    }
    val duckPercent = quantizeDuck(duck)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.voice_cues_master_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.voice_cues_master_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.voiceCuesEnabled,
                    onCheckedChange = { AppSettings.setVoiceCuesEnabled(it) },
                    enabled = hasAnyLines
                )
            }
            if (!hasAnyLines) {
                Text(
                    text = stringResource(R.string.voice_cues_need_phrase),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.voice_cues_duck_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.percent_format, duckPercent),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = duck,
                onValueChange = { duck = it },
                valueRange = SettingsState.VOICE_CUE_DUCK_MIN_PERCENT.toFloat()..
                    SettingsState.VOICE_CUE_DUCK_MAX_PERCENT.toFloat(),
                steps = duckSteps(),
                onValueChangeFinished = {
                    AppSettings.setVoiceCueDuckPercent(quantizeDuck(duck))
                }
            )
            Text(
                text = stringResource(R.string.voice_cues_duck_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VoiceCard(
    settings: SettingsState,
    onPickVoice: (VoiceCueLanguage) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.voice_cues_voice_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.voice_cues_voice_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            VoiceSettingRow(
                label = stringResource(R.string.voice_cues_voice_ru),
                value = settings.voiceCueRuVoice
                    ?: stringResource(R.string.voice_cues_voice_auto),
                onClick = { onPickVoice(VoiceCueLanguage.RUSSIAN) }
            )
            HorizontalDivider()
            VoiceSettingRow(
                label = stringResource(R.string.voice_cues_voice_en),
                value = settings.voiceCueEnVoice
                    ?: stringResource(R.string.voice_cues_voice_auto),
                onClick = { onPickVoice(VoiceCueLanguage.ENGLISH) }
            )
        }
    }
}

@Composable
private fun VoiceSettingRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp)
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VoicePickerDialog(
    language: VoiceCueLanguage,
    currentVoice: String?,
    settings: SettingsState,
    speaker: VoiceCueSpeaker,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var voices by remember(language) { mutableStateOf<List<VoiceCueVoice>?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ttsError = stringResource(R.string.voice_cues_tts_error)
    val sample = stringResource(
        when (language) {
            VoiceCueLanguage.RUSSIAN -> R.string.voice_cues_sample_ru
            VoiceCueLanguage.ENGLISH -> R.string.voice_cues_sample_en
        }
    )

    LaunchedEffect(language) {
        voices = speaker.availableVoices(language)
    }

    fun previewVoice(name: String?) {
        val speech = settings.toVoiceCueSpeechSettings().let {
            when (language) {
                VoiceCueLanguage.RUSSIAN -> it.copy(ruVoice = name)
                VoiceCueLanguage.ENGLISH -> it.copy(enVoice = name)
            }
        }
        scope.launch {
            if (!speaker.speak(sample, speech)) {
                Toast.makeText(context, ttsError, Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (language) {
                        VoiceCueLanguage.RUSSIAN -> R.string.voice_cues_voice_pick_ru
                        VoiceCueLanguage.ENGLISH -> R.string.voice_cues_voice_pick_en
                    }
                )
            )
        },
        text = {
            Column {
                VoiceOptionRow(
                    title = stringResource(R.string.voice_cues_voice_auto),
                    subtitle = stringResource(R.string.voice_cues_voice_auto_hint),
                    selected = currentVoice == null,
                    onSelect = { onSelect(null) },
                    onPreview = { previewVoice(null) }
                )
                val list = voices
                when {
                    list == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.voice_cues_voice_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    list.isEmpty() -> Text(
                        text = stringResource(R.string.voice_cues_voice_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(list, key = { it.name }) { voice ->
                            HorizontalDivider()
                            val network = stringResource(R.string.voice_cues_voice_network)
                            VoiceOptionRow(
                                title = voice.name,
                                subtitle = if (voice.needsNetwork) {
                                    "${voice.locale.displayName} · $network"
                                } else {
                                    voice.locale.displayName
                                },
                                selected = voice.name == currentVoice,
                                onSelect = { onSelect(voice.name) },
                                onPreview = { previewVoice(voice.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun VoiceOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onPreview) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.voice_cues_preview),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun VoiceCueModeCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    hasLines: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    lines: List<String>,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
    intervalContent: @Composable () -> Unit
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = hasLines
                )
            }
            if (!hasLines) {
                Text(
                    text = stringResource(R.string.voice_cues_need_phrase),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            intervalContent()

            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.voice_cues_phrases_count, lines.size),
                style = MaterialTheme.typography.titleSmall
            )
            if (lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.voice_cues_no_phrases),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                lines.forEachIndexed { index, line ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp)
                        )
                        IconButton(onClick = { onEdit(index) }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.voice_cues_edit_phrase_cd)
                            )
                        }
                        IconButton(onClick = { onDelete(index) }) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = stringResource(R.string.voice_cues_delete_phrase)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onAdd,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.voice_cues_add_phrase))
                }
                TextButton(onClick = onPreview, enabled = lines.isNotEmpty()) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.voice_cues_preview))
                }
            }
        }
    }
}

@Composable
private fun FixedIntervalControl(settings: SettingsState) {
    var value by remember(settings.voiceCueFixedIntervalSec) {
        mutableFloatStateOf(settings.voiceCueFixedIntervalSec.toFloat())
    }
    val seconds = quantizeInterval(value)

    Text(
        text = stringResource(
            R.string.voice_cues_fixed_interval,
            formatDuration(seconds)
        ),
        style = MaterialTheme.typography.titleSmall
    )
    Slider(
        value = value,
        onValueChange = { value = it },
        valueRange = SettingsState.VOICE_CUE_MIN_INTERVAL_SEC.toFloat()..
            SettingsState.VOICE_CUE_MAX_INTERVAL_SEC.toFloat(),
        steps = intervalSteps(),
        onValueChangeFinished = {
            AppSettings.setVoiceCueFixedIntervalSec(quantizeInterval(value))
        }
    )
}

@Composable
private fun RandomIntervalControl(settings: SettingsState) {
    var range by remember(
        settings.voiceCueRandomMinSec,
        settings.voiceCueRandomMaxSec
    ) {
        mutableStateOf(
            settings.voiceCueRandomMinSec.toFloat()..
                settings.voiceCueRandomMaxSec.toFloat()
        )
    }
    val minSeconds = quantizeInterval(range.start)
    val maxSeconds = quantizeInterval(range.endInclusive)

    Text(
        text = stringResource(
            R.string.voice_cues_random_range,
            formatDuration(minSeconds),
            formatDuration(maxSeconds)
        ),
        style = MaterialTheme.typography.titleSmall
    )
    RangeSlider(
        value = range,
        onValueChange = { range = it },
        valueRange = SettingsState.VOICE_CUE_MIN_INTERVAL_SEC.toFloat()..
            SettingsState.VOICE_CUE_MAX_INTERVAL_SEC.toFloat(),
        steps = intervalSteps(),
        onValueChangeFinished = {
            AppSettings.setVoiceCueRandomRange(
                quantizeInterval(range.start),
                quantizeInterval(range.endInclusive)
            )
        }
    )
}

@Composable
private fun PhraseDialog(
    editor: PhraseEditor,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(editor) { mutableStateOf(editor.text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (editor.index == null) {
                        R.string.voice_cues_add_phrase
                    } else {
                        R.string.voice_cues_edit_phrase
                    }
                )
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.voice_cues_phrase_label)) },
                placeholder = { Text(stringResource(R.string.voice_cues_phrase_hint)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim()) },
                enabled = text.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return when {
        minutes == 0 -> stringResource(R.string.voice_cues_interval_seconds, rest)
        rest == 0 -> stringResource(R.string.voice_cues_interval_minutes, minutes)
        else -> stringResource(R.string.voice_cues_interval_min_sec, minutes, rest)
    }
}

private fun quantizeInterval(value: Float): Int {
    val step = SettingsState.VOICE_CUE_INTERVAL_STEP_SEC
    return ((value / step).roundToInt() * step).coerceIn(
        SettingsState.VOICE_CUE_MIN_INTERVAL_SEC,
        SettingsState.VOICE_CUE_MAX_INTERVAL_SEC
    )
}

private fun quantizeDuck(value: Float): Int {
    val step = SettingsState.VOICE_CUE_DUCK_STEP_PERCENT
    return ((value / step).roundToInt() * step).coerceIn(
        SettingsState.VOICE_CUE_DUCK_MIN_PERCENT,
        SettingsState.VOICE_CUE_DUCK_MAX_PERCENT
    )
}

private fun intervalSteps(): Int {
    val count = (SettingsState.VOICE_CUE_MAX_INTERVAL_SEC -
        SettingsState.VOICE_CUE_MIN_INTERVAL_SEC) /
        SettingsState.VOICE_CUE_INTERVAL_STEP_SEC
    return (count - 1).coerceAtLeast(0)
}

private fun duckSteps(): Int {
    val count = (SettingsState.VOICE_CUE_DUCK_MAX_PERCENT -
        SettingsState.VOICE_CUE_DUCK_MIN_PERCENT) /
        SettingsState.VOICE_CUE_DUCK_STEP_PERCENT
    return (count - 1).coerceAtLeast(0)
}

private data class PhraseEditor(
    val fixed: Boolean,
    val index: Int?,
    val text: String
)

private const val WIDE_SCREEN_DP = 600
