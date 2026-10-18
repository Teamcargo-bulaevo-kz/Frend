package com.example.assistant

/**
 * Сопоставление того, что сказал пользователь («Ивану», «маме», «Асель»), с именем в телефонной книге
 * («Иван Петров», «Мама»). Чистый Kotlin, без Android: проверяется юнит-тестами.
 */
object ContactMatch {

    /** Слово из запроса подходит слову из имени контакта: то же слово или то же слово в другом падеже. */
    fun tokenMatches(query: String, name: String): Boolean {
        if (query == name) return true
        val shortest = minOf(query.length, name.length)
        var p = 0
        while (p < shortest && query[p] == name[p]) p++
        // Одно слово — начало другого («ивану» ~ «иван», «брату» ~ «брат»)
        if (shortest >= 3 && p == shortest && maxOf(query.length, name.length) - shortest <= 3) return true
        // Одинаковая длина, отличается только окончание («маме» ~ «мама»), но «марину» !~ «мария»
        if (shortest >= 4 && query.length == name.length && p >= shortest - 1) return true
        // Имя в телефоне латиницей, а сказали по-русски (и наоборот)
        val a = TextMatch.key(query)
        val b = TextMatch.key(name)
        return a.length >= 4 && b.length >= 4 && TextMatch.similarity(a, b) >= 0.86
    }

    /** Каждое слово запроса нашло пару среди слов имени. */
    fun matches(query: String, contactName: String): Boolean {
        val q = TextMatch.normalize(query).split(' ').filter { it.isNotEmpty() }
        val n = TextMatch.normalize(contactName).split(' ').filter { it.isNotEmpty() }
        if (q.isEmpty() || n.isEmpty()) return false
        return q.all { qt -> n.any { nt -> tokenMatches(qt, nt) } }
    }

    /** Имя совпало целиком (без учёта падежа последнего слова), нужно, чтобы выбрать «Иван», а не «Иван Петров». */
    fun exact(query: String, contactName: String): Boolean {
        val q = TextMatch.normalize(query).split(' ').filter { it.isNotEmpty() }
        val n = TextMatch.normalize(contactName).split(' ').filter { it.isNotEmpty() }
        return q.size == n.size && q.indices.all { tokenMatches(q[it], n[it]) }
    }
}
