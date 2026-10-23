package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test fun defaultWakeWordAndCommand() {
        assertTrue(WakeWord.isWakeWord("Пятница"))
        assertTrue(WakeWord.isWakeWord("пятниса"))
        assertEquals("включи музыку", WakeWord.commandAfter("Пятница, включи музыку"))
        assertEquals("", WakeWord.commandAfter("Пятница"))
    }

    @Test fun customWakeWord() {
        assertTrue(WakeWord.isWakeWord("Джарвис", "джарвис"))
        assertEquals("включи музыку", WakeWord.commandAfter("Джарвис включи музыку", "джарвис"))
        assertNull(WakeWord.commandAfter("включи музыку", "джарвис"))
        assertFalse(WakeWord.isWakeWord("Пятница", "джарвис"))
    }

    @Test fun emptyAndShortConfigurationUsesDefault() {
        assertEquals("пятница", WakeWord.clean(""))
        assertEquals("пятница", WakeWord.clean("ок"))
        assertEquals("джарвис", WakeWord.clean(" Джарвис "))
    }

    @Test fun wavHeaderAndAudioLevel() {
        val wav = WavEncoder.encode(shortArrayOf(1, -1, 300), 8000)
        assertEquals(50, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        assertTrue(WavEncoder.rms(ShortArray(100) { 1000 }) > 900)
        assertEquals(0.0, WavEncoder.rms(ShortArray(100)), 0.0001)
    }
}
