package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchTest {

    @Test fun normalizeLowersAndCleans() {
        assertEquals("привет мир елка 7 30", TextMatch.normalize("  Привет,  МИР!! Ёлка 7:30 "))
        assertEquals("", TextMatch.normalize("...!!!"))
    }

    @Test fun keyIsTheSameForCyrillicAndLatin() {
        assertEquals(TextMatch.key("Linkin Park"), TextMatch.key("Линкин Парк"))
        assertEquals(TextMatch.key("Telegram"), TextMatch.key("телеграм"))
        assertEquals(TextMatch.key("Instagram"), TextMatch.key("инстаграм"))
    }

    @Test fun similarityBasics() {
        assertEquals(1.0, TextMatch.similarity("abc", "abc"), 0.0001)
        assertEquals(0.0, TextMatch.similarity("abc", "xyz"), 0.0001)
        assertTrue(TextMatch.similarity("basta", "bastu") >= 0.79)
    }

    @Test fun distance() {
        assertEquals(3, TextMatch.distance("kitten", "sitting"))
        assertEquals(0, TextMatch.distance("", ""))
        assertEquals(4, TextMatch.distance("", "abcd"))
    }
}
