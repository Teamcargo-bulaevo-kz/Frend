package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Служба специальных возможностей. Нужна для двух вещей, которые Android не даёт обычным приложениям:
 * сделать скриншот и нажать плитку в шторке (Wi-Fi, Bluetooth, режим полёта...). Экран она читает только по просьбе
 * пользователя и только если он включил «Чтение экрана» в настройках ассистента (readScreen). Пароли не читаются.
 * Включается один раз вручную: Настройки → Специальные возможности → Assistant.
 */
class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AssistantAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    /** Сворачивает ассистента на рабочий стол и через секунду делает скриншот. */
    fun screenshotAfterHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) }, 1200)
    }

    // ---------------------------------------------------------------- плитки шторки

    /**
     * Открывает шторку, находит плитку по названию и нажимает её. want: true включить, false выключить,
     * null просто нажать. Возвращает текст-отчёт для пользователя.
     */
    suspend fun quickSetting(name: String, want: Boolean?): String {
        val labels = QuickSettings.labelsFor(name)
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        var tile: AccessibilityNodeInfo? = null
        for (attempt in 0 until 6) {
            delay(if (attempt == 0) 900 else 500)
            tile = findTile(labels)
            if (tile != null) break
        }
        if (tile == null) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return "Не нашёл в шторке плитку «$name». На этом телефоне она может называться иначе или быть на втором экране шторки."
        }

        val key = QuickSettings.resolve(name)
        // Настоящее состояние из системы надёжнее подписи на плитке
        val before = SystemState.isOn(this, key) ?: tileState(tile)
        if (want != null && before == want) {
            closeShade()
            return "«$name» уже ${if (want) "включено" else "выключено"}"
        }

        // Нажимаем и проверяем результат; если не сработало, нажимаем ещё раз (максимум 2 раза)
        var current: Boolean? = before
        var clicked = false
        for (round in 0 until 2) {
            val node = if (round == 0) tile else findTile(labels)
            if (node == null || !clickNode(node)) {
                if (!clicked) {
                    closeShade()
                    return "Не получилось нажать плитку «$name»."
                }
                break
            }
            clicked = true
            // Ждём, пока система переключится (Wi-Fi и Bluetooth включаются не мгновенно)
            for (wait in 0 until 6) {
                delay(500)
                current = SystemState.isOn(this, key) ?: findTile(labels)?.let { tileState(it) }
                if (want == null || current == want) break
            }
            if (want == null || current == want || current == null) break
        }
        closeShade()
        return when {
            want != null && current == want -> "«$name» теперь ${if (want) "включено" else "выключено"}"
            want != null && current != null -> "Не получилось ${if (want) "включить" else "выключить"} «$name»: телефон не переключился. Попробуй вручную в шторке."
            current != null -> "«$name» теперь ${if (current) "включено" else "выключено"}"
            want != null -> "Нажал «$name» (${if (want) "включить" else "выключить"}). Телефон не показал состояние, проверь на экране."
            else -> "Нажал «$name»"
        }
    }

    /** Служба получила право читать окна (после обновления его надо выдать заново, перевключив службу). */
    fun hasWindowAccess(): Boolean = serviceInfo?.capabilities?.and(
        android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
    ).let { it != null && it != 0 }

    /**
     * Текст открытого окна для вопросов «что на экране». Пароли пропускаются, длина ограничена,
     * чтобы не тратить лимит модели. Возвращает null, если окно не отдаёт содержимое.
     */
    fun readScreen(maxChars: Int = 3000): String? {
        val root = rootInActiveWindow ?: return null
        val lines = ArrayList<String>()
        var total = 0
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || visited > 800 || total >= maxChars) return
            visited++
            if (!n.isVisibleToUser) return
            if (!n.isPassword) {
                val t = nodeText(n).trim()
                if (t.isNotEmpty() && lines.lastOrNull() != t) {
                    lines.add(t)
                    total += t.length + 1
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        if (lines.isEmpty()) return null
        val pkg = root.packageName?.toString().orEmpty()
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
        return ("Открыто: $label\n" + lines.joinToString("\n")).take(maxChars)
    }

    private fun closeShade() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        return false
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        try {
            windows.forEach { w -> w.root?.let { result.add(it) } }
        } catch (e: Exception) {
            // окна недоступны: пробуем активное
        }
        rootInActiveWindow?.let { result.add(it) }
        return result
    }

    private fun nodeText(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.text, n.contentDescription).joinToString(" ")

    /** Ищет плитку по подписи среди всех окон; предпочитает кликабельные элементы с самым коротким совпадением. */
    private fun findTile(labels: List<String>): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE
        for (root in roots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < 600) {
                val node = stack.removeLast()
                visited++
                val text = TextMatch.normalize(nodeText(node))
                if (text.isNotEmpty() && labels.any { text.contains(it) }) {
                    // Чем короче подпись, тем точнее совпадение; кликабельные лучше
                    val score = text.length + if (node.isClickable) 0 else 50
                    if (score < bestScore) { best = node; bestScore = score }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
            }
        }
        return best
    }

    private fun tileState(node: AccessibilityNodeInfo): Boolean? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 4) {
            if (n.isCheckable) return n.isChecked
            val described = if (android.os.Build.VERSION.SDK_INT >= 30) n.stateDescription?.toString() else null
            QuickSettings.stateFrom(listOfNotNull(described, n.contentDescription?.toString(), n.text?.toString()).joinToString(" "))
                ?.let { return it }
            n = n.parent
            depth++
        }
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
