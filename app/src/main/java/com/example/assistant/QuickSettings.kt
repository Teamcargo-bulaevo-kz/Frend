package com.example.assistant

/**
 * Плитки шторки: как их называют люди и как они подписаны на экране телефона.
 * Чистый Kotlin, проверяется юнит-тестами.
 */
object QuickSettings {

    /** Ключ → слова, по которым плитку узнают в речи и по подписи на экране (в нормализованном виде). */
    val tiles: Map<String, List<String>> = linkedMapOf(
        "wifi" to listOf("wi fi", "wifi", "вай фай", "вайфай", "вай-фай"),
        "bluetooth" to listOf("bluetooth", "блютуз", "блютус", "блутус"),
        "mobile_data" to listOf("мобильный интернет", "мобильные данные", "передача данных", "мобильн", "mobile data", "интернет", "данные"),
        "airplane" to listOf("режим полета", "авиарежим", "режим в самолете", "airplane", "полет", "самолет"),
        "dnd" to listOf("не беспокоить", "dnd", "do not disturb", "режим тишины"),
        "location" to listOf("геолокация", "местоположение", "геопозиция", "location", "gps"),
        "hotspot" to listOf("точка доступа", "режим модема", "hotspot", "модем", "раздача интернета", "раздать интернет"),
        "nfc" to listOf("nfc", "нфс", "бесконтактная"),
        "rotation" to listOf("автоповорот", "поворот экрана", "auto rotate", "rotation", "поворот"),
        "battery_saver" to listOf("экономия заряда", "энергосбережение", "режим энергосбережения", "battery saver", "экономия энергии"),
        "dark_theme" to listOf("темная тема", "темный режим", "dark theme", "dark mode", "ночная тема"),
        "silent" to listOf("без звука", "бесшумный", "беззвучный", "silent", "тихий режим", "вибрация")
    )

    /** Что сказал пользователь → ключ плитки. null — такой плитки в списке нет (ищем по подписи как есть). */
    fun resolve(name: String): String? {
        val n = TextMatch.normalize(name)
        if (n.isEmpty()) return null
        // Сначала точное слово/фраза, потом по вхождению; длинные названия первыми
        return tiles.entries.firstOrNull { (_, aliases) -> aliases.any { n == TextMatch.normalize(it) } }?.key
            ?: tiles.entries.firstOrNull { (_, aliases) ->
                aliases.sortedByDescending { it.length }.any { n.contains(TextMatch.normalize(it)) }
            }?.key
    }

    /** Слова, по которым ищем плитку на экране. */
    fun labelsFor(name: String): List<String> {
        val key = resolve(name)
        val list = if (key != null) tiles.getValue(key) else listOf(name)
        return list.map { TextMatch.normalize(it) }.filter { it.isNotEmpty() }
    }

    /** По подписи плитки понять, включена ли она: true, false или null, если непонятно. */
    fun stateFrom(text: String): Boolean? {
        val n = " " + TextMatch.normalize(text) + " "
        val off = listOf("выключ", "отключ", " off ", "неактив", "не активн", " отключено ")
        val on = listOf("включ", " on ", "активн")
        return when {
            off.any { n.contains(it) } -> false
            on.any { n.contains(it) } -> true
            else -> null
        }
    }
}
