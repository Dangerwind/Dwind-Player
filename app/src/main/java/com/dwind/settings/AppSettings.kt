package com.dwind.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Режим оформления: как в системе, принудительно светлая или тёмная. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromName(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/** Язык интерфейса: как в системе, принудительно русский или английский. */
enum class LanguageMode {
    SYSTEM, RUSSIAN, ENGLISH;

    companion object {
        fun fromName(value: String?): LanguageMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/** Пользовательские настройки приложения, доступные из Compose. */
data class SettingsState(
    val bpmMin: Int = DEFAULT_BPM_MIN,
    val bpmMax: Int = DEFAULT_BPM_MAX,
    val showGenre: Boolean = true,
    val greenThresholdPercent: Int = DEFAULT_GREEN_THRESHOLD_PERCENT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val languageMode: LanguageMode = LanguageMode.SYSTEM
) {
    companion object {
        const val DEFAULT_BPM_MIN = 40
        const val DEFAULT_BPM_MAX = 255

        /**
         * Порог уверенности автоопределения (в %), начиная с которого цифра BPM
         * показывается зелёной; ниже порога — жёлто-коричневой.
         */
        const val DEFAULT_GREEN_THRESHOLD_PERCENT = 75

        /** Границы регулировки порога «зелёного» BPM (в %). */
        const val GREEN_THRESHOLD_MIN = 50
        const val GREEN_THRESHOLD_MAX = 100

        /** Абсолютные границы, в которых вообще имеет смысл искать темп. */
        const val HARD_MIN = 20
        const val HARD_MAX = 300
    }
}

/**
 * Синглтон-хранилище настроек поверх SharedPreferences.
 * Настроек мало и они нужны сразу из нескольких мест (детектор, UI, плеер),
 * поэтому держим один StateFlow, который читается как из Compose, так и из репозитория.
 */
object AppSettings {
    const val PREFS = "app_settings"
    const val KEY_LANGUAGE_MODE = "language_mode"
    private const val KEY_BPM_MIN = "bpm_min"
    private const val KEY_BPM_MAX = "bpm_max"
    private const val KEY_SHOW_GENRE = "show_genre"
    private const val KEY_GREEN_THRESHOLD = "green_threshold_percent"
    private const val KEY_THEME_MODE = "theme_mode"

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Вызывается один раз из Application.onCreate(). */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _state.value = SettingsState(
            bpmMin = clampMin(p.getInt(KEY_BPM_MIN, SettingsState.DEFAULT_BPM_MIN)),
            bpmMax = clampMax(p.getInt(KEY_BPM_MAX, SettingsState.DEFAULT_BPM_MAX)),
            showGenre = p.getBoolean(KEY_SHOW_GENRE, true),
            greenThresholdPercent = clampThreshold(
                p.getInt(KEY_GREEN_THRESHOLD, SettingsState.DEFAULT_GREEN_THRESHOLD_PERCENT)
            ),
            themeMode = ThemeMode.fromName(p.getString(KEY_THEME_MODE, null)),
            languageMode = LanguageMode.fromName(p.getString(KEY_LANGUAGE_MODE, null))
        )
    }

    val bpmMin: Int get() = _state.value.bpmMin
    val bpmMax: Int get() = _state.value.bpmMax
    val showGenre: Boolean get() = _state.value.showGenre
    val greenThresholdPercent: Int get() = _state.value.greenThresholdPercent
    val themeMode: ThemeMode get() = _state.value.themeMode
    val languageMode: LanguageMode get() = _state.value.languageMode

    /** Порог зелёного цвета как доля 0..1 (для сравнения с уверенностью). */
    val greenThreshold: Double get() = _state.value.greenThresholdPercent / 100.0

    /** Сохраняет диапазон, приводя значения к корректному порядку и границам. */
    fun setBpmRange(min: Int, max: Int) {
        var lo = clampMin(min)
        var hi = clampMax(max)
        if (lo >= hi) {
            // Не даём инвертировать диапазон: подтягиваем вторую границу.
            if (min <= max) hi = (lo + 1).coerceAtMost(SettingsState.HARD_MAX)
            else lo = (hi - 1).coerceAtLeast(SettingsState.HARD_MIN)
        }
        _state.value = _state.value.copy(bpmMin = lo, bpmMax = hi)
        prefs?.edit()?.putInt(KEY_BPM_MIN, lo)?.putInt(KEY_BPM_MAX, hi)?.apply()
    }

    fun setShowGenre(value: Boolean) {
        _state.value = _state.value.copy(showGenre = value)
        prefs?.edit()?.putBoolean(KEY_SHOW_GENRE, value)?.apply()
    }

    /** Сохраняет порог «зелёного» BPM (в процентах, 50..100). */
    fun setGreenThresholdPercent(value: Int) {
        val v = clampThreshold(value)
        _state.value = _state.value.copy(greenThresholdPercent = v)
        prefs?.edit()?.putInt(KEY_GREEN_THRESHOLD, v)?.apply()
    }

    /** Сохраняет режим оформления (системная / светлая / тёмная). */
    fun setThemeMode(mode: ThemeMode) {
        _state.value = _state.value.copy(themeMode = mode)
        prefs?.edit()?.putString(KEY_THEME_MODE, mode.name)?.apply()
    }

    /** Сохраняет язык интерфейса (как в системе / русский / английский). */
    fun setLanguageMode(mode: LanguageMode) {
        _state.value = _state.value.copy(languageMode = mode)
        prefs?.edit()?.putString(KEY_LANGUAGE_MODE, mode.name)?.commit()
    }

    private fun clampMin(v: Int) = v.coerceIn(SettingsState.HARD_MIN, SettingsState.HARD_MAX - 1)
    private fun clampMax(v: Int) = v.coerceIn(SettingsState.HARD_MIN + 1, SettingsState.HARD_MAX)
    private fun clampThreshold(v: Int) =
        v.coerceIn(SettingsState.GREEN_THRESHOLD_MIN, SettingsState.GREEN_THRESHOLD_MAX)
}
