package com.example.assistant

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Голосовое управление экраном и приложениями через службу специальных возможностей и Shizuku. */
object ScreenActions {

    private fun service(context: Context): AssistantAccessibilityService? {
        val s = AssistantAccessibilityService.instance
        if (s == null) PhoneActions.launch(context, Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return s
    }

    private const val NEED =
        "Нужна служба специальных возможностей «Assistant»: открыл настройки — включи её (или «Выдать все разрешения через Shizuku»)."

    /** action: back, home, recents, notifications, quick_settings, lock, power, screenshot, split, click, scroll, type. */
    suspend fun control(context: Context, action: String, text: String?, direction: String?): String {
        val s = service(context) ?: return NEED
        return withContext(Dispatchers.Main) {
            when (action) {
                "click" -> {
                    if (!Privacy.screenAllowed(context)) "Чтобы нажимать по названию, включи «Чтение экрана» (меню «Что ассистент может видеть») или «Живой экран»."
                    else s.clickText(text.orEmpty())
                }
                "scroll" -> s.scroll(direction ?: "down")
                "type" -> if (text.isNullOrBlank()) "Скажи, что написать." else s.typeText(text)
                "screenshot" -> { s.screenshotAfterHome(); "Делаю скриншот" }
                else -> if (s.globalAction(action)) when (action) {
                    "back" -> "Назад"; "home" -> "На главный экран"; "recents" -> "Недавние приложения"
                    "notifications" -> "Открыл уведомления"; "quick_settings" -> "Открыл шторку"; "lock" -> "Заблокировал экран"
                    "power" -> "Меню питания"; "split" -> "Разделил экран"; else -> "Готово"
                } else "Это действие на этом телефоне недоступно."
            }
        }
    }

    /** Закрывает приложение (как «Остановить» в настройках). Ничего не удаляет. Через Shizuku, с подтверждением. */
    fun closeApp(context: Context, name: String, confirmed: Boolean): String {
        if (!Shell.available(context)) return "Закрывать приложения могу через Shizuku: включи его в настройках ассистента."
        val (label, pkg) = findApp(context, name) ?: return "Не нашёл приложение «$name»."
        if (pkg == context.packageName) return "Себя закрыть не могу."
        if (!confirmed) return Confirm.ask("Закрыть «$label»? Несохранённое в нём может потеряться.")
        val r = Shell.control(context, "am force-stop $pkg")
        return if (r.ok) "Закрыл «$label»" else "Не получилось закрыть «$label»: ${r.output.take(80)}"
    }

    /** Установленное приложение по названию на значке: (название, пакет). */
    fun findApp(context: Context, rawName: String): Pair<String, String>? {
        val name = TextMatch.normalize(rawName)
        if (name.isEmpty()) return null
        val key = TextMatch.key(name)
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var best: Pair<String, String>? = null
        var bestScore = 0
        for (info in pm.queryIntentActivities(launcher, 0)) {
            val label = info.loadLabel(pm).toString()
            val l = TextMatch.normalize(label)
            val lk = TextMatch.key(label)
            val score = when {
                l == name || (key.length >= 3 && lk == key) -> 4
                l.startsWith(name) || (key.length >= 4 && lk.startsWith(key)) -> 3
                l.contains(name) || (key.length >= 4 && lk.contains(key)) -> 2
                key.length >= 4 && TextMatch.similarity(lk, key) >= 0.75 -> 1
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = label to info.activityInfo.packageName }
        }
        return best
    }
}
