package com.example.assistant

/**
 * Распознавание слова активации («Джарвис» по умолчанию, можно поменять) в тексте.
 * Чистый Kotlin. Офлайн-распознавание часто искажает слово, поэтому сравнение нечёткое.
 */
object WakeWord {

    const val DEFAULT = "джарвис"

    /** Расстояние Левенштейна между двумя строками. */
    internal fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Приводит введённое слово к виду для сравнения. Пусто или слишком коротко — берётся стандартное. */
    fun clean(word: String?): String {
        val w = TextMatch.normalize(word.orEmpty()).replace(" ", "")
        return if (w.length < 4) DEFAULT else w
    }

    /** Сколько ошибок распознавания прощаем для слова такой длины. */
    private fun allowed(target: String) = if (target.length >= 7) 2 else 1

    /** Слово (или несколько слов, склеенных вместе) похоже на слово активации. */
    fun isWakeWord(word: String, target: String = DEFAULT): Boolean {
        val t = clean(target)
        val w = TextMatch.normalize(word).replace(" ", "")
        if (w.isEmpty()) return false
        if (t == DEFAULT && (w.startsWith("джарв") || w.startsWith("жарв") || w.startsWith("джерв") ||
                w.startsWith("чарв") || w.startsWith("jarv") || w.startsWith("гарвис") || w.startsWith("джаврис"))) return true
        if (w.length < t.length - 2) return false
        return distance(w, t) <= allowed(t)
    }

    /**
     * Если в тексте есть слово активации, возвращает команду после него (может быть пустой), иначе null.
     * Учитывает, что слово могло распознаться как два слова («джар вис»).
     */
    fun commandAfter(text: String, target: String = DEFAULT): String? {
        val words = TextMatch.normalize(text).split(' ').filter { it.isNotEmpty() }
        for (i in words.indices) {
            if (isWakeWord(words[i], target)) return words.drop(i + 1).joinToString(" ")
            if (i + 1 < words.size && isWakeWord(words[i] + words[i + 1], target)) {
                return words.drop(i + 2).joinToString(" ")
            }
        }
        return null
    }
}
