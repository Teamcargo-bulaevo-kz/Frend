package com.example.assistant

import org.json.JSONObject

/** План сложной просьбы (как список задач у Claude): шаги, сколько выполнено, картинка с галочками. Чистый Kotlin. */
object AgentPlan {

    const val NOTE =
        "СЛОЖНАЯ ПРОСЬБА. Если нужно несколько действий подряд или одно зависит от результата другого (найти, потом отправить; " +
            "узнать, потом решить) — сначала вызови update_plan со всеми шагами, затем вызывай функции по шагам. Результат каждой функции " +
            "придёт тебе: продолжай, пока план не выполнен, обновляя update_plan (done — сколько шагов сделано). " +
            "В конце — короткий итог, что сделано. Не выдумывай данные, которых не вернули функции."

    private val joiners = listOf(" и ", " потом ", " затем ", " после этого ", " а потом ", " а затем ", " сначала ", " после чего ", " заодно ", " ещё ", " еще ")

    /** Похоже на просьбу из нескольких шагов. */
    fun multiStep(prompt: String): Boolean {
        val t = " " + TextMatch.normalize(prompt) + " "
        if (joiners.any { t.contains(it) }) return true
        // Несколько глаголов-команд подряд через запятую: «найди номер, отправь маме»
        val verbs = t.split(' ').count { it.length > 3 && (it.endsWith("и") || it.endsWith("ай") || it.endsWith("ей")) && it in commandVerbs }
        return verbs >= 2
    }

    private val commandVerbs = setOf(
        "найди", "отправь", "напиши", "позвони", "включи", "выключи", "поставь", "открой", "запиши", "напомни", "сохрани",
        "посчитай", "узнай", "проверь", "добавь", "удали", "переведи", "сделай", "покажи", "разложи", "прочитай", "создай"
    )

    fun steps(args: JSONObject): List<String> {
        val a = args.optJSONArray("steps") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            when (val e = a.opt(i)) {
                is String -> e
                is JSONObject -> e.optString("text", e.optString("step", ""))
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
        }.take(10)
    }

    /** План картинкой: сделанные — с галочкой, текущий — «сейчас». */
    fun visual(plan: List<String>, done: Int): String =
        VisualSpec.block(VisualSpec.listJson("План", "check", plan.mapIndexed { i, s ->
            Visual.Item(s, if (i == done && done < plan.size) "сейчас" else "", done = i < done)
        }))
}
