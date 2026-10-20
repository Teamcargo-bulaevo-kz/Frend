package com.example.assistant

/** Проверка ответа на явную бессмыслицу (например, «+65° на улице»). Чистый Kotlin, есть тесты. */
object Sanity {

    private val weatherWords = Regex("погод|на улице|температур|ветер|осадки|дождь|снег|ощущается|прогноз", RegexOption.IGNORE_CASE)
    private val degrees = Regex("([+\\-−–]?\\d{1,3})(?:[.,]\\d+)?\\s*(?:°|º|градус\\p{L}*)")

    /** true — в ответе про погоду есть температура, которой на Земле не бывает. */
    fun implausibleTemperature(text: String): Boolean {
        if (!weatherWords.containsMatchIn(text)) return false
        return degrees.findAll(text).any { m ->
            val raw = m.groupValues[1].replace('−', '-').replace('–', '-').removePrefix("+")
            val v = raw.toIntOrNull() ?: return@any false
            v > 58 || v < -70
        }
    }

    const val WARNING = "\n⚠️ Температура в ответе выглядит неправдоподобно. Переспроси или уточни город."
}
