package com.dwind.bpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Тесты жанровой классификации. Главная регрессия: раньше «Фолк» побеждал
 * почти на любом треке из-за формул с отрицательными «отсутствующими» членами
 * и свободным смещением. Теперь модель — расстояние до профилей с мягкими
 * приорами, и доминирования одного жанра быть не должно.
 */
class GenreClassifierTest {

    private fun features(
        low: Double,
        mid: Double,
        high: Double,
        centroidHz: Double,
        onsetRate: Double,
        perc: Double
    ) = GenreFeatures(low, mid, high, centroidHz, onsetRate, perc)

    private fun classify(
        low: Double = 0.25,
        mid: Double = 0.5,
        high: Double = 0.25,
        centroidHz: Double = 2000.0,
        onsetRate: Double = 3.0,
        perc: Double = 0.25
    ): GenreResult = GenreClassifier.classify(
        features(low, mid, high, centroidHz, onsetRate, perc)
    )

    @Test
    fun `folk is not the dominant genre over a plausible feature space`() {
        var folkWins = 0
        var total = 0
        val lowVals = listOf(0.05, 0.15, 0.25, 0.35, 0.45, 0.55)
        val midVals = listOf(0.2, 0.3, 0.4, 0.5, 0.6, 0.7)
        val rhythmVals = listOf(0.15, 0.35, 0.55, 0.75, 0.95)
        val percVals = listOf(0.1, 0.2, 0.3, 0.45, 0.6)
        val centroidVals = listOf(1000.0, 1800.0, 2600.0, 3200.0)

        for (l in lowVals) for (m in midVals) {
            val h = 1.0 - l - m
            if (h < 0.0 || h > 0.9) continue
            for (r in rhythmVals) for (p in percVals) for (c in centroidVals) {
                val res = classify(l, m, h, c, r * 6.0, p)
                total++
                if (res.label == Genres.FOLK) folkWins++
            }
        }

        val share = folkWins.toDouble() / total
        assertTrue(
            "Доля побед «Фолк» слишком велика: $folkWins из $total ($share)",
            share < 0.15
        )
    }

    @Test
    fun `bass heavy track favours hip-hop-like genres`() {
        val top = classify(low = 0.52, mid = 0.30, high = 0.18, centroidHz = 1500.0, onsetRate = 3.0, perc = 0.35)
        val plausible = setOf(Genres.HIPHOP, Genres.RNB, Genres.REGGAE, Genres.ELECTRONIC)
        assertTrue("Ожидался один из $plausible, получено ${top.label}", top.label in plausible)
    }

    @Test
    fun `slow acoustic track favours calm genres`() {
        val top = classify(low = 0.26, mid = 0.5, high = 0.24, centroidHz = 1900.0, onsetRate = 0.8, perc = 0.15)
        val plausible = setOf(Genres.CLASSICAL, Genres.FOLK, Genres.JAZZ, Genres.BLUES)
        assertTrue("Ожидался один из $plausible, получено ${top.label}", top.label in plausible)
    }

    @Test
    fun `fast punchy track favours energetic genres`() {
        val top = classify(low = 0.18, mid = 0.4, high = 0.42, centroidHz = 2700.0, onsetRate = 5.9, perc = 0.6)
        val plausible = setOf(Genres.METAL, Genres.PUNK, Genres.DNB, Genres.ELECTRONIC, Genres.ROCK)
        assertTrue("Ожидался один из $plausible, получено ${top.label}", top.label in plausible)
    }

    @Test
    fun `silence-like features do not crash and yield valid distribution`() {
        val res = classify(low = 0.0, mid = 0.0, high = 0.0, centroidHz = 0.0, onsetRate = 0.0, perc = 0.0)
        assertTrue("Метка не должна быть пустой", res.label.isNotEmpty())
        val sum = res.probabilities.values.sum()
        assertEquals("Сумма вероятностей должна быть 1", 1.0, sum, 1e-6)
        assertTrue("Уверенность в диапазоне 0..1", res.confidence in 0.0..1.0)
    }

    @Test
    fun `naive noise features yield valid distribution`() {
        val res = classify(low = 0.33, mid = 0.34, high = 0.33, centroidHz = 4000.0, onsetRate = 6.0, perc = 1.0)
        val sum = res.probabilities.values.sum()
        assertEquals(1.0, sum, 1e-6)
        assertTrue(abs(res.confidence - res.probabilities.getValue(res.label)) < 1e-9)
    }
}
