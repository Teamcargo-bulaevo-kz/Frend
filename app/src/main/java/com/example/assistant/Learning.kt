package com.example.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Как ассистент учится работать без модели (0 токенов, без интернета):
 *  - точная фраза → функция (как раньше);
 *  - шаблон с числами: «громкость на 30» выучено один раз — «громкость на 70» тоже понимается сам;
 *  - связка: если модель для фразы вызвала несколько функций, в следующий раз они выполняются сами;
 *  - то, чему научил пользователь: «когда я говорю „я дома“, включи вайфай и выключи мобильный интернет».
 * Всё хранится в той же таблице выученных фраз (схема базы не меняется). Чистые функции — здесь, покрыты тестами.
 */
object Learning {

    const val TEMPLATE_PREFIX = "T:"
    const val MACRO = "__macro__"
    const val TAUGHT = "__say__"

    private val number = Regex("(?<![\\p{L}\\d])\\d{1,4}(?![\\p{L}\\d])")

    /** Числа во фразе по порядку. */
    fun numbers(phrase: String): List<Int> = number.findAll(phrase).mapNotNull { it.value.toIntOrNull() }.toList()

    /** «поставь громкость на 30» → «поставь громкость на #». null — чисел нет. */
    fun template(phrase: String): String? = if (number.containsMatchIn(phrase)) TEMPLATE_PREFIX + phrase.replace(number, "#") else null

    /**
     * Аргументы с числами из фразы заменяются ссылками «#0», «#1»…, чтобы шаблон подставлял новые числа.
     * null — числа аргументов не совпали с числами фразы (тогда шаблон учить нельзя).
     */
    fun generalize(phrase: String, args: JSONObject): JSONObject? {
        val nums = numbers(phrase)
        if (nums.isEmpty()) return null
        val out = JSONObject()
        var used = false
        for (k in args.keys()) {
            val v = args.opt(k)
            val n = (v as? Number)?.toDouble()
            val idx = if (n != null && n == Math.floor(n)) nums.indexOf(n.toInt()) else -1
            if (idx >= 0) { out.put(k, "#$idx"); used = true } else out.put(k, v)
        }
        return if (used) out else null
    }

    /** Подставляет числа новой фразы в шаблонные аргументы. */
    fun fill(args: JSONObject, phrase: String): JSONObject? {
        val nums = numbers(phrase)
        val out = JSONObject()
        for (k in args.keys()) {
            val v = args.opt(k)
            if (v is String && v.matches(Regex("#\\d"))) {
                out.put(k, nums.getOrNull(v.drop(1).toInt()) ?: return null)
            } else out.put(k, v)
        }
        return out
    }

    /** Функции, которые не зависят от разговора и безопасны для повторения без модели. */
    fun cacheable(name: String, args: JSONObject): Boolean = when (name) {
        "set_alarm", "play_music", "music_control", "set_volume", "flashlight", "open_camera", "take_screenshot", "open_app",
        "open_browser", "open_settings", "open_dialer", "quick_setting", "set_brightness", "music_wave", "device_info",
        "notifications", "set_timer", "calendar_list", "list_reminders", "live_screen", "screen_translate" -> true
        "screen_control" -> args.optString("action") !in setOf("click", "type")
        "files" -> args.optString("action") in setOf("storage", "big", "duplicates")
        else -> false
    }

    fun macroJson(calls: List<Pair<String, JSONObject>>): String =
        JSONArray().apply { calls.forEach { (n, a) -> put(JSONObject().put("name", n).put("args", a)) } }.toString()

    fun macroCalls(json: String): List<Pair<String, JSONObject>> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("name") to (it.optJSONObject("args") ?: JSONObject()) } }
    } catch (e: Exception) { emptyList() }

    // ---------------------------------------------------------------- «когда я говорю …, делай …»

    private val teachRe = Regex(
        "^(?:запомни[,:]?\\s+|научись[,:]?\\s+)?когда я (?:говорю|скажу|произношу)\\s+[«\"]?(.+?)[»\"]?\\s*(?:[,:—–-]+\\s*|\\s+то\\s+)(?:то\\s+)?(?:ты\\s+)?(?:делай|сделай|выполняй|выполни)?\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )

    /** «Когда я говорю „я дома“, включи вайфай и выключи мобильный интернет» → фраза и список команд. */
    fun parseTeach(raw: String): Pair<String, List<String>>? {
        val m = teachRe.find(raw.trim().trimEnd('.', '!')) ?: return null
        val phrase = TextMatch.normalize(m.groupValues[1])
        val actions = m.groupValues[2].split(Regex("\\s+(?:и|потом|затем|а потом|после этого)\\s+|[,;]\\s*"))
            .map { it.trim() }.filter { it.length >= 3 }
        if (phrase.isEmpty() || phrase.split(' ').size > 6 || actions.isEmpty()) return null
        return phrase to actions
    }

    /** Похожая выученная фраза (для неточного совпадения): та же длина в словах, почти те же буквы. */
    fun close(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (numbers(a) != numbers(b)) return false
        if (kotlin.math.abs(a.split(' ').size - b.split(' ').size) > 1) return false
        return TextMatch.similarity(a, b) >= 0.88
    }
}
