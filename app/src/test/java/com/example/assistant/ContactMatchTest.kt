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
        assertTrue(WakeWord.isWakeWord("Джарвис"))
        assertTrue(WakeWord.isWakeWord("жарвис"))
        assertFalse(WakeWord.isWakeWord("включи"))
        org.junit.Assert.assertEquals("включи музыку", WakeWord.commandAfter("Джарвис, включи музыку"))
        org.junit.Assert.assertEquals("", WakeWord.commandAfter("джарвис"))
        org.junit.Assert.assertNull(WakeWord.commandAfter("включи музыку"))
    }

    @Test fun confirmWords() {
        assertTrue(Confirm.isYes("Да"))
        assertTrue(Confirm.isYes("да, удаляй"))
        assertTrue(Confirm.isYes("подтверждаю"))
        assertFalse(Confirm.isYes("нет"))
        assertFalse(Confirm.isYes("да не надо совсем"))
        assertTrue(Confirm.isNo("нет"))
        assertTrue(Confirm.isNo("не надо"))
        assertTrue(Confirm.isNo("Отмена"))
        assertFalse(Confirm.isNo("да"))
        assertTrue(Confirm.isAsk(Confirm.ask("Удалить?")))
        org.junit.Assert.assertEquals("Удалить?", Confirm.question(Confirm.ask("Удалить?")))
    }
}
