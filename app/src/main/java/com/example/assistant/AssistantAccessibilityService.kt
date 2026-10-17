package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Служба специальных возможностей. Нужна для двух вещей, которые Android не даёт обычным приложениям:
 * сделать скриншот и нажать плитку в шторке (Wi-Fi, Bluetooth, режим полёта...). Экран она не читает:
 * содержимое смотрится только в шторке и только когда ассистент выполняет такую команду.
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

        val before = tileState(tile)
        if (want != null && before == want) {
            closeShade()
            return "«$name» уже ${if (want) "включено" else "выключено"}"
        }
        if (!clickNode(tile)) {
            closeShade()
            return "Не получилось нажать плитку «$name»."
        }
        delay(700)
        val after = findTile(labels)?.let { tileState(it) }
        closeShade()
        return when {
            after != null -> "«$name» теперь ${if (after) "включено" else "выключено"}"
            want != null -> "Нажал «$name» (${if (want) "включить" else "выключить"}). Проверь состояние: телефон не показал его."
            else -> "Нажал «$name»"
        }
    }

    /** Служба получила право читать окна (после обновления его надо выдать заново, перевключив службу). */
    fun hasWindowAccess(): Boolean = serviceInfo?.capabilities?.and(
        android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
    ).let { it != null && it != 0 }

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
