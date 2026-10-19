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
        "location" to listOf("геолокация", "геолокацию", "местоположение", "геопозиция", "геопозицию", "location", "gps"),
        "hotspot" to listOf("точка доступа", "точку доступа", "точки доступа", "режим модема", "hotspot", "модем", "раздача интернета", "раздать интернет"),
        "nfc" to listOf("nfc", "нфс", "бесконтактная"),
        "rotation" to listOf("автоповорот", "поворот экрана", "auto rotate", "rotation", "поворот"),
        "battery_saver" to listOf("экономия заряда", "экономию заряда", "энергосбережение", "режим энергосбережения", "battery saver", "экономия энергии"),
        "dark_theme" to listOf("темная тема", "темную тему", "темный режим", "dark theme", "dark mode", "ночная тема"),
        "silent" to listOf("без звука", "бесшумный", "беззвучный", "silent", "тихий режим", "вибрация", "вибрацию")
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

    /**
     * По подписи плитки понять, включена ли она: true, false или null, если непонятно.
     * Понимает полные слова и сокращения («Выкл.», «Вкл.»), русские и английские подписи.
     * Если в подписи есть и то и другое, решает то, что встретилось раньше.
     */
    fun stateFrom(text: String): Boolean? {
        val words = TextMatch.normalize(text).split(' ').filter { it.isNotEmpty() }
        fun isOff(w: String) = w == "off" || w == "disabled" || w == "inactive" || w.startsWith("выкл") ||
            w.startsWith("отключен") || w.startsWith("неактив")
        fun isOn(w: String) = w == "on" || w == "enabled" || w == "active" || w == "вкл" ||
            (w.startsWith("включен")) || w.startsWith("активн")
        for (w in words) {
            if (isOff(w)) return false
            if (isOn(w)) return true
        }
        return null
    }
}
