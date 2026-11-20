package com.example.assistant

import org.junit.Assert.*
import org.junit.Test

class MoodPaletteTest {
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test fun emptyGivesDefault() = assertEquals(MoodPalette.DEFAULT, MoodPalette.from(emptyMap()))

    @Test fun aggressiveIsBrightRed() {
        val a = MoodPalette.from(mapOf("dark" to 70, "energetic" to 30), arousal = 80)
        assertTrue(red(a.color) > 220); assertTrue(a.brightness > 0.85f); assertTrue(a.reactivity > 0.9f)
    }

    @Test fun gloomyIsDarkAndSlow() {
        val a = MoodPalette.from(mapOf("dark" to 80, "sad" to 20), arousal = 20)
        assertTrue(a.brightness < 0.4f); assertTrue(a.speed < 0.6f)
    }

    @Test fun calmIsPaleAndGentle() {
        val a = MoodPalette.from(mapOf("calm" to 90, "focus" to 10))
        assertTrue(blue(a.color) > 200); assertTrue(a.reactivity < 0.4f)
    }
}
