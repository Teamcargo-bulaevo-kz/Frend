package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortTitleTest {
    @Test fun removesBracketsAndTails() {
        assertEquals("Звезда по имени Солнце", SpeechText.shortTitle("Звезда по имени Солнце (Official Video)"))
        assertEquals("Song", SpeechText.shortTitle("Song - Official Audio"))
    }

    @Test fun longTitlesAreCut() {
        val s = SpeechText.shortTitle("Очень длинное название песни которое никто не захочет слушать целиком вслух")
        assertTrue(s, s.length <= 40)
    }

    @Test fun shortTitleUnchanged() {
        assertEquals("Кино", SpeechText.shortTitle("Кино"))
    }
}
