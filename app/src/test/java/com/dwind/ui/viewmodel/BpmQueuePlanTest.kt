package com.dwind.ui.viewmodel

import com.dwind.settings.SettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BpmQueuePlanTest {
    private val allIds = listOf("1", "2", "3", "4", "5")
    private val bpm = mapOf(
        "1" to 100.0,
        "2" to 120.0,
        "3" to 140.0,
        "4" to 160.0
        // "5" — BPM не определён
    )

    @Test
    fun filterDisabled_keepsWholeQueue() {
        val plan = planBpmQueue(
            allIds = allIds,
            bpmById = bpm,
            currentId = "1",
            settings = SettingsState(bpmFilterEnabled = false)
        )

        assertEquals(allIds, plan.queueIds)
        assertEquals(allIds.toSet(), plan.playableIds)
        assertNull(plan.preservedId)
    }

    @Test
    fun filterEnabled_keepsOnlyPlayableTracks() {
        val plan = planBpmQueue(
            allIds = allIds,
            bpmById = bpm,
            currentId = null,
            settings = SettingsState(
                bpmFilterEnabled = true,
                filterBpmMin = 120,
                filterBpmMax = 140
            )
        )

        assertEquals(listOf("2", "3"), plan.queueIds)
        assertEquals(setOf("2", "3"), plan.playableIds)
        assertNull(plan.preservedId)
    }

    @Test
    fun filterEnabled_currentOutsideRange_playsFirstThenPlayable() {
        val plan = planBpmQueue(
            allIds = allIds,
            bpmById = bpm,
            currentId = "1",
            settings = SettingsState(
                bpmFilterEnabled = true,
                filterBpmMin = 120,
                filterBpmMax = 140
            )
        )

        assertEquals(listOf("1", "2", "3"), plan.queueIds)
        assertEquals(setOf("2", "3"), plan.playableIds)
        assertEquals("1", plan.preservedId)
    }

    @Test
    fun filterEnabled_currentInsideRange_keepsNaturalOrder() {
        val plan = planBpmQueue(
            allIds = allIds,
            bpmById = bpm,
            currentId = "3",
            settings = SettingsState(
                bpmFilterEnabled = true,
                filterBpmMin = 120,
                filterBpmMax = 140
            )
        )

        assertEquals(listOf("2", "3"), plan.queueIds)
        assertEquals(setOf("2", "3"), plan.playableIds)
        assertNull(plan.preservedId)
    }

    @Test
    fun filterEnabled_noPlayableTracks_leavesQueueUntouched() {
        val plan = planBpmQueue(
            allIds = allIds,
            bpmById = bpm,
            currentId = "1",
            settings = SettingsState(
                bpmFilterEnabled = true,
                filterBpmMin = 200,
                filterBpmMax = 220
            )
        )

        assertNull(plan.queueIds)
        assertEquals(emptySet<String>(), plan.playableIds)
        assertNull(plan.preservedId)
    }
}
