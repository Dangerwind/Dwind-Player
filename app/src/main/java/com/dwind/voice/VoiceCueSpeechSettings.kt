package com.dwind.voice

import com.dwind.settings.SettingsState
import java.util.Locale

/** Язык фразы, определённый по её тексту. */
enum class VoiceCueLanguage {
    RUSSIAN,
    ENGLISH
}

/** Доступный системный голос для выбора в настройках. */
data class VoiceCueVoice(
    val name: String,
    val locale: Locale,
    val quality: Int,
    val needsNetwork: Boolean
)

/**
 * Параметры озвучки: выбранные голоса для каждого языка.
 * null в поле голоса означает автоматический выбор системного голоса.
 */
data class VoiceCueSpeechSettings(
    val ruVoice: String? = null,
    val enVoice: String? = null
) {
    fun voiceFor(text: String): String? = when (detectVoiceCueLanguage(text)) {
        VoiceCueLanguage.RUSSIAN -> ruVoice
        VoiceCueLanguage.ENGLISH -> enVoice
    }

    /**
     * Имя сохранённого голоса, если он всё ещё есть в системе. Если голос
     * пропал (сменился движок или удалены данные), возвращаем null — тогда
     * используется автоматический выбор по языку.
     */
    fun resolveVoiceName(text: String, availableNames: Set<String>): String? =
        voiceFor(text)?.takeIf { it in availableNames }
}

fun detectVoiceCueLanguage(text: String): VoiceCueLanguage =
    if (text.any { it in '\u0400'..'\u04FF' }) VoiceCueLanguage.RUSSIAN
    else VoiceCueLanguage.ENGLISH

fun SettingsState.toVoiceCueSpeechSettings(): VoiceCueSpeechSettings = VoiceCueSpeechSettings(
    ruVoice = voiceCueRuVoice,
    enVoice = voiceCueEnVoice
)
