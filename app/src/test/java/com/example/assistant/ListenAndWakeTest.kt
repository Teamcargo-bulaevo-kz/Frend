package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenAndWakeTest {

    @Test fun dayOfWeekInSpeechDoesNotWake() {
        assertNull(WakeWord.commandAfter("встретимся в пятницу вечером", "пятница"))
        assertNull(WakeWord.commandAfter("до пятницы ещё два дня", "пятница"))
        assertNull(WakeWord.commandAfter("в эту пятницу будет дождь", "пятница"))
        assertNull(WakeWord.commandAfter("я тебе позвоню когда то после работы в пятницу", "пятница"))
        assertNull(WakeWord.commandAfter("мы поедем куда нибудь далеко пятница была", "пятница"))
    }

    @Test fun addressingStillWakes() {
        assertEquals("включи музыку", WakeWord.commandAfter("пятница включи музыку", "пятница"))
        assertEquals("какая погода", WakeWord.commandAfter("эй пятница какая погода", "пятница"))
        assertEquals("", WakeWord.commandAfter("ну пятница", "пятница"))
        assertEquals("поставь будильник", WakeWord.commandAfter("слушай пятница поставь будильник", "пятница"))
        // искажения в начале фразы прощаются, как раньше
        assertEquals("включи свет", WakeWord.commandAfter("пятнице включи свет", "пятница"))
    }

    @Test fun followUpAcceptsRequestsOnly() {
        assertTrue(ListenFilter.followUp("а завтра"))
        assertTrue(ListenFilter.followUp("какая погода в астане"))
        assertTrue(ListenFilter.followUp("включи музыку"))
        assertTrue(ListenFilter.followUp("да"))
        assertTrue(ListenFilter.followUp("хватит"))
        assertTrue(ListenFilter.followUp("ну расскажи подробнее"))
        assertTrue(ListenFilter.followUp("что угодно", isCommand = true))
        assertFalse(ListenFilter.followUp("ага"))
        assertFalse(ListenFilter.followUp("угу понятно"))
        assertFalse(ListenFilter.followUp("мы вчера с ребятами ходили в кино было здорово"))
        assertFalse(ListenFilter.followUp(""))
    }

    @Test fun meaningfulCommandAfterWake() {
        assertTrue(ListenFilter.meaningful("включи свет"))
        assertTrue(ListenFilter.meaningful("да"))
        assertFalse(ListenFilter.meaningful("ну"))
        assertFalse(ListenFilter.meaningful("эм ага"))
    }

    @Test fun bargeInOnLoudspeakerOnlyByStopWords() {
        val spoken = "Сегодня в Астане облачно, плюс пять"
        assertFalse(BargeIn.isUserSpeech("поставь будильник на семь утра", spoken, "пятница", onlyStopWords = true))
        assertTrue(BargeIn.isUserSpeech("стоп", spoken, "пятница", onlyStopWords = true))
        assertTrue(BargeIn.isUserSpeech("пятница", spoken, "пятница", onlyStopWords = true))
        assertTrue(BargeIn.isUserSpeech("поставь будильник на семь утра", spoken, "пятница", onlyStopWords = false))
    }
}
