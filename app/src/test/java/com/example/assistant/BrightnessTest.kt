package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessTest {

    @Test fun hundredPercentIsDeviceMaximum() {
        assertEquals(255, Brightness.sliderToSetting(100, 1, 255))
        assertEquals(4095, Brightness.sliderToSetting(100, 1, 4095))
        assertEquals(2047, Brightness.sliderToSetting(100, 1, 2047))
    }

    @Test fun halfSliderIsNotHalfLinear() {
        // Как у системного ползунка: середина — это примерно 8% линейной яркости
        val half = Brightness.sliderToSetting(50, 0, 4095)
        assertTrue("$half", half in 300..380)
    }

    @Test fun roundTrip() {
        for (p in listOf(5, 20, 50, 75, 100)) {
            val v = Brightness.sliderToSetting(p, 1, 4095)
            assertTrue("$p → $v → ${Brightness.settingToSlider(v, 1, 4095)}", kotlin.math.abs(Brightness.settingToSlider(v, 1, 4095) - p) <= 2)
        }
    }

    @Test fun xiaomiScaleDetected() {
        assertEquals(4095, Brightness.guessMax(null, 3000))
        assertEquals(2047, Brightness.guessMax(null, 1500))
        assertEquals(255, Brightness.guessMax(null, 120))
        assertEquals(2047, Brightness.guessMax(2047, 120))
        assertEquals(4095, Brightness.guessMax(255, 3000))
    }
}
