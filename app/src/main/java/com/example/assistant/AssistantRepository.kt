package com.example.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Связывает модель (GroqClient) с реальными действиями (PhoneActions, MusicActions, NoteActions...).
 * Сначала пробует обойтись без модели (правила и выученные фразы), потом идёт в Groq.
 */
class AssistantRepository(private val context: Context, apiKey: String) {

    private companion object {
        // Общие для всех экземпляров: сводку одновременно собирает только один
        val summaryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val summarizing = AtomicBoolean(false)

        /** Действие, которое ждёт ответа «да» или «нет»; живёт 5 минут. */
        @Volatile var pendingState: Pending? = null
        const val CONFIRM_TTL_MS = 5 * 60_000L
    }

    class PendingAction(val name: String, val args: JSONObject)
    class Pending(val actions: List<PendingAction>, val at: Long)

    /** Опасные действия, которые функции запросили подтвердить в ходе этого запроса. */
    private val asks = mutableListOf<PendingAction>()

    private val client = GroqClient(apiKey, context.applicationContext)
    private val summaryBatch = 12
    private val patternDao = NoteDatabase.getInstance(context).commandPatternDao()

    private fun normalize(text: String): String =
        text.trim().lowercase(Locale.getDefault())

    /**
     * В кэш выученных фраз попадают только действия, которые не зависят от контекста разговора
     * и от текущего состояния. Заметки, плейлисты и поиск файлов зависят от них, поэтому их
     * всегда обрабатывает модель, которая видит историю чата.
     */
    private val cacheableFunctions = setOf(
        "set_alarm", "play_music", "music_control", "set_volume", "flashlight", "open_camera",
        "take_screenshot", "open_app", "open_browser", "open_settings", "open_dialer"
    )

    /** Сколько последних сообщений уходит в модель: для команд хватает немногих, для беседы нужно больше. */
    private val commandHistory = 8
    private val chatHistory = 24

    /**
     * Три уровня, чтобы тратить меньше токенов:
     * 1. Правила (LocalCommands) и выученные фразы: модель не участвует, 0 токенов.
     * 2. Команда, которую правила не поняли: короткий запрос к модели (нужные функции, короткая
     *    история), ответ модели выполняется как есть, без второго вызова.
     * 3. Обычная беседа: длинная история и сжатый список функций.
     */
    suspend fun handle(prompt: String): String {
        // Ответ на вопрос «Удалить? Скажи да или нет»
        val waiting = pendingState
        if (waiting != null) {
            pendingState = null
            if (System.currentTimeMillis() - waiting.at < CONFIRM_TTL_MS) {
                if (Confirm.isYes(prompt)) {
                    val done = ArrayList<String>()
                    for (action in waiting.actions) done.add(execute(action.name, action.args, confirmed = true))
                    return done.joinToString("\n")
                }
                if (Confirm.isNo(prompt)) return "Хорошо, отменил."
            }
        }
        asks.clear()
        val answer = handleInner(prompt)
        if (asks.isNotEmpty()) {
            pendingState = Pending(asks.toList(), System.currentTimeMillis())
            return answer + "\nСкажи «да» или «нет»."
        }
        return answer
    }

    private suspend fun handleInner(prompt: String): String {
        val normalized = normalize(prompt)

        // Уровень 1: правила
        LocalCommands.parse(prompt)?.let { command ->
            runLocal(command)?.let { return it }
        }
        // «Включи Imagine Dragons»: если такое есть в музыке телефона, модель не нужна
        LocalCommands.musicCandidate(prompt)?.let { query ->
            val known = withContext(Dispatchers.IO) { MusicLibrary.index(context).search(query, 1).isNotEmpty() }
            if (known) return execute("play_music", JSONObject().put("query", query).put("shuffle", false))
        }
        // Уровень 1: фразы, которым ассистент уже научился у модели
        val cached = patternDao.find(normalized)?.takeIf { it.functionName in cacheableFunctions }
        if (cached != null) {
            val args = try { JSONObject(cached.argumentsJson) } catch (e: Exception) { JSONObject() }
            val result = execute(cached.functionName, args)
            patternDao.upsert(cached.copy(useCount = cached.useCount + 1, lastUsed = System.currentTimeMillis()))
            return result
        }

        return try {
            val answer = try {
                askModel(prompt, normalized)
            } catch (e: GroqApiException) {
                // Короткий лимит «в минуту»: подождём и повторим один раз сами
                val wait = if (e.code == 429) MusicTagger.retryDelayMs(e.message.orEmpty()) else null
                if (wait == null || wait > 20_000L) throw e
                kotlinx.coroutines.delay(wait + 500)
                askModel(prompt, normalized)
            }
            answer.also { summarizeInBackground() }
        } catch (e: Exception) {
            FriendlyError.text(e)
        }
    }

    // ---------------------------------------------------------------- сводка старых сообщений

    /**
     * Когда за пределами окна истории накопилось достаточно старых сообщений, сжимает их в короткую сводку.
     * Модель дальше получает сводку и последние сообщения, а не весь чат: помнит давнее и тратит меньше токенов.
     */
    private fun summarizeInBackground() {
        val chatId = ChatHistory.currentId(context)
        val all = ChatHistory.load(context, chatId)
        val old = all.dropLast(chatHistory)
        val previous = ChatHistory.summary(context, chatId)
        val fresh = old.filter { it.timestamp > (previous?.upTo ?: 0L) }
        if (fresh.size < summaryBatch || !summarizing.compareAndSet(false, true)) return
        summaryScope.launch {
            try {
                val transcript = fresh.joinToString("\n") {
                    (if (it.role == "user") "Пользователь: " else "Ассистент: ") + it.text.take(400)
                }
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content",
                        "Сожми переписку в краткую сводку до 150 слов по-русски: что известно о пользователе, о чём договорились, " +
                            "важные числа и имена, незаконченные дела. Без вступлений." +
                            (previous?.let { " Прежняя сводка (дополни её): ${it.text}" } ?: "")))
                    .put(JSONObject().put("role", "user").put("content", transcript))
                val result = client.sendConversation(messages, null, effort = "low", maxTokens = 500)
                if (result is GroqResult.FinalText && result.text.isNotBlank()) {
                    ChatHistory.setSummary(context, chatId, ChatHistory.Summary(result.text.trim(), fresh.last().timestamp))
                }
            } catch (e: Exception) {
                // не получилось — попробуем в следующий раз
            } finally {
                summarizing.set(false)
            }
        }
    }

    private suspend fun runLocal(command: LocalCommand): String? {
        if (command.name == "say") return command.args["text"] as? String
        val args = JSONObject()
        for ((key, value) in command.args) args.put(key, if (value is Collection<*>) JSONArray(value.toList()) else value)
        val result = execute(command.name, args)
        // Приложение не нашлось по названию: пусть разберётся модель (она знает, как оно называется на значке)
        if (command.name == "open_app" && result.startsWith("Не нашёл")) return null
        return result
    }

    /** «Мой город — Астана», «меня зовут Анна»: запоминаем на телефоне, чтобы ассистент знал пользователя. */
    private suspend fun setProfile(field: String, value: String): String {
        val v = value.trim().trimEnd('.', '!')
        if (v.isEmpty()) return "Не расслышал, что запомнить."
        return when (field.trim().lowercase()) {
            "name", "имя" -> { UserProfile.setName(context, v); "Хорошо, буду звать тебя $v." }
            "city", "город" -> {
                val city = withContext(Dispatchers.IO) { Weather.geocode(v) }
                if (city == null) "Не нашёл город «$v». Попробуй назвать по-другому."
                else { UserProfile.setCity(context, city); "Запомнил: твой город — ${city.name}. Погоду и местные данные буду брать для него." }
            }
            else -> "Могу запомнить имя или город."
        }
    }

    /**
     * Поиск в интернете. Погоду берём из прогноза без ИИ; ответ поиска проверяем на бессмыслицу
     * (температура вроде +65°) и один раз перепроверяем.
     */
    private suspend fun webSearchChecked(query: String): String {
        if (Weather.isWeatherQuery(query)) Weather.report(context, query)?.let { return it }
        var answer = client.webSearch(query)
        if (Sanity.implausibleTemperature(answer)) {
            answer = client.webSearch("$query (в градусах Цельсия; прошлый ответ был нереальным, проверь данные)")
            if (Sanity.implausibleTemperature(answer)) answer += Sanity.WARNING
        }
        return answer
    }

    /** Уровни 2 и 3: запрос к модели. */
    private suspend fun askModel(prompt: String, normalizedPhrase: String): String {
        val history = ChatHistory.load(context)
        val recent = history.takeLast(4).map { it.text }
        val groups = Tools.groupsFor(listOf(prompt) + recent)
        val commandMode = groups.isNotEmpty()

        val window = if (commandMode) commandHistory else chatHistory
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", client.systemPrompt())
            })
            ChatHistory.summary(context)?.let { sum ->
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Краткое содержание более ранней части разговора: ${sum.text}")
                })
            }
            for (m in history.takeLast(window)) {
                put(JSONObject().apply {
                    put("role", if (m.role == "assistant") "assistant" else "user")
                    put("content", m.text)
                })
            }
        }
        val tools = if (commandMode) Tools.subset(groups) else Tools.compact

        return when (val result = client.sendConversation(
            messages, tools,
            effort = if (commandMode) "low" else "medium",
            maxTokens = if (commandMode) 1024 else 2048
        )) {
            is GroqResult.FinalText ->
                if (Sanity.implausibleTemperature(result.text)) result.text + Sanity.WARNING else result.text
            is GroqResult.FunctionCalls -> {
                val outputs = result.calls.map { execute(it.name, it.arguments) }

                // Запоминаем фразу только если это было одно простое действие
                val only = result.calls.singleOrNull()
                if (only != null && only.name in cacheableFunctions) {
                    patternDao.upsert(
                        CommandPatternEntity(
                            phrase = normalizedPhrase,
                            functionName = only.name,
                            argumentsJson = only.arguments.toString()
                        )
                    )
                }
                // Содержимое файлов и баз модель должна осмыслить (найти, посчитать, пересказать): второй короткий вызов
                val needsAnalysis = result.calls.any {
                    it.name == "shared_folder" && it.arguments.optString("action") in setOf("read", "sql")
                }
                if (needsAnalysis) {
                    val followUp = JSONArray()
                    for (i in 0 until messages.length()) followUp.put(messages.get(i))
                    followUp.put(result.assistantMessage)
                    result.calls.forEachIndexed { i, call ->
                        followUp.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", call.id)
                            put("content", outputs[i])
                        })
                    }
                    val second = client.sendConversation(followUp, null, effort = "low", maxTokens = 1024)
                    if (second is GroqResult.FinalText && second.text.isNotBlank()) return second.text
                }
                // Результат функций уже написан по-человечески: показываем его как есть
                outputs.joinToString("\n")
            }
        }
    }

    // ---------------------------------------------------------------- выполнение функций

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null

    /** Список строк из аргумента: массив или одна строка. */
    private fun JSONObject.optStringList(key: String): List<String> {
        val array = optJSONArray(key)
        if (array != null) {
            return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
        }
        return optStringOrNull(key)?.let { listOf(it) } ?: emptyList()
    }

    private fun sharedFolder(a: JSONObject, confirmed: Boolean): String = when (a.optString("action", "list")) {
        "read" -> SharedFolder.read(context, a.optString("name", ""), a.optStringOrNull("query"))
        "sql" -> SharedFolder.sql(a.optString("name", ""), a.optString("sql", ""))
        "write" -> SharedFolder.write(a.optString("name", ""), a.optString("content", ""), a.optBoolean("append", false), confirmed)
        "merge" -> SharedFolder.merge(a.optStringList("names"), a.optStringOrNull("output"), a.optBoolean("dedupe", false), confirmed)
        "convert" -> SharedFolder.convert(a.optString("name", ""), a.optStringOrNull("output"), confirmed)
        else -> SharedFolder.list(context, a.optStringOrNull("folder"))
    }

    /** Переключатели из шторки: автоповорот меняем напрямую, остальные нажимаем через службу специальных возможностей. */
    private suspend fun quickSetting(setting: String, state: String): String {
        val want: Boolean? = when (state.lowercase()) { "on" -> true; "off" -> false; else -> null }
        if (TextMatch.normalize(setting).contains("фонар")) return PhoneActions.setFlashlight(context, want ?: true)
        if (QuickSettings.resolve(setting) == "rotation") return withContext(Dispatchers.IO) { SystemActions.rotation(context, want) }
        val service = AssistantAccessibilityService.instance
        if (service == null || !service.hasWindowAccess()) {
            PhoneActions.launch(context, android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return "Чтобы переключать Wi-Fi, Bluetooth и другое из шторки, включи службу «Assistant» в специальных возможностях " +
                "(если уже включена, выключи и включи заново: после обновления ей нужны новые права). " +
                "Если пункт серый: Настройки → Приложения → Assistant → меню ⋮ → «Разрешить ограниченные настройки»."
        }
        return service.quickSetting(setting, want)
    }

    /** Распознаёт текст на картинке из общей папки моделью с «зрением». */
    private suspend fun recognizeImage(name: String?, task: String?, saveAs: String?, confirmed: Boolean): String {
        if (!saveAs.isNullOrBlank() && !confirmed && withContext(Dispatchers.IO) { SharedFolder.outputExists(saveAs) }) {
            return Confirm.ask("Файл «$saveAs» уже есть в Исходящих. Перезаписать его результатом распознавания?")
        }
        val file = withContext(Dispatchers.IO) { SharedFolder.imageFile(name) }
            ?: return "Не нашёл картинку. Положи её во Входящие или прикрепи в чате кнопкой 📎."
        val encoded = withContext(Dispatchers.IO) { ImageUtil.jpegBase64(file) }
            ?: return "Не смог открыть «${file.name}»."
        val prompt = (task?.takeIf { it.isNotBlank() } ?: "Перепиши весь текст с изображения точно, как он написан.") +
            " Отвечай только результатом, без пояснений."
        val text = client.vision(encoded, prompt)
        if (saveAs.isNullOrBlank()) return text
        val saved = withContext(Dispatchers.IO) { SharedFolder.saveRecognized(saveAs, text, confirmed) }
        return "$saved\n\n" + text.take(600)
    }

    /** Выполняет одну функцию. Никогда не бросает исключений: ошибка возвращается текстом. */
    private suspend fun execute(name: String, a: JSONObject, confirmed: Boolean = false): String {
        val result = executeRaw(name, a, confirmed)
        if (Confirm.isAsk(result)) {
            asks.add(PendingAction(name, a))
            return Confirm.question(result)
        }
        return result
    }

    private suspend fun executeRaw(name: String, a: JSONObject, confirmed: Boolean): String = try {
        when (name) {
            "set_alarm" -> PhoneActions.setAlarm(
                context, a.optInt("hour", 0), a.optInt("minute", 0), a.optStringOrNull("label")
            )
            "flashlight" -> PhoneActions.setFlashlight(context, a.optBoolean("on", true))
            "open_camera" -> PhoneActions.openCamera(context, a.optStringOrNull("mode"))
            "take_screenshot" -> PhoneActions.takeScreenshot(context)
            "open_app" -> PhoneActions.openApp(context, a.optString("name", ""))
            "open_browser" -> PhoneActions.openBrowser(
                context, a.optStringOrNull("url"), a.optStringOrNull("search_query")
            )
            "open_settings" -> PhoneActions.openSettings(context, a.optStringOrNull("section"))
            "open_dialer" -> PhoneActions.openDialer(context, a.optStringOrNull("number"))
            "open_whatsapp" -> PhoneActions.openWhatsApp(
                context, a.optStringOrNull("phone"), a.optStringOrNull("message")
            )

            "play_music" -> withContext(Dispatchers.IO) {
                MusicActions.playMusic(
                    context, a.optString("query", ""), a.optBoolean("shuffle", false),
                    a.optStringOrNull("mood"), a.optStringOrNull("genre"), a.optBoolean("liked", false)
                )
            }
            "music_control" -> MusicActions.control(context, a.optString("action", ""))
            "set_volume" -> MusicActions.setVolume(
                context, a.optString("action", ""), a.optIntOrNull("percent"), a.optStringOrNull("stream")
            )
            "music_rate" -> withContext(Dispatchers.IO) {
                MusicActions.rate(
                    context, a.optString("action", ""), a.optStringOrNull("query"), a.optStringList("moods"),
                    a.optStringOrNull("genre"), a.optIntOrNull("energy"), a.optStringOrNull("tempo")
                )
            }
            "music_tags" -> withContext(Dispatchers.IO) { MusicActions.tags(context, a.optString("action", ""), a.optStringOrNull("file")) }
            "music_library" -> withContext(Dispatchers.IO) {
                MusicActions.library(context, a.optStringOrNull("query"))
            }
            "playlist" -> withContext(Dispatchers.IO) {
                MusicActions.playlist(
                    context, a.optString("action", ""), a.optStringOrNull("name"),
                    a.optStringList("queries"), a.optBoolean("shuffle", false), confirmed,
                    a.optBoolean("current", false), a.optStringOrNull("target")
                )
            }

            "find_media" -> withContext(Dispatchers.IO) {
                MediaActions.findMedia(
                    context, a.optString("kind", "photo"), a.optStringOrNull("query"),
                    a.optInt("limit", 5), a.optBoolean("open", false)
                )
            }
            "find_files" -> withContext(Dispatchers.IO) {
                MediaActions.findFiles(
                    context, a.optStringOrNull("query"), a.optStringOrNull("folder"),
                    a.optInt("limit", 10), a.optBoolean("open", false)
                )
            }

            "shared_folder" -> when (a.optString("action", "list")) {
                "ocr" -> recognizeImage(a.optStringOrNull("name"), a.optStringOrNull("task"), a.optStringOrNull("save_as"), confirmed)
                else -> withContext(Dispatchers.IO) { sharedFolder(a, confirmed) }
            }

            "call_contact" -> withContext(Dispatchers.IO) { ContactActions.call(context, a.optString("name", ""), confirmed) }
            "message_contact" -> withContext(Dispatchers.IO) {
                ContactActions.message(context, a.optString("name", ""), a.optStringOrNull("text"), a.optStringOrNull("app"))
            }
            "find_contact" -> withContext(Dispatchers.IO) { ContactActions.info(context, a.optString("name", "")) }

            "web_search" -> webSearchChecked(a.optString("query", ""))
            "set_profile" -> setProfile(a.optString("field", ""), a.optString("value", ""))
            "schedule_digest" -> DigestActions.add(context, a.optString("topic", ""), a.optStringOrNull("time"))
            "list_digests" -> DigestActions.list(context)
            "cancel_digest" -> DigestActions.cancel(context, a.optStringOrNull("query"), confirmed)
            "set_brightness" -> withContext(Dispatchers.IO) {
                SystemActions.brightness(context, a.optIntOrNull("percent"), a.optStringOrNull("change"))
            }
            "quick_setting" -> quickSetting(a.optString("setting", ""), a.optString("state", "toggle"))

            "set_timer" -> ReminderActions.setTimer(context, a.optInt("seconds", 0), a.optStringOrNull("label"))
            "set_reminder" -> ReminderActions.setReminder(
                context, a.optString("text", ""), a.optIntOrNull("in_minutes"), a.optStringOrNull("date"), a.optStringOrNull("time")
            )
            "list_reminders" -> ReminderActions.list(context)
            "cancel_reminder" -> ReminderActions.cancel(context, a.optStringOrNull("query"), confirmed)
            "calendar_add" -> withContext(Dispatchers.IO) {
                CalendarActions.add(
                    context, a.optString("title", ""), a.optStringOrNull("date"), a.optStringOrNull("time"),
                    a.optIntOrNull("duration_minutes"), a.optStringOrNull("description")
                )
            }
            "calendar_list" -> withContext(Dispatchers.IO) {
                CalendarActions.list(context, a.optStringOrNull("date"), a.optIntOrNull("days"))
            }

            "create_note" -> NoteActions.createNote(context, a.optString("text", ""))
            "list_notes" -> NoteActions.listNotes(context)
            "search_notes" -> NoteActions.searchNotes(context, a.optString("query", ""))
            "delete_note" -> NoteActions.deleteNote(context, a.optString("query", ""), confirmed)
            else -> "Неизвестная функция: $name"
        }
    } catch (e: Exception) {
        "Не получилось выполнить «$name»: ${e.message}"
    }
}
