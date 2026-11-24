package com.example.assistant

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Помощь по экрану: «что тут?», «помоги ответить», «что делать с этой ошибкой», перевод экрана.
 * Основной способ — снимок экрана (Android 11+) и модель со зрением: она видит и текст, и картинки, и кнопки.
 * Запасной — текст окна через службу специальных возможностей.
 * «Живой экран»: пока он включён, вопросы о том, что видно, автоматически идут вместе с текущим снимком экрана.
 */
object ScreenAssist {

    private const val PREFS = "assistant_prefs"
    private const val KEY_LIVE = "live_screen"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun live(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIVE, false)

    /** Включает «Живой экран»: вопросы о видимом идут со снимком, у края экрана кнопки «?» и «Aあ». */
    fun setLive(context: Context, on: Boolean): String {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIVE, on).apply()
        if (on) Privacy.setScreenAllowed(context, true)
        val service = AssistantAccessibilityService.instance
        android.os.Handler(android.os.Looper.getMainLooper()).post { ScreenOverlay.setBubble(context, on) }
        if (service == null) return needService()
        return if (on) "Живой экран включён: спрашивай про то, что видно, — я смотрю на экран. У края экрана кнопки «?» (спросить) и «Aあ» (перевести)."
        else "Живой экран выключен"
    }

    private fun needService() =
        "Нужна служба специальных возможностей: Настройки → Специальные возможности → Assistant (или «Выдать все разрешения через Shizuku» в настройках)."

    /** Слова, по которым понятно, что вопрос про то, что сейчас на экране. */
    private val deixis = listOf(
        "экран", "тут", "здесь", "это", "этот", "эта", "эту", "эти", "этом", "этой", "вижу", "видишь", "написано", "открыто",
        "ошибк", "кнопк", "что делать", "что нажать", "куда нажать", "ответь", "ответить", "переведи", "картинк", "фото",
        "таблиц", "сообщени", "комментари", "задач", "реши", "объясни", "помоги"
    )

    /** Вопрос относится к экрану (для «Живого экрана»): есть указательные слова или просьба помочь с видимым. */
    fun aboutScreen(prompt: String): Boolean {
        val t = TextMatch.normalize(prompt)
        return deixis.any { d -> t.split(' ').any { it.startsWith(d) } || (d.contains(' ') && t.contains(d)) }
    }

    private fun client(context: Context) = GroqClient(BuildConfig.GROQ_API_KEY, context.applicationContext)

    /** Ответ на вопрос о том, что на экране. Не бросает исключений. */
    suspend fun ask(context: Context, question: String): String {
        val service = AssistantAccessibilityService.instance ?: return needService()
        if (!Privacy.screenAllowed(context)) {
            return "Смотреть на экран сейчас запрещено. Скажи «включи живой экран» или включи «Чтение экрана» в меню «Что ассистент может видеть»."
        }
        val image = withContext(Dispatchers.Main) { service.screenshotJpeg() }
        val q = question.ifBlank { "Что на экране? Коротко объясни и подскажи, чем могу помочь." }
        return try {
            if (image != null) {
                client(context).vision(image,
                    "Ты голосовой ассистент в телефоне пользователя, перед тобой снимок его экрана. Вопрос: «$q». " +
                        "Ответь по-русски коротко (1–4 предложения), опираясь только на то, что видно. Если просят что-то сделать " +
                        "в приложении, скажи, что нажать. Если просят ответить на сообщение — предложи готовый текст ответа. " +
                        "Не пересказывай весь экран без просьбы. Пароли и номера карт не повторяй.")
            } else {
                val text = withContext(Dispatchers.Main) { service.readScreen(4000) } ?: return "Не получилось увидеть экран: окно не отдаёт содержимое" +
                    if (Build.VERSION.SDK_INT >= 30) ", а снимок не получился (перевключи службу специальных возможностей)." else "."
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "Ты ассистент в телефоне. Тебе дан текст с экрана пользователя. Ответь по-русски коротко на его вопрос, опираясь на этот текст."))
                    .put(JSONObject().put("role", "user").put("content", "Экран:\n$text\n\nВопрос: $q"))
                (client(context).sendConversation(messages, null, effort = "low", maxTokens = 800) as? GroqResult.FinalText)?.text ?: "Модель не ответила"
            }
        } catch (e: Exception) {
            FriendlyError.text(e)
        }
    }

    /**
     * Переводит экран: надписи заменяются переводом прямо на своих местах; если окно не отдаёт текст
     * (картинка, игра, видео) — перевод текста со снимка в карточке. Возвращает короткий отчёт.
     */
    suspend fun translate(context: Context, target: String = "русский"): String {
        val service = AssistantAccessibilityService.instance ?: return needService()
        val nodes = withContext(Dispatchers.Main) { service.textNodes() }
        val pkg = withContext(Dispatchers.Main) { service.currentPackage() }
        val todo = nodes.filter { TranslateBatch.needsTranslation(it.first, target) }
        val client = client(context)
        try {
            if (todo.isNotEmpty()) {
                val translated = ArrayList<Pair<String, android.graphics.Rect>>()
                for (chunk in TranslateBatch.chunks(todo.map { it.first })) {
                    val messages = JSONArray()
                        .put(JSONObject().put("role", "system").put("content", TranslateBatch.prompt(target)))
                        .put(JSONObject().put("role", "user").put("content", TranslateBatch.input(chunk)))
                    val answer = (client.sendConversation(messages, null, effort = "low", maxTokens = 4000) as? GroqResult.FinalText)?.text.orEmpty()
                    val lines = TranslateBatch.parse(answer, chunk.size) ?: continue
                    val offset = translated.size
                    lines.forEachIndexed { i, t -> translated.add(t to todo[offset + i].second) }
                }
                if (translated.isNotEmpty()) {
                    withContext(Dispatchers.Main) { ScreenOverlay.showTranslation(translated, pkg) }
                    return "Перевёл ${translated.size} надписей на $target. Коснись экрана, чтобы убрать перевод."
                }
            }
            if (todo.isEmpty() && nodes.isNotEmpty() && Build.VERSION.SDK_INT < 30) return "На экране уже всё на языке «$target»."
            val image = withContext(Dispatchers.Main) { service.screenshotJpeg(1600) }
                ?: return if (nodes.isNotEmpty()) "На экране уже всё на языке «$target»." else "Не получилось прочитать экран для перевода."
            val text = client.vision(image, "Найди весь текст на снимке экрана и переведи его на $target язык. " +
                "Сохрани порядок и разбивку на строки. Если текст уже на этом языке, напиши: «Переводить нечего». Только перевод, без пояснений.")
            withContext(Dispatchers.Main) { ScreenOverlay.showCard("Перевод · $target", text) }
            return text
        } catch (e: Exception) {
            return FriendlyError.text(e)
        }
    }

    // ---------------------------------------------------------------- кнопки у края экрана

    /** «?»: начинаем разговор голосом; ответы идут со снимком экрана, пока включён «Живой экран». */
    fun askByVoice(context: Context) {
        if (!live(context)) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIVE, true).apply()
        Speaker.stop()
        WakeWordService.startDialog(context)
    }

    /** «Aあ»: переводим экран на русский. */
    fun translateFromBubble(context: Context) {
        ScreenOverlay.showCard("Перевод", "Перевожу…", 8_000L)
        scope.launch {
            val result = translate(context)
            if (!result.startsWith("Перевёл")) ScreenOverlay.showCard("Перевод", result)
            else ScreenOverlay.removeCard()
        }
    }
}
