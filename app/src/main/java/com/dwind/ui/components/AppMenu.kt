package com.dwind.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dwind.BuildConfig
import com.dwind.settings.AppSettings
import com.dwind.settings.SettingsState
import com.dwind.settings.ThemeMode
import kotlin.math.roundToInt

/**
 * Правое меню-троеточие: измерение BPM (диапазон и порог автоопределения),
 * выбор темы, показ стиля, справка и «О программе».
 *
 * @param iconTint цвет иконки; по умолчанию — как у содержимого (для шапки
 *   приложения передаётся белый, чтобы читалось поверх градиента).
 */
@Composable
fun AppOverflowMenu(
    iconTint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    onHelp: () -> Unit = {}
) {
    val settings by AppSettings.state.collectAsState()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    var showMeasurementDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Меню",
                tint = if (iconTint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else iconTint
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            MenuItem(
                icon = Icons.Filled.Tune,
                text = "Измерение bpm"
            ) {
                expanded = false
                showMeasurementDialog = true
            }
            MenuItem(
                icon = Icons.Filled.DarkMode,
                text = "Тема: ${themeLabel(settings.themeMode)}"
            ) {
                expanded = false
                showThemeDialog = true
            }
            DropdownMenuItem(
                text = { Text("Отображать стиль") },
                leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                trailingIcon = {
                    Checkbox(
                        checked = settings.showGenre,
                        onCheckedChange = null
                    )
                },
                onClick = { AppSettings.setShowGenre(!settings.showGenre) }
            )
            HorizontalDivider()
            MenuItem(icon = Icons.AutoMirrored.Filled.HelpOutline, text = "Помощь") {
                expanded = false
                onHelp()
            }
            MenuItem(icon = Icons.Filled.Info, text = "О программе") {
                expanded = false
                showAbout = true
            }
        }
    }

    if (showMeasurementDialog) {
        BpmMeasurementDialog(
            currentMin = settings.bpmMin,
            currentMax = settings.bpmMax,
            currentPercent = settings.greenThresholdPercent,
            onDismiss = { showMeasurementDialog = false },
            onSave = { lo, hi, percent ->
                AppSettings.setBpmRange(lo, hi)
                AppSettings.setGreenThresholdPercent(percent)
                showMeasurementDialog = false
            }
        )
    }

    if (showThemeDialog) {
        ThemeDialog(
            current = settings.themeMode,
            onDismiss = { showThemeDialog = false },
            onSave = { mode ->
                AppSettings.setThemeMode(mode)
                showThemeDialog = false
            }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("О программе") },
            text = {
                Column {
                    Text("Dwind Player", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Версия ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Дата сборки: ${BuildConfig.BUILD_DATE}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Разработчик: Андрей Кокорев",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = "GitHub: https://github.com/Dangerwind",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { uriHandler.openUri("https://github.com/Dangerwind") }
                    )
                    Text(
                        "Приложение создано с использованием ИИ-агентов.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("Закрыть") }
            }
        )
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick
    )
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "как в системе"
    ThemeMode.LIGHT -> "светлая"
    ThemeMode.DARK -> "тёмная"
}

@Composable
private fun ThemeDialog(
    current: ThemeMode,
    onDismiss: () -> Unit,
    onSave: (ThemeMode) -> Unit
) {
    val options = listOf(
        ThemeMode.SYSTEM to "Как в системе",
        ThemeMode.LIGHT to "Светлая",
        ThemeMode.DARK to "Тёмная"
    )
    var selected by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Тема оформления") },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == mode,
                                role = Role.RadioButton,
                                onClick = { selected = mode }
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == mode,
                            onClick = null
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun BpmMeasurementDialog(
    currentMin: Int,
    currentMax: Int,
    currentPercent: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Int) -> Unit
) {
    val hardMin = SettingsState.HARD_MIN.toFloat()
    val hardMax = SettingsState.HARD_MAX.toFloat()
    var range by remember {
        mutableStateOf(
            currentMin.toFloat().coerceIn(hardMin, hardMax)..
                currentMax.toFloat().coerceIn(hardMin, hardMax)
        )
    }
    val thresholdMin = SettingsState.GREEN_THRESHOLD_MIN.toFloat()
    val thresholdMax = SettingsState.GREEN_THRESHOLD_MAX.toFloat()
    var threshold by remember {
        mutableStateOf(currentPercent.toFloat().coerceIn(thresholdMin, thresholdMax))
    }

    val lo = range.start.roundToInt()
    val hi = range.endInclusive.roundToInt()
    val percent = threshold.roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Измерение BPM") },
        text = {
            Column {
                // ---- Диапазон BPM --------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Диапазон BPM",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "от $lo до $hi",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = hardMin..hardMax,
                    steps = (SettingsState.HARD_MAX - SettingsState.HARD_MIN - 1)
                )
                Text(
                    "Сужение диапазона убирает ложное удвоение и ошибки распознавания. " +
                        "Изменение не пересчитывает уже посчитанные треки — запустите BPM-анализ заново.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ---- Порог «зелёного» BPM ------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Порог «зелёного» BPM",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "$percent%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    valueRange = thresholdMin..thresholdMax,
                    steps = ((thresholdMax - thresholdMin).toInt() / 5) - 1
                )
                Text(
                    "Если уверенность автоопределения не ниже порога — цифра BPM зелёная, " +
                        "ниже порога — жёлто-коричневая. Ручной BPM всегда синий.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(lo, hi, percent) }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
