package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioResamplerTest {

    @Test
    fun sameRateIsJustScaled() {
        val out = AudioResampler.toModel(shortArrayOf(0, 16384, -16384), 3, 8000)
        assertEquals(3, out.size)
        assertEquals(0.5f, out[1], 0.0001f)
        assertEquals(-0.5f, out[2], 0.0001f)
    }

    @Test
    fun frameOfEveryRateGivesExactly800Samples() {
        for (rate in AudioResampler.RATES) {
            val n = AudioResampler.frameSamples(rate)
            val out = AudioResampler.toModel(ShortArray(n) { 1000 }, n, rate)
            assertEquals("rate $rate", 800, out.size)
            // постоянный сигнал остаётся постоянным (без искажений уровня)
            for (v in out) assertEquals("rate $rate", 1000 / 32768.0f, v, 0.0005f)
        }
    }

    @Test
    fun lowToneKeepsLevelAndHighToneIsDamped() {
        val rate = 48000
        val n = AudioResampler.frameSamples(rate)
        fun tone(freq: Double) = ShortArray(n) { (10000 * Math.sin(2 * Math.PI * freq * it / rate)).toInt().toShort() }
        fun peak(a: FloatArray) = a.maxOf { Math.abs(it) }
        val low = peak(AudioResampler.toModel(tone(500.0), n, rate))
        val high = peak(AudioResampler.toModel(tone(11000.0), n, rate))
        assertTrue("низкий тон сохранился: $low", low > 10000 / 32768.0f * 0.9f)
        assertTrue("высокий тон приглушён: $high", high < low * 0.5f)
    }

    @Test
    fun emptyInput() {
        assertEquals(0, AudioResampler.toModel(ShortArray(0), 0, 16000).size)
    }
}
