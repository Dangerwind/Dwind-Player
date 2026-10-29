package com.dwind.bpm

import kotlinx.coroutines.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Полоса частот анализа. */
data class BandSpec(val lowHz: Double, val highHz: Double)

/**
 * Конфигурация ансамблевого детектора BPM.
 *
 * @param minBpm/minBpm рабочий диапазон темпа
 * @param fftSize/hopSize параметры STFT (75% перекрытие при hop = fftSize/4)
 * @param bands полосы анализа (низ/середина/верх — кик/бас, снейр/гитара/вокал, хэты/атаки)
 * @param bandWeights веса полос при объединении огибающих
 * @param windowSeconds/windowStepSeconds окно и шаг для темпограммы и анализа стабильности
 * @param combOffBeatPenalty штраф за энергию между долями гребёнчатого фильтра
 * @param priorCenterBpm/priorSigmaLog мягкий априорный приоритет темпа (в лог-шкале). Широкий,
 *        чтобы НЕ подавлять 60/90/140/180/220.
 * @param genrePriorStrength сила мягкой жанровой поправки (0 — выключено)
 */
data class BpmDetectorConfig(
    val minBpm: Double = 55.0,
    val maxBpm: Double = 255.0,
    val fftSize: Int = 2048,
    val hopSize: Int = 512,
    val targetSampleRate: Int = 22050,
    val bands: List<BandSpec> = listOf(
        BandSpec(20.0, 150.0),
        BandSpec(150.0, 2000.0),
        BandSpec(2000.0, 9000.0)
    ),
    val bandWeights: List<Double> = listOf(0.40, 0.25, 0.35),
    val windowSeconds: Double = 17.0,
    val windowStepSeconds: Double = 5.0,
    val combOffBeatPenalty: Double = 0.30,
    val priorCenterBpm: Double = 122.0,
    val priorSigmaLog: Double = 0.60,
    val genrePriorStrength: Double = 0.10
)

/**
 * Ансамблевый офлайн-детектор темпа.
 *
 * Пайплайн: DC-removal и децимация → STFT (Ханн, 75% перекрытие) →
 * мультиполосная спектральная флюенция + комплексно-доменная разница → сглаживание,
 * локальная нормировка и выпрямление → объединение полос → темпограмма (ACF по окнам)
 * → кросс-корреляционные кандидаты → разрешение октав (×0.5, ×1.5, ×2) →
 * гребёнчатый фильтр (проверка долей и меж-долей с перебором фаз) →
 * мягкий жанровый/априорный приоритет → уверенность.
 */
class EnsembleBpmDetector(private val config: BpmDetectorConfig = BpmDetectorConfig()) {

    fun detect(
        inputSamples: FloatArray,
        inputSampleRate: Int,
        isActive: () -> Boolean = { true }
    ): BpmDetectionResult {
        if (inputSampleRate <= 0 || inputSamples.size < config.fftSize * 2) {
            return BpmDetectionResult.Failure(BpmDetectionFailure.DECODE_FAILED)
        }
        val pre = preprocess(inputSamples, inputSampleRate)
        val samples = pre.first
        val sampleRate = pre.second
        if (samples.size < config.fftSize * 2) {
            return BpmDetectionResult.Failure(BpmDetectionFailure.NO_ONSETS)
        }
        val onsetData = computeOnsets(samples, sampleRate, isActive)
            ?: return BpmDetectionResult.Failure(BpmDetectionFailure.NO_ONSETS)
        if (!isActive()) throw CancellationException("BPM detection cancelled")
        val onset = onsetData.envelope
        if (onset.size < 24) return BpmDetectionResult.Failure(BpmDetectionFailure.NO_ONSETS)

        val genre = GenreClassifier.classify(
            GenreFeatures(
                lowRatio = onsetData.lowRatio,
                midRatio = onsetData.midRatio,
                highRatio = onsetData.highRatio,
                spectralCentroidHz = onsetData.spectralCentroid,
                onsetRate = onsetData.onsetRate,
                percussiveness = onsetData.percussiveness
            )
        )

        val solved = solve(onset, onsetData.frameRate, genre, isActive)
            ?: return BpmDetectionResult.Failure(BpmDetectionFailure.WEAK_BEAT)

        return BpmDetectionResult.Success(
            bpm = solved.bpm,
            quality = solved.confidence.toFloat(),
            candidates = solved.candidates,
            genre = genre.label,
            genreConfidence = genre.confidence,
            beatPositions = solved.beats
        )
    }

    // ---------------------------------------------------------------- preprocessing

    /** Убирает DC и (при необходимости) децимирует сигнал с простым усредняющим фильтром. */
    private fun preprocess(samples: FloatArray, sampleRate: Int): Pair<FloatArray, Int> {
        var mean = 0.0
        for (s in samples) mean += s
        mean /= samples.size

        val factor = max(1, (sampleRate.toDouble() / config.targetSampleRate).roundToInt())
        if (factor <= 1) {
            val out = FloatArray(samples.size)
            for (i in samples.indices) out[i] = (samples[i] - mean).toFloat()
            return out to sampleRate
        }
        val outLen = samples.size / factor
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            var acc = 0.0
            val base = i * factor
            var j = 0
            while (j < factor) {
                acc += samples[base + j] - mean
                j++
            }
            out[i] = (acc / factor).toFloat()
        }
        return out to (sampleRate / factor)
    }

    // ---------------------------------------------------------------- onset envelope

    private data class OnsetData(
        val envelope: DoubleArray,
        val frameRate: Double,
        val lowRatio: Double,
        val midRatio: Double,
        val highRatio: Double,
        val spectralCentroid: Double,
        val onsetRate: Double,
        val percussiveness: Double
    )

    private fun computeOnsets(
        samples: FloatArray,
        sampleRate: Int,
        isActive: () -> Boolean
    ): OnsetData? {
        val fftSize = config.fftSize
        val hop = config.hopSize
        val nBins = fftSize / 2
        if (samples.size < fftSize) return null
        val nFrames = (samples.size - fftSize) / hop + 1
        if (nFrames < 8) return null
        val frameRate = sampleRate.toDouble() / hop

        val window = FloatArray(fftSize)
        for (i in 0 until fftSize) {
            window[i] = (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat()
        }

        val fft = Fft(fftSize)
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val prevRe = FloatArray(nBins)
        val prevIm = FloatArray(nBins)
        val prevMag = FloatArray(nBins)
        val mag = FloatArray(nBins)

        val bands = config.bands
        val loBin = IntArray(bands.size)
        val hiBin = IntArray(bands.size)
        for (b in bands.indices) {
            val lo = ceil(bands[b].lowHz * fftSize / sampleRate).toInt().coerceIn(1, nBins - 1)
            val hi = floor(bands[b].highHz * fftSize / sampleRate).toInt().coerceIn(1, nBins - 1)
            loBin[b] = min(lo, hi)
            hiBin[b] = max(lo, hi)
        }

        // коэффициент поворота фазы между кадрами для комплексно-доменной разницы
        val cosRot = FloatArray(nBins)
        val sinRot = FloatArray(nBins)
        for (i in 0 until nBins) {
            val angle = 2.0 * PI * i * hop / fftSize
            cosRot[i] = cos(angle).toFloat()
            sinRot[i] = sin(angle).toFloat()
        }

        val fluxMag = Array(bands.size) { DoubleArray(nFrames) }
        val fluxCplx = Array(bands.size) { DoubleArray(nFrames) }
        val bandEnergy = DoubleArray(bands.size)
        var centroidSum = 0.0
        var centroidCnt = 0
        var first = true

        for (f in 0 until nFrames) {
            if (!isActive()) throw CancellationException("BPM detection cancelled")
            val start = f * hop
            var i = 0
            while (i < fftSize) {
                re[i] = samples[start + i] * window[i]
                im[i] = 0f
                i++
            }
            fft.transform(re, im)
            for (k in 0 until nBins) {
                val rr = re[k]
                val ii = im[k]
                mag[k] = sqrt(rr * rr + ii * ii)
            }
            var num = 0.0
            var den = 0.0
            for (k in 1 until nBins) {
                num += k * mag[k]
                den += mag[k]
            }
            if (den > 1e-6) {
                centroidSum += (num / den) * sampleRate / fftSize
                centroidCnt++
            }

            for (b in bands.indices) {
                val lo = loBin[b]
                val hi = hiBin[b]
                var fm = 0.0
                var fc = 0.0
                var be = 0.0
                for (k in lo..hi) {
                    if (!first) {
                        val dm = mag[k] - prevMag[k]
                        if (dm > 0) fm += dm
                        val pr = prevRe[k] * cosRot[k] - prevIm[k] * sinRot[k]
                        val pi2 = prevRe[k] * sinRot[k] + prevIm[k] * cosRot[k]
                        val dr = re[k] - pr
                        val di = im[k] - pi2
                        val v = sqrt(dr * dr + di * di) - prevMag[k]
                        if (v > 0) fc += v
                    }
                    be += mag[k].toDouble() * mag[k]
                }
                fluxMag[b][f] = fm
                fluxCplx[b][f] = fc
                bandEnergy[b] += be
            }

            for (k in 0 until nBins) {
                prevMag[k] = mag[k]
                prevRe[k] = re[k]
                prevIm[k] = im[k]
            }
            first = false
        }

        val combined = DoubleArray(nFrames)
        // Огибающие по полосам: сглаживаем, вычитаем локальное среднее, выпрямляем.
        // Каждую полосу приводим к её робастному пику (99-й квантиль) так, чтобы
        // веса задавали баланс полос, но не терялась иерархия акцентов внутри полосы.
        // Пол — 25% от глобального пика, чтобы не усиливать шум тихой полосы.
        val rectBands = ArrayList<DoubleArray>(bands.size)
        val bandPeaks = DoubleArray(bands.size)
        for (b in bands.indices) {
            val raw = DoubleArray(nFrames)
            for (f in 0 until nFrames) raw[f] = fluxMag[b][f] + 0.35 * fluxCplx[b][f]
            val rect = rectify(raw, frameRate)
            rectBands.add(rect)
            bandPeaks[b] = percentile(rect, 0.99)
        }
        val globalPeak = (bandPeaks.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
        val floor = 0.25 * globalPeak
        for (b in bands.indices) {
            val w = config.bandWeights.getOrElse(b) { 1.0 }
            val rect = rectBands[b]
            val scale = max(bandPeaks[b], floor).coerceAtLeast(1e-9)
            for (f in 0 until nFrames) combined[f] += w * rect[f] / scale
        }
        for (f in combined.indices) if (combined[f] < 0) combined[f] = 0.0

        val total = bandEnergy.sum() + 1e-9
        val lowRatio = bandEnergy.getOrElse(0) { 0.0 } / total
        val midRatio = bandEnergy.getOrElse(1) { 0.0 } / total
        val highRatio = bandEnergy.getOrElse(2) { 0.0 } / total
        val centroid = if (centroidCnt > 0) centroidSum / centroidCnt else 0.0
        val onsetRate = estimateOnsetRate(combined, frameRate)
        val percussiveness = estimatePercussiveness(combined)

        return OnsetData(
            envelope = combined,
            frameRate = frameRate,
            lowRatio = lowRatio,
            midRatio = midRatio,
            highRatio = highRatio,
            spectralCentroid = centroid,
            onsetRate = onsetRate,
            percussiveness = percussiveness
        )
    }

    /** Сглаживание, вычитание локального среднего, выпрямление (без приведения к СКО). */
    private fun rectify(raw: DoubleArray, frameRate: Double): DoubleArray {
        if (raw.isEmpty()) return raw
        val smoothW = max(1, (0.040 * frameRate).roundToInt())
        val meanW = max(1, (0.55 * frameRate).roundToInt())
        val smoothed = movingAverage(raw, smoothW)
        val localMean = movingAverage(smoothed, meanW)
        val out = DoubleArray(smoothed.size)
        for (i in out.indices) {
            val d = smoothed[i] - localMean[i]
            out[i] = if (d > 0.0) d else 0.0
        }
        return out
    }

    /** q-й квантиль (0..1) значений массива. */
    private fun percentile(src: DoubleArray, q: Double): Double {
        if (src.isEmpty()) return 0.0
        val copy = src.clone()
        copy.sort()
        val idx = ((copy.size - 1) * q).roundToInt().coerceIn(0, copy.size - 1)
        return copy[idx]
    }

    private fun movingAverage(src: DoubleArray, w: Int): DoubleArray {
        val n = src.size
        val out = DoubleArray(n)
        if (n == 0) return out
        val half = max(0, w / 2)
        var sum = 0.0
        var count = 0
        val initEnd = min(half, n - 1)
        for (i in 0..initEnd) {
            sum += src[i]
            count++
        }
        for (i in 0 until n) {
            out[i] = if (count > 0) sum / count else 0.0
            val add = i + half + 1
            val remove = i - half
            if (add < n) {
                sum += src[add]
                count++
            }
            if (remove >= 0) {
                sum -= src[remove]
                count--
            }
        }
        return out
    }

    private fun estimateOnsetRate(env: DoubleArray, frameRate: Double): Double {
        if (env.isEmpty()) return 0.0
        var mean = 0.0
        for (v in env) mean += v
        mean /= env.size
        var variance = 0.0
        for (v in env) variance += (v - mean) * (v - mean)
        variance /= env.size
        val std = sqrt(variance)
        val th = mean + 0.6 * std
        var peaks = 0
        for (i in 1 until env.size - 1) {
            if (env[i] > th && env[i] >= env[i - 1] && env[i] > env[i + 1]) peaks++
        }
        val seconds = env.size / frameRate
        return if (seconds > 0) peaks / seconds else 0.0
    }

    private fun estimatePercussiveness(env: DoubleArray): Double {
        if (env.isEmpty()) return 0.0
        var mean = 0.0
        var mx = 1e-9
        for (v in env) {
            mean += v
            if (v > mx) mx = v
        }
        mean /= env.size
        return (mean / mx).coerceIn(0.0, 1.0)
    }

    // ---------------------------------------------------------------- solving

    private data class Peak(val lag: Int, val value: Double)
    private data class WindowData(val start: Int, val end: Int, val peaks: List<Peak>)
    private data class Cluster(var bpm: Double, var score: Double, var count: Int)
    private data class CombResult(
        val score: Double,
        val meanBeat: Double,
        val meanOff: Double,
        val phase: Double,
        val coverage: Double = 0.0
    )
    private data class Row(
        val bpm: Double,
        val comb: Double,
        val meanBeat: Double,
        val phase: Double,
        val temp: Double,
        val agreement: Double,
        val phaseStability: Double,
        val prior: Double,
        val genreBoost: Double
    )
    private data class Scored(val row: Row, val combNorm: Double, val total: Double)
    private data class SolveResult(
        val bpm: Double,
        val confidence: Double,
        val candidates: List<Double>,
        val beats: List<Double>
    )

    private fun solve(
        onset: DoubleArray,
        frameRate: Double,
        genre: GenreResult,
        isActive: () -> Boolean
    ): SolveResult? {
        val maxLag = (60.0 / config.minBpm * frameRate).roundToInt()
        val minLag = max(2, (60.0 / config.maxBpm * frameRate).roundToInt())
        if (maxLag <= minLag || onset.size < 32) return null

        val globalAcf = acf(onset, 0, onset.size, minLag, maxLag)
        var acfMax = 1e-9
        for (lag in minLag..maxLag) if (globalAcf[lag] > acfMax) acfMax = globalAcf[lag]

        val windows = windowedAnalysis(onset, frameRate, minLag, maxLag)
        val clusters = clusterPeaks(windows, frameRate)
        if (clusters.isEmpty()) return null

        val baseBpms = clusters.take(8).map { it.bpm }
        val coarse = LinkedHashSet<Double>()
        val mults = doubleArrayOf(0.5, 1.0, 1.5, 2.0)
        for (b in baseBpms) {
            for (m in mults) {
                val v = b * m
                if (v >= config.minBpm && v <= config.maxBpm) coarse.add(v)
            }
        }
        if (coarse.isEmpty()) return null

        // Уточняем кандидатов по гребёнчатому фильтру на непрерывной шкале темпа:
        // пики АКФ квантованы целым лагом (±7% на быстрых темпах), поэтому без
        // уточнения кандидат 191.5 вместо 190 «разъезжается» по фазе.
        val candidateSet = LinkedHashSet<Long>()
        for (c in coarse) {
            val refined = refineBpm(onset, frameRate, c)
            candidateSet.add((refined * 4).roundToLong())
        }
        if (candidateSet.isEmpty()) return null

        val envMean = onset.average().coerceAtLeast(1e-9)

        val rows = ArrayList<Row>(candidateSet.size)
        for (key in candidateSet) {
            if (!isActive()) throw CancellationException("BPM detection cancelled")
            val bpm = key / 4.0
            val comb = combScoreRange(onset, 0, onset.size, frameRate, bpm, config.combOffBeatPenalty)
            val temp = tempScoreFor(globalAcf, frameRate, bpm, minLag, maxLag, acfMax)
            val agreement = agreementFor(bpm, windows, frameRate)
            val phaseStability = phaseStabilityFor(onset, frameRate, bpm, windows)
            val prior = logNormalPrior(bpm)
            val genreBoost = genre.tempoBoost(bpm)
            rows.add(Row(bpm, comb.score, comb.meanBeat, comb.phase, temp, agreement, phaseStability, prior, genreBoost))
        }
        if (rows.isEmpty()) return null

        val maxComb = rows.maxOf { it.comb }
        val scored = rows.map { r ->
            val combNorm = if (maxComb > 0) (r.comb / maxComb).coerceIn(0.0, 1.0) else 0.0
            val base = 0.35 * combNorm +
                0.25 * r.temp +
                0.20 * r.agreement +
                0.10 * r.phaseStability +
                0.10 * r.prior
            val total = base * (1.0 + config.genrePriorStrength * (r.genreBoost - 1.0))
            Scored(r, combNorm, total)
        }.sortedByDescending { it.total }

        // Разрешение октав: выбираем вариант с лучшим итоговым скором, но при
        // почти равных скорах внутри октавного семейства предпочитаем более
        // быстрый темп, если он покрывает больше онсетов (учёт субдивизий).
        val winnerByTotal = scored.first()
        val familyFlags = BooleanArray(scored.size)
        familyFlags[0] = true
        val queue = ArrayDeque<Int>()
        queue.add(0)
        while (queue.isNotEmpty()) {
            val ci = queue.removeFirst()
            for (i in scored.indices) {
                if (!familyFlags[i] && sameOctave(scored[i].row.bpm, scored[ci].row.bpm)) {
                    familyFlags[i] = true
                    queue.add(i)
                }
            }
        }
        var winner = winnerByTotal
        var bestCombMember = winnerByTotal
        for (i in scored.indices) {
            if (familyFlags[i] && scored[i].row.comb > bestCombMember.row.comb) bestCombMember = scored[i]
        }
        // Если внутри октавного семейства есть кандидат с заметно лучшей
        // гребёнкой (акцент + покрытие онсетов), доверяем ему — так исправляются
        // октавные ошибки на быстрых темпах (например, 190 вместо 95).
        if (bestCombMember.row.comb > winnerByTotal.row.comb * 1.06) {
            winner = bestCombMember
        }

        var nextOther: Scored? = null
        for (i in scored.indices) {
            if (!familyFlags[i]) {
                nextOther = scored[i]
                break
            }
        }
        val margin = if (nextOther != null && winner.total > 0.0) {
            ((winner.total - nextOther.total) / winner.total).coerceIn(0.0, 1.0)
        } else 1.0

        // Стабильность темпа по окнам (с приведением октав к общей шкале).
        val windowBpms = windows.mapNotNull { w -> w.peaks.firstOrNull()?.let { 60.0 * frameRate / it.lag } }
        val octNorm = windowBpms.map { normalizeOctave(it) }.sorted()
        var relSpread = 0.0
        if (octNorm.size >= 3) {
            val median = octNorm[octNorm.size / 2]
            val mad = octNorm.map { abs(it - median) }.sorted()[octNorm.size / 2]
            relSpread = if (median > 0.0) mad / median else 0.0
        }
        val variableTempo = relSpread > 0.06
        val stability = (1.0 - relSpread / 0.15).coerceIn(0.0, 1.0)

        val topCandidates = scored.take(6)
        val harmonic = if (topCandidates.size <= 1) 1.0 else {
            topCandidates.drop(1).count { octaveRelated(it.row.bpm, winner.row.bpm) }
                .toDouble() / (topCandidates.size - 1)
        }
        val onBeat = (winner.row.meanBeat / (envMean * 2.0)).coerceIn(0.0, 1.0)

        var confidence = 0.30 * margin +
            0.25 * winner.combNorm +
            0.20 * stability +
            0.15 * winner.row.agreement +
            0.10 * harmonic
        confidence = confidence.coerceIn(0.0, 1.0)
        if (variableTempo) confidence *= 0.7

        val bpm = (winner.row.bpm * 10).roundToInt() / 10.0
        val beats = beatPositions(onset.size, frameRate, winner.row.bpm, winner.row.phase)
        val candidatesOut = scored.take(6).map { (it.row.bpm * 10).roundToInt() / 10.0 }.distinct()

        return SolveResult(bpm, confidence, candidatesOut, beats)
    }

    private fun acf(x: DoubleArray, start: Int, end: Int, minLag: Int, maxLag: Int): DoubleArray {
        val len = end - start
        val r = DoubleArray(maxLag + 2)
        var energy = 1e-12
        for (t in 0 until len) {
            val v = x[start + t]
            energy += v * v
        }
        for (lag in minLag..maxLag) {
            var s = 0.0
            val lim = len - lag
            var idx = 0
            while (idx < lim) {
                s += x[start + idx] * x[start + idx + lag]
                idx++
            }
            r[lag] = s / energy
        }
        return r
    }

    private fun localPeaks(r: DoubleArray, minLag: Int, maxLag: Int): List<Peak> {
        val peaks = ArrayList<Peak>()
        for (lag in (minLag + 1) until maxLag) {
            if (r[lag] > r[lag - 1] && r[lag] >= r[lag + 1] && r[lag] > 0.05) {
                peaks.add(Peak(lag, r[lag]))
            }
        }
        return peaks.sortedByDescending { it.value }.take(6)
    }

    private fun windowedAnalysis(onset: DoubleArray, frameRate: Double, minLag: Int, maxLag: Int): List<WindowData> {
        val win = max(16, (config.windowSeconds * frameRate).roundToInt()).coerceAtMost(onset.size)
        val step = max(1, (config.windowStepSeconds * frameRate).roundToInt())
        val res = ArrayList<WindowData>()
        var start = 0
        while (true) {
            val end = min(start + win, onset.size)
            if (end - start < max(16, win / 3)) break
            val r = acf(onset, start, end, minLag, maxLag)
            res.add(WindowData(start, end, localPeaks(r, minLag, maxLag)))
            if (end >= onset.size) break
            start += step
        }
        if (res.isEmpty()) {
            val r = acf(onset, 0, onset.size, minLag, maxLag)
            res.add(WindowData(0, onset.size, localPeaks(r, minLag, maxLag)))
        }
        return res
    }

    private fun clusterPeaks(windows: List<WindowData>, frameRate: Double): List<Cluster> {
        val clusters = ArrayList<Cluster>()
        for (w in windows) {
            for (p in w.peaks) {
                val bpm = 60.0 * frameRate / p.lag
                val existing = clusters.firstOrNull { abs(it.bpm - bpm) <= 0.02 * max(it.bpm, bpm) }
                if (existing != null) {
                    existing.bpm = (existing.bpm * existing.count + bpm) / (existing.count + 1)
                    existing.score += p.value
                    existing.count++
                } else {
                    clusters.add(Cluster(bpm, p.value, 1))
                }
            }
        }
        return clusters.sortedByDescending { it.score }
    }

    private fun combScoreRange(
        onset: DoubleArray,
        start: Int,
        end: Int,
        frameRate: Double,
        bpm: Double,
        penalty: Double,
        maxPhase: Int = 96
    ): CombResult {
        val period = 60.0 / bpm * frameRate
        val len = end - start
        if (period < 2.0 || period > len) return CombResult(-1.0, 0.0, 0.0, 0.0, 0.0)
        var totalOnset = 0.0
        for (i in start until end) totalOnset += onset[i]
        totalOnset = max(totalOnset, 1e-9)
        val nPhase = min(maxPhase, max(16, (period * 4).roundToInt()))
        var bestScore = -1e9
        var bestPhase = 0.0
        var bestBeat = 0.0
        var bestOff = 0.0
        var bestCoverage = 0.0
        for (p in 0 until nPhase) {
            val phase = p.toDouble() / nPhase * period
            var beatSum = 0.0
            var beatCnt = 0
            var offSum = 0.0
            var offCnt = 0
            var pos = start + phase
            while (pos < end) {
                beatSum += interp(onset, pos)
                beatCnt++
                val off = pos + period * 0.5
                if (off < end) {
                    offSum += interp(onset, off)
                    offCnt++
                }
                pos += period
            }
            if (beatCnt == 0) continue
            val mb = beatSum / beatCnt
            val mo = if (offCnt > 0) offSum / offCnt else 0.0
            val sc = mb - penalty * mo
            if (sc > bestScore) {
                bestScore = sc
                bestPhase = phase
                bestBeat = mb
                bestOff = mo
                bestCoverage = (beatSum / totalOnset).coerceIn(0.0, 1.0)
            }
        }
        if (bestScore <= -1e8) return CombResult(-1.0, 0.0, 0.0, 0.0, 0.0)
        return CombResult(bestScore * bestCoverage, bestBeat, bestOff, bestPhase, bestCoverage)
    }

    /** Локально уточняет темп вокруг bpm0, максимизируя гребёнчатый скор. */
    private fun refineBpm(onset: DoubleArray, frameRate: Double, bpm0: Double): Double {
        val lo = max(config.minBpm, bpm0 * 0.93)
        val hi = min(config.maxBpm, bpm0 * 1.07)
        var best = bpm0
        var bestScore = -1e9
        var v = lo
        while (v <= hi) {
            val c = combScoreRange(onset, 0, onset.size, frameRate, v, config.combOffBeatPenalty, maxPhase = 32)
            if (c.score > bestScore) {
                bestScore = c.score
                best = v
            }
            v += 0.1
        }
        return best
    }

    private fun interp(x: DoubleArray, pos: Double): Double {
        val i = floor(pos).toInt()
        if (i < 0) return x[0]
        if (i >= x.size - 1) return x[x.size - 1]
        val frac = pos - i
        return x[i] * (1.0 - frac) + x[i + 1] * frac
    }

    private fun tempScoreFor(
        acf: DoubleArray,
        frameRate: Double,
        bpm: Double,
        minLag: Int,
        maxLag: Int,
        acfMax: Double
    ): Double {
        val l = (60.0 / bpm * frameRate).roundToInt().coerceIn(minLag, maxLag)
        var best = 0.0
        for (d in -2..2) {
            val idx = (l + d).coerceIn(minLag, maxLag)
            if (acf[idx] > best) best = acf[idx]
        }
        return (best / acfMax).coerceIn(0.0, 1.0)
    }

    private fun agreementFor(bpm: Double, windows: List<WindowData>, frameRate: Double): Double {
        if (windows.isEmpty()) return 0.5
        var hit = 0
        for (w in windows) {
            val ok = w.peaks.any { p -> octaveRelated(60.0 * frameRate / p.lag, bpm) }
            if (ok) hit++
        }
        return hit.toDouble() / windows.size
    }

    private fun phaseStabilityFor(
        onset: DoubleArray,
        frameRate: Double,
        bpm: Double,
        windows: List<WindowData>
    ): Double {
        if (windows.isEmpty()) return 0.5
        val scores = windows.map { combScoreRange(onset, it.start, it.end, frameRate, bpm, config.combOffBeatPenalty).score }
        val mx = scores.maxOrNull() ?: 0.0
        if (mx <= 0.0) return 0.0
        return scores.count { it > 0.5 * mx }.toDouble() / scores.size
    }

    /** Кандидаты считаются октавно-связанными, если их отношение близко к 1/2, 2/3, 3/4, 1, 4/3, 3/2, 2. */
    private fun octaveRelated(a: Double, b: Double): Boolean {
        if (b <= 0.0 || a <= 0.0) return false
        val ratio = a / b
        val targets = doubleArrayOf(0.5, 2.0 / 3.0, 0.75, 1.0, 4.0 / 3.0, 1.5, 2.0)
        for (t in targets) {
            if (abs(ratio - t) <= 0.04 * t) return true
        }
        return false
    }

    /** Чисто октавная связь (×0.5 или ×2) — используется при разрешении октав. */
    private fun sameOctave(a: Double, b: Double): Boolean {
        if (b <= 0.0 || a <= 0.0) return false
        val ratio = a / b
        return abs(ratio - 0.5) <= 0.03 || abs(ratio - 2.0) <= 0.06 || abs(ratio - 1.0) <= 0.03
    }

    /** Приводит темп к «средней» октаве [85, 170), чтобы сравнивать октавные варианты. */
    private fun normalizeOctave(bpm: Double): Double {
        var v = bpm
        while (v >= 170.0) v /= 2.0
        while (v < 85.0) v *= 2.0
        return v
    }

    private fun logNormalPrior(bpm: Double): Double {
        if (bpm <= 0.0) return 0.0
        val x = ln(bpm / config.priorCenterBpm) / config.priorSigmaLog
        return exp(-0.5 * x * x)
    }

    private fun beatPositions(n: Int, frameRate: Double, bpm: Double, phaseFrames: Double): List<Double> {
        val period = 60.0 / bpm * frameRate
        if (period < 1.0) return emptyList()
        val out = ArrayList<Double>()
        var pos = phaseFrames
        var guard = 0
        while (pos < n && guard++ < 4000) {
            out.add(pos / frameRate)
            pos += period
        }
        return out
    }

    // ---------------------------------------------------------------- FFT

    /** Итеративное БПФ radix-2 (Кули-Тьюки), n — степень двойки. */
    private class Fft(private val n: Int) {
        private val cosTable = FloatArray(n / 2)
        private val sinTable = FloatArray(n / 2)
        private val bitRev = IntArray(n)

        init {
            for (i in 0 until n / 2) {
                val angle = -2.0 * PI * i / n
                cosTable[i] = cos(angle).toFloat()
                sinTable[i] = sin(angle).toFloat()
            }
            val bits = Integer.numberOfTrailingZeros(n)
            for (i in 0 until n) {
                var x = i
                var r = 0
                for (b in 0 until bits) {
                    r = (r shl 1) or (x and 1)
                    x = x shr 1
                }
                bitRev[i] = r
            }
        }

        fun transform(re: FloatArray, im: FloatArray) {
            for (i in 0 until n) {
                val j = bitRev[i]
                if (j > i) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var size = 2
            while (size <= n) {
                val half = size / 2
                val step = n / size
                var i = 0
                while (i < n) {
                    var j = i
                    var k = 0
                    while (j < i + half) {
                        val l = j + half
                        val tpre = re[l] * cosTable[k] - im[l] * sinTable[k]
                        val tpim = re[l] * sinTable[k] + im[l] * cosTable[k]
                        re[l] = re[j] - tpre
                        im[l] = im[j] - tpim
                        re[j] += tpre
                        im[j] += tpim
                        j++
                        k += step
                    }
                    i += size
                }
                size = size shl 1
            }
        }
    }
}
