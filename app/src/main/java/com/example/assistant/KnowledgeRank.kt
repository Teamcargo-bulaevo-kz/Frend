package com.example.assistant

/** Запись базы знаний. */
data class Fact(val id: Long, val title: String, val body: String, val tags: String = "", val source: String = "")

/**
 * Поиск по базе знаний без интернета: совпадение основ слов (прощаются окончания), заголовок весит больше,
 * редкие слова важнее частых. Чистый Kotlin, есть тесты.
 */
object KnowledgeRank {

    private val stop = setOf(
        "что", "как", "где", "когда", "какой", "какая", "какие", "это", "про", "о", "об", "в", "на", "и", "а", "у", "меня", "мой", "моя",
        "мне", "ты", "знаешь", "скажи", "расскажи", "напомни", "найди", "базе", "базу", "базы", "есть", "ли", "по", "для", "с", "из", "не"
    )

    fun stems(text: String): Set<String> = TextMatch.normalize(text).split(' ')
        .filter { it.length >= 2 && it !in stop }.map { if (it.length > 5) it.take(5) else it }.toSet()

    data class Hit(val fact: Fact, val score: Double)

    fun search(query: String, facts: List<Fact>, limit: Int = 5): List<Hit> {
        val q = stems(query)
        if (q.isEmpty() || facts.isEmpty()) return emptyList()
        val df = HashMap<String, Int>()
        val docs = facts.map { f ->
            val t = stems(f.title + " " + f.tags); val b = stems(f.body)
            (t + b).forEach { df[it] = (df[it] ?: 0) + 1 }
            Triple(f, t, b)
        }
        val n = facts.size.toDouble()
        return docs.mapNotNull { (f, t, b) ->
            var s = 0.0
            for (w in q) {
                val idf = kotlin.math.ln(1 + n / ((df[w] ?: 0) + 0.5))
                if (w in t) s += 2.0 * idf else if (w in b) s += idf
            }
            val coverage = q.count { it in t || it in b }.toDouble() / q.size
            if (coverage < 0.5) null else Hit(f, s * coverage)
        }.sortedByDescending { it.score }.take(limit)
    }

    /** Ответ из найденного: самое подходящее целиком, остальное — заголовками. */
    fun answer(hits: List<Hit>): String {
        val top = hits.first().fact
        val more = hits.drop(1).take(3).map { it.fact.title }
        return "${top.title}: ${top.body}" + if (more.isNotEmpty()) "\nЕщё в базе: ${more.joinToString("; ")}." else ""
    }
}
