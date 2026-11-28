package com.example.assistant

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * «Понималка» — маленькая офлайн-модель, которая понимает команду, сказанную своими словами:
 * «вырубай инет» → «отключи мобильный интернет», «ничего не слышу» → «громче».
 *
 * Как устроена: каждая фраза превращается в набор признаков — основы слов (первые 5 букв) и кусочки по 3 буквы
 * (так прощаются окончания и ошибки распознавания), с весами TF-IDF. Новая фраза сравнивается со всеми примерами
 * (косинусная близость); побеждает команда с самым похожим примером, если она заметно лучше второй.
 * Обучение — это просто добавление примеров: предустановленные (assets/brain/intents.tsv), придуманные моделью Groq
 * и выученные из жизни. Весит килобайты, учится за миллисекунды, работает без интернета. Чистый Kotlin, есть тесты.
 */
class IntentModel(examples: List<Pair<String, String>>) {

    data class Match(val canonical: String, val score: Double, val margin: Double, val example: String)

    private class Vec(val canonical: String, val text: String, val weights: Map<String, Double>, val norm: Double)

    private val idf: Map<String, Double>
    private val vectors: List<Vec>
    val size: Int get() = vectors.size
    val intents: Int

    init {
        val docs = examples.map { (c, t) -> Triple(c, t, features(t)) }.filter { it.third.isNotEmpty() }
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.third.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = docs.size.coerceAtLeast(1)
        idf = df.mapValues { ln((n + 1.0) / (it.value + 0.5)) }
        vectors = docs.map { (c, t, f) ->
            val w = f.mapValues { (k, tf) -> tf * (idf[k] ?: 0.0) }
            Vec(c, t, w, sqrt(w.values.sumOf { it * it }).coerceAtLeast(1e-9))
        }
        intents = docs.map { it.first }.distinct().size
    }

    /** Лучшая команда для фразы или null, если уверенности мало. */
    fun predict(text: String, minScore: Double = MIN_SCORE, minMargin: Double = MIN_MARGIN): Match? {
        val all = rank(text)
        val best = all.firstOrNull() ?: return null
        val second = all.drop(1).firstOrNull { it.canonical != best.canonical }?.score ?: 0.0
        val m = best.copy(margin = best.score - second)
        return m.takeIf { it.score >= minScore && it.margin >= minMargin }
    }

    /** Лучший пример каждой команды, по убыванию похожести. */
    fun rank(text: String): List<Match> {
        val f = features(text)
        if (f.isEmpty()) return emptyList()
        val q = f.mapValues { (k, tf) -> tf * (idf[k] ?: ln(vectors.size + 1.0)) }
        val qn = sqrt(q.values.sumOf { it * it }).coerceAtLeast(1e-9)
        val best = HashMap<String, Match>()
        for (v in vectors) {
            var dot = 0.0
            for ((k, w) in q) v.weights[k]?.let { dot += w * it }
            val s = dot / (qn * v.norm)
            val cur = best[v.canonical]
            if (cur == null || s > cur.score) best[v.canonical] = Match(v.canonical, s, 0.0, v.text)
        }
        return best.values.sortedByDescending { it.score }
    }

    companion object {
        const val MIN_SCORE = 0.58
        const val MIN_MARGIN = 0.07

        private val stop = setOf("пожалуйста", "please", "ну", "а", "и", "мне", "давай", "можешь", "сможешь", "ка", "плиз", "эй", "слушай", "так", "же", "вот")

        /** Признаки фразы: основы слов и трёхбуквенные кусочки. Числа сводятся к одному признаку. */
        fun features(text: String): Map<String, Double> {
            val words = TextMatch.normalize(Calc.wordsToDigits(TextMatch.normalize(text.replace("{n}", "0")))).split(' ')
                .filter { it.isNotEmpty() && it !in stop }
            val f = HashMap<String, Double>()
            for (w in words) {
                if (w.all { it.isDigit() }) { f["#num"] = (f["#num"] ?: 0.0) + 1.0; continue }
                f["w:" + w.take(5)] = (f["w:" + w.take(5)] ?: 0.0) + 2.0
                val padded = "^$w$"
                for (i in 0..padded.length - 3) {
                    val g = "c:" + padded.substring(i, i + 3)
                    f[g] = (f[g] ?: 0.0) + 1.0
                }
            }
            return f
        }

        /** Разбор файла: «канон<TAB>вариант|вариант». Сам канон тоже пример. Строки с # — комментарии. */
        fun parseTsv(text: String): List<Pair<String, String>> = text.lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('\t') }
            .flatMap { line ->
                val canonical = line.substringBefore('\t').trim()
                val variants = line.substringAfter('\t').split('|').map { it.trim() }.filter { it.isNotEmpty() }
                (listOf(canonical) + variants).map { canonical to it }.asSequence()
            }.toList()

        /** Подставляет число из фразы в канон с {n}. null — числа нет, а канон его требует. */
        fun fill(canonical: String, phrase: String): String? {
            if (!canonical.contains("{n}")) return canonical
            val n = Learning.numbers(Calc.wordsToDigits(TextMatch.normalize(phrase))).firstOrNull() ?: return null
            return canonical.replace("{n}", n.toString())
        }

        fun toTsv(map: Map<String, List<String>>): String =
            map.entries.joinToString("\n") { (c, v) -> c + "\t" + v.distinct().joinToString("|") }
    }
}
