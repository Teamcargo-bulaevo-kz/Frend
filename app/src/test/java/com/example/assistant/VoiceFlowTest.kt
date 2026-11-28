package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFlowTest {

    @Test fun everyCueSoundsDifferent() {
        val tones = Cues.Kind.values().map { Cues.tone(it).first }
        assertEquals("у каждого сигнала свой звук", tones.size, tones.toSet().size)
        assertTrue(Cues.isStart(Cues.Kind.LISTEN))
        assertFalse(Cues.isStart(Cues.Kind.ERROR))
        Cues.Kind.values().forEach { assertTrue(Cues.tone(it).second in 50..400) }
    }

    @Test fun speechWatchdogScalesWithText() {
        val short = Speaker.estimateMs("Включаю", 1f)
        val long = Speaker.estimateMs("а".repeat(600), 1f)
        assertTrue(short in 3000..6000)
        assertTrue(long > short)
        assertTrue(Speaker.estimateMs("а".repeat(100000), 1f) <= 60_000L)
        // Медленная озвучка — дольше
        assertTrue(Speaker.estimateMs("а".repeat(300), 0.6f) > Speaker.estimateMs("а".repeat(300), 1.5f))
    }

    @Test fun noiseNeverBecomesCommand() {
        for (t in listOf("а", "мм", "э", "ну", "угу ага")) assertFalse(t, ListenFilter.meaningful(t))
        for (t in listOf("да", "нет", "включи свет", "стоп")) assertTrue(t, ListenFilter.meaningful(t))
    }
}
