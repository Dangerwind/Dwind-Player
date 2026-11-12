package com.dwind.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BpmFilterTest {
    @Test
    fun filterDisabled_allowsAll() {
        val state = SettingsState()
        assertTrue(state.isBpmAllowed(null))
        assertTrue(state.isBpmAllowed(0.0))
        assertTrue(state.isBpmAllowed(500.0))
    }

    @Test
    fun filterEnabled_unknownBpmIsNotAllowed() {
        val state = SettingsState(bpmFilterEnabled = true, filterBpmMin = 120, filterBpmMax = 140)
        assertFalse(state.isBpmAllowed(null))
    }

    @Test
    fun filterEnabled_boundariesAreInclusive() {
        val state = SettingsState(bpmFilterEnabled = true, filterBpmMin = 120, filterBpmMax = 140)
        assertTrue(state.isBpmAllowed(120.0))
        assertTrue(state.isBpmAllowed(140.0))
        assertFalse(state.isBpmAllowed(119.0))
        assertFalse(state.isBpmAllowed(141.0))
    }

    @Test
    fun filterEnabled_comparesDisplayedValue() {
        val state = SettingsState(bpmFilterEnabled = true, filterBpmMin = 120, filterBpmMax = 140)
        // На экране BPM показывается целой частью: 140.9 выглядит как 140.
        assertTrue(state.isBpmAllowed(140.9))
        // 119.9 на экране — 119, значит вне диапазона.
        assertFalse(state.isBpmAllowed(119.9))
    }

    @Test
    fun filterEnabled_singleBpmRange() {
        val state = SettingsState(bpmFilterEnabled = true, filterBpmMin = 90, filterBpmMax = 90)
        assertTrue(state.isBpmAllowed(90.0))
        assertTrue(state.isBpmAllowed(90.9))
        assertFalse(state.isBpmAllowed(89.9))
        assertFalse(state.isBpmAllowed(91.0))
    }
}
