package com.dwind.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.dwind.settings.LanguageMode
import com.dwind.settings.LocaleHelper

/**
 * Подменяет локаль интерфейса прямо в дереве Compose, без пересоздания активности.
 *
 * Благодаря этому смена языка применяется мгновенно и целиком: перерисовываются
 * и экраны за меню, и само открытое меню (в отличие от `recreate()`, при котором
 * пару кадров оставалось видно старое окно меню на прежнем языке).
 *
 * [stringResource] читает строки из [LocalContext], поэтому вместе с контекстом
 * обновляем и [LocalConfiguration] — так строка перезапрашивается из ресурсов
 * с нужной локалью.
 */
@Composable
fun LocalizedContent(mode: LanguageMode, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(base, mode) { LocaleHelper.wrap(base, mode) }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        content = content
    )
}
