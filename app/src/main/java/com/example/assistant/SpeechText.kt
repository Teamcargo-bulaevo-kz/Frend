package com.example.assistant

/** Подготовка текста к озвучке (чистый Kotlin, есть тесты). */
object SpeechText {

    private const val MAX_SPOKEN = 600

    /**
     * Убирает то, что плохо звучит вслух: разметку, значки, ссылки. Переносы строк и пункты списка
     * превращаются в паузы (точки), а обрезается текст по границе предложения, а не посреди слова.
     */
    fun clean(text: String): String {
        val flat = text
            .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " Код показан на экране. ")
            .replace(Regex("[*_#`▪]+"), " ")
            .replace(Regex("https?://\\S+"), "ссылка")
            .replace(Regex("[\\p{So}\\p{Cs}]"), "")
            .replace(Regex("\\s*[\\n\\r]+\\s*[•\\-–]?\\s*"), ". ")
            .replace("•", " ")
            .replace(Regex("([.!?…:;,])\\s*\\.\\s+"), "$1 ")
            .replace(Regex("\\s+"), " ").trim()
        if (flat.length <= MAX_SPOKEN) return flat
        val cut = flat.take(MAX_SPOKEN)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > MAX_SPOKEN / 3) cut.substring(0, end + 1) else cut
    }
}
