package com.dwind.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dwind.settings.AppSettings
import com.dwind.voice.VoiceCueConfig
import com.dwind.voice.VoiceCueEvent
import com.dwind.voice.VoiceCueScheduler
import com.dwind.voice.VoiceCueSpeaker
import com.dwind.voice.toVoiceCueConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Голосовые подсказки во время воспроизведения.
 *
 * Живёт в [PlaybackService], поэтому таймеры и озвучка продолжают работать
 * при свёрнутом приложении и выключенном экране. Перед фразой музыка плавно
 * притихает, после — возвращается к прежней громкости.
 */
class VoiceCueController(
    context: Context,
    private val player: ExoPlayer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speaker = VoiceCueSpeaker(context.applicationContext)
    private val scheduler = VoiceCueScheduler()
    private val events = Channel<List<VoiceCueEvent>>(Channel.UNLIMITED)

    private var config: VoiceCueConfig = AppSettings.state.value.toVoiceCueConfig()
    private var loopJob: Job? = null
    private var released = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            scheduler.onPlayingChanged(isPlaying, now())
            restartLoop()
        }
    }

    init {
        player.addListener(playerListener)
        scheduler.onConfigChanged(config, now())
        scheduler.onPlayingChanged(player.isPlaying, now())

        scope.launch {
            AppSettings.state
                .map { it.toVoiceCueConfig() }
                .distinctUntilChanged()
                .collect { newConfig ->
                    config = newConfig
                    scheduler.onConfigChanged(newConfig, now())
                    if (!newConfig.enabled) {
                        // Выключили во время фразы — сразу замолкаем.
                        speaker.stop()
                    }
                    restartLoop()
                }
        }

        // Фразы озвучиваются строго по одной: даже если оба режима совпали,
        // выбираем одну случайную фразу, а не читаем весь список.
        scope.launch {
            for (batch in events) {
                if (config.enabled) announce(batch)
            }
        }

        restartLoop()
    }

    private fun restartLoop() {
        loopJob?.cancel()
        if (!config.enabled) return
        loopJob = scope.launch {
            while (isActive) {
                val delayMs = scheduler.nextDelayMs(now()) ?: break
                if (delayMs > 0) delay(delayMs)
                val due = scheduler.poll(now())
                if (due.isEmpty()) {
                    // Защита от холостого цикла, если событие успело отмениться.
                    delay(500)
                } else {
                    events.send(due)
                }
            }
        }
    }

    private suspend fun announce(batch: List<VoiceCueEvent>) {
        if (!config.enabled) return
        val originalVolume = player.volume
        val duckVolume = config.duckPercent
            .coerceIn(0, 50) / 100f

        try {
            fadeTo(duckVolume, 300)
            // Даём музыке притихнуть, прежде чем начнётся речь.
            delay(1_000)
            // На каждое срабатывание читаем ровно одну случайную фразу.
            val event = batch.randomOrNull() ?: return
            if (config.enabled) speaker.speak(event.line, config.speech)
        } finally {
            speaker.stop()
            if (released) {
                player.volume = originalVolume
            } else {
                withContext(NonCancellable) {
                    fadeTo(originalVolume, 250)
                }
            }
        }
    }

    private suspend fun fadeTo(target: Float, durationMs: Long) {
        val start = player.volume
        if (abs(start - target) < 0.01f) {
            player.volume = target
            return
        }
        val steps = 10
        for (i in 1..steps) {
            player.volume = start + (target - start) * i / steps
            delay(durationMs / steps)
        }
    }

    fun release() {
        released = true
        loopJob?.cancel()
        scope.cancel()
        speaker.shutdown()
        player.removeListener(playerListener)
    }

    private fun now(): Long = SystemClock.elapsedRealtime()
}
