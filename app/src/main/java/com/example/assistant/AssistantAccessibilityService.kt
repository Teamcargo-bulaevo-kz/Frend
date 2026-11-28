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
        // Кнопка «Живого экрана», если пользователь её включал
        handler.postDelayed({ ScreenOverlay.restoreBubble(this) }, 800)
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
        for (attempt in 0 until 8) {
            delay(if (attempt == 0) 900 else 500)
            tile = findTile(labels)
            if (tile != null) break
            // Плитки нет на первом экране шторки: раскрываем её полностью жестом вниз, потом листаем влево
            if (attempt == 2) swipe(0.5f, 0.15f, 0.5f, 0.75f)
            if (attempt == 5) swipe(0.85f, 0.35f, 0.15f, 0.35f)
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
            // Первый раз — обычным нажатием, второй (если не переключилось) — касанием центра плитки:
            // в пункте управления MIUI/HyperOS нажатие на «кликабельного родителя» иногда ничего не делает
            val pressed = node != null && (if (round == 0) clickNode(node) || tapCenter(node) else tapCenter(node) || clickNode(node))
            if (!pressed) {
                if (!clicked) {
                    closeShade()
                    return "Не получилось нажать плитку «$name»."
                }
                break
            }
            clicked = true
            // Ждём, пока система переключится (Wi-Fi и Bluetooth включаются не мгновенно)
            var tileNow: Boolean? = null
            for (wait in 0 until 8) {
                delay(450)
                val sys = SystemState.isOn(this, key)
                tileNow = findTile(labels)?.let { tileState(it) }
                // Если система ещё не обновилась, но плитка уже показывает нужное — считаем, что получилось
                current = if (want != null && tileNow == want) want else sys ?: tileNow
                if (want == null || current == want) break
            }
            // Повторно нажимаем, только если и система, и сама плитка показывают старое состояние:
            // иначе второе нажатие вернуло бы всё обратно (так и «не выключался» мобильный интернет)
            if (want == null || current == want || current == null) break
            if (tileNow != null && tileNow != before) break
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

    /** Жест по экрану в долях ширины и высоты. */
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val dm = resources.displayMetrics
        val path = android.graphics.Path().apply {
            moveTo(dm.widthPixels * x1, dm.heightPixels * y1); lineTo(dm.widthPixels * x2, dm.heightPixels * y2)
        }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 280)).build()
        return dispatchGesture(g, null, null)
    }

    private fun tapCenter(node: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        return r.width() > 0 && tap(r.exactCenterX(), r.exactCenterY())
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

    // ---------------------------------------------------------------- управление экраном голосом

    /** Системные кнопки: назад, домой, недавние, уведомления, шторка, блокировка, меню питания, скриншот. */
    fun globalAction(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            "lock" -> if (android.os.Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot" -> if (android.os.Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            "split" -> if (android.os.Build.VERSION.SDK_INT >= 24) GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN else return false
            else -> return false
        }
        return performGlobalAction(action)
    }

    /** Нажимает элемент экрана по подписи («нажми Отправить»). Если элемент не нажимается, касается его центра. */
    fun clickText(query: String): String {
        val q = TextMatch.normalize(query)
        if (q.isEmpty()) return "Скажи, что нажать."
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE
        for (root in roots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < 1500) {
                val node = stack.removeLast()
                visited++
                if (node.isVisibleToUser && !node.isPassword) {
                    val text = TextMatch.normalize(nodeText(node))
                    if (text.isNotEmpty()) {
                        val score = when {
                            text == q -> 0
                            text.startsWith(q) -> 10 + text.length - q.length
                            text.contains(q) -> 30 + text.length - q.length
                            TextMatch.similarity(text, q) >= 0.75 -> 60
                            else -> -1
                        }
                        if (score >= 0 && score + (if (node.isClickable) 0 else 5) < bestScore) { best = node; bestScore = score + if (node.isClickable) 0 else 5 }
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
            }
        }
        val node = best ?: return "Не нашёл на экране «$query»."
        if (clickNode(node)) return "Нажал «${nodeText(node).take(40)}»"
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        return if (tap(r.exactCenterX(), r.exactCenterY())) "Нажал «${nodeText(node).take(40)}»" else "Не получилось нажать «$query»."
    }

    /** Касание экрана в точке (нужна возможность жестов в службе). */
    fun tap(x: Float, y: Float): Boolean {
        if (x < 0 || y < 0) return false
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)).build()
        return dispatchGesture(gesture, null, null)
    }

    /** Листает экран: up/down/left/right. Сначала прокручиваемым списком, потом жестом. */
    fun scroll(direction: String): String {
        val forward = direction == "down" || direction == "right"
        var target: AccessibilityNodeInfo? = null
        var area = 0
        rootInActiveWindow?.let { root ->
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < 1500) {
                val n = stack.removeLast()
                visited++
                if (n.isScrollable && n.isVisibleToUser) {
                    val r = android.graphics.Rect(); n.getBoundsInScreen(r)
                    val a = r.width() * r.height()
                    if (a > area) { area = a; target = n }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (target?.performAction(action) == true) return if (forward) "Пролистал дальше" else "Пролистал назад"
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val path = android.graphics.Path()
        when (direction) {
            "up" -> { path.moveTo(w / 2, h * 0.3f); path.lineTo(w / 2, h * 0.75f) }
            "left" -> { path.moveTo(w * 0.2f, h / 2); path.lineTo(w * 0.8f, h / 2) }
            "right" -> { path.moveTo(w * 0.8f, h / 2); path.lineTo(w * 0.2f, h / 2) }
            else -> { path.moveTo(w / 2, h * 0.75f); path.lineTo(w / 2, h * 0.3f) }
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300)).build()
        return if (dispatchGesture(gesture, null, null)) "Пролистал" else "Не получилось пролистать"
    }

    /** Вводит текст в поле, где стоит курсор (или в первое поле ввода на экране). */
    fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "Не вижу окно."
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: run {
            var found: AccessibilityNodeInfo? = null
            val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
            while (stack.isNotEmpty() && found == null) {
                val n = stack.removeLast()
                if (n.isEditable && n.isVisibleToUser) found = n
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
            found
        } ?: return "На экране нет поля ввода."
        if (field.isPassword) return "В поле пароля текст не ввожу."
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Ввёл текст" else "Поле не приняло текст."
    }

    /** Видимые подписи с координатами — для переводчика поверх экрана. Пароли и пустое пропускаются. */
    fun textNodes(maxNodes: Int = 120): List<Pair<String, android.graphics.Rect>> {
        val root = rootInActiveWindow ?: return emptyList()
        if (root.packageName == packageName) return emptyList()
        val result = ArrayList<Pair<String, android.graphics.Rect>>()
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 2000 && result.size < maxNodes) {
            val n = stack.removeLast()
            visited++
            if (!n.isVisibleToUser) continue
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && !n.isPassword && t.any { it.isLetter() }) {
                val r = android.graphics.Rect(); n.getBoundsInScreen(r)
                if (r.width() > 8 && r.height() > 8) result.add(t.take(500) to r)
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
        }
        return result
    }

    /** Пакет открытого приложения (для «Живого экрана» и переводчика). */
    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    /** Снимок экрана в JPEG base64 (Android 11+). null — нельзя: старый Android или служба включена до обновления. */
    suspend fun screenshotJpeg(maxSide: Int = 1280): String? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        // Наши подсказки поверх экрана не должны попасть на снимок
        val hidden = ScreenOverlay.hideTemporarily()
        if (hidden) delay(120)
        try {
            return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                try {
                    takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            val encoded = try {
                                val hw = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                val bmp = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                result.hardwareBuffer.close()
                                bmp?.let { ImageUtil.bitmapJpegBase64(it, maxSide) }
                            } catch (e: Exception) { null }
                            if (cont.isActive) cont.resumeWith(Result.success(encoded))
                        }
                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resumeWith(Result.success(null))
                        }
                    })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            }
        } finally {
            if (hidden) ScreenOverlay.restore()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg == packageName) return
        // Перевод поверх экрана убираем, когда пользователь ушёл на другое окно
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) ScreenOverlay.onWindowChanged(pkg)
        // Подсказки «покажи как»: пользователь нажал или открылся новый экран — пора следующий шаг
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) ScreenGuide.onScreenChanged()
    }

    /** Элемент экрана для подсказок: номер, подпись, роль, место. */
    class UiNode(val index: Int, val label: String, val role: String, val rect: android.graphics.Rect, val node: AccessibilityNodeInfo)

    /** Видимые элементы, с которыми можно что-то сделать (кнопки, переключатели, поля, пункты списка). */
    fun interactiveNodes(max: Int = 70): List<UiNode> {
        val result = ArrayList<UiNode>()
        val seen = HashSet<String>()
        for (root in roots()) {
            if (root.packageName == packageName) continue
            val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < 2500 && result.size < max) {
                val n = stack.removeLast()
                visited++
                if (!n.isVisibleToUser) continue
                val actionable = n.isClickable || n.isCheckable || n.isEditable || n.isLongClickable
                var label = nodeText(n).trim()
                if (label.isEmpty() && actionable) {
                    // Подпись кнопки часто у ребёнка
                    for (i in 0 until n.childCount) { val ch = n.getChild(i) ?: continue; val t = nodeText(ch).trim(); if (t.isNotEmpty()) { label = t; break } }
                }
                if (label.isEmpty() && actionable) label = n.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ').orEmpty()
                if (actionable && label.isNotEmpty() && !n.isPassword) {
                    val r = android.graphics.Rect(); n.getBoundsInScreen(r)
                    val key = "$label|${r.centerX() / 20}|${r.centerY() / 20}"
                    if (r.width() > 4 && r.height() > 4 && seen.add(key)) {
                        val role = when {
                            n.isEditable -> "поле ввода"
                            n.isCheckable -> "переключатель, " + if (n.isChecked) "вкл" else "выкл"
                            n.className?.contains("Tab") == true -> "вкладка"
                            else -> "кнопка"
                        }
                        result += UiNode(result.size + 1, label.take(60), role, r, n)
                    }
                }
                for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
            }
        }
        // Сверху вниз, слева направо — так модели проще понимать экран
        return result.sortedWith(compareBy({ it.rect.top / 40 }, { it.rect.left })).mapIndexed { i, u -> UiNode(i + 1, u.label, u.role, u.rect, u.node) }
    }

    /** Нажимает элемент (для «сделай сам»). */
    fun press(u: UiNode): Boolean = clickNode(u.node) || tap(u.rect.exactCenterX(), u.rect.exactCenterY())

    /** Название открытого приложения. */
    fun currentAppLabel(): String {
        val pkg = currentPackage()
        return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ScreenOverlay.removeAll()
        instance = null
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
