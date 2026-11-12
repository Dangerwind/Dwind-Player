package com.dwind.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
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
     *
     * Результат — обёртка над [base], а не «голый» контекст из
     * [Context.createConfigurationContext]: Compose ищет владельцев
     * (`ActivityResultRegistryOwner`, `OnBackPressedDispatcherOwner` и т. п.),
     * поднимаясь по цепочке `baseContext`. Если её разорвать, приложение падает
     * с «No ActivityResultRegistryOwner was provided» — например, при открытии
     * меню с экспортом/импортом после смены языка.
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
        val localized = base.createConfigurationContext(configuration)
        return LocalizedContext(base, localized)
    }

    /** Локаль, применённая к контексту (первая в списке). */
    fun localeOf(context: Context): Locale =
        context.resources.configuration.locales[0]

    /**
     * Контекст с ресурсами переопределённой локали, который при этом сохраняет
     * исходный контекст в цепочке `baseContext` (см. [wrap]).
     */
    private class LocalizedContext(
        base: Context,
        localized: Context
    ) : ContextWrapper(base) {
        private val localizedResources: Resources = localized.resources
        private val localizedTheme: Resources.Theme = localized.theme

        override fun getResources(): Resources = localizedResources

        override fun getAssets(): AssetManager = localizedResources.assets

        override fun getTheme(): Resources.Theme = localizedTheme
    }
}

