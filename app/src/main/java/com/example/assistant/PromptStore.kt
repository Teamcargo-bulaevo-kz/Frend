package com.example.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Промпт и навыки»: что пользователь сам настраивает в поведении ассистента.
 *  • Мои указания — короткий текст, который уходит в каждый разговор с моделью («обращайся на ты», «я учусь в 9 классе»).
 *  • Встроенные инструкции — наглядные ответы, доска и чертёж, план для сложных задач, голосовые запросы: каждую можно выключить.
 *  • Навыки-инструкции — свои «режимы»: название, слова-триггеры и текст; когда в просьбе есть триггер, текст добавляется к запросу
 *    (без триггеров — всегда). Например «Репетитор»: «объясни, реши» → «объясняй по шагам, задавай наводящие вопросы».
 * Хранится в настройках приложения; ничего не уходит наружу, кроме самого текста в запросе к модели.
 */
object PromptStore {

    const val VISUAL = "visual"
    const val BOARD = "board"
    const val PLAN = "plan"
    const val VOICE = "voice"

    data class BuiltIn(val id: String, val title: String, val summary: String, val text: String)

    val builtIns: List<BuiltIn> get() = listOf(
        BuiltIn(VISUAL, "Наглядные ответы", "Диаграммы, таблицы, деревья, списки и мини-приложения в ответах", AssistantPrompt.VISUAL_NOTE),
        BuiltIn(BOARD, "Доска и чертёж", "Объясняет мелом на доске и чертит по ГОСТ, по шагам и с озвучкой", AssistantPrompt.BOARD_NOTE),
        BuiltIn(PLAN, "План для сложных задач", "Сначала план шагов, потом выполнение по шагам с галочками", AgentPlan.NOTE),
        BuiltIn(VOICE, "Голосовые запросы", "Понимать распознанную речь по смыслу, переспрашивать перед необратимым", AssistantPrompt.VOICE_NOTE)
    )

    data class Instruction(val name: String, val triggers: List<String>, val text: String, val enabled: Boolean = true)

    private const val PREFS = "assistant_prefs"
    private const val KEY_CUSTOM = "prompt_custom"
    private const val KEY_LIST = "prompt_instructions"
    private const val MAX_CUSTOM = 1500

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun builtInEnabled(context: Context, id: String) = prefs(context).getBoolean("prompt_builtin_$id", true)
    fun setBuiltIn(context: Context, id: String, on: Boolean) = prefs(context).edit().putBoolean("prompt_builtin_$id", on).apply()

    fun custom(context: Context): String = prefs(context).getString(KEY_CUSTOM, "").orEmpty()
    fun setCustom(context: Context, text: String) = prefs(context).edit().putString(KEY_CUSTOM, text.trim().take(MAX_CUSTOM)).apply()

    fun instructions(context: Context): List<Instruction> = parse(prefs(context).getString(KEY_LIST, null))

    fun saveInstructions(context: Context, list: List<Instruction>) = prefs(context).edit().putString(KEY_LIST, toJson(list)).apply()

    fun upsert(context: Context, item: Instruction, oldName: String? = null) {
        val list = instructions(context).filterNot { it.name.equals(oldName ?: item.name, true) || it.name.equals(item.name, true) }
        saveInstructions(context, list + item)
    }

    fun remove(context: Context, name: String) = saveInstructions(context, instructions(context).filterNot { it.name.equals(name, true) })

    /** Что добавить к системным сообщениям для этой просьбы; null — нечего. */
    fun systemAddition(context: Context, prompt: String): String? = compose(custom(context), instructions(context), prompt)

    // ---------------------------------------------------------------- чистая логика (тесты)

    /** Подходит ли инструкция к просьбе: без триггеров — всегда, иначе есть хотя бы одно слово-триггер (по началу слова). */
    fun matches(item: Instruction, prompt: String): Boolean {
        if (!item.enabled || item.text.isBlank()) return false
        if (item.triggers.isEmpty()) return true
        val t = TextMatch.normalize(prompt)
        val words = t.split(' ')
        return item.triggers.any { tr ->
            val n = TextMatch.normalize(tr)
            n.isNotEmpty() && (if (n.contains(' ')) t.contains(n) else words.any { it.startsWith(n) })
        }
    }

    fun compose(custom: String, list: List<Instruction>, prompt: String): String? {
        val parts = ArrayList<String>()
        if (custom.isNotBlank()) parts += "УКАЗАНИЯ ПОЛЬЗОВАТЕЛЯ (выполняй всегда, если не противоречат безопасности): ${custom.trim()}"
        list.filter { matches(it, prompt) }.take(4).forEach { parts += "РЕЖИМ «${it.name}»: ${it.text.trim().take(1200)}" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun parse(json: String?): List<Instruction> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            if (name.isEmpty()) null
            else Instruction(name, splitTriggers(o.optString("triggers")), o.optString("text"), o.optBoolean("enabled", true))
        }
    } catch (_: Exception) { emptyList() }

    fun toJson(list: List<Instruction>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("name", it.name).put("triggers", it.triggers.joinToString(", ")).put("text", it.text).put("enabled", it.enabled)) }
    }.toString()

    fun splitTriggers(s: String): List<String> = s.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}
