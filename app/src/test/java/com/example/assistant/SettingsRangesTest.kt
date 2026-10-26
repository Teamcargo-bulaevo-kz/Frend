package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRangesTest {
    @Test fun cueVolumeIsClamped() {
        assertEquals(0, SettingsRanges.cueVolume(-1))
        assertEquals(100, SettingsRanges.cueVolume(120))
        assertEquals(45, SettingsRanges.cueVolume(45))
    }

    @Test fun networkTimeoutsHaveSafeBounds() {
        assertEquals(5, SettingsRanges.connectTimeout(0))
        assertEquals(120, SettingsRanges.connectTimeout(999))
        assertEquals(5, SettingsRanges.readTimeout(-10))
        assertEquals(180, SettingsRanges.readTimeout(999))
    }

    @Test fun timingSettingsAreClamped() {
        assertEquals(0, SettingsRanges.rateLimitWait(-1))
        assertEquals(120, SettingsRanges.rateLimitWait(999))
        assertEquals(3, SettingsRanges.commandWindow(0))
        assertEquals(60, SettingsRanges.commandWindow(999))
        assertEquals(0, SettingsRanges.afterReplyDelay(-1))
        assertEquals(10_000, SettingsRanges.afterReplyDelay(20_000))
        assertEquals(500, SettingsRanges.voiceSilence(0))
        assertEquals(5_000, SettingsRanges.voiceSilence(10_000))
    }

    @Test fun appearanceSettingsAreClamped() {
        assertEquals(80, SettingsRanges.textScale(0))
        assertEquals(150, SettingsRanges.textScale(300))
        assertEquals(110, SettingsRanges.textScale(110))
    }
}
