package com.dwind.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCueSpeechSettingsTest {
    @Test
    fun detectsRussianByCyrillic() {
        assertEquals(VoiceCueLanguage.RUSSIAN, detectVoiceCueLanguage("Смените партнёра"))
    }

    @Test
    fun detectsEnglishByLatinText() {
        assertEquals(VoiceCueLanguage.ENGLISH, detectVoiceCueLanguage("Change partners"))
    }

    @Test
    fun mixedTextWithCyrillicCountsAsRussian() {
        assertEquals(VoiceCueLanguage.RUSSIAN, detectVoiceCueLanguage("Step 1 — шаг вперёд"))
    }

    @Test
    fun picksVoiceForDetectedLanguage() {
        val settings = VoiceCueSpeechSettings(ruVoice = "ru-voice", enVoice = "en-voice")
        assertEquals("ru-voice", settings.voiceFor("Привет"))
        assertEquals("en-voice", settings.voiceFor("Hello"))
    }

    @Test
    fun automaticSelectionWhenNoVoiceSaved() {
        val settings = VoiceCueSpeechSettings()
        assertNull(settings.voiceFor("Привет"))
        assertNull(settings.voiceFor("Hello"))
    }

    @Test
    fun keepsSavedVoiceWhenItIsAvailable() {
        val settings = VoiceCueSpeechSettings(ruVoice = "ru-voice")
        assertEquals(
            "ru-voice",
            settings.resolveVoiceName("Привет", setOf("ru-voice", "other"))
        )
    }

    @Test
    fun fallsBackWhenSavedVoiceDisappeared() {
        val settings = VoiceCueSpeechSettings(ruVoice = "gone")
        assertNull(settings.resolveVoiceName("Привет", setOf("other")))
    }

    @Test
    fun savedVoiceIsIgnoredForOtherLanguage() {
        val settings = VoiceCueSpeechSettings(ruVoice = "ru-voice")
        assertNull(settings.resolveVoiceName("Hello", setOf("ru-voice")))
    }
}
