package com.example.assistant

import org.json.JSONArray

/**
 * Пакетный перевод надписей экрана одной просьбой к модели: на входе JSON-массив строк, на выходе такой же массив.
 * Чистый Kotlin, покрыт тестами.
 */
object TranslateBatch {

    /** Языки, на которые переводим: ключ — как говорит пользователь, значение — название для модели. */
    val languages: Map<String, String> = linkedMapOf(
        "рус" to "русский", "англ" to "английский", "казах" to "казахский", "немец" to "немецкий", "француз" to "французский",
        "испан" to "испанский", "итальян" to "итальянский", "китай" to "китайский", "турец" to "турецкий", "украин" to "украинский",
        "араб" to "арабский", "япон" to "японский", "корей" to "корейский", "узбек" to "узбекский", "польск" to "польский"
    )

    /** «переведи экран на английский» → «английский». По умолчанию — русский. */
    fun targetFrom(text: String): String {
        val t = TextMatch.normalize(text)
        return languages.entries.firstOrNull { (stem, _) -> t.split(' ').any { it.startsWith(stem) && it != "русскую" } }?.value ?: "русский"
    }

    private fun isCyrillic(c: Char) = c in 'Ѐ'..'ӿ'
    private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** Нужно ли переводить надпись: в ней есть буквы и большинство из них не на языке перевода. */
    fun needsTranslation(text: String, target: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 2) return false
        val share = when (target) {
            "русский", "украинский", "казахский" -> letters.count(::isCyrillic).toDouble() / letters.length
            "английский", "немецкий", "французский", "испанский", "итальянский", "турецкий", "польский", "узбекский" ->
                letters.count(::isLatin).toDouble() / letters.length
            else -> 0.0
        }
        return share < 0.6
    }

    fun prompt(target: String): String =
        "Переведи каждую строку массива на $target язык. Верни ТОЛЬКО JSON-массив строк той же длины и в том же порядке. " +
            "Кратко и естественно, как перевели бы интерфейс приложения. Имена, числа, ссылки и коды оставляй как есть."

    fun input(lines: List<String>): String = JSONArray(lines).toString()

    /** Ответ модели → переводы. null — ответ не того размера или не JSON. */
    fun parse(response: String, expected: Int): List<String>? {
        val start = response.indexOf('[')
        val end = response.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            val arr = JSONArray(response.substring(start, end + 1))
            if (arr.length() != expected) return null
            (0 until arr.length()).map { arr.optString(it) }
        } catch (e: Exception) { null }
    }

    /** Делит строки на пачки, чтобы запрос не был слишком длинным. */
    fun chunks(lines: List<String>, maxChars: Int = 3500, maxItems: Int = 60): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var size = 0
        for (l in lines) {
            if (cur.isNotEmpty() && (size + l.length > maxChars || cur.size >= maxItems)) { out.add(cur); cur = ArrayList(); size = 0 }
            cur.add(l); size += l.length
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }
}
