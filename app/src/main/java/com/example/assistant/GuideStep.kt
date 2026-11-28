package com.example.assistant

import org.json.JSONObject

/** Один шаг подсказки «покажи как»: что сказать, какой элемент, какое действие. Чистый Kotlin, есть тесты. */
data class GuideStep(val say: String, val target: Int, val action: String, val app: String = "") {
    companion object {
        val actions = setOf("tap", "scroll_down", "scroll_up", "back", "home", "open_app", "type", "done", "stuck")

        /** Ответ модели → шаг. Мусор вокруг JSON и неизвестные действия терпимы. */
        fun parse(text: String): GuideStep? {
            val start = text.indexOf('{'); val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try {
                val o = JSONObject(text.substring(start, end + 1))
                val target = o.optInt("target", 0).coerceAtLeast(0)
                var action = o.optString("action", if (target > 0) "tap" else "stuck").lowercase().trim()
                if (action !in actions) action = if (target > 0) "tap" else "stuck"
                if (action == "tap" && target == 0) action = "stuck"
                GuideStep(o.optString("say").trim().take(160).ifBlank { "Смотри на экран" }, target, action, o.optString("app").take(60))
            } catch (e: Exception) { null }
        }

        /** Опасное нажатие («удалить», «оплатить», «отправить») «сделай сам» не выполняет — только пользователь. */
        fun risky(label: String): Boolean {
            val t = TextMatch.normalize(label)
            return listOf("удал", "оплат", "купить", "куплю", "отправ", "подтверд", "перевест", "перевод", "сброс", "очист", "стереть", "выйти из аккаунта", "pay", "delete", "send", "buy", "confirm", "reset")
                .any { t.contains(it) }
        }
    }
}
