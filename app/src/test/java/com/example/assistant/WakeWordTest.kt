package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {

    @Test
    fun defaultWordAndMisheard() {
        for (w in listOf("Пятница", "пятнице", "пятнит", "пятница,")) assertTrue(w, WakeWord.isWakeWord(w, "пятница"))
        assertFalse(WakeWord.isWakeWord("включи", "пятница"))
        assertFalse(WakeWord.isWakeWord("музыка", "пятница"))
    }

    @Test
    fun wordSplitInTwo() {
        assertEquals("включи свет", WakeWord.commandAfter("пят ница включи свет", "пятница"))
        assertEquals("", WakeWord.commandAfter("пят ница", "пятница"))
    }

    @Test
    fun customWord() {
        assertTrue(WakeWord.isWakeWord("Джарвис", "джарвис"))
        assertTrue(WakeWord.isWakeWord("жарвис", "джарвис"))
        assertTrue(WakeWord.isWakeWord("джарвиз", "джарвис"))
        assertFalse(WakeWord.isWakeWord("пятница", "джарвис"))
        assertFalse(WakeWord.isWakeWord("царь", "джарвис"))
        assertEquals("включи музыку", WakeWord.commandAfter("Джарвис, включи музыку", "джарвис"))
        assertNull(WakeWord.commandAfter("включи музыку", "джарвис"))
    }

    @Test
    fun cleanFallsBackToDefault() {
        assertEquals("пятница", WakeWord.clean(""))
        assertEquals("пятница", WakeWord.clean("ок"))
        assertEquals("джарвис", WakeWord.clean("  Джарвис "))
    }

    @Test
    fun distance() {
        assertEquals(0, WakeWord.distance("abc", "abc"))
        assertEquals(1, WakeWord.distance("abc", "abd"))
        assertEquals(3, WakeWord.distance("", "abc"))
    }
}
