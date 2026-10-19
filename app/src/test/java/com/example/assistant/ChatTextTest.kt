package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextTest {

    @Test fun plainTextIsOneSegment() {
        assertEquals(listOf<Segment>(Segment.Text("Привет")), MessageParser.split("Привет"))
    }

    @Test fun codeBlockIsSeparated() {
        val parts = MessageParser.split("Вот пример:\n```python\nprint(1)\nprint(2)\n```\nГотово")
        assertEquals(3, parts.size)
        assertEquals(Segment.Text("Вот пример:"), parts[0])
        assertEquals(Segment.Code("python", "print(1)\nprint(2)"), parts[1])
        assertEquals(Segment.Text("Готово"), parts[2])
    }

    @Test fun unclosedCodeBlockStillWorks() {
        val parts = MessageParser.split("```\nabc")
        assertEquals(listOf<Segment>(Segment.Code("", "abc")), parts)
    }

    @Test fun inlineStyles() {
        val s = MessageParser.styled("Это **важно** и `код`")
        assertEquals("Это важно и код", s.text)
        assertEquals(listOf(4..8), s.bold)
        assertEquals(listOf(12..14), s.mono)
    }

    @Test fun speechCleaningMakesPausesAndSkipsCode() {
        val t = SpeechText.clean("**Список**:\n• один\n• два\n```\nx=1\n```")
        assertTrue(t, !t.contains("*") && !t.contains("•") && !t.contains("x=1"))
        assertTrue(t, t.contains("Код показан на экране"))
        assertEquals("ссылка", SpeechText.clean("https://example.com/a"))
    }

    @Test fun longSpeechCutsAtSentenceEnd() {
        val long = (1..100).joinToString(" ") { "Это предложение номер $it." }
        val t = SpeechText.clean(long)
        assertTrue(t.length <= 600)
        assertTrue(t.endsWith("."))
    }
}

class BriefAndErrorTest {
    @Test fun shortAnswerIsReadFully() {
        assertEquals("Фонарик включён.", SpeechText.brief("Фонарик включён."))
    }

    @Test fun longAnswerIsShortened() {
        val long = (1..30).joinToString(" ") { "Это длинное предложение номер $it." }
        val t = SpeechText.brief(long)
        assertTrue(t, t.length < 400)
        assertTrue(t, t.endsWith("Подробности на экране."))
        assertTrue(t, t.contains("номер 1."))
    }

    @Test fun codeAnswerSaysCodeOnScreen() {
        val t = SpeechText.brief("Вот пример страницы приветствия на HTML, которая показывает надпись Hello World по центру экрана и использует простую разметку без скриптов и стилей, чтобы было проще начать.\n```html\n<h1>Hello</h1>\n```\nСохрани файл как index.html и открой в браузере, всё заработает сразу.")
        assertTrue(t, t.contains("Код показан на экране"))
        assertTrue(t, !t.contains("<h1>"))
    }

    @Test fun rateLimitIsFriendly() {
        val msg = "Groq API error 429: {\"error\":{\"message\":\"Rate limit reached ... tokens per minute (TPM): Limit 8000. Please try again in 12.3825s.\"}}"
        val t = FriendlyError.fromMessage(msg)
        assertTrue(t, t.contains("в минуту") && t.contains("13"))
        assertTrue(FriendlyError.fromMessage("Groq API error 429: tokens per day (TPD) limit").contains("Дневной"))
    }
}
