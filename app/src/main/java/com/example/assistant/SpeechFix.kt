package com.example.assistant

/**
 * Исправление типичных ошибок офлайн-распознавания в словах команд: «включите» → «включи», «паставь» → «поставь»,
 * «будильнек» → «будильник». Используется только как вторая попытка, если правила не поняли фразу как есть,
 * поэтому уже работающие команды не меняются. Чистый Kotlin, есть тесты.
 *
 * Осторожность: глаголы исправляются только первым словом (команда начинается с глагола), остальные слова —
 * только если похожи на слово-признак команды (будильник, фонарик…). Если два варианта одинаково похожи — не трогаем.
 */
object SpeechFix {

    /** Глаголы, с которых начинаются команды правил. Звонки, удаление и отправка сюда не входят намеренно. */
    private val verbs = listOf(
        "включи", "выключи", "отключи", "поставь", "запусти", "открой", "найди", "покажи", "сделай", "сними",
        "разбуди", "заведи", "прибавь", "убавь", "погаси", "зажги", "сфотографируй", "сфоткай", "увеличь", "уменьши",
        "громче", "тише", "поищи"
    )

    /** Слова-признаки команд правил. */
    private val keywords = listOf(
        "будильник", "таймер", "фонарик", "скриншот", "громкость", "яркость", "погода", "погоду", "музыку",
        "песню", "плейлист", "камеру", "заметки"
    )

    private val verbSet = verbs.toSet()
    private val keywordSet = keywords.toSet()

    /** Нормализованный текст с исправленными словами (или тот же, если исправлять нечего). */
    fun correct(raw: String): String {
        val words = TextMatch.normalize(raw).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        return words.mapIndexed { i, word ->
            when {
                word in verbSet || word in keywordSet -> word
                i == 0 -> closest(word, verbs) ?: closest(word, keywords) ?: word
                else -> closest(word, keywords) ?: word
            }
        }.joinToString(" ")
    }

    /**
     * Самое похожее слово из списка: та же первая буква, длина отличается не больше чем на 2,
     * не больше 1 ошибки для коротких слов и 2 для длинных (от 7 букв). Ничья — null.
     */
    internal fun closest(word: String, candidates: List<String>): String? {
        if (word.length < 4 || word.any { it.isDigit() }) return null
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        var tie = false
        for (c in candidates) {
            if (c[0] != word[0] || kotlin.math.abs(c.length - word.length) > 2) continue
            val allowed = if (maxOf(c.length, word.length) >= 7) 2 else 1
            val d = TextMatch.distance(word, c)
            if (d > allowed) continue
            when {
                d < bestDistance -> { best = c; bestDistance = d; tie = false }
                d == bestDistance && c != best -> tie = true
            }
        }
        return if (tie) null else best
    }
}
