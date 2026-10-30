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
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dwind.BuildConfig
import com.dwind.R
import com.dwind.settings.AppSettings
import com.dwind.settings.LanguageMode
import com.dwind.settings.SettingsState
import com.dwind.settings.ThemeMode
import kotlin.math.roundToInt

/**
 * Правое меню-троеточие: измерение BPM (диапазон и порог автоопределения),
 * выбор темы и языка, показ стиля, справка и «О программе».
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
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var showMeasurementDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    // Текущий язык интерфейса определяем по уже применённой локали контекста:
    // это верно и для системного языка, и для ручного переключения.
    val isRussian = context.resources.configuration.locales[0].language == "ru"
    val languageItemLabel = if (isRussian) {
        stringResource(R.string.language_switch_to_english)
    } else {
        stringResource(R.string.language_switch_to_russian)
    }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.menu_content_description),
                tint = if (iconTint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else iconTint
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            MenuItem(
                icon = Icons.Filled.Tune,
                text = stringResource(R.string.menu_bpm_measurement)
            ) {
                expanded = false
                showMeasurementDialog = true
            }
            MenuItem(
                icon = Icons.Filled.DarkMode,
                text = stringResource(R.string.menu_theme, themeLabel(settings.themeMode))
            ) {
                expanded = false
                showThemeDialog = true
            }
            MenuItem(
                icon = Icons.Filled.Language,
                text = languageItemLabel
            ) {
                // Язык применяется мгновенно через LocalizedContent в дереве
                // Compose, поэтому пересоздавать активность не нужно.
                AppSettings.setLanguageMode(
                    if (isRussian) LanguageMode.ENGLISH else LanguageMode.RUSSIAN
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_show_genre)) },
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
            MenuItem(icon = Icons.AutoMirrored.Filled.HelpOutline, text = stringResource(R.string.menu_help)) {
                expanded = false
                onHelp()
            }
            MenuItem(icon = Icons.Filled.Info, text = stringResource(R.string.menu_about)) {
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
            title = { Text(stringResource(R.string.about_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.about_build_date, BuildConfig.BUILD_DATE),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.about_developer),
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
                        stringResource(R.string.about_ai_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.action_close)) }
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

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_label_system
        ThemeMode.LIGHT -> R.string.theme_label_light
        ThemeMode.DARK -> R.string.theme_label_dark
    }
)

@Composable
private fun ThemeDialog(
    current: ThemeMode,
    onDismiss: () -> Unit,
    onSave: (ThemeMode) -> Unit
) {
    val options = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.theme_option_system),
        ThemeMode.LIGHT to stringResource(R.string.theme_option_light),
        ThemeMode.DARK to stringResource(R.string.theme_option_dark)
    )
    var selected by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_dialog_title)) },
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
            TextButton(onClick = { onSave(selected) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
        title = { Text(stringResource(R.string.measurement_dialog_title)) },
        text = {
            Column {
                // ---- Диапазон BPM --------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.bpm_range_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.bpm_range_value, lo, hi),
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
                    stringResource(R.string.bpm_range_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ---- Порог «зелёного» BPM ------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.green_threshold_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.percent_format, percent),
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
                    stringResource(R.string.green_threshold_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(lo, hi, percent) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
