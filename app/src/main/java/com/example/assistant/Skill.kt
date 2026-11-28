package com.example.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Навык: на одну фразу — ответ и цепочка обычных команд («я дома» → вайфай вкл, мобильный интернет выкл).
 * Хранится файлом JSON в Assistant/Навыки, его можно править, выключать и отдать другому. Чистый Kotlin, есть тесты.
 */
data class Skill(val name: String, val triggers: List<String>, val steps: List<String>, val answer: String = "", val enabled: Boolean = true) {

    fun toJson(): JSONObject = JSONObject().put("name", name).put("triggers", JSONArray(triggers)).put("answer", answer)
        .put("steps", JSONArray(steps)).put("enabled", enabled)

    /** Фраза запускает навык: совпадает с триггером (с допуском на ошибки распознавания) или с названием. */
    fun matches(prompt: String): Boolean {
        val p = TextMatch.normalize(prompt)
        if (p.isEmpty()) return false
        return (triggers + name).map { TextMatch.normalize(it) }.filter { it.isNotEmpty() }.any { t ->
            p == t || (t.length >= 6 && TextMatch.similarity(p, t) >= 0.86)
        }
    }

    companion object {
        fun parse(o: JSONObject): Skill? {
            val name = o.optString("name").trim().take(60)
            fun list(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
            val triggers = list("triggers").take(20)
            val steps = list("steps").take(10)
            if (name.isEmpty() || (triggers.isEmpty() && steps.isEmpty())) return null
            return Skill(name, triggers, steps, o.optString("answer").take(300), o.optBoolean("enabled", true))
        }

        fun parseList(json: String): List<Skill> = try {
            val t = json.trim()
            if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parse) } }
            else listOfNotNull(parse(JSONObject(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))))
        } catch (e: Exception) { emptyList() }

        /** Имя файла навыка: «Доброе утро» → «Доброе утро.json». */
        fun fileName(name: String) = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "навык" } + ".json"
    }
}
