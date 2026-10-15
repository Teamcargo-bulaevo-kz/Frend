package com.example.assistant

import java.util.Locale

/** Нормализация и нечёткое сравнение текста (русский и английский). Чистый Kotlin, без Android. */
object TextMatch {

    /** Нижний регистр, «ё» как «е», всё кроме букв и цифр заменено пробелом, пробелы схлопнуты. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var lastSpace = true
        for (ch in text.lowercase(Locale.ROOT)) {
            val c = if (ch == 'ё') 'е' else ch
            if (c.isLetterOrDigit()) {
                sb.append(c)
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
            }
        }
        return sb.toString().trim()
    }

    private val cyrillic = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
        'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f",
        'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sh", 'ъ' to "", 'ы' to "i",
        'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    private val cNotH = Regex("c(?!h)")
    private val gSoft = Regex("g(?=[eiy])")
    private val doubled = Regex("(.)\\1+")

    /**
     * Ключ для сравнения «на слух»: кириллица переводится в латиницу, затем похожие по звучанию
     * буквы сводятся к одной ("Линкин Парк" и "Linkin Park" дают один ключ). Пробелы убраны.
     */
    fun key(text: String): String {
        val sb = StringBuilder()
        for (ch in normalize(text)) {
            val mapped = cyrillic[ch]
            if (mapped != null) sb.append(mapped) else if (ch != ' ') sb.append(ch)
        }
        return sb.toString()
            .replace("dzh", "j").replace("dj", "j").replace("zh", "j")
            .replace("ph", "f").replace("ck", "k").replace("kh", "h")
            .replace(cNotH, "k")
            .replace(gSoft, "j")
            .replace('w', 'v').replace("x", "ks").replace('q', 'k').replace('y', 'i')
            .replace(doubled, "$1")
    }

    /** Расстояние Левенштейна. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** Похожесть от 0 до 1 (1 — одинаковые). */
    fun similarity(a: String, b: String): Double {
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - distance(a, b).toDouble() / longest
    }
}
