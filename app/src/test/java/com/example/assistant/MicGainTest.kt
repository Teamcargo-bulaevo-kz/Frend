package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MicGainTest {
    private fun frame(amp: Int) = ShortArray(800) { i -> if (i % 2 == 0) amp.toShort() else (-amp).toShort() }
    private fun peak(p: ShortArray) = p.maxOf { abs(it.toInt()) }

    @Test fun quietSpeechIsAmplified() {
        val g = MicGain { 20 }
        var last = frame(150)
        repeat(60) { last = frame(150); g.process(last, last.size) }
        assertTrue("peak=${peak(last)}", peak(last) > 1500)
        assertTrue(g.currentGain <= 20.0)
    }

    @Test fun loudSpeechIsNotAmplifiedNorClipped() {
        val g = MicGain { 20 }
        val f = frame(12000)
        g.process(f, f.size)
        assertEquals(12000, peak(f))
    }

    @Test fun gainNeverExceedsLimit() {
        val g = MicGain { 5 }
        repeat(100) { val f = frame(40); g.process(f, f.size) }
        assertTrue(g.currentGain <= 5.0 + 1e-9)
    }

    @Test fun limitOneMeansNoChange() {
        val g = MicGain { 1 }
        val f = frame(100)
        g.process(f, f.size)
        assertEquals(100, peak(f))
    }
}
