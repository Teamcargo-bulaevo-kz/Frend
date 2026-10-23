package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {

    @Test
    fun defaultWordAndMisheard() {
        for (w in listOf("Джарвис", "жарвис", "джарвиз", "чарвис", "джервис", "Jarvis")) {
            assertTrue(w, WakeWord.isWakeWord(w))
        }
        assertFalse(WakeWord.isWakeWord("включи"))
        assertFalse(WakeWord.isWakeWord("царь"))
        assertFalse(WakeWord.isWakeWord("музыка"))
    }

    @Test
    fun wordSplitInTwo() {
        assertEquals("включи свет", WakeWord.commandAfter("джар вис включи свет"))
        assertEquals("", WakeWord.commandAfter("джар вис"))
    }

    @Test
    fun customWord() {
        assertTrue(WakeWord.isWakeWord("Пятница", "пятница"))
        assertTrue(WakeWord.isWakeWord("пятниса", "пятница"))
        assertFalse(WakeWord.isWakeWord("джарвис", "пятница"))
        assertEquals("включи музыку", WakeWord.commandAfter("Пятница включи музыку", "Пятница"))
        assertNull(WakeWord.commandAfter("включи музыку", "пятница"))
    }

    @Test
    fun cleanFallsBackToDefault() {
        assertEquals("джарвис", WakeWord.clean(""))
        assertEquals("джарвис", WakeWord.clean("ок"))
        assertEquals("пятница", WakeWord.clean("  Пятница "))
    }

    @Test
    fun distance() {
        assertEquals(0, WakeWord.distance("abc", "abc"))
        assertEquals(1, WakeWord.distance("abc", "abd"))
        assertEquals(3, WakeWord.distance("", "abc"))
    }

    @Test
    fun wavHeaderAndSize() {
        val wav = WavEncoder.encode(shortArrayOf(1, -1, 300), 16000)
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        // частота дискретизации 16000 = 0x3E80
        assertEquals(0x80, wav[24].toInt() and 0xFF)
        assertEquals(0x3E, wav[25].toInt() and 0xFF)
        // первый отсчёт = 1
        assertEquals(1, wav[44].toInt())
    }

    @Test
    fun rmsSilenceAndLoud() {
        assertEquals(0.0, WavEncoder.rms(ShortArray(100)), 0.0001)
        assertTrue(WavEncoder.rms(ShortArray(100) { 1000 }) > 900)
    }
}
