package com.dwind.bpm

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Метки жанров, отображаемые в интерфейсе. */
object Genres {
    const val ELECTRONIC = "Электроника"
    const val ROCK = "Рок"
    const val ROCKNROLL = "Рок-н-ролл"
    const val BLUES = "Блюз"
    const val POP = "Поп"
    const val HIPHOP = "Хип-хоп"
    const val JAZZ = "Джаз"
    const val DNB = "Drum'n'Bass"
    const val CLASSICAL = "Классика"
    const val RNB = "R'nB / Соул"
    const val REGGAE = "Регги"
    const val FOLK = "Фолк"
    const val METAL = "Метал"
    const val PUNK = "Панк"
    const val UNKNOWN = "Неизвестно"

    /** Метка «другое» для ручного выбора стиля. */
    const val OTHER = "Иное"

    /** Список стилей для ручного выбора (первым — «Иное»). */
    val MANUAL_CHOICES: List<String> = listOf(
        OTHER, ELECTRONIC, ROCK, ROCKNROLL, POP, HIPHOP, JAZZ, BLUES,
        CLASSICAL, RNB, REGGAE, FOLK, METAL, PUNK, DNB
    )
}

/**
 * Акустические признаки, извлечённые из спектра/огибающей онсетов.
 * Все доли нормированы в 0..1.
 */
data class GenreFeatures(
    val lowRatio: Double,
    val midRatio: Double,
    val highRatio: Double,
    val spectralCentroidHz: Double,
    val onsetRate: Double,
    val percussiveness: Double
)

/**
 * Результат жанровой классификации: наиболее вероятная метка, её уверенность
 * и мягкое распределение вероятностей по жанрам.
 */
data class GenreResult(
    val label: String,
    val confidence: Double,
    val probabilities: Map<String, Double>
) {
    /**
     * Мягкая поправка темпа под жанр. Никогда не ограничивает диапазон,
     * лишь слегка повышает/понижает вклад в итоговый скор (±10-15%).
     */
    fun tempoBoost(bpm: Double): Double {
        var boost = 1.0
        for ((label, p) in probabilities) {
            boost += 0.15 * p * affinity(label, bpm)
        }
        return boost.coerceIn(0.85, 1.15)
    }

    companion object {
        /** affinity: +1 — темп типичен для жанра, -0.6 — нетипичен, 0 — нейтрально. */
        private fun affinity(label: String, bpm: Double): Double = when (label) {
            Genres.ELECTRONIC ->
                if (bpm in 100.0..145.0) 1.0 else if (bpm < 80.0 || bpm > 200.0) -0.6 else 0.0
            Genres.ROCK ->
                when {
                    bpm in 110.0..180.0 -> 1.0
                    bpm in 160.0..220.0 -> 0.3
                    bpm < 70.0 -> -0.3
                    else -> 0.0
                }
            Genres.ROCKNROLL ->
                if (bpm in 140.0..220.0) 1.0 else if (bpm < 90.0) -0.3 else 0.0
            Genres.BLUES ->
                if (bpm in 60.0..90.0 || bpm in 120.0..180.0) 1.0 else 0.0
            Genres.POP ->
                if (bpm in 95.0..140.0) 1.0 else if (bpm < 70.0 || bpm > 190.0) -0.5 else 0.0
            Genres.HIPHOP ->
                if (bpm in 60.0..100.0 || bpm in 120.0..160.0) 1.0 else 0.0
            Genres.JAZZ -> 0.0
            Genres.DNB ->
                if (bpm in 160.0..185.0 || bpm in 80.0..92.0) 1.0 else 0.0
            Genres.CLASSICAL -> 0.0 // темп переменный, не подсказываем
            Genres.RNB ->
                if (bpm in 60.0..100.0 || bpm in 120.0..160.0) 1.0 else 0.0
            Genres.REGGAE ->
                if (bpm in 60.0..90.0 || bpm in 120.0..150.0) 1.0 else 0.0
            Genres.FOLK ->
                if (bpm in 80.0..140.0) 0.5 else 0.0
            Genres.METAL ->
                if (bpm in 100.0..200.0) 1.0 else 0.0
            Genres.PUNK ->
                if (bpm in 150.0..220.0) 1.0 else 0.0
            else -> 0.0
        }
    }
}

/**
 * Правиловая (feature-based) классификация жанра. Она принципиально мягкая:
 * результат используется только для отображения и лёгкой коррекции скора,
 * но НЕ ограничивает диапазон BPM.
 */
object GenreClassifier {

    /**
     * Акустический профиль жанра в пространстве нормированных признаков.
     * Все компоненты, кроме [prior], заданы в диапазоне 0..1.
     */
    private data class Profile(
        val bass: Double,
        val mid: Double,
        val treble: Double,
        val brightness: Double,
        val rhythm: Double,
        val sharpness: Double,
        val prior: Double,
    )

    /** Веса признаков: ритм важнее всего, тембровые доли — умеренно. */
    private val WEIGHTS = doubleArrayOf(1.0, 1.2, 1.0, 0.8, 1.4, 0.4)

    /** Температура softmax: выше — мягче распределение. */
    private const val TEMPERATURE = 0.30

    /**
     * Эталонные профили жанров. Специально разнесены в пространстве признаков,
     * чтобы ни один жанр не занимал «центр» и не побеждал всегда.
     */
    private val PROFILES: List<Pair<String, Profile>> = listOf(
        Genres.ELECTRONIC to Profile(0.34, 0.32, 0.34, 0.70, 0.90, 0.62, 1.06),
        Genres.ROCK to Profile(0.18, 0.55, 0.27, 0.48, 0.84, 0.72, 1.05),
        Genres.ROCKNROLL to Profile(0.18, 0.52, 0.30, 0.56, 0.90, 0.70, 0.95),
        Genres.POP to Profile(0.22, 0.46, 0.32, 0.70, 0.68, 0.60, 1.08),
        Genres.HIPHOP to Profile(0.50, 0.28, 0.16, 0.36, 0.48, 0.70, 1.02),
        Genres.JAZZ to Profile(0.34, 0.44, 0.20, 0.50, 0.46, 0.52, 0.98),
        Genres.BLUES to Profile(0.30, 0.54, 0.14, 0.36, 0.46, 0.58, 0.95),
        Genres.DNB to Profile(0.34, 0.28, 0.36, 0.60, 1.00, 0.78, 0.94),
        Genres.CLASSICAL to Profile(0.26, 0.50, 0.24, 0.46, 0.14, 0.36, 0.96),
        Genres.RNB to Profile(0.44, 0.40, 0.16, 0.48, 0.60, 0.62, 1.02),
        Genres.REGGAE to Profile(0.48, 0.36, 0.14, 0.38, 0.62, 0.54, 0.92),
        Genres.FOLK to Profile(0.26, 0.54, 0.16, 0.38, 0.28, 0.46, 0.95),
        Genres.METAL to Profile(0.18, 0.38, 0.40, 0.66, 0.98, 0.80, 0.95),
        Genres.PUNK to Profile(0.18, 0.44, 0.36, 0.62, 0.96, 0.78, 0.92),
    )

    fun classify(f: GenreFeatures): GenreResult {
        val density = (f.onsetRate / 6.0).coerceIn(0.0, 1.0)
        val perc = f.percussiveness.coerceIn(0.0, 1.0)
        val centroid = (f.spectralCentroidHz / 4000.0).coerceIn(0.0, 1.0)
        val l = f.lowRatio.coerceIn(0.0, 1.0)
        val m = f.midRatio.coerceIn(0.0, 1.0)
        val h = f.highRatio.coerceIn(0.0, 1.0)

        // Взвешенное расстояние до эталонного профиля жанра. Чем ближе —
        // тем выше оценка. Свободных (не зависящих от трека) смещений нет,
        // поэтому ни один жанр не получает преимущество «по умолчанию».
        val scores = LinkedHashMap<String, Double>()
        for ((label, p) in PROFILES) {
            val dist =
                WEIGHTS[0] * sq(l - p.bass) +
                WEIGHTS[1] * sq(m - p.mid) +
                WEIGHTS[2] * sq(h - p.treble) +
                WEIGHTS[3] * sq(centroid - p.brightness) +
                WEIGHTS[4] * sq(density - p.rhythm) +
                WEIGHTS[5] * sq(perc - p.sharpness)
            scores[label] = ln(p.prior) - dist
        }

        // softmax с температурой
        val temperature = TEMPERATURE
        val maxScore = scores.values.maxOrNull() ?: 0.0
        var sum = 0.0
        val expScores = LinkedHashMap<String, Double>()
        for ((k, v) in scores) {
            val e = exp((v - maxScore) / temperature)
            expScores[k] = e
            sum += e
        }
        val probs = LinkedHashMap<String, Double>()
        var bestLabel = Genres.UNKNOWN
        var bestProb = 0.0
        for ((k, e) in expScores) {
            val p = if (sum > 0) e / sum else 0.0
            probs[k] = p
            if (p > bestProb) { bestProb = p; bestLabel = k }
        }
        return GenreResult(bestLabel, max(0.0, bestProb), probs)
    }

    private fun sq(x: Double): Double = x * x
}
