package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MicGainTest {
    private fun frame(amp: Float) = FloatArray(800) { i -> if (i % 2 == 0) amp else -amp }
    private fun peak(f: FloatArray) = f.maxOf { abs(it) }

    @Test fun whisperIsAmplified() {
        val g = MicGain(1f, true)
        var last = frame(0.005f)
        repeat(80) { last = frame(0.005f); g.process(last) }
        assertTrue("peak=${peak(last)}", peak(last) > 0.05f)
        assertTrue(g.currentAutoGain <= 30.0)
    }

    @Test fun loudSpeechIsNotAmplified() {
        val g = MicGain(1f, true)
        val f = frame(0.4f)
        g.process(f)
        assertEquals(0.4f, peak(f), 1e-6f)
    }

    @Test fun autoGainRespectsLimit() {
        val g = MicGain(1f, true, 5f)
        repeat(100) { g.process(frame(0.001f)) }
        assertTrue(g.currentAutoGain <= 5.0 + 1e-9)
    }

    @Test fun fixedGainClipsAtOne() {
        val g = MicGain(4f, false)
        val f = frame(0.5f)
        g.process(f)
        assertEquals(1f, peak(f), 1e-6f)
    }

    @Test fun noGainMeansNoChange() {
        val g = MicGain(1f, false)
        val f = frame(0.1f)
        g.process(f)
        assertEquals(0.1f, peak(f), 1e-6f)
    }
}
