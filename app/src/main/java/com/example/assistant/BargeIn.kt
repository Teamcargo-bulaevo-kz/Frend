package com.example.assistant

/**
 * Перебивание ассистента голосом. Пока ассистент говорит, микрофон продолжает слушать, но распознаватель
 * слышит и самого ассистента (эхо из динамика). Отличаем так: эхо состоит из слов, которые ассистент
 * сейчас произносит, а пользователь говорит другое. Стоп-слова («стоп», «подожди», «хватит») и слово
 * активации прерывают сразу. Чистые функции без Android: покрыты тестами.
 */
object BargeIn {

    /** Слова, которыми пользователь просит ассистента замолчать. */
    val stopWords = setOf(
        "стоп", "стой", "хватит", "подожди", "погоди", "замолчи", "помолчи", "тихо", "достаточно", "довольно", "стопэ"
    )

    /** Слова, которыми пользователь заканчивает диалог. */
    val endPhrases = setOf(
        "пока", "хватит", "стоп", "все спасибо", "всё спасибо", "спасибо все", "спасибо всё", "спасибо пока", "конец диалога",
        "закончим", "закончи диалог", "выйди из диалога", "стоп диалог", "отбой", "достаточно", "на этом все", "на этом всё", "больше ничего"
    )

    private fun words(text: String): List<String> = TextMatch.normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Слово есть среди произносимых ассистентом (с допуском на ошибки распознавания). */
    private fun inSpoken(word: String, spoken: Set<String>): Boolean {
        if (word in spoken) return true
        if (word.length <= 3) return spoken.any { it.startsWith(word) }
        val stem = word.take(4)
        return spoken.any { s ->
            (s.length >= 4 && s.take(4) == stem) || (kotlin.math.abs(s.length - word.length) <= 2 && TextMatch.similarity(s, word) >= 0.7)
        }
    }

    /**
     * Похоже ли распознанное во время озвучки на речь пользователя, а не на эхо ассистента.
     * [partial] — что услышал распознаватель, [spoken] — что сейчас говорит ассистент, [wakeWord] — слово активации.
     */
    fun isUserSpeech(partial: String, spoken: String, wakeWord: String = ""): Boolean {
        val heard = words(partial)
        if (heard.isEmpty()) return false
        val said = words(spoken).toSet()
        // Слово активации и стоп-слова, если их нет в речи ассистента
        if (wakeWord.isNotBlank() && heard.any { TextMatch.similarity(it, wakeWord) >= 0.75 } && said.none { TextMatch.similarity(it, wakeWord) >= 0.75 }) return true
        if (heard.any { it in stopWords && it !in said }) return true
        // Обычная речь: хотя бы три слова, и большинство не из того, что говорит ассистент
        if (heard.size < 3) return false
        val foreign = heard.count { !inSpoken(it, said) }
        return foreign >= 3 && foreign.toDouble() / heard.size >= 0.6
    }

    /** Убирает из распознанного начало, совпадающее с речью ассистента, и стоп-слова в начале. */
    fun stripEcho(text: String, spoken: String, wakeWord: String = ""): String {
        val heard = words(text)
        val said = words(spoken).toSet()
        var i = 0
        while (i < heard.size) {
            val w = heard[i]
            val isWake = wakeWord.isNotBlank() && TextMatch.similarity(w, wakeWord) >= 0.75
            if (isWake || w in stopWords || inSpoken(w, said)) i++ else break
        }
        return heard.drop(i).joinToString(" ")
    }

    /** Пользователь хочет закончить диалог. */
    fun isEnd(text: String): Boolean = TextMatch.normalize(text).trim() in endPhrases
}
