package com.dwind.ui.help

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dwind.R

/** Один шаг интерактивного гайда: к какому элементу указывать и что рассказать. */
data class HelpStep(
    /** Идентификатор якоря, зарегистрированного через [helpTarget]. null — без стрелки. */
    val targetId: String?,
    val title: String,
    val text: String
)

/**
 * Реестр координат элементов, к которым указывает гайд. Заполняется самими
 * элементами через [helpTarget]; читается оверлеем [HelpGuide].
 */
class HelpTargetRegistry {
    val bounds = mutableStateMapOf<String, Rect>()
}

val LocalHelpTargets = compositionLocalOf<HelpTargetRegistry?> { null }

/**
 * Помечает элемент как цель для интерактивного гайда. Координаты считаются
 * в корне композиции — той же системе, что и у оверлея [HelpGuide].
 */
@Composable
fun Modifier.helpTarget(id: String): Modifier {
    val registry = LocalHelpTargets.current ?: return this
    // Когда элемент пропадает из композиции (например, мини-плеер без трека),
    // убираем его координаты — иначе гайд попытался бы указать на пустое место.
    DisposableEffect(registry, id) {
        onDispose { registry.bounds.remove(id) }
    }
    return this.onGloballyPositioned { coordinates ->
        registry.bounds[id] = coordinates.boundsInRoot()
    }
}

/**
 * Интерактивный гайд поверх всего экрана: затемняет фон, подсвечивает целевой
 * элемент, рисует стрелку и облачко с описанием. Кнопки «Далее» и «Закончить».
 * Тап по затемнению — тоже «Далее».
 */
@Composable
fun HelpGuide(
    steps: List<HelpStep>,
    registry: HelpTargetRegistry,
    onFinish: () -> Unit
) {
    if (steps.isEmpty()) {
        LaunchedEffect(Unit) { onFinish() }
        return
    }

    var index by remember { mutableIntStateOf(0) }
    val safeIndex = index.coerceIn(0, steps.lastIndex)
    val step = steps[safeIndex]
    val density = LocalDensity.current

    var container by remember { mutableStateOf(IntSize.Zero) }
    var bubble by remember { mutableStateOf(IntSize.Zero) }

    val highlight = step.targetId?.let { registry.bounds[it] }
        ?.inflate(with(density) { 6.dp.toPx() })

    val margin = with(density) { 12.dp.roundToPx() }
    val arrow = with(density) { 16.dp.roundToPx() }

    // Ширина облачка: не более 320dp и не более ширины экрана минус поля.
    val bubbleWidth = with(density) {
        minOf(320.dp, (container.width.toDp() - 32.dp).coerceAtLeast(200.dp))
    }
    val bubbleW = if (bubble.width > 0) bubble.width else with(density) { bubbleWidth.roundToPx() }
    val bubbleH = bubble.height

    // Ограничение высоты текста: длинное резюме не должно вылезать за экран.
    val contentMaxHeight = with(density) {
        (container.height.toDp() - 180.dp).coerceAtLeast(96.dp)
    }
    val scrollState = rememberScrollState()

    val bx: Int
    val by: Int
    if (highlight != null && bubbleH > 0 && container.width > 0) {
        val spaceBelow = container.height - highlight.bottom
        val placeBelow = spaceBelow >= bubbleH + arrow + margin
        by = if (placeBelow) (highlight.bottom + arrow + margin).toInt()
             else (highlight.top - bubbleH - arrow - margin).toInt().coerceAtLeast(margin)
        bx = (highlight.center.x - bubbleW / 2f).toInt()
            .coerceIn(margin, (container.width - bubbleW - margin).coerceAtLeast(margin))
    } else {
        bx = ((container.width - bubbleW) / 2).coerceAtLeast(margin)
        by = ((container.height - bubbleH) / 2).coerceAtLeast(margin)
    }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { container = it }) {
        // Затемнение, подсветка цели и стрелка.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(safeIndex) {
                    detectTapGestures {
                        if (safeIndex < steps.lastIndex) index = safeIndex + 1
                    }
                }
        ) {
            val scrim = Color.Black.copy(alpha = 0.62f)
            if (highlight == null) {
                drawRect(color = scrim)
            } else {
                // Четыре прямоугольника вокруг подсвеченной области.
                drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, highlight.top))
                drawRect(scrim, topLeft = Offset(0f, highlight.bottom), size = Size(size.width, size.height - highlight.bottom))
                drawRect(scrim, topLeft = Offset(0f, highlight.top), size = Size(highlight.left, highlight.height))
                drawRect(scrim, topLeft = Offset(highlight.right, highlight.top), size = Size(size.width - highlight.right, highlight.height))

                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(highlight.left, highlight.top),
                    size = Size(highlight.width, highlight.height),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )

                if (bubbleH > 0) {
                    val start = Offset(bx + bubbleW / 2f, by + bubbleH / 2f)
                    val end = highlight.center
                    drawLine(Color.White, start, end, strokeWidth = 4.dp.toPx())
                }
            }
        }

        Card(
            modifier = Modifier
                .offset { IntOffset(bx, by) }
                .width(bubbleWidth)
                .onSizeChanged { bubble = it },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Column(
                    modifier = Modifier
                        .heightIn(max = contentMaxHeight)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = stringResource(R.string.help_step_of, safeIndex + 1, steps.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.padding(top = 4.dp))
                    Text(
                        text = step.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.padding(top = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onFinish) { Text(stringResource(R.string.help_finish)) }
                    if (safeIndex < steps.lastIndex) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(onClick = { index = safeIndex + 1 }) { Text(stringResource(R.string.help_next)) }
                    }
                }
            }
        }
    }
}
