package com.example.assistant

/** Распознавание слова «Пятница» в тексте. Чистый Kotlin. */
object WakeWord {

    /** Учитывает обычные варианты распознавания слова «Пятница». */
    fun isWakeWord(word: String): Boolean {
        val w = TextMatch.normalize(word)
        return w.startsWith("пятниц") || w.startsWith("пятнит") || w.startsWith("пятни")
    }

    /** Если в тексте есть «Пятница», возвращает команду после него (может быть пустой), иначе null. */
    fun commandAfter(text: String): String? {
        val words = TextMatch.normalize(text).split(' ').filter { it.isNotEmpty() }
        val i = words.indexOfFirst { isWakeWord(it) }
        if (i < 0) return null
        return words.drop(i + 1).joinToString(" ")
    }
}
