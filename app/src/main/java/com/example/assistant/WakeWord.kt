package com.example.assistant

/** Сопоставление распознанной фразы с настраиваемым словом активации. */
object WakeWord {
    const val DEFAULT = "пятница"

    fun clean(word: String?): String {
        val normalized = TextMatch.normalize(word.orEmpty()).replace(" ", "")
        return if (normalized.length < 4) DEFAULT else normalized
    }

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

    fun isWakeWord(word: String, target: String = DEFAULT): Boolean {
        val expected = clean(target)
        val actual = TextMatch.normalize(word).replace(" ", "")
        if (actual.isEmpty() || actual.length < expected.length - 2) return false
        // Частые варианты распознавания по умолчанию.
        if (expected == DEFAULT && (actual.startsWith("пятниц") || actual.startsWith("пятнит") || actual.startsWith("пятни"))) return true
        return distance(actual, expected) <= if (expected.length >= 7) 2 else 1
    }

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
