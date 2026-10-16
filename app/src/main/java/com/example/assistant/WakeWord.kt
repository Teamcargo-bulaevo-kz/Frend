package com.example.assistant

/** Распознавание слова «Джарвис» в тексте. Чистый Kotlin. */
object WakeWord {

    /** Слово похоже на «Джарвис» (распознавание речи пишет его по-разному). */
    fun isWakeWord(word: String): Boolean {
        val w = TextMatch.normalize(word)
        return w.startsWith("джарв") || w.startsWith("жарв") || w.startsWith("джерв") || w.startsWith("чарв") ||
            w.startsWith("jarv") || w.startsWith("гарвис") || w.startsWith("джаврис")
    }

    /** Если в тексте есть «Джарвис», возвращает команду после него (может быть пустой), иначе null. */
    fun commandAfter(text: String): String? {
        val words = TextMatch.normalize(text).split(' ').filter { it.isNotEmpty() }
        val i = words.indexOfFirst { isWakeWord(it) }
        if (i < 0) return null
        return words.drop(i + 1).joinToString(" ")
    }
}
