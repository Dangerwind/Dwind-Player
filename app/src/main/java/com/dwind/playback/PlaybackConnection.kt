package com.dwind.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlaybackConnection(context: Context) {
    private val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(context, sessionToken).buildAsync()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected
    
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller
    
    init {
        controllerFuture.addListener({
            try {
                val controller = controllerFuture.get()
                _controller.value = controller
                _isConnected.value = true
            } catch (e: Exception) {
                _isConnected.value = false
            }
        }, MoreExecutors.directExecutor())
    }
    
    fun release() {
        MediaController.releaseFuture(controllerFuture)
    }
}
