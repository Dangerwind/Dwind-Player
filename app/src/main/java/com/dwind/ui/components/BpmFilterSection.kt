package com.dwind.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dwind.R
import com.dwind.settings.AppSettings
import com.dwind.settings.SettingsState
import com.dwind.ui.help.helpTarget
import kotlin.math.roundToInt

/** Запас по краям линейки, чтобы крайние треки не были прижаты к самому краю. */
private const val BPM_FILTER_PADDING = 5

/**
 * Панель фильтра воспроизведения по BPM: переключатель «только в диапазоне»
 * и раскрывающаяся линейка с двумя движками.
 *
 * Границы линейки строятся по реальным BPM треков текущего списка: если в папке
 * есть только 90 и 110 BPM, диапазон будет 90–110, а не общий 20–300.
 * Пока BPM не определены, включить фильтр нельзя — переключатель серый и
 * объясняет, что сначала нужен анализ.
 *
 * На узком экране телефона подпись и значение диапазона разводятся на две
 * строки, чтобы ничего не обрезалось. На планшете и в ландшафте остаётся
 * компактная однострочная раскладка.
 *
 * @param availableBpms целые значения BPM треков текущего списка (только определённые).
 */
@Composable
fun BpmFilterSection(
    availableBpms: List<Int>,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val settings by AppSettings.state.collectAsState()
    val noBpmMessage = stringResource(R.string.bpm_filter_need_bpm)

    val availableMin = availableBpms.minOrNull()
    val availableMax = availableBpms.maxOrNull()
    val hasBpm = availableMin != null && availableMax != null

    // По краям линейки оставляем по 5 BPM запаса: если треки 80–120, движки
    // ходят 75–125. Так крайний трек не приходится ловить у самого края.
    val sliderMin = (availableMin?.minus(BPM_FILTER_PADDING) ?: settings.filterBpmMin)
        .coerceIn(SettingsState.HARD_MIN, SettingsState.HARD_MAX)
    val sliderMax = (availableMax?.plus(BPM_FILTER_PADDING) ?: settings.filterBpmMax)
        .coerceIn(SettingsState.HARD_MIN, SettingsState.HARD_MAX)
    val adjustable = hasBpm && sliderMin < sliderMax

    // Текущий выбор влезает в линейку с запасом по краям.
    val savedLo = settings.filterBpmMin
    val savedHi = settings.filterBpmMax
    val selectedMin: Int
    val selectedMax: Int
    if (hasBpm) {
        // Если сохранённый диапазон вообще не пересекается с этим списком,
        // показываем весь доступный диапазон, а не схлопываем его в одну точку.
        if (savedHi >= sliderMin && savedLo <= sliderMax) {
            selectedMin = savedLo.coerceIn(sliderMin, sliderMax)
            selectedMax = savedHi.coerceIn(sliderMin, sliderMax)
        } else {
            selectedMin = sliderMin
            selectedMax = sliderMax
        }
    } else {
        selectedMin = sliderMin
        selectedMax = sliderMax
    }
    val lo = minOf(selectedMin, selectedMax)
    val hi = maxOf(selectedMin, selectedMax)

    var range by remember { mutableStateOf(lo.toFloat()..hi.toFloat()) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    // Диапазон из настроек или набор треков изменился — подтягиваем движки.
    LaunchedEffect(lo, hi) {
        val start = lo.toFloat()
        val end = hi.toFloat()
        if (range.start != start || range.endInclusive != end) {
            range = start..end
        }
    }

    // Фильтр включён, а сохранённый диапазон выходит за пределы текущих BPM —
    // сужаем его, чтобы список и очередь работали по реальным значениям.
    LaunchedEffect(hasBpm, settings.bpmFilterEnabled, lo, hi) {
        if (hasBpm && settings.bpmFilterEnabled &&
            (settings.filterBpmMin != lo || settings.filterBpmMax != hi)
        ) {
            AppSettings.setFilterBpmRange(lo, hi)
        }
    }

    val switchEnabled = hasBpm || settings.bpmFilterEnabled
    // Пока движки двигают, показываем их положение сразу, не дожидаясь отпускания.
    val liveMin = range.start.roundToInt()
    val liveMax = range.endInclusive.roundToInt()
    val rangeText = when {
        !hasBpm -> stringResource(R.string.bpm_filter_no_bpm)
        liveMin == liveMax -> stringResource(R.string.bpm_filter_range_single, liveMin)
        else -> stringResource(R.string.bpm_filter_range_short, liveMin, liveMax)
    }
    val rangeColor = if (settings.bpmFilterEnabled && hasBpm) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

    val onSwitchChange: ((Boolean) -> Unit)? = if (switchEnabled) {
        { checked ->
            if (checked) {
                // Включаем ровно по диапазону текущего списка.
                AppSettings.setFilterBpmRange(lo, hi)
            }
            AppSettings.setBpmFilterEnabled(checked)
        }
    } else {
        null
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Узкий телефон: значение диапазона уходит на отдельную строку.
        val narrow = maxWidth < 380.dp
        val labelStyle = if (compact || narrow) MaterialTheme.typography.labelMedium
        else MaterialTheme.typography.bodyMedium

        Column(modifier = Modifier.fillMaxWidth()) {
            if (narrow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 12.dp,
                            end = 4.dp,
                            top = if (compact) 0.dp else 2.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BpmFilterSwitch(
                        checked = settings.bpmFilterEnabled,
                        enabled = switchEnabled,
                        onCheckedChange = onSwitchChange,
                        noBpmMessage = noBpmMessage
                    )
                    Text(
                        text = stringResource(R.string.bpm_filter_switch),
                        style = labelStyle,
                        color = if (hasBpm) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (adjustable) {
                        BpmFilterTuneButton(expanded = expanded) { expanded = !expanded }
                    }
                }
                Text(
                    text = rangeText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = rangeColor,
                    maxLines = 1,
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = if (compact) 0.dp else 2.dp
                    )
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 12.dp,
                            end = 4.dp,
                            top = if (compact) 0.dp else 2.dp,
                            bottom = if (compact) 0.dp else 2.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BpmFilterSwitch(
                        checked = settings.bpmFilterEnabled,
                        enabled = switchEnabled,
                        onCheckedChange = onSwitchChange,
                        noBpmMessage = noBpmMessage
                    )
                    Text(
                        text = stringResource(R.string.bpm_filter_switch),
                        style = labelStyle,
                        color = if (hasBpm) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = rangeText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = rangeColor,
                        maxLines = 1
                    )
                    if (adjustable) {
                        BpmFilterTuneButton(expanded = expanded) { expanded = !expanded }
                    }
                }
            }

            if (expanded && adjustable) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    RangeSlider(
                        value = range,
                        onValueChange = { range = it },
                        onValueChangeFinished = {
                            AppSettings.setFilterBpmRange(
                                range.start.roundToInt(),
                                range.endInclusive.roundToInt()
                            )
                        },
                        valueRange = sliderMin.toFloat()..sliderMax.toFloat(),
                        steps = (sliderMax - sliderMin - 1).coerceAtLeast(0)
                    )
                    // В низком ландшафте подсказка съедает место у списка — там она не нужна.
                    if (!compact) {
                        Text(
                            text = stringResource(R.string.bpm_filter_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Переключатель фильтра с подсказкой-тостом, пока BPM ещё не определён. */
@Composable
private fun BpmFilterSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    noBpmMessage: String
) {
    val context = LocalContext.current
    Box {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.helpTarget("bpmFilter")
        )
        // Выключенный переключатель сам нажатие не обрабатывает —
        // ловим тап поверх него и подсказываем, что нужен BPM.
        if (!enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable {
                        Toast.makeText(context, noBpmMessage, Toast.LENGTH_SHORT).show()
                    }
            )
        }
    }
}

/** Кнопка раскрытия линейки диапазона. */
@Composable
private fun BpmFilterTuneButton(expanded: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            Icons.Filled.Tune,
            contentDescription = stringResource(R.string.cd_bpm_filter_settings),
            modifier = Modifier.size(20.dp),
            tint = if (expanded) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
