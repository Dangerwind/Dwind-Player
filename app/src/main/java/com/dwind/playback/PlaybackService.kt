package com.dwind.playback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.random.Random

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var voiceCues: VoiceCueController? = null
    private var player: ExoPlayer? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Команда сброса может прийти раньше, чем сервис применит новую очередь
     * или включит перемешивание: контроллер отправляет обычные команды
     * плеера с небольшой задержкой, а служебные — сразу. Поэтому сброс
     * откладывается до события плеера, а [fallbackShuffleReset] страхует
     * случай, когда команда пришла уже после применения состояния.
     */
    private var shuffleResetPending = false

    private val fallbackShuffleReset = Runnable {
        val exoPlayer = player ?: return@Runnable
        if (!shuffleResetPending) return@Runnable
        // Если режим ещё не включился, сброс выполнится по событию включения.
        if (exoPlayer.shuffleModeEnabled) {
            clearPendingShuffleReset()
            resetShuffleOrder(exoPlayer)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        exoPlayer.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                applyPendingShuffleReset()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                // Выключение перемешивания не отменяет заказ на сброс: он
                // понадобится, когда режим включат снова, — проход начнётся
                // заново с текущего трека.
                if (shuffleModeEnabled) applyPendingShuffleReset()
            }
        })
        voiceCues = VoiceCueController(this, exoPlayer)
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(fallbackShuffleReset)
        voiceCues?.release()
        voiceCues = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    /**
     * Служебная команда для сброса порядка перемешивания.
     *
     * Через неё контроллер сообщает сервису, что очередь нужно перемешать
     * заново: текущий трек остаётся первым, а остальные идут в новом случайном
     * порядке. Без этого ExoPlayer помнил бы старый порядок, и после ручного
     * выбора трека часть очереди уже считалась бы проигранной.
     */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(RESET_SHUFFLE_COMMAND, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != RESET_SHUFFLE_COMMAND) {
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            shuffleResetPending = true
            mainHandler.removeCallbacks(fallbackShuffleReset)
            mainHandler.postDelayed(fallbackShuffleReset, SHUFFLE_RESET_FALLBACK_MS)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun applyPendingShuffleReset() {
        if (!shuffleResetPending) return
        val exoPlayer = player ?: return
        if (!exoPlayer.shuffleModeEnabled) return
        clearPendingShuffleReset()
        resetShuffleOrder(exoPlayer)
    }

    private fun clearPendingShuffleReset() {
        shuffleResetPending = false
        mainHandler.removeCallbacks(fallbackShuffleReset)
    }

    /** Текущий трек — первым, все остальные — в новом случайном порядке. */
    private fun resetShuffleOrder(player: Player) {
        val exoPlayer = player as? ExoPlayer ?: return
        if (!exoPlayer.shuffleModeEnabled) return
        val count = exoPlayer.mediaItemCount
        if (count < 2) return
        val current = exoPlayer.currentMediaItemIndex
        if (current !in 0 until count) return

        val seed = System.nanoTime()
        val order = IntArray(count)
        order[0] = current
        var position = 1
        for (index in (0 until count).filter { it != current }.shuffled(Random(seed))) {
            order[position++] = index
        }
        exoPlayer.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, seed))
    }

    companion object {
        /** Страховка на случай, если команда сброса пришла после команд плеера. */
        private const val SHUFFLE_RESET_FALLBACK_MS = 500L

        /** Команда «пересобрать порядок перемешивания» для текущей очереди. */
        const val RESET_SHUFFLE_COMMAND = "com.dwind.RESET_SHUFFLE"
    }
}
