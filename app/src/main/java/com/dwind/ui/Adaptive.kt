package com.dwind.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Признаки размера окна, вычисленные из текущей конфигурации. Управляют
 * адаптивностью: телефон получает компактную раскладку, планшет и широкий
 * экран — полную, как раньше.
 */
data class WindowSizeInfo(
    val widthDp: Int,
    val heightDp: Int,
    val isLandscape: Boolean,
    /**
     * Узкий экран (телефон в портрете): имя трека не помещается в одну строку
     * с кнопками плеера — подпись выносим над ними.
     */
    val isNarrowWidth: Boolean,
    /**
     * Низкий экран (телефон в ландшафте): высокую шапку с подзаголовком и
     * полноширинными вкладками заменяем на компактную однострочную.
     */
    val isShortHeight: Boolean
) {
    companion object {
        /** Ниже этой ширины считаем экран «узким» (типичные телефоны — 320–430dp). */
        const val NARROW_WIDTH_THRESHOLD = 480

        /** Ниже этой высоты считаем экран «низким» (телефон в ландшафте). */
        const val SHORT_HEIGHT_THRESHOLD = 480
    }
}

/** Читает размеры окна из конфигурации; пересчитывается при повороте и смене размера. */
@Composable
fun rememberWindowSizeInfo(): WindowSizeInfo {
    val configuration = LocalConfiguration.current
    return WindowSizeInfo(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        isNarrowWidth = configuration.screenWidthDp < WindowSizeInfo.NARROW_WIDTH_THRESHOLD,
        isShortHeight = configuration.screenHeightDp < WindowSizeInfo.SHORT_HEIGHT_THRESHOLD
    )
}

/**
 * Сколько высоты мини-плеер занимает у нижнего края. Списки и плавающие кнопки
 * резервируют эту величину, чтобы контент не прятался за плеером. В компактном
 * (двухстрочном) режиме плеер выше.
 */
object MiniPlayerMetrics {
    val normalInset: Dp = 120.dp
    val compactInset: Dp = 180.dp

    fun inset(compact: Boolean): Dp = if (compact) compactInset else normalInset
}
