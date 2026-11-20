package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInTest {

    private val spoken = "Завтра в Астане облачно, днём до пяти градусов, ночью до минус трёх, ветер северный"

    @Test fun echoOfAssistantIsNotUserSpeech() {
        assertFalse(BargeIn.isUserSpeech("завтра в астане облачно", spoken, "пятница"))
        assertFalse(BargeIn.isUserSpeech("днем до пяти градус", spoken, "пятница"))
        // искажённое эхо
        assertFalse(BargeIn.isUserSpeech("завтро в остане облочно днём", spoken, "пятница"))
    }

    @Test fun stopWordsAndWakeWordInterrupt() {
        assertTrue(BargeIn.isUserSpeech("стоп", spoken, "пятница"))
        assertTrue(BargeIn.isUserSpeech("подожди", spoken, "пятница"))
        assertTrue(BargeIn.isUserSpeech("пятница", spoken, "пятница"))
    }

    @Test fun newRequestInterrupts() {
        assertTrue(BargeIn.isUserSpeech("поставь будильник на семь", spoken, "пятница"))
        assertTrue(BargeIn.isUserSpeech("а какой курс доллара", spoken, "пятница"))
        // одно-два чужих слова — ещё не перебивание (может быть шум)
        assertFalse(BargeIn.isUserSpeech("курс", spoken, "пятница"))
    }

    @Test fun stripEchoKeepsRequest() {
        assertEquals("поставь будильник на семь", BargeIn.stripEcho("облачно днём стоп поставь будильник на семь", spoken, "пятница"))
        assertEquals("какой курс доллара", BargeIn.stripEcho("пятница какой курс доллара", spoken, "пятница"))
        assertEquals("", BargeIn.stripEcho("стоп", spoken, "пятница"))
    }

    @Test fun endPhrases() {
        assertTrue(BargeIn.isEnd("Хватит"))
        assertTrue(BargeIn.isEnd("всё, спасибо"))
        assertFalse(BargeIn.isEnd("включи музыку"))
    }
}
