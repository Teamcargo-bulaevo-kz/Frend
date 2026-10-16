package com.example.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatchTest {

    private fun yes(query: String, name: String) = assertTrue("«$query» должно подойти к «$name»", ContactMatch.matches(query, name))
    private fun no(query: String, name: String) = assertFalse("«$query» не должно подходить к «$name»", ContactMatch.matches(query, name))

    @Test fun declensions() {
        yes("маме", "Мама")
        yes("ивану", "Иван Петров")
        yes("ивана петрова", "Иван Петров")
        yes("наташе", "Наташа")
        yes("сергею", "Сергей")
        yes("александру", "Александр Иванов")
        yes("асель", "Асель")
    }

    @Test fun differentNames() {
        no("марину", "Мария")
        no("олега", "Оля")
        no("ивану", "Игорь")
        no("маме", "Максим")
    }

    @Test fun otherAlphabet() {
        yes("алекс", "Alex")
    }

    @Test fun exactPrefersFullName() {
        assertTrue(ContactMatch.exact("иван", "Иван"))
        assertFalse(ContactMatch.exact("иван", "Иван Петров"))
    }

    @Test fun wakeWord() {
        assertTrue(WakeWordService.isWakeWord("Джарвис"))
        assertTrue(WakeWordService.isWakeWord("жарвис"))
        assertFalse(WakeWordService.isWakeWord("включи"))
        org.junit.Assert.assertEquals("включи музыку", WakeWordService.commandAfterWakeWord("Джарвис, включи музыку"))
        org.junit.Assert.assertEquals("", WakeWordService.commandAfterWakeWord("джарвис"))
        org.junit.Assert.assertNull(WakeWordService.commandAfterWakeWord("включи музыку"))
    }
}
