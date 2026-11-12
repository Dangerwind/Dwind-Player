package com.dwind.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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
    val languageMode: LanguageMode = LanguageMode.SYSTEM,
    /** Включён ли фильтр воспроизведения по диапазону BPM. */
    val bpmFilterEnabled: Boolean = false,
    /** Нижняя граница фильтра воспроизведения (независимо от диапазона распознавания). */
    val filterBpmMin: Int = DEFAULT_BPM_MIN,
    /** Верхняя граница фильтра воспроизведения. */
    val filterBpmMax: Int = DEFAULT_BPM_MAX,
    /** Включены ли голосовые подсказки во время воспроизведения. */
    val voiceCuesEnabled: Boolean = false,
    /** Включён ли режим подсказок через равные промежутки. */
    val voiceCueFixedEnabled: Boolean = false,
    /** Интервал режима с равными промежутками, в секундах. */
    val voiceCueFixedIntervalSec: Int = DEFAULT_VOICE_CUE_FIXED_INTERVAL_SEC,
    /** Включён ли режим случайных заданий. */
    val voiceCueRandomEnabled: Boolean = false,
    /** Минимальный случайный интервал, в секундах. */
    val voiceCueRandomMinSec: Int = DEFAULT_VOICE_CUE_RANDOM_MIN_SEC,
    /** Максимальный случайный интервал, в секундах. */
    val voiceCueRandomMaxSec: Int = DEFAULT_VOICE_CUE_RANDOM_MAX_SEC,
    /** Громкость музыки во время фразы, в процентах. */
    val voiceCueDuckPercent: Int = DEFAULT_VOICE_CUE_DUCK_PERCENT,
    /** Фразы режима с равными промежутками. */
    val voiceCueFixedLines: List<String> = emptyList(),
    /** Фразы режима случайных заданий. */
    val voiceCueRandomLines: List<String> = emptyList(),
    /** Имя выбранного русского голоса; null — автоматический выбор. */
    val voiceCueRuVoice: String? = null,
    /** Имя выбранного английского голоса; null — автоматический выбор. */
    val voiceCueEnVoice: String? = null
) {
    /**
     * Попадает ли трек в диапазон воспроизведения.
     * Пока фильтр выключен, разрешены все треки, включая ещё не проанализированные.
     * Когда фильтр включён, трек без BPM считается неподходящим.
     * Сравниваем целую часть — ровно так, как BPM показывается на экране.
     */
    fun isBpmAllowed(bpm: Double?): Boolean {
        if (!bpmFilterEnabled) return true
        if (bpm == null) return false
        return bpm.toInt() in filterBpmMin..filterBpmMax
    }

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

        // ---- Голосовые подсказки --------------------------------------

        /** Минимальный интервал подсказок. */
        const val VOICE_CUE_MIN_INTERVAL_SEC = 15
        /** Максимальный интервал подсказок. */
        const val VOICE_CUE_MAX_INTERVAL_SEC = 600
        /** Шаг настройки интервалов. */
        const val VOICE_CUE_INTERVAL_STEP_SEC = 15
        /** Интервал режима с равными промежутками по умолчанию — 1,5 минуты. */
        const val DEFAULT_VOICE_CUE_FIXED_INTERVAL_SEC = 90
        /** Случайный интервал по умолчанию: от 30 секунд... */
        const val DEFAULT_VOICE_CUE_RANDOM_MIN_SEC = 30
        /** ...до 3 минут. */
        const val DEFAULT_VOICE_CUE_RANDOM_MAX_SEC = 180
        /** Громкость музыки во время фразы по умолчанию — 20%. */
        const val DEFAULT_VOICE_CUE_DUCK_PERCENT = 20
        /** Границы настройки громкости музыки во время фразы. */
        const val VOICE_CUE_DUCK_MIN_PERCENT = 0
        const val VOICE_CUE_DUCK_MAX_PERCENT = 50
        /** Шаг настройки громкости во время фразы. */
        const val VOICE_CUE_DUCK_STEP_PERCENT = 5
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
    private const val KEY_BPM_FILTER_ENABLED = "bpm_filter_enabled"
    private const val KEY_FILTER_BPM_MIN = "filter_bpm_min"
    private const val KEY_FILTER_BPM_MAX = "filter_bpm_max"
    private const val KEY_VOICE_CUES_ENABLED = "voice_cues_enabled"
    private const val KEY_VOICE_CUE_FIXED_ENABLED = "voice_cue_fixed_enabled"
    private const val KEY_VOICE_CUE_FIXED_INTERVAL = "voice_cue_fixed_interval_sec"
    private const val KEY_VOICE_CUE_RANDOM_ENABLED = "voice_cue_random_enabled"
    private const val KEY_VOICE_CUE_RANDOM_MIN = "voice_cue_random_min_sec"
    private const val KEY_VOICE_CUE_RANDOM_MAX = "voice_cue_random_max_sec"
    private const val KEY_VOICE_CUE_DUCK_PERCENT = "voice_cue_duck_percent"
    private const val KEY_VOICE_CUE_FIXED_LINES = "voice_cue_fixed_lines"
    private const val KEY_VOICE_CUE_RANDOM_LINES = "voice_cue_random_lines"
    private const val KEY_VOICE_CUE_RU_VOICE = "voice_cue_ru_voice"
    private const val KEY_VOICE_CUE_EN_VOICE = "voice_cue_en_voice"

    private val stringListSerializer = ListSerializer(String.serializer())
    private val linesJson = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Вызывается один раз из Application.onCreate(). */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val filterLo = clampFilter(p.getInt(KEY_FILTER_BPM_MIN, SettingsState.DEFAULT_BPM_MIN))
        val filterHi = clampFilter(p.getInt(KEY_FILTER_BPM_MAX, SettingsState.DEFAULT_BPM_MAX))
        val cueRandomLo = clampVoiceCueInterval(
            p.getInt(KEY_VOICE_CUE_RANDOM_MIN, SettingsState.DEFAULT_VOICE_CUE_RANDOM_MIN_SEC)
        )
        val cueRandomHi = clampVoiceCueInterval(
            p.getInt(KEY_VOICE_CUE_RANDOM_MAX, SettingsState.DEFAULT_VOICE_CUE_RANDOM_MAX_SEC)
        )
        _state.value = SettingsState(
            bpmMin = clampMin(p.getInt(KEY_BPM_MIN, SettingsState.DEFAULT_BPM_MIN)),
            bpmMax = clampMax(p.getInt(KEY_BPM_MAX, SettingsState.DEFAULT_BPM_MAX)),
            showGenre = p.getBoolean(KEY_SHOW_GENRE, true),
            greenThresholdPercent = clampThreshold(
                p.getInt(KEY_GREEN_THRESHOLD, SettingsState.DEFAULT_GREEN_THRESHOLD_PERCENT)
            ),
            themeMode = ThemeMode.fromName(p.getString(KEY_THEME_MODE, null)),
            languageMode = LanguageMode.fromName(p.getString(KEY_LANGUAGE_MODE, null)),
            bpmFilterEnabled = p.getBoolean(KEY_BPM_FILTER_ENABLED, false),
            filterBpmMin = minOf(filterLo, filterHi),
            filterBpmMax = maxOf(filterLo, filterHi),
            voiceCuesEnabled = p.getBoolean(KEY_VOICE_CUES_ENABLED, false),
            voiceCueFixedEnabled = p.getBoolean(KEY_VOICE_CUE_FIXED_ENABLED, false),
            voiceCueFixedIntervalSec = clampVoiceCueInterval(
                p.getInt(
                    KEY_VOICE_CUE_FIXED_INTERVAL,
                    SettingsState.DEFAULT_VOICE_CUE_FIXED_INTERVAL_SEC
                )
            ),
            voiceCueRandomEnabled = p.getBoolean(KEY_VOICE_CUE_RANDOM_ENABLED, false),
            voiceCueRandomMinSec = minOf(cueRandomLo, cueRandomHi),
            voiceCueRandomMaxSec = maxOf(cueRandomLo, cueRandomHi),
            voiceCueDuckPercent = clampVoiceCueDuck(
                p.getInt(KEY_VOICE_CUE_DUCK_PERCENT, SettingsState.DEFAULT_VOICE_CUE_DUCK_PERCENT)
            ),
            voiceCueFixedLines = decodeLines(p.getString(KEY_VOICE_CUE_FIXED_LINES, null)),
            voiceCueRandomLines = decodeLines(p.getString(KEY_VOICE_CUE_RANDOM_LINES, null)),
            voiceCueRuVoice = p.getString(KEY_VOICE_CUE_RU_VOICE, null)?.takeIf { it.isNotBlank() },
            voiceCueEnVoice = p.getString(KEY_VOICE_CUE_EN_VOICE, null)?.takeIf { it.isNotBlank() }
        )
    }

    val bpmMin: Int get() = _state.value.bpmMin
    val bpmMax: Int get() = _state.value.bpmMax
    val showGenre: Boolean get() = _state.value.showGenre
    val greenThresholdPercent: Int get() = _state.value.greenThresholdPercent
    val themeMode: ThemeMode get() = _state.value.themeMode
    val languageMode: LanguageMode get() = _state.value.languageMode
    val bpmFilterEnabled: Boolean get() = _state.value.bpmFilterEnabled
    val filterBpmMin: Int get() = _state.value.filterBpmMin
    val filterBpmMax: Int get() = _state.value.filterBpmMax
    val voiceCuesEnabled: Boolean get() = _state.value.voiceCuesEnabled
    val voiceCueFixedEnabled: Boolean get() = _state.value.voiceCueFixedEnabled
    val voiceCueFixedIntervalSec: Int get() = _state.value.voiceCueFixedIntervalSec
    val voiceCueRandomEnabled: Boolean get() = _state.value.voiceCueRandomEnabled
    val voiceCueRandomMinSec: Int get() = _state.value.voiceCueRandomMinSec
    val voiceCueRandomMaxSec: Int get() = _state.value.voiceCueRandomMaxSec
    val voiceCueDuckPercent: Int get() = _state.value.voiceCueDuckPercent
    val voiceCueFixedLines: List<String> get() = _state.value.voiceCueFixedLines
    val voiceCueRandomLines: List<String> get() = _state.value.voiceCueRandomLines
    val voiceCueRuVoice: String? get() = _state.value.voiceCueRuVoice
    val voiceCueEnVoice: String? get() = _state.value.voiceCueEnVoice

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

    /** Включает или выключает фильтр воспроизведения по диапазону BPM. */
    fun setBpmFilterEnabled(value: Boolean) {
        _state.value = _state.value.copy(bpmFilterEnabled = value)
        prefs?.edit()?.putBoolean(KEY_BPM_FILTER_ENABLED, value)?.apply()
    }

    /**
     * Сохраняет диапазон фильтра воспроизведения.
     * Границы могут совпадать: если в списке единственный BPM, фильтр работает по нему.
     */
    fun setFilterBpmRange(min: Int, max: Int) {
        var lo = clampFilter(min)
        var hi = clampFilter(max)
        if (lo > hi) {
            val t = lo
            lo = hi
            hi = t
        }
        _state.value = _state.value.copy(filterBpmMin = lo, filterBpmMax = hi)
        prefs?.edit()?.putInt(KEY_FILTER_BPM_MIN, lo)?.putInt(KEY_FILTER_BPM_MAX, hi)?.apply()
    }

    // ---- Голосовые подсказки ------------------------------------------

    fun setVoiceCuesEnabled(value: Boolean) {
        _state.value = _state.value.copy(voiceCuesEnabled = value)
        prefs?.edit()?.putBoolean(KEY_VOICE_CUES_ENABLED, value)?.apply()
    }

    fun setVoiceCueFixedEnabled(value: Boolean) {
        _state.value = _state.value.copy(voiceCueFixedEnabled = value)
        prefs?.edit()?.putBoolean(KEY_VOICE_CUE_FIXED_ENABLED, value)?.apply()
    }

    fun setVoiceCueFixedIntervalSec(value: Int) {
        val v = clampVoiceCueInterval(value)
        _state.value = _state.value.copy(voiceCueFixedIntervalSec = v)
        prefs?.edit()?.putInt(KEY_VOICE_CUE_FIXED_INTERVAL, v)?.apply()
    }

    fun setVoiceCueRandomEnabled(value: Boolean) {
        _state.value = _state.value.copy(voiceCueRandomEnabled = value)
        prefs?.edit()?.putBoolean(KEY_VOICE_CUE_RANDOM_ENABLED, value)?.apply()
    }

    /** Сохраняет случайный диапазон, приводя границы к корректному порядку. */
    fun setVoiceCueRandomRange(min: Int, max: Int) {
        var lo = clampVoiceCueInterval(min)
        var hi = clampVoiceCueInterval(max)
        if (lo > hi) {
            val t = lo
            lo = hi
            hi = t
        }
        _state.value = _state.value.copy(voiceCueRandomMinSec = lo, voiceCueRandomMaxSec = hi)
        prefs?.edit()?.putInt(KEY_VOICE_CUE_RANDOM_MIN, lo)?.putInt(KEY_VOICE_CUE_RANDOM_MAX, hi)?.apply()
    }

    fun setVoiceCueDuckPercent(value: Int) {
        val v = clampVoiceCueDuck(value)
        _state.value = _state.value.copy(voiceCueDuckPercent = v)
        prefs?.edit()?.putInt(KEY_VOICE_CUE_DUCK_PERCENT, v)?.apply()
    }

    fun setVoiceCueFixedLines(lines: List<String>) {
        val cleaned = cleanLines(lines)
        _state.value = _state.value.copy(voiceCueFixedLines = cleaned)
        prefs?.edit()?.putString(KEY_VOICE_CUE_FIXED_LINES, encodeLines(cleaned))?.apply()
    }

    fun setVoiceCueRandomLines(lines: List<String>) {
        val cleaned = cleanLines(lines)
        _state.value = _state.value.copy(voiceCueRandomLines = cleaned)
        prefs?.edit()?.putString(KEY_VOICE_CUE_RANDOM_LINES, encodeLines(cleaned))?.apply()
    }

    /** Сохраняет выбранный русский голос; null — автоматический выбор. */
    fun setVoiceCueRuVoice(name: String?) {
        val value = name?.takeIf { it.isNotBlank() }
        _state.value = _state.value.copy(voiceCueRuVoice = value)
        prefs?.edit()?.apply {
            if (value == null) remove(KEY_VOICE_CUE_RU_VOICE)
            else putString(KEY_VOICE_CUE_RU_VOICE, value)
        }?.apply()
    }

    /** Сохраняет выбранный английский голос; null — автоматический выбор. */
    fun setVoiceCueEnVoice(name: String?) {
        val value = name?.takeIf { it.isNotBlank() }
        _state.value = _state.value.copy(voiceCueEnVoice = value)
        prefs?.edit()?.apply {
            if (value == null) remove(KEY_VOICE_CUE_EN_VOICE)
            else putString(KEY_VOICE_CUE_EN_VOICE, value)
        }?.apply()
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
    private fun clampFilter(v: Int) = v.coerceIn(SettingsState.HARD_MIN, SettingsState.HARD_MAX)
    private fun clampThreshold(v: Int) =
        v.coerceIn(SettingsState.GREEN_THRESHOLD_MIN, SettingsState.GREEN_THRESHOLD_MAX)

    private fun clampVoiceCueInterval(v: Int): Int {
        val clamped = v.coerceIn(
            SettingsState.VOICE_CUE_MIN_INTERVAL_SEC,
            SettingsState.VOICE_CUE_MAX_INTERVAL_SEC
        )
        val step = SettingsState.VOICE_CUE_INTERVAL_STEP_SEC
        return ((clamped + step / 2) / step) * step
    }

    private fun clampVoiceCueDuck(v: Int): Int {
        val clamped = v.coerceIn(
            SettingsState.VOICE_CUE_DUCK_MIN_PERCENT,
            SettingsState.VOICE_CUE_DUCK_MAX_PERCENT
        )
        val step = SettingsState.VOICE_CUE_DUCK_STEP_PERCENT
        return ((clamped + step / 2) / step) * step
    }

    private fun cleanLines(lines: List<String>): List<String> =
        lines.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun encodeLines(lines: List<String>): String =
        linesJson.encodeToString(stringListSerializer, lines)

    private fun decodeLines(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = runCatching {
            linesJson.decodeFromString(stringListSerializer, raw)
        }.getOrDefault(emptyList())
        return cleanLines(decoded)
    }
}
