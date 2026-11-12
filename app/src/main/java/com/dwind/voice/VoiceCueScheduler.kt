package com.dwind.voice

import com.dwind.settings.SettingsState
import kotlin.random.Random

/** Режим голосовых подсказок. */
enum class VoiceCueMode {
    /** Через равные промежутки. */
    FIXED,

    /** Случайные задания. */
    RANDOM
}

/** Готовая к озвучиванию фраза. */
data class VoiceCueEvent(
    val mode: VoiceCueMode,
    val line: String
)

/**
 * Настройки, от которых зависит планировщик. Вынесены отдельно, чтобы
 * сервис мог реагировать только на изменения голосовых подсказок, а не на
 * любые настройки приложения.
 */
data class VoiceCueConfig(
    val enabled: Boolean = false,
    val fixedEnabled: Boolean = false,
    val fixedIntervalSec: Int = SettingsState.DEFAULT_VOICE_CUE_FIXED_INTERVAL_SEC,
    val randomEnabled: Boolean = false,
    val randomMinSec: Int = SettingsState.DEFAULT_VOICE_CUE_RANDOM_MIN_SEC,
    val randomMaxSec: Int = SettingsState.DEFAULT_VOICE_CUE_RANDOM_MAX_SEC,
    val duckPercent: Int = SettingsState.DEFAULT_VOICE_CUE_DUCK_PERCENT,
    val fixedLines: List<String> = emptyList(),
    val randomLines: List<String> = emptyList(),
    val speech: VoiceCueSpeechSettings = VoiceCueSpeechSettings()
)

fun SettingsState.toVoiceCueConfig(): VoiceCueConfig = VoiceCueConfig(
    enabled = voiceCuesEnabled,
    fixedEnabled = voiceCueFixedEnabled,
    fixedIntervalSec = voiceCueFixedIntervalSec,
    randomEnabled = voiceCueRandomEnabled,
    randomMinSec = voiceCueRandomMinSec,
    randomMaxSec = voiceCueRandomMaxSec,
    duckPercent = voiceCueDuckPercent,
    fixedLines = voiceCueFixedLines,
    randomLines = voiceCueRandomLines,
    speech = toVoiceCueSpeechSettings()
)

/**
 * Чистая логика расписания голосовых подсказок.
 *
 * Время передаётся снаружи (монотонные миллисекунды), поэтому класс легко
 * тестируется без реальных задержек. Сервис вызывает [onConfigChanged] и
 * [onPlayingChanged], затем ждёт [nextDelayMs] и забирает готовые события
 * через [poll].
 */
class VoiceCueScheduler(private val random: Random = Random.Default) {
    private var config = VoiceCueConfig()
    private var playing = false

    private var fixedDueAt: Long? = null
    private var randomDueAt: Long? = null
    private var fixedRemainingMs: Long? = null
    private var randomRemainingMs: Long? = null

    private var lastFixedLine: String? = null
    private var lastRandomLine: String? = null

    /** Применяет новые настройки. Изменённый интервал начинает отсчёт заново. */
    fun onConfigChanged(newConfig: VoiceCueConfig, now: Long) {
        val old = config
        config = newConfig

        if (!newConfig.enabled) {
            clearSchedule()
            return
        }

        val fixedAvailable = newConfig.fixedEnabled && newConfig.fixedLines.isNotEmpty()
        val fixedWasAvailable = old.enabled && old.fixedEnabled && old.fixedLines.isNotEmpty()
        if (!fixedAvailable) {
            fixedDueAt = null
            fixedRemainingMs = null
        } else if (!fixedWasAvailable || old.fixedIntervalSec != newConfig.fixedIntervalSec) {
            scheduleFixed(now)
        } else if (fixedDueAt == null && fixedRemainingMs == null) {
            scheduleFixed(now)
        }

        val randomAvailable = newConfig.randomEnabled && newConfig.randomLines.isNotEmpty()
        val randomWasAvailable = old.enabled && old.randomEnabled && old.randomLines.isNotEmpty()
        val randomRangeChanged = old.randomMinSec != newConfig.randomMinSec ||
            old.randomMaxSec != newConfig.randomMaxSec
        if (!randomAvailable) {
            randomDueAt = null
            randomRemainingMs = null
        } else if (!randomWasAvailable || randomRangeChanged) {
            scheduleRandom(now)
        } else if (randomDueAt == null && randomRemainingMs == null) {
            scheduleRandom(now)
        }
    }

    /**
     * Переключает состояние воспроизведения. На паузе отсчёт замирает,
     * после продолжения идёт с того же места.
     */
    fun onPlayingChanged(newPlaying: Boolean, now: Long) {
        if (playing == newPlaying) return
        playing = newPlaying

        if (newPlaying) {
            resumeFixed(now)
            resumeRandom(now)
        } else {
            fixedRemainingMs = fixedDueAt?.let { (it - now).coerceAtLeast(0L) }
            fixedDueAt = null
            randomRemainingMs = randomDueAt?.let { (it - now).coerceAtLeast(0L) }
            randomDueAt = null
        }
    }

    /** Сколько миллисекунд ждать до ближайшей подсказки; null — ждать нечего. */
    fun nextDelayMs(now: Long): Long? {
        if (!playing || !config.enabled) return null
        val due = listOfNotNull(fixedDueAt, randomDueAt).minOrNull() ?: return null
        return (due - now).coerceAtLeast(0L)
    }

    /**
     * Забирает все подсказки, время которых наступило. Если оба режима
     * сработали одновременно, вернутся обе — сервис озвучит их по очереди.
     */
    fun poll(now: Long): List<VoiceCueEvent> {
        if (!playing || !config.enabled) return emptyList()
        val events = mutableListOf<VoiceCueEvent>()

        val fixed = fixedDueAt
        if (fixed != null && now >= fixed) {
            if (config.fixedEnabled && config.fixedLines.isNotEmpty()) {
                val line = pickLine(config.fixedLines, lastFixedLine)
                lastFixedLine = line
                events += VoiceCueEvent(VoiceCueMode.FIXED, line)
                scheduleFixed(now)
            } else {
                fixedDueAt = null
            }
        }

        val randomDue = randomDueAt
        if (randomDue != null && now >= randomDue) {
            if (config.randomEnabled && config.randomLines.isNotEmpty()) {
                val line = pickLine(config.randomLines, lastRandomLine)
                lastRandomLine = line
                events += VoiceCueEvent(VoiceCueMode.RANDOM, line)
                scheduleRandom(now)
            } else {
                randomDueAt = null
            }
        }

        return events
    }

    private fun scheduleFixed(now: Long) {
        val intervalMs = intervalSec(config.fixedIntervalSec) * 1000L
        if (playing) {
            fixedDueAt = now + intervalMs
            fixedRemainingMs = null
        } else {
            fixedDueAt = null
            fixedRemainingMs = intervalMs
        }
    }

    private fun scheduleRandom(now: Long) {
        val intervalMs = randomIntervalSec() * 1000L
        if (playing) {
            randomDueAt = now + intervalMs
            randomRemainingMs = null
        } else {
            randomDueAt = null
            randomRemainingMs = intervalMs
        }
    }

    private fun resumeFixed(now: Long) {
        if (!config.fixedEnabled || config.fixedLines.isEmpty()) return
        val remaining = fixedRemainingMs
        if (remaining != null) {
            fixedDueAt = now + remaining
            fixedRemainingMs = null
        } else if (fixedDueAt == null) {
            scheduleFixed(now)
        }
    }

    private fun resumeRandom(now: Long) {
        if (!config.randomEnabled || config.randomLines.isEmpty()) return
        val remaining = randomRemainingMs
        if (remaining != null) {
            randomDueAt = now + remaining
            randomRemainingMs = null
        } else if (randomDueAt == null) {
            scheduleRandom(now)
        }
    }

    private fun clearSchedule() {
        fixedDueAt = null
        randomDueAt = null
        fixedRemainingMs = null
        randomRemainingMs = null
    }

    private fun intervalSec(value: Int): Int = value.coerceIn(
        SettingsState.VOICE_CUE_MIN_INTERVAL_SEC,
        SettingsState.VOICE_CUE_MAX_INTERVAL_SEC
    )

    private fun randomIntervalSec(): Int {
        val min = intervalSec(config.randomMinSec)
        val max = intervalSec(config.randomMaxSec).coerceAtLeast(min)
        return if (min >= max) min else random.nextInt(min, max + 1)
    }

    private fun pickLine(lines: List<String>, last: String?): String {
        if (lines.size == 1) return lines.first()
        val candidates = if (last != null) lines.filter { it != last } else lines
        val pool = candidates.ifEmpty { lines }
        return pool[random.nextInt(pool.size)]
    }
}
