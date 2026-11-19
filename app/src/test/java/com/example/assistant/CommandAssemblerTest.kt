package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAssemblerTest {

    @Test fun cutByPause() {
        assertTrue(CommandAssembler.looksIncomplete("поставь"))
        assertTrue(CommandAssembler.looksIncomplete("Поставь мне"))
        assertTrue(CommandAssembler.looksIncomplete("напомни мне"))
        assertTrue(CommandAssembler.looksIncomplete("поставь будильник на"))
        assertTrue(CommandAssembler.looksIncomplete("включи песню про"))
        assertTrue(CommandAssembler.looksIncomplete("какая"))
        assertTrue(CommandAssembler.looksIncomplete("найди пожалуйста"))
    }

    @Test fun complete() {
        assertFalse(CommandAssembler.looksIncomplete("поставь будильник на семь"))
        assertFalse(CommandAssembler.looksIncomplete("включи фонарик"))
        assertFalse(CommandAssembler.looksIncomplete("позвони маме"))
        assertFalse(CommandAssembler.looksIncomplete("какая погода"))
        assertFalse(CommandAssembler.looksIncomplete("да"))
        assertFalse(CommandAssembler.looksIncomplete("нет"))
        assertFalse(CommandAssembler.looksIncomplete("пауза"))
        assertFalse(CommandAssembler.looksIncomplete("громче"))
        assertFalse(CommandAssembler.looksIncomplete("прибавь"))
        assertFalse(CommandAssembler.looksIncomplete("ты слышишь меня"))
        assertFalse(CommandAssembler.looksIncomplete(""))
    }

    @Test fun join() {
        assertEquals("поставь будильник на семь", CommandAssembler.join("поставь", " будильник на семь"))
        assertEquals("включи", CommandAssembler.join("", "включи"))
        assertEquals("включи", CommandAssembler.join("включи", " "))
        // Склейка оборванной команды и продолжения даёт законченную
        val full = CommandAssembler.join("напомни мне", "купить хлеб")
        assertFalse(CommandAssembler.looksIncomplete(full))
    }
}
