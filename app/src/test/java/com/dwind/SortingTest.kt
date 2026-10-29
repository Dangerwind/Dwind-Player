package com.dwind

import org.junit.Assert.assertEquals
import org.junit.Test

class SortingTest {
    @Test
    fun sortByFileName_basic() {
        val items = listOf("b.mp3", "a.mp3", "c.mp3")
        val sorted = items.sortedBy { it.lowercase() }
        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3"), sorted)
    }

    @Test
    fun sortByBpm_unknownLast() {
        data class Track(val name: String, val bpm: Double?)
        val list = listOf(
            Track("a", 120.0),
            Track("b", null),
            Track("c", 90.0)
        )
        val sorted = list.sortedWith(
            compareBy<Track> { it.bpm == null }.thenBy { it.bpm }
        )
        assertEquals(listOf(90.0, 120.0, null), sorted.map { it.bpm })
    }

    @Test
    fun manualBpmTakesPriority() {
        data class Bpm(val value: Double?, val source: String) // manual/auto
        val auto = Bpm(120.0, "auto")
        val manual = Bpm(118.0, "manual")
        val effective = if (manual.source == "manual") manual.value else auto.value
        assertEquals(118.0, effective)
    }
}
