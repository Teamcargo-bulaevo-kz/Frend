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

    /**
     * Короткий пересказ для озвучки: первые одно-два предложения, остальное «на экране».
     * Короткие ответы (до ~260 символов) читаются целиком.
     */
    fun brief(text: String): String {
        val hadCode = text.contains("```")
        val flat = clean(text)
        if (flat.length <= BRIEF_LIMIT) return flat
        val sentences = Regex("(?<=[.!?…])\\s+").split(flat).filter { it.isNotBlank() }
        val sb = StringBuilder()
        var count = 0
        for (sent in sentences) {
            if (count >= 2 || (sb.isNotEmpty() && sb.length + sent.length > BRIEF_LIMIT)) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(sent)
            count++
        }
        var out = sb.toString().ifBlank { flat.take(BRIEF_LIMIT) }
        if (out.length > BRIEF_LIMIT + 60) out = out.take(BRIEF_LIMIT).trimEnd() + "…"
        return if (hadCode && !out.contains("Код показан")) "$out Код показан на экране." else "$out Подробности на экране."
    }

    /** Короткое название трека для голоса: без скобок, хвостов вроде «Official Video» и не длиннее ~40 символов. */
    fun shortTitle(title: String, limit: Int = 40): String {
        var t = title.replace(Regex("""\s*[(\[{][^)\]}]*[)\]}]"""), " ")
        t = t.replace(Regex("""(?i)\s*[-–—|]\s*(official|lyrics?|audio|video|clip|клип|текст|премьера|hd|hq|remix).*$"""), "")
        t = t.replace(Regex("""\s+"""), " ").trim()
        if (t.isEmpty()) t = title.trim()
        if (t.length <= limit) return t
        val cut = t.take(limit)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', '.', '-', ' ')
    }

    private const val BRIEF_LIMIT = 260
}
