package com.dwind.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Применяет выбранный язык интерфейса к контексту.
 *
 * [LanguageMode.SYSTEM] ничего не меняет — приложение берёт язык системы
 * (русский при русской системе, английский для всех остальных). Режимы
 * RUSSIAN и ENGLISH принудительно переопределяют локаль.
 */
object LocaleHelper {

    /** Режим, сохранённый в настройках (может быть null, если ничего не выбрано). */
    fun readMode(context: Context): String? =
        context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
            .getString(AppSettings.KEY_LANGUAGE_MODE, null)

    /** Оборачивает базовый контекст сохранённой настройкой языка. */
    fun wrap(base: Context): Context =
        wrap(base, LanguageMode.fromName(readMode(base)))

    /**
     * Возвращает контекст с локалью выбранного режима.
     * Для [LanguageMode.SYSTEM] возвращается исходный контекст (язык системы).
     */
    fun wrap(base: Context, mode: LanguageMode): Context {
        val language = when (mode) {
            LanguageMode.RUSSIAN -> "ru"
            LanguageMode.ENGLISH -> "en"
            LanguageMode.SYSTEM -> return base
        }
        val locale = Locale(language)
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(configuration)
    }

    /** Локаль, применённая к контексту (первая в списке). */
    fun localeOf(context: Context): Locale =
        context.resources.configuration.locales[0]
}
