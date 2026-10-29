package com.dwind.bpm

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.Executors
import kotlin.math.max

object BpmExecution {
    private val cpuCount = Runtime.getRuntime().availableProcessors()
    val permits = max(1, cpuCount / 2)
    
    private val dispatcher = Executors.newFixedThreadPool(permits) { r ->
        Thread(r, "bpm-detector").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    
    private val semaphore = Semaphore(permits)
    
    fun getDispatcher() = dispatcher
    
    suspend fun <T> withLimitedPermit(block: suspend () -> T): T {
        return semaphore.withPermit { block() }
    }
}
