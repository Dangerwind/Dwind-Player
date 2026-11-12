package com.dwind.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VoiceCueSchedulerTest {
    private fun fixedConfig(
        intervalSec: Int = 60,
        lines: List<String> = listOf("A")
    ) = VoiceCueConfig(
        enabled = true,
        fixedEnabled = true,
        fixedIntervalSec = intervalSec,
        fixedLines = lines
    )

    @Test
    fun firstCueComesAfterInterval_notImmediately() {
        val scheduler = VoiceCueScheduler(Random(0))
        scheduler.onConfigChanged(fixedConfig(intervalSec = 60), now = 0)
        scheduler.onPlayingChanged(true, now = 0)

        assertEquals(60_000L, scheduler.nextDelayMs(0))
        assertTrue(scheduler.poll(59_999).isEmpty())
        assertEquals(1, scheduler.poll(60_000).size)
    }

    @Test
    fun pauseFreezesCountdown_andResumeContinuesFromSamePlace() {
        val scheduler = VoiceCueScheduler(Random(0))
        scheduler.onConfigChanged(fixedConfig(intervalSec = 60), now = 0)
        scheduler.onPlayingChanged(true, now = 0)

        scheduler.onPlayingChanged(false, now = 20_000)
        assertNull(scheduler.nextDelayMs(20_000))
        assertTrue(scheduler.poll(1_000_000).isEmpty())

        scheduler.onPlayingChanged(true, now = 100_000)
        assertEquals(40_000L, scheduler.nextDelayMs(100_000))
        assertTrue(scheduler.poll(139_999).isEmpty())
        assertEquals(1, scheduler.poll(140_000).size)
    }

    @Test
    fun changedIntervalRestartsCountdown() {
        val scheduler = VoiceCueScheduler(Random(0))
        scheduler.onConfigChanged(fixedConfig(intervalSec = 60), now = 0)
        scheduler.onPlayingChanged(true, now = 0)

        scheduler.onConfigChanged(fixedConfig(intervalSec = 30), now = 10_000)

        assertEquals(30_000L, scheduler.nextDelayMs(10_000))
        assertTrue(scheduler.poll(39_999).isEmpty())
        assertEquals(1, scheduler.poll(40_000).size)
    }

    @Test
    fun bothModesDueTogether_returnTwoEvents() {
        val config = VoiceCueConfig(
            enabled = true,
            fixedEnabled = true,
            fixedIntervalSec = 30,
            fixedLines = listOf("fixed"),
            randomEnabled = true,
            randomMinSec = 30,
            randomMaxSec = 30,
            randomLines = listOf("random")
        )
        val scheduler = VoiceCueScheduler(Random(0))
        scheduler.onConfigChanged(config, now = 0)
        scheduler.onPlayingChanged(true, now = 0)

        val events = scheduler.poll(30_000)

        assertEquals(2, events.size)
        assertEquals(setOf(VoiceCueMode.FIXED, VoiceCueMode.RANDOM), events.map { it.mode }.toSet())
        assertEquals(setOf("fixed", "random"), events.map { it.line }.toSet())
    }

    @Test
    fun samePhraseIsNotRepeatedTwiceInARow() {
        val scheduler = VoiceCueScheduler(Random(0))
        scheduler.onConfigChanged(fixedConfig(intervalSec = 15, lines = listOf("A", "B")), now = 0)
        scheduler.onPlayingChanged(true, now = 0)

        val first = scheduler.poll(15_000).single().line
        val second = scheduler.poll(30_000).single().line
        val third = scheduler.poll(45_000).single().line

        assertNotEquals(first, second)
        assertNotEquals(second, third)
    }

    @Test
    fun disabledSchedulerProducesNoEvents() {
        val scheduler = VoiceCueScheduler(Random(0))
        val config = fixedConfig(intervalSec = 15).copy(enabled = false)
        scheduler.onConfigChanged(config, now = 0)
        scheduler.onPlayingChanged(true, now = 0)

        assertNull(scheduler.nextDelayMs(0))
        assertTrue(scheduler.poll(1_000_000).isEmpty())
    }

    @Test
    fun modeWithoutPhrasesDoesNotSchedule() {
        val config = VoiceCueConfig(
            enabled = true,
            fixedEnabled = true,
            fixedIntervalSec = 15,
            fixedLines = emptyList()
        )
        val scheduler = VoiceCueScheduler(Random(0))
        scheduler.onConfigChanged(config, now = 0)
        scheduler.onPlayingChanged(true, now = 0)

        assertNull(scheduler.nextDelayMs(0))
        assertTrue(scheduler.poll(1_000_000).isEmpty())
    }
}
