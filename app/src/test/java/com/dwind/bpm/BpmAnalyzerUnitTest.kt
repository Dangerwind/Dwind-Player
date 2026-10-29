package com.dwind.bpm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Синтетические тесты корректности ансамблевого детектора BPM.
 *
 * Для каждого «жанра» генерируется перкуссионный рисунок с известным темпом
 * (кик/снейр/хэты + опциональный бас), после чего проверяется, что детектор
 * находит именно этот темп и не «уезжает» на октаву (120 ≠ 60/240).
 */
class BpmAnalyzerUnitTest {

    private val sr = 22050

    // ------------------------------------------------------------ синтез сигналов

    private fun synthTrack(
        bpm: Double,
        seconds: Double,
        kickEveryBeats: Int = 1,
        kickOffset: Int = 0,
        snareBeats: Set<Int> = setOf(2, 4),
        hatDiv: Int = 2,
        bass: Boolean = false,
        sampleRate: Int = sr,
        seed: Long = 42L
    ): FloatArray {
        val n = (seconds * sampleRate).toInt()
        val out = FloatArray(n)
        val rnd = Random(seed)
        val beatSec = 60.0 / bpm
        val totalBeats = (seconds / beatSec).toInt()
        var b = 0
        while (b < totalBeats) {
            val t = b * beatSec
            if (kickEveryBeats > 0 && ((b - kickOffset) % kickEveryBeats == 0)) addKick(out, t, sampleRate)
            if (snareBeats.contains((b % 4) + 1)) addSnare(out, t, sampleRate, rnd)
            if (hatDiv > 0) {
                for (h in 0 until hatDiv) addHat(out, t + h * beatSec / hatDiv, sampleRate, rnd)
            }
            b++
        }
        if (bass) {
            for (i in out.indices) out[i] += (0.08 * sin(2 * PI * 55.0 * i / sampleRate)).toFloat()
        }
        return out
    }

    private fun addKick(buf: FloatArray, tSec: Double, sampleRate: Int) {
        val start = (tSec * sampleRate).toInt()
        val len = (0.14 * sampleRate).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / sampleRate
            buf[idx] += (0.95 * exp(-20.0 * t) * sin(2 * PI * 58.0 * t)).toFloat()
        }
    }

    private fun addSnare(buf: FloatArray, tSec: Double, sampleRate: Int, rnd: Random) {
        val start = (tSec * sampleRate).toInt()
        val len = (0.10 * sampleRate).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / sampleRate
            val env = exp(-30.0 * t)
            val noise = rnd.nextDouble() * 2 - 1
            val tone = 0.4 * sin(2 * PI * 190.0 * t)
            buf[idx] += ((noise * 0.7 + tone) * env * 0.6).toFloat()
        }
    }

    private fun addHat(buf: FloatArray, tSec: Double, sampleRate: Int, rnd: Random) {
        val start = (tSec * sampleRate).toInt()
        val len = (0.04 * sampleRate).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / sampleRate
            val env = exp(-90.0 * t)
            buf[idx] += ((rnd.nextDouble() * 2 - 1) * 0.28 * env).toFloat()
        }
    }

    // ------------------------------------------------------------ проверки

    private fun detect(
        samples: FloatArray,
        sampleRate: Int = sr,
        minBpm: Double = 55.0,
        maxBpm: Double = 255.0
    ): BpmDetectionResult =
        EnsembleBpmDetector(BpmDetectorConfig(minBpm = minBpm, maxBpm = maxBpm))
            .detect(samples, sampleRate)

    private fun assertBpm(expected: Double, result: BpmDetectionResult, tolFrac: Double = 0.03) {
        assertTrue("Ожидался успех, получено: $result", result is BpmDetectionResult.Success)
        val bpm = (result as BpmDetectionResult.Success).bpm
        val tol = max(2.0, expected * tolFrac)
        assertTrue("Ожидался ~$expected BPM, получено $bpm", abs(bpm - expected) <= tol)
    }

    // ------------------------------------------------------------ жанровые сценарии

    @Test
    fun electronic_120bpm() {
        val samples = synthTrack(bpm = 120.0, seconds = 20.0, hatDiv = 4, bass = true)
        assertBpm(120.0, detect(samples))
    }

    @Test
    fun pop_105bpm() {
        val samples = synthTrack(bpm = 105.0, seconds = 18.0, hatDiv = 2)
        assertBpm(105.0, detect(samples))
    }

    @Test
    fun rock_150bpm() {
        val samples = synthTrack(bpm = 150.0, seconds = 18.0, hatDiv = 2)
        assertBpm(150.0, detect(samples))
    }

    @Test
    fun rockAndRoll_190bpm() {
        val samples = synthTrack(bpm = 190.0, seconds = 18.0, hatDiv = 2)
        assertBpm(190.0, detect(samples))
    }

    @Test
    fun slowBlues_75bpm() {
        val samples = synthTrack(bpm = 75.0, seconds = 20.0, hatDiv = 2, bass = true)
        assertBpm(75.0, detect(samples))
    }

    @Test
    fun bluesRock_130bpm() {
        val samples = synthTrack(bpm = 130.0, seconds = 18.0, hatDiv = 2)
        assertBpm(130.0, detect(samples))
    }

    @Test
    fun hipHop_88bpm() {
        // кик на 1 и 3, снейр на 2 и 4
        val samples = synthTrack(bpm = 88.0, seconds = 20.0, kickEveryBeats = 2, hatDiv = 2, bass = true)
        assertBpm(88.0, detect(samples))
    }

    @Test
    fun drumAndBass_174bpm() {
        val samples = synthTrack(bpm = 174.0, seconds = 18.0, hatDiv = 2, bass = true)
        assertBpm(174.0, detect(samples))
    }

    @Test
    fun slow_68bpm() {
        val samples = synthTrack(bpm = 68.0, seconds = 20.0, hatDiv = 2, bass = true)
        assertBpm(68.0, detect(samples))
    }

    // ------------------------------------------------------------ разрешение октав

    @Test
    fun octave_120_isNot_60_or_240() {
        val samples = synthTrack(bpm = 120.0, seconds = 20.0, hatDiv = 2)
        val result = detect(samples)
        assertTrue(result is BpmDetectionResult.Success)
        val bpm = (result as BpmDetectionResult.Success).bpm
        assertTrue("Октавная ошибка: получено $bpm вместо ~120", abs(bpm - 120.0) <= 4.0)
    }

    @Test
    fun octave_140_isNot_70_or_280() {
        val samples = synthTrack(bpm = 140.0, seconds = 20.0, hatDiv = 2)
        val result = detect(samples)
        assertTrue(result is BpmDetectionResult.Success)
        val bpm = (result as BpmDetectionResult.Success).bpm
        assertTrue("Октавная ошибка: получено $bpm вместо ~140", abs(bpm - 140.0) <= 4.0)
    }

    // ------------------------------------------------------------ устойчивость

    @Test
    fun variableTempo_returnsSuccess() {
        // 8 секунд на 120 BPM, затем 8 секунд на 145 BPM
        val first = synthTrack(bpm = 120.0, seconds = 8.0, hatDiv = 2, seed = 1)
        val second = synthTrack(bpm = 145.0, seconds = 8.0, hatDiv = 2, seed = 2)
        val samples = FloatArray(first.size + second.size)
        first.copyInto(samples, 0)
        second.copyInto(samples, first.size)
        val result = detect(samples)
        assertTrue(result is BpmDetectionResult.Success)
        val bpm = (result as BpmDetectionResult.Success).bpm
        assertTrue("Переменный темп: неожиданный результат $bpm", bpm in 100.0..160.0)
    }

    @Test
    fun silence_returnsFailure() {
        val result = detect(FloatArray(sr * 10))
        assertTrue(result is BpmDetectionResult.Failure)
    }

    @Test
    fun noise_doesNotCrash() {
        val rnd = Random(7)
        val samples = FloatArray(sr * 10) { (rnd.nextDouble() * 2 - 1).toFloat() * 0.3f }
        val result = detect(samples)
        assertTrue(result is BpmDetectionResult.Success || result is BpmDetectionResult.Failure)
    }

    @Test
    fun veryShort_doesNotCrash() {
        val samples = synthTrack(bpm = 120.0, seconds = 0.05, hatDiv = 1)
        val result = detect(samples)
        assertTrue(result is BpmDetectionResult.Failure)
    }

    @Test
    fun beatPositions_areProducedForClearTrack() {
        val samples = synthTrack(bpm = 120.0, seconds = 16.0, hatDiv = 2)
        val result = detect(samples)
        assertTrue(result is BpmDetectionResult.Success)
        val beats = (result as BpmDetectionResult.Success).beatPositions
        assertTrue("Ожидались позиции долей", beats.size >= 8)
        // интервалы между долями близки к 0.5 с
        val intervals = beats.zipWithNext { a, b -> b - a }
        val mean = intervals.average()
        assertTrue("Средний интервал долей $mean не похож на 120 BPM", abs(mean - 0.5) < 0.05)
    }

    @Test
    fun configurableRange_isRespected() {
        val samples = synthTrack(bpm = 70.0, seconds = 20.0, hatDiv = 2, bass = true)
        val result = detect(samples, minBpm = 60.0, maxBpm = 90.0)
        assertTrue(result is BpmDetectionResult.Success)
        val bpm = (result as BpmDetectionResult.Success).bpm
        assertTrue("BPM $bpm вне заданного диапазона", bpm in 60.0..90.0)
    }
}
