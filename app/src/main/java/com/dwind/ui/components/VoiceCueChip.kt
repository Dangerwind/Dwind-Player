package com.dwind.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dwind.R
import com.dwind.settings.AppSettings
import com.dwind.ui.rememberWindowSizeInfo

/**
 * Компактная кнопка голосовых подсказок для строки с сортировкой и BPM.
 *
 * Обычное нажатие включает и выключает подсказки, долгое — открывает полные
 * настройки. Включённая кнопка окрашена так же, как кнопка BPM.
 *
 * Если экран достаточно широкий (планшет или телефон в горизонтальном
 * положении), на кнопке видна подпись «Подсказки». На узком экране остаётся
 * только иконка, чтобы всё помещалось в одну строку.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceCueChip(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {}
) {
    val settings by AppSettings.state.collectAsState()
    val window = rememberWindowSizeInfo()
    val showLabel = !window.isNarrowWidth
    val context = LocalContext.current
    val hasLines = settings.voiceCueFixedLines.isNotEmpty() ||
        settings.voiceCueRandomLines.isNotEmpty()
    val enabled = settings.voiceCuesEnabled && hasLines
    val needPhrase = stringResource(R.string.voice_cues_need_phrase_short)

    val containerColor = when {
        !hasLines -> MaterialTheme.colorScheme.surfaceVariant
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        !hasLines -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        enabled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .height(if (showLabel) 32.dp else 30.dp)
            .combinedClickable(
                onClick = {
                    if (hasLines) {
                        AppSettings.setVoiceCuesEnabled(!settings.voiceCuesEnabled)
                    } else {
                        Toast.makeText(context, needPhrase, Toast.LENGTH_SHORT).show()
                    }
                },
                onLongClick = onOpenSettings
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (showLabel) 10.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.RecordVoiceOver,
                contentDescription = stringResource(R.string.cd_voice_cues),
                modifier = Modifier.size(if (showLabel) 18.dp else 16.dp)
            )
            if (showLabel) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.voice_cues_chip_label),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}
