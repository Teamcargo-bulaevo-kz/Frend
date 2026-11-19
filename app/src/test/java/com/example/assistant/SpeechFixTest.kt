package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechFixTest {

    @Test fun politeAndDistortedVerbs() {
        assertEquals("включи фонарик", SpeechFix.correct("Включите фонарик"))
        assertEquals("выключи фонарик", SpeechFix.correct("выключите фонарик"))
        assertEquals("поставь будильник на 7", SpeechFix.correct("поставьте будильнек на 7"))
        assertEquals("сделай скриншот", SpeechFix.correct("сделай скриншод"))
    }

    @Test fun leavesOtherWordsAlone() {
        // Не первое слово-глагол не трогаем: «я включил» — рассказ, а не команда
        assertEquals("я включил фонарик вчера", SpeechFix.correct("я включил фонарик вчера"))
        assertEquals("позвони маме", SpeechFix.correct("Позвони маме"))
        assertEquals("включи баста", SpeechFix.correct("включи баста"))
        assertEquals("", SpeechFix.correct("  "))
    }

    @Test fun closest() {
        assertEquals("фонарик", SpeechFix.closest("фанарик", listOf("фонарик", "будильник")))
        // Короткие слова и слова с цифрами не исправляем
        assertNull(SpeechFix.closest("тиш", listOf("тише")))
        assertNull(SpeechFix.closest("7утра", listOf("утра")))
        // Другая первая буква — не исправляем
        assertNull(SpeechFix.closest("ванарик", listOf("фонарик")))
        // Одинаково похожи на два слова — не угадываем
        assertNull(SpeechFix.closest("погоде", listOf("погода", "погоду")))
    }
}
