package com.dwind.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dwind.R
import com.dwind.bpm.Genres
import com.dwind.ui.genreLabel
import com.dwind.ui.rememberWindowSizeInfo
import kotlin.math.roundToInt

data class BpmEditTarget(
    val trackId: String,
    val uri: String,
    val fileName: String,
    val title: String,
    val currentBpm: Double?
)

/**
 * Tap-tempo со статистическим критерием готовности:
 * считает средний BPM нажатий и его погрешность (SD/√n).
 * «Готово» — когда погрешность среднего ≤ 1 BPM и сделано достаточно нажатий.
 * «Сохранить» доступно всегда — пишет текущее среднее.
 *
 * Стиль выбирается вручную; по умолчанию — «Иное».
 */
@Composable
fun BpmEditDialog(
    target: BpmEditTarget,
    onDismiss: () -> Unit,
    onSave: (Double, String) -> Unit
) {
    val taps = remember(target.trackId) { mutableStateListOf<Long>() }
    var avgBpm by remember(target.trackId) { mutableStateOf<Int?>(null) }
    var margin by remember(target.trackId) { mutableStateOf<Double?>(null) } // ± BPM
    var ready by remember(target.trackId) { mutableStateOf(false) }
    var genre by remember(target.trackId) {
        mutableStateOf(Genres.OTHER)
    }
    var genreMenuOpen by remember(target.trackId) { mutableStateOf(false) }

    fun evaluate() {
        if (taps.size < 2) {
            avgBpm = null; margin = null; ready = false
            return
        }
        val stamps = taps.toList()
        val intervals = stamps.zipWithNext { a, b -> (b - a).coerceAtLeast(1L) }.map { it.toDouble() }
        val n = intervals.size
        val mean = intervals.average()
        // стандартное отклонение интервалов
        val sd = if (n > 1) kotlin.math.sqrt(intervals.map { (it - mean) * (it - mean) }.sum() / (n - 1)) else 0.0
        // переводим разброс в BPM (delta-метод) и делим на √n — погрешность среднего
        val sdBpm = 60000.0 * sd / (mean * mean)
        val err = if (n > 1) sdBpm / kotlin.math.sqrt(n.toDouble()) else sdBpm
        avgBpm = (60000.0 / mean).roundToInt()
        margin = err
        ready = n >= 3 && err <= 1.0
    }

    fun registerTap() {
        val now = SystemClock.elapsedRealtime()
        // пауза длиннее 2 секунд — считаем заново
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.add(now)
        if (taps.size > 32) taps.removeAt(0)
        evaluate()
    }

    // На низком экране (телефон в ландшафте) диалог не помещается целиком —
    // делаем содержимое прокручиваемым и ужимаем отступы, высоту кнопки Tap
    // и селектор стиля (одной строкой вместо двух).
    val shortScreen = rememberWindowSizeInfo().isShortHeight

    @Composable
    fun genreSelector(compact: Boolean) {
        val button: @Composable () -> Unit = {
            OutlinedButton(
                onClick = { genreMenuOpen = true },
                modifier = if (compact) Modifier.height(36.dp) else Modifier.fillMaxWidth(),
                contentPadding = if (compact) PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                 else ButtonDefaults.ContentPadding,
                shape = if (compact) MaterialTheme.shapes.small else MaterialTheme.shapes.medium
            ) {
                Text(
                    text = genreLabel(genre),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    style = if (compact) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.labelLarge
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
            if (compact) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.genre_field_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(modifier = Modifier.weight(1f)) { button() }
                }
            } else {
                Text(
                    text = stringResource(R.string.genre_field_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                button()
            }
            DropdownMenu(
                expanded = genreMenuOpen,
                onDismissRequest = { genreMenuOpen = false }
            ) {
                Genres.MANUAL_CHOICES.forEach { label ->
                    DropdownMenuItem(
                        text = { Text(genreLabel(label)) },
                        onClick = {
                            genre = label
                            genreMenuOpen = false
                        }
                    )
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tap_tempo_title, target.title), maxLines = 1) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(if (shortScreen) 6.dp else 12.dp)
            ) {
                target.currentBpm?.let {
                    Text(
                        text = stringResource(R.string.bpm_current, it.toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { registerTap() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (shortScreen) 44.dp else 64.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null)
                    Text(
                        text = "  ${stringResource(R.string.tap_button)}",
                        fontSize = if (shortScreen) 20.sp else 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val marginText = margin?.let { " ±%.1f".format(it) } ?: ""
                    Text(
                        text = when {
                            avgBpm != null -> "${avgBpm}$marginText BPM"
                            target.currentBpm != null -> "${target.currentBpm.toInt()} BPM"
                            else -> "— BPM"
                        },
                        style = if (shortScreen) MaterialTheme.typography.titleSmall
                                else MaterialTheme.typography.titleMedium,
                        color = if (ready) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(
                        onClick = {
                            taps.clear()
                            avgBpm = null
                            margin = null
                            ready = false
                        },
                        enabled = taps.isNotEmpty()
                    ) { Text(stringResource(R.string.action_reset)) }
                }
                Text(
                    text = when {
                        ready -> stringResource(R.string.bpm_ready)
                        taps.size < 2 && target.currentBpm != null ->
                            stringResource(R.string.bpm_can_save_style)
                        taps.size < 2 -> stringResource(R.string.tap_hint_first)
                        else -> stringResource(R.string.tap_hint_more)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ready) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Ручной выбор стиля (первым — «Иное»)
                genreSelector(compact = shortScreen)
            }
        },
        confirmButton = {
            val savable = avgBpm != null || target.currentBpm != null
            TextButton(
                onClick = {
                    val value = avgBpm?.toDouble() ?: target.currentBpm
                    value?.let { onSave(it, genre) }
                },
                enabled = savable
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
