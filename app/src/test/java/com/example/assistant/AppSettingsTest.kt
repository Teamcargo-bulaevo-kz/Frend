package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test fun valuesAreClampedToRange() {
        val s = AppSettings.AFTER_ANSWER_MS
        assertEquals(s.min, s.clamp(-5))
        assertEquals(s.max, s.clamp(99_999))
        assertEquals(1800, s.clamp(1800))
    }

    @Test fun defaultsAreInsideRanges() {
        listOf(
            AppSettings.CUE_START_VOLUME, AppSettings.CUE_END_VOLUME, AppSettings.REQUEST_TIMEOUT_S,
            AppSettings.CONNECT_TIMEOUT_S, AppSettings.RETRY_WAIT_S, AppSettings.VOICE_DELAY_MS,
            AppSettings.AFTER_ANSWER_MS, AppSettings.VOICE_SILENCE_MS, AppSettings.CHAT_TEXT_SP, AppSettings.FONT_SCALE
        ).forEach { assertEquals(it.key, it.default, it.clamp(it.default)) }
    }

    @Test fun featureKeyMatchesSavedSwitches() {
        assertEquals("feature_flashlight", AppSettings.featureKey("flashlight"))
    }
}
