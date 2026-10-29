package com.dwind.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.dwind.settings.AppSettings

/**
 * Цвет BPM по типу источника и уверенности:
 * синий (primary) — ручной ввод (tap-tempo, выбор вручную);
 * зелёный         — авто, уверенность ≥ порога (настраивается в меню, по умолчанию 60%);
 * жёлто-коричневый — авто, уверенность ниже порога.
 */
@Composable
fun bpmConfidenceColor(confidence: Double?, manual: Boolean): Color {
    if (manual) return MaterialTheme.colorScheme.primary
    val c = confidence ?: return MaterialTheme.colorScheme.onSurfaceVariant
    // Наблюдаем за настройкой, чтобы цвет обновлялся сразу после смены порога.
    val settings by AppSettings.state.collectAsState()
    val threshold = settings.greenThresholdPercent / 100.0
    return if (c >= threshold) Color(0xFF2E7D32) else Color(0xFFF9A825)
}
