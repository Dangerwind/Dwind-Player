package com.dwind.bpm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Результат определения BPM.
 *
 * @param bpm наиболее вероятный темп (или null, если определить не удалось)
 * @param confidence уверенность 0..1
 * @param method использованный метод
 * @param error код ошибки (если есть)
 * @param candidates альтернативные гипотезы темпа
 * @param genre наиболее вероятный жанр (мягкая оценка)
 * @param genreConfidence уверенность жанра 0..1
 * @param beatPositions позиции долей в секундах относительно начала проанализированного фрагмента
 */
data class BpmResult(
    val bpm: Double?,
    val confidence: Double?,
    val method: String?,
    val error: String? = null,
    val candidates: List<Double> = emptyList(),
    val genre: String? = null,
    val genreConfidence: Double? = null,
    val beatPositions: List<Double> = emptyList()
)

interface BpmAnalyzer {
    suspend fun analyze(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean = { false },
        minBpm: Double = 55.0,
        maxBpm: Double = 255.0
    ): BpmResult
}

class MediaBpmAnalyzer : BpmAnalyzer {
    override suspend fun analyze(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean,
        minBpm: Double,
        maxBpm: Double
    ): BpmResult =
        withContext(BpmExecution.getDispatcher()) {
            BpmExecution.withLimitedPermit {
                try {
                    val decoded = decodeMono(context, uri, isCancelled)
                    if (decoded == null || decoded.samples.isEmpty() || decoded.sampleRate <= 0) {
                        return@withLimitedPermit BpmResult(null, null, null, error = "decode_failed")
                    }
                    if (isCancelled()) return@withLimitedPermit BpmResult(null, null, METHOD, error = "cancelled")

                    val lo = minBpm.coerceIn(20.0, 299.0)
                    val hi = maxBpm.coerceIn(lo + 1.0, 300.0)
                    val detector = EnsembleBpmDetector(
                        BpmDetectorConfig(minBpm = lo, maxBpm = hi)
                    )
                    when (val result = detector.detect(
                        inputSamples = decoded.samples,
                        inputSampleRate = decoded.sampleRate,
                        isActive = { !isCancelled() }
                    )) {
                        is BpmDetectionResult.Success -> BpmResult(
                            bpm = result.bpm,
                            confidence = result.quality.toDouble(),
                            method = METHOD,
                            candidates = result.candidates,
                            genre = result.genre,
                            genreConfidence = result.genreConfidence,
                            beatPositions = result.beatPositions
                        )
                        is BpmDetectionResult.Failure -> BpmResult(null, null, METHOD, error = result.reason.name)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BpmResult(null, null, METHOD, error = e.message)
                }
            }
        }

    companion object {
        private const val METHOD = "ensemble-v2"
    }

    private data class DecodedPcm(val samples: FloatArray, val sampleRate: Int)

    private fun decodeMono(context: Context, uri: Uri, isCancelled: () -> Boolean): DecodedPcm? {
        val extractor = MediaExtractor()
        try {
            runCatching { extractor.setDataSource(context, uri, null) }.getOrNull() ?: return null
            var trackIndex = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    trackIndex = i
                    mime = m
                    break
                }
            }
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val sampleRateIn = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100)
            val channelsIn = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
            val trackDurUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(-1L)
            val trackSec = if (trackDurUs > 0) trackDurUs / 1000000.0 else 0.0

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val targetRate = 44100
            val maxSeconds = 50
            if (trackSec > maxSeconds) {
                val startSec = 26.7.coerceIn(0.0, (trackSec - maxSeconds).coerceAtLeast(0.0))
                if (startSec > 0) {
                    runCatching { extractor.seekTo((startSec * 1000000.0).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
                }
            }

            val out = ArrayList<Float>(targetRate * maxSeconds)
            val info = MediaCodec.BufferInfo()
            val bytesBuf = ByteArray(256 * 1024)
            val ratio = sampleRateIn.toDouble() / targetRate.toDouble()
            var decPos = 0.0
            var decSum = 0.0
            var decCnt = 0
            var totalSec = 0f
            var inputDone = false
            var guard = 0
            try {
                while (guard++ < 800000) {
                    if (isCancelled()) break
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(10000)
                        if (inIdx >= 0) {
                            val buf = decoder.getInputBuffer(inIdx)
                            if (buf != null) {
                                val size = runCatching { extractor.readSampleData(buf, 0) }.getOrDefault(-1)
                                if (size < 0 || totalSec >= maxSeconds.toFloat()) {
                                    runCatching { decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                                    inputDone = true
                                } else {
                                    totalSec = (extractor.sampleTime / 1000000f)
                                    runCatching { decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0) }
                                    runCatching { extractor.advance() }
                                }
                            } else {
                                runCatching { decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                                inputDone = true
                            }
                        }
                    }
                    val outIdx = decoder.dequeueOutputBuffer(info, 10000)
                    if (outIdx >= 0) {
                        if (info.size > 0 && !isCancelled()) {
                            val buf = decoder.getOutputBuffer(outIdx)
                            if (buf != null) {
                                val n = minOf(info.size, bytesBuf.size)
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                buf.get(bytesBuf, 0, n)
                                val frames = n / (2 * channelsIn)
                                for (fr in 0 until frames) {
                                    if (isCancelled()) break
                                    var s = 0.0
                                    for (ch in 0 until channelsIn) {
                                        val base = (fr * channelsIn + ch) * 2
                                        if (base + 1 >= n) break
                                        val v = (bytesBuf[base].toInt() and 0xFF) or (bytesBuf[base + 1].toInt() shl 8)
                                        s += v.toShort().toDouble() / 32768.0
                                    }
                                    val mono = s / channelsIn.toDouble()
                                    decSum += mono
                                    decPos += 1.0 / ratio
                                    while (decPos >= 1.0 && out.size < targetRate * maxSeconds) {
                                        out.add((decSum / if (decCnt < 1) 1.0 else decCnt.toDouble()).toFloat())
                                        decSum = 0.0
                                        decCnt = 0
                                        decPos -= 1.0
                                    }
                                    decCnt++
                                }
                            }
                        }
                        runCatching { decoder.releaseOutputBuffer(outIdx, false) }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) break
                }
            } finally {
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
            if (out.isEmpty()) return null
            return DecodedPcm(out.toFloatArray(), targetRate)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }
}

sealed interface BpmDetectionResult {
    data class Success(
        val bpm: Double,
        val quality: Float,
        val candidates: List<Double> = emptyList(),
        val genre: String? = null,
        val genreConfidence: Double? = null,
        val beatPositions: List<Double> = emptyList()
    ) : BpmDetectionResult

    data class Failure(val reason: BpmDetectionFailure) : BpmDetectionResult
}

enum class BpmDetectionFailure {
    DECODE_FAILED,
    NO_ONSETS,
    WEAK_BEAT,
    INTERNAL_ERROR
}
