package com.dwind.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Небольшая обёртка над системным TTS. Один экземпляр создаётся сервисом
 * воспроизведения, второй может использоваться для кнопки «Прослушать» в
 * настройках.
 *
 * Язык выбирается по тексту фразы: кириллица — русский голос, иначе
 * английский. Если нужного голоса нет, пробуем системный.
 */
class VoiceCueSpeaker(context: Context) {
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private val pending = AtomicReference<((Boolean) -> Unit)?>(null)
    private val activeUtteranceId = AtomicReference<String?>(null)

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
            if (!ready.isCompleted) ready.complete(ok)
        }
    }

    /**
     * Читает фразу и ждёт окончания. Возвращает false, если TTS недоступен,
     * не успел инициализироваться или произошла ошибка синтеза.
     *
     * Если для языка фразы выбран конкретный голос и он ещё установлен в
     * системе, используется он. Иначе — автоматический выбор по языку.
     */
    suspend fun speak(
        text: String,
        settings: VoiceCueSpeechSettings = VoiceCueSpeechSettings()
    ): Boolean {
        val readyOk = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!readyOk) return false
        val engine = tts ?: return false

        val locale = localeFor(text)

        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val wantedTag = locale.language
        val selected = settings
            .resolveVoiceName(text, voices.map { it.name }.toSet())
            ?.let { name ->
                voices.firstOrNull {
                    it.name == name && it.locale.language.equals(wantedTag, ignoreCase = true)
                }
            }
        if (selected != null && engine.setVoice(selected) == TextToSpeech.SUCCESS) {
            // Голос выбран — язык и произношение берутся из него.
        } else {
            val language = engine.setLanguage(locale)
            if (language == TextToSpeech.LANG_MISSING_DATA ||
                language == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setLanguage(Locale.getDefault())
            }
        }

        return suspendCancellableCoroutine { continuation ->
            val id = UUID.randomUUID().toString()
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && activeUtteranceId.get() == id) finish(true)
                }

                @Suppress("DEPRECATION")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && activeUtteranceId.get() == id) finish(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && activeUtteranceId.get() == id) finish(false)
                }
            }

            activeUtteranceId.set(id)
            pending.set { ok ->
                if (continuation.isActive) continuation.resume(ok)
            }
            engine.setOnUtteranceProgressListener(listener)

            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result != TextToSpeech.SUCCESS) {
                finish(false)
            }

            continuation.invokeOnCancellation {
                pending.set(null)
                activeUtteranceId.compareAndSet(id, null)
                engine.stop()
            }
        }
    }

    /** Доступные системные голоса для выбранного языка. */
    suspend fun availableVoices(language: VoiceCueLanguage): List<VoiceCueVoice> {
        val readyOk = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!readyOk) return emptyList()
        val engine = tts ?: return emptyList()
        val tag = if (language == VoiceCueLanguage.RUSSIAN) "ru" else "en"

        return runCatching { engine.voices }.getOrNull().orEmpty()
            .asSequence()
            .filter { it.locale.language.equals(tag, ignoreCase = true) }
            .distinctBy { it.name }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            .map {
                VoiceCueVoice(
                    name = it.name,
                    locale = it.locale,
                    quality = it.quality,
                    needsNetwork = it.isNetworkConnectionRequired
                )
            }
            .toList()
    }

    /** Останавливает текущую фразу, если она звучит. */
    fun stop() {
        tts?.stop()
        finish(false)
    }

    fun shutdown() {
        stop()
        if (!ready.isCompleted) ready.complete(false)
        tts?.shutdown()
        tts = null
    }

    private fun finish(ok: Boolean) {
        activeUtteranceId.set(null)
        val callback = pending.getAndSet(null) ?: return
        callback(ok)
    }

    private fun localeFor(text: String): Locale = when (detectVoiceCueLanguage(text)) {
        VoiceCueLanguage.RUSSIAN -> Locale("ru", "RU")
        VoiceCueLanguage.ENGLISH -> Locale.ENGLISH
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 5_000L
    }
}
