package com.example.assistant

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Покажи, как включить NFC», «где тут отменить подписку»: ассистент смотрит на экран, обводит нужную кнопку,
 * рисует стрелку, говорит, что нажать, и ждёт. Нажал — следующий шаг. «Сделай сам» — нажимает за пользователя
 * (кроме опасного: удалить, оплатить, отправить — это только сам пользователь). Работает в любом приложении.
 */
object ScreenGuide {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    @Volatile private var waiter: CompletableDeferred<String>? = null
    private const val MAX_STEPS = 14

    val active: Boolean get() = job?.isActive == true

    /** Служба сообщила: пользователь нажал что-то или открылся новый экран. */
    fun onScreenChanged() { changedAt = System.currentTimeMillis(); waiter?.complete("changed") }

    @Volatile private var changedAt = 0L

    fun stop() {
        waiter?.complete("stop")
        job?.cancel()
        job = null
        ScreenOverlay.removeGuide()
    }

    private const val SYSTEM =
        "Ты ведёшь пользователя по интерфейсу Android-телефона к цели, по одному шагу. Тебе дают цель, открытое приложение, " +
            "уже сделанные шаги и пронумерованный список элементов экрана. Верни ТОЛЬКО JSON: " +
            "{\"say\":\"что сделать сейчас, до 12 слов, по-русски\",\"target\":номер элемента или 0," +
            "\"action\":\"tap|scroll_down|scroll_up|back|home|open_app|done|stuck\",\"app\":\"для open_app — название приложения\"}. " +
            "tap — нажать элемент target. Нужного элемента нет — scroll_down или back. Нужное в другом приложении (например в Настройках) — open_app. " +
            "done — цель уже достигнута (say — что получилось). stuck — не знаешь, как дальше (say — совет)."

    /** Запуск. auto — «сделай сам»: нажимать за пользователя. Возвращает первый ответ для чата. */
    fun start(context: Context, goal: String, auto: Boolean): String {
        val service = AssistantAccessibilityService.instance
            ?: return "Нужна служба специальных возможностей «Assistant» (Настройки → Специальные возможности или «Выдать все разрешения через Shizuku»)."
        if (goal.isBlank()) return "Скажи, что нужно сделать: например «покажи, как включить NFC»."
        stop()
        Privacy.setScreenAllowed(context, true)
        val app = context.applicationContext
        job = scope.launch { run(app, service, goal, auto) }
        return if (auto) "Делаю по шагам: «$goal». Опасное (удалить, оплатить, отправить) нажмёшь сам. «Стоп» — на карточке."
        else "Показываю по шагам: «$goal». Нажимай, куда показываю. «Сделай сам» — нажму за тебя."
    }

    private suspend fun run(context: Context, s: AssistantAccessibilityService, goal: String, auto: Boolean) {
        val client = GroqClient(BuildConfig.GROQ_API_KEY, context)
        val done = ArrayList<String>()
        var stuckTimes = 0
        try {
            for (stepNo in 1..MAX_STEPS) {
                delay(if (stepNo == 1) 900 else 700) // экран успевает дорисоваться
                val nodes = s.interactiveNodes()
                val appLabel = s.currentAppLabel()
                val step = ask(client, s, goal, appLabel, done, nodes) ?: run {
                    ScreenOverlay.showGuideCard(stepNo, "Не получилось понять экран. Попробуй ещё раз.", null, auto, final = true); return
                }
                when (step.action) {
                    "done" -> { ScreenOverlay.showGuideCard(stepNo, "Готово: ${step.say}", null, auto, final = true); Speaker.speak(context, step.say); return }
                    "stuck" -> {
                        stuckTimes++
                        ScreenOverlay.showGuideCard(stepNo, step.say, null, auto, final = stuckTimes >= 2)
                        Speaker.speak(context, step.say)
                        if (stuckTimes >= 2) return
                        if (wait(90_000) == "stop") return
                        continue
                    }
                    "open_app" -> {
                        ScreenOverlay.showGuideCard(stepNo, step.say, null, auto, final = false)
                        Speaker.speak(context, step.say)
                        PhoneActions.openApp(context, step.app.ifBlank { "настройки" })
                        done += "открыл приложение ${step.app}"
                        delay(1500)
                        continue
                    }
                }
                val target = nodes.firstOrNull { it.index == step.target }
                val shownAt = System.currentTimeMillis()
                ScreenOverlay.showGuideCard(stepNo, step.say, target?.rect, auto, final = false)
                Speaker.speak(context, step.say)
                val canAuto = auto && (target == null || !GuideStep.risky(target.label))
                val result = if (canAuto) { delay(1600); "do" } else wait(120_000, shownAt)
                when (result) {
                    "stop" -> return
                    "do" -> perform(s, step, target)
                    "timeout" -> { ScreenOverlay.showGuideCard(stepNo, "Жду слишком долго — остановился. Скажи снова, если нужно.", null, auto, final = true); return }
                }
                done += step.say + (target?.let { " («${it.label}»)" } ?: "")
            }
            ScreenOverlay.showGuideCard(MAX_STEPS, "Слишком много шагов — остановился. Возможно, цель уже рядом.", null, auto, final = true)
        } finally {
            waiter = null
        }
    }

    private fun perform(s: AssistantAccessibilityService, step: GuideStep, target: AssistantAccessibilityService.UiNode?) {
        when (step.action) {
            "tap" -> target?.let { s.press(it) }
            "scroll_down" -> s.scroll("down")
            "scroll_up" -> s.scroll("up")
            "back" -> s.globalAction("back")
            "home" -> s.globalAction("home")
        }
    }

    /** Ждёт нажатия пользователя, нового экрана или кнопок карточки. */
    private suspend fun wait(timeoutMs: Long, since: Long = System.currentTimeMillis()): String {
        val d = CompletableDeferred<String>()
        waiter = d
        // Пользователь успел нажать, пока ассистент думал или говорил
        if (changedAt > since) return "changed"
        return withTimeoutOrNull(timeoutMs) { d.await() } ?: "timeout"
    }

    /** Кнопки карточки подсказки. */
    fun onDoIt() { waiter?.complete("do") }
    fun onStop() = stop()

    private suspend fun ask(client: GroqClient, s: AssistantAccessibilityService, goal: String, app: String,
                            done: List<String>, nodes: List<AssistantAccessibilityService.UiNode>): GuideStep? = withContext(Dispatchers.IO) {
        try {
            val user = buildString {
                append("Цель: ").append(goal).append('\n')
                append("Открыто: ").append(app).append('\n')
                if (done.isNotEmpty()) append("Уже сделано: ").append(done.takeLast(6).joinToString("; ")).append('\n')
                append("Элементы экрана:\n")
                nodes.forEach { append(it.index).append(". «").append(it.label).append("» [").append(it.role).append("]\n") }
            }
            if (nodes.isEmpty()) {
                // Окно не отдаёт элементы (игра, видео): смотрим на снимок, подсказываем словами
                val image = withContext(Dispatchers.Main) { s.screenshotJpeg() } ?: return@withContext null
                return@withContext GuideStep.parse(client.vision(image, "$SYSTEM\nЭлементов нет, target всегда 0, опиши словами, где нажать.\n$user"))
            }
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM))
                .put(JSONObject().put("role", "user").put("content", user))
            val text = (client.sendConversation(messages, null, effort = "low", maxTokens = 400) as? GroqResult.FinalText)?.text.orEmpty()
            GuideStep.parse(text)
        } catch (e: Exception) { null }
    }
}
