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

    companion object {
        // Общие для всех экземпляров: сводку одновременно собирает только один
        val summaryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val summarizing = AtomicBoolean(false)

        /** Действие, которое ждёт ответа «да» или «нет»; живёт 5 минут. */
        @Volatile var pendingState: Pending? = null

        /** Ждёт ли ассистент ответа «да/нет» (для голосового режима: слушать без слова активации). */
        fun hasPendingConfirmation(): Boolean =
            pendingState?.let { System.currentTimeMillis() - it.at < CONFIRM_TTL_MS } ?: false
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
    /**
     * @param fromVoice запрос пришёл голосом: модель предупреждаем, что распознавание могло исказить слова.
     */
    /** Текущий запрос пользователя: нужен функциям, которые отвечают на него сами (вопрос про экран). */
    private var currentPrompt = ""

    suspend fun handle(prompt: String, fromVoice: Boolean = false): String {
        currentPrompt = prompt
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
        val answer = handleInner(prompt, fromVoice)
        if (asks.isNotEmpty()) {
            pendingState = Pending(asks.toList(), System.currentTimeMillis())
            return answer + "\nСкажи «да» или «нет»."
        }
        return answer
    }

    private suspend fun handleInner(prompt: String, fromVoice: Boolean): String {
        val normalized = normalize(prompt)

        // Уровень 1: правила
        LocalCommands.parse(prompt)?.let { command ->
            runLocal(command)?.let { return it }
        }
        // «Живой экран»: вопрос о том, что видно, — сразу со снимком экрана модели со зрением
        if (ScreenAssist.live(context) && ScreenAssist.aboutScreen(prompt)) {
            val answer = ScreenAssist.ask(context, prompt)
            withContext(Dispatchers.Main) { ScreenOverlay.showCard("Про экран", VisualSpec.plain(answer)) }
            return answer
        }
        // «Включи Imagine Dragons»: если такое есть в музыке телефона, модель не нужна
        LocalCommands.musicCandidate(prompt)?.let { query ->
            val known = withContext(Dispatchers.IO) { MusicLibrary.index(context).search(query, 1).isNotEmpty() }
            if (known) return execute("play_music", JSONObject().put("query", query).put("shuffle", false))
        }
        // Уровень 1: фразы, шаблоны и связки, которым ассистент уже научился (у модели или у пользователя)
        runLearned(normalized)?.let { return it }
        // Навыки: «я дома», «доброе утро» и свои
        withContext(Dispatchers.IO) { OfflineBrain.skillFor(context, prompt) }?.let { return runSkill(it) }
        // Понималка: команда своими словами («вырубай инет» → «отключи мобильный интернет»), без модели
        understood(prompt, relaxed = false)?.let { return it }

        if (OfflineBrain.mode(context) == "offline") return offlineAnswer(prompt)
        return try {
            val answer = try {
                askModel(prompt, normalized, fromVoice)
            } catch (e: GroqApiException) {
                // Короткий лимит «в минуту»: подождём и повторим один раз сами
                val wait = if (e.code == 429) MusicTagger.retryDelayMs(e.message.orEmpty()) else null
                val maxWait = context.getSharedPreferences("assistant_prefs", android.content.Context.MODE_PRIVATE)
                    .getInt("retry_wait_seconds", 20).coerceIn(0, 30) * 1000L
                if (wait == null || maxWait == 0L || wait > maxWait) throw e
                kotlinx.coroutines.delay(wait + 500)
                askModel(prompt, normalized, fromVoice)
            }
            answer.also { summarizeInBackground() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Нет связи или модель недоступна: пробуем справиться офлайн, а не просто «ошибка»
            if (OfflineBrain.mode(context) == "online") FriendlyError.text(e)
            else offlineAnswer(prompt, FriendlyError.text(e))
        }
    }

    // ---------------------------------------------------------------- офлайн-мозг

    private var learnedLoaded = false

    /** Понималка: находит каноническую команду и выполняет её. null — не уверена. */
    private suspend fun understood(prompt: String, relaxed: Boolean): String? {
        if (!learnedLoaded) {
            learnedLoaded = true
            // Выученные фразы тоже становятся примерами: похожие формулировки понимаются без модели
            OfflineBrain.learnedExamples = try {
                patternDao.top(300).filter { !it.phrase.startsWith(Learning.TEMPLATE_PREFIX) }.map { ("learned:" + it.phrase) to it.phrase }
            } catch (e: Exception) { emptyList() }
            OfflineBrain.invalidate()
        }
        val match = withContext(Dispatchers.Default) { OfflineBrain.understand(context, prompt, relaxed) } ?: return null
        ListenLog.add(context, "понималка", "«$prompt» → «${match.canonical}» (${"%.2f".format(match.score)})")
        return resolveCanonical(match.canonical, prompt)
    }

    private suspend fun resolveCanonical(canonical: String, prompt: String): String? {
        if (canonical.startsWith("skill:")) {
            val name = canonical.removePrefix("skill:")
            return withContext(Dispatchers.IO) { OfflineBrain.skills(context).firstOrNull { it.name == name } }?.let { runSkill(it) }
        }
        if (canonical.startsWith("learned:")) return runLearned(canonical.removePrefix("learned:"))
        val filled = IntentModel.fill(canonical, prompt) ?: return null
        LocalCommands.parse(filled)?.let { return runLocal(it) }
        return runLearned(normalize(filled))
    }

    /** Навык: сначала ответ, потом его команды по порядку. */
    private suspend fun runSkill(skill: Skill): String {
        ListenLog.add(context, "навык", skill.name)
        val results = ArrayList<String>()
        if (skill.answer.isNotBlank()) results += skill.answer
        for (step in skill.steps) {
            val n = normalize(step)
            val r = LocalCommands.parse(step)?.let { runLocal(it) } ?: runLearned(n)
                ?: understood(step, relaxed = false)
                ?: if (OfflineBrain.mode(context) != "offline") try { askModel(step, n, false) } catch (e: Exception) { "«$step» — сейчас недоступно" } else "«$step» — без интернета не умею"
            results += r
        }
        return results.filter { it.isNotBlank() }.joinToString("\n")
    }

    /** Без интернета: понималка «помягче», потом база знаний, иначе честно говорим, что умеем офлайн. */
    private suspend fun offlineAnswer(prompt: String, reason: String? = null): String {
        understood(prompt, relaxed = true)?.let { return it }
        val hits = withContext(Dispatchers.IO) { OfflineBrain.search(context, prompt) }
        if (hits.isNotEmpty() && hits.first().score >= 1.5) return "Из базы знаний (без интернета):\n" + KnowledgeRank.answer(hits)
        return (reason?.let { "$it\n" } ?: "") + "Без интернета на это ответить не могу. Офлайн работают: команды телефона, музыка, будильники и таймеры, " +
            "заметки, навыки («мои навыки») и база знаний («что ты знаешь про …»)."
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
    private suspend fun askModel(prompt: String, normalizedPhrase: String, fromVoice: Boolean = false): String {
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
            if (!commandMode || AssistantPrompt.wantsVisual(prompt)) put(JSONObject().put("role", "system").put("content", AssistantPrompt.VISUAL_NOTE))
            // Личная база знаний: подходящие записи подсказываем модели (номера, коды, справки пользователя)
            try {
                val hits = OfflineBrain.search(context, prompt, 3).filter { it.score >= 2.0 }
                if (hits.isNotEmpty()) put(JSONObject().put("role", "system").put("content",
                    "Из базы знаний пользователя (используй, если относится к вопросу):\n" + hits.joinToString("\n") { "• ${it.fact.title}: ${it.fact.body.take(300)}" }))
            } catch (_: Exception) {}
            ChatHistory.summary(context)?.let { sum ->
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Краткое содержание более ранней части разговора: ${sum.text}")
                })
            }
            val shown = history.takeLast(window)
            for ((i, m) in shown.withIndex()) {
                // Пометку «голосом» ставим прямо перед самим запросом: последним остаётся сообщение пользователя
                if (fromVoice && i == shown.lastIndex && m.role != "assistant") {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", AssistantPrompt.VOICE_NOTE)
                    })
                }
                put(JSONObject().apply {
                    put("role", if (m.role == "assistant") "assistant" else "user")
                    // Схемы и мини-приложения из прошлых ответов модели не нужны — экономим токены
                    put("content", VisualSpec.stripForModel(m.text))
                })
            }
        }
        val multi = AgentPlan.multiStep(prompt)
        if (multi && messages.length() > 1) {
            // Подсказка про план — системным сообщением перед последней репликой пользователя
            val last = messages.getJSONObject(messages.length() - 1)
            messages.put(messages.length() - 1, JSONObject().put("role", "system").put("content", AgentPlan.NOTE))
            messages.put(last)
        }
        val tools = AppSettings.filterTools(context, if (commandMode) Tools.subset(groups, plan = multi) else Tools.compact)

        return when (val result = client.sendConversation(
            messages, tools,
            effort = if (commandMode) "low" else "medium",
            maxTokens = if (AssistantPrompt.wantsVisual(prompt)) 5000 else if (commandMode) 1024 else 2048
        )) {
            is GroqResult.FinalText ->
                if (Sanity.implausibleTemperature(result.text)) result.text + Sanity.WARNING else result.text
            is GroqResult.FunctionCalls -> runAgent(prompt, normalizedPhrase, messages, tools, result)
        }
    }

    // ---------------------------------------------------------------- несколько шагов подряд и план

    /** Функции, которые только узнают что-то: их результат может понадобиться следующему шагу. */
    private val infoTools = setOf(
        "find_contact", "device_info", "web_search", "list_notes", "search_notes", "calendar_list", "list_reminders",
        "find_files", "find_media", "music_library", "notifications", "read_screen", "shared_folder", "list_digests", "recognize_image"
    )

    /**
     * Выполняет функции, которые вызвала модель. Если задача из нескольких шагов (есть план или результат нужен дальше),
     * отдаёт результаты модели и продолжает — до 5 кругов, как агент. Простые команды — как раньше: один круг, без второго запроса.
     */
    private suspend fun runAgent(prompt: String, phrase: String, messages: JSONArray, tools: JSONArray, first: GroqResult.FunctionCalls): String {
        var current = first
        val convo = JSONArray().apply { for (i in 0 until messages.length()) put(messages.get(i)) }
        val shown = ArrayList<String>()
        var plan: List<String> = emptyList()
        var planDone = 0
        var final: String? = null
        val multi = AgentPlan.multiStep(prompt)
        for (round in 1..5) {
            val outputs = current.calls.map { call ->
                if (call.name == "update_plan") {
                    AgentPlan.steps(call.arguments).takeIf { it.isNotEmpty() }?.let { plan = it }
                    planDone = call.arguments.optInt("done", planDone).coerceIn(0, plan.size)
                    "План принят. Выполняй шаги по порядку и обновляй план."
                } else execute(call.name, call.arguments)
            }
            if (round == 1) learn(phrase, current.calls)
            current.calls.forEachIndexed { i, c -> if (c.name != "update_plan") shown += outputs[i] }
            // Ждём «да/нет» от пользователя — дальше не идём
            if (asks.isNotEmpty()) break
            val analysis = current.calls.any { it.name == "shared_folder" && it.arguments.optString("action") in setOf("read", "sql") }
            val needMore = analysis || (plan.isNotEmpty() && planDone < plan.size) ||
                (multi && current.calls.any { it.name in infoTools || it.name == "update_plan" })
            if (!needMore) break
            convo.put(current.assistantMessage)
            current.calls.forEachIndexed { i, call ->
                convo.put(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", outputs[i].take(3000)))
            }
            val next = try { client.sendConversation(convo, tools, effort = "low", maxTokens = 1200) } catch (e: Exception) { break }
            if (next is GroqResult.FinalText) { final = next.text.takeIf { it.isNotBlank() }; if (plan.isNotEmpty()) planDone = plan.size; break }
            current = next as GroqResult.FunctionCalls
        }
        val text = final ?: shown.filter { it.isNotBlank() }.joinToString("\n")
        return if (plan.size >= 2) text + AgentPlan.visual(plan, planDone) else text
    }

    /** Запоминаем: одно действие — фразу и шаблон с числами; несколько безопасных — связку. */
    private suspend fun learn(phrase: String, calls: List<ToolCall>) {
        val real = calls.filter { it.name != "update_plan" }
        if (real.isEmpty() || !real.all { Learning.cacheable(it.name, it.arguments) }) return
        if (real.size == 1) {
            val c = real.first()
            patternDao.upsert(CommandPatternEntity(phrase = phrase, functionName = c.name, argumentsJson = c.arguments.toString()))
            val t = Learning.template(phrase)
            val g = Learning.generalize(phrase, c.arguments)
            if (t != null && g != null) patternDao.upsert(CommandPatternEntity(phrase = t, functionName = c.name, argumentsJson = g.toString()))
        } else if (real.size <= 5) {
            patternDao.upsert(CommandPatternEntity(phrase = phrase, functionName = Learning.MACRO, argumentsJson = Learning.macroJson(real.map { it.name to it.arguments })))
        }
    }

    /** Выученное: точная фраза, шаблон с числами, похожая фраза, связка или то, чему научил пользователь. */
    private suspend fun runLearned(phrase: String, depth: Int = 0): String? {
        val hit = patternDao.find(phrase)
            ?: Learning.template(phrase)?.let { patternDao.find(it) }
            ?: patternDao.top(300).firstOrNull { !it.phrase.startsWith(Learning.TEMPLATE_PREFIX) && Learning.close(it.phrase, phrase) }
            ?: return null
        val result = when (hit.functionName) {
            Learning.TAUGHT -> {
                if (depth > 0) return null
                val commands = try { JSONArray(hit.argumentsJson).let { a -> (0 until a.length()).map { a.optString(it) } } } catch (e: Exception) { return null }
                commands.map { cmd ->
                    val n = normalize(cmd)
                    LocalCommands.parse(cmd)?.let { runLocal(it) } ?: runLearned(n, depth + 1) ?: askModel(cmd, n, false)
                }.joinToString("\n")
            }
            Learning.MACRO -> Learning.macroCalls(hit.argumentsJson).filter { Learning.cacheable(it.first, it.second) }
                .map { (n, a) -> execute(n, a) }.joinToString("\n").ifBlank { return null }
            else -> {
                val raw = try { JSONObject(hit.argumentsJson) } catch (e: Exception) { JSONObject() }
                val args = if (hit.phrase.startsWith(Learning.TEMPLATE_PREFIX)) Learning.fill(raw, phrase) ?: return null else raw
                if (!Learning.cacheable(hit.functionName, args)) return null
                execute(hit.functionName, args)
            }
        }
        patternDao.upsert(hit.copy(useCount = hit.useCount + 1, lastUsed = System.currentTimeMillis()))
        return result
    }

    /** «Когда я говорю …, делай …»: запоминаем связку команд пользователя. */
    private suspend fun teach(phrase: String, commands: List<String>): String {
        patternDao.upsert(CommandPatternEntity(phrase = phrase, functionName = Learning.TAUGHT, argumentsJson = JSONArray(commands).toString()))
        return "Запомнил: на «$phrase» буду делать — " + commands.joinToString("; ") + ". Работает без интернета." +
            VisualSpec.block(VisualSpec.listJson("«$phrase»", "steps", commands.map { Visual.Item(it) }))
    }

    /** «Чему ты научился» — таблица выученного. */
    private suspend fun learnedList(): String {
        val all = patternDao.top(200)
        if (all.isEmpty()) return "Пока ничего не выучил. Я запоминаю команды, которые понял через модель, — в следующий раз выполняю их сам, без интернета."
        val taught = all.count { it.functionName == Learning.TAUGHT }
        val rows = all.take(25).map { p ->
            listOf(p.phrase.removePrefix(Learning.TEMPLATE_PREFIX).replace("#", "N"),
                when (p.functionName) { Learning.TAUGHT -> "твоя связка"; Learning.MACRO -> "связка действий"; else -> p.functionName }, p.useCount.toString())
        }
        val table = org.json.JSONObject().put("type", "table").put("title", "Чему я научился").put("columns", JSONArray(listOf("Фраза", "Что делаю", "Раз")))
            .put("rows", JSONArray(rows.map { JSONArray(it) }))
        return "Выучено фраз: ${all.size} (из них твоих связок: $taught). Они выполняются без модели и без интернета. «Забудь фразу …» — удалить." +
            VisualSpec.block(table)
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
        // Shizuku → сами → плитка в шторке → экран настроек, с проверкой результата и журналом (SystemToggle)
        return SystemToggle.set(context, setting, want)
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

    private suspend fun executeRaw(name: String, a: JSONObject, confirmed: Boolean): String {
        val enabled = context.getSharedPreferences("assistant_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("feature_$name", true)
        if (!enabled) return "Функция «$name» отключена в настройках."
        return try {
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
            "music_control" -> MusicActions.control(context, a.optString("action", ""), a.optIntOrNull("amount"))
            "calculate" -> a.optString("expression").let { e -> Calc.eval(e)?.let { "${e.replace("*", " × ").replace("/", " ÷ ")} = ${Calc.format(it)}" } ?: "Не смог посчитать «$e»" }
            "music_wave" -> withContext(Dispatchers.IO) {
                MusicActions.wave(context, a.optString("kind", "my_wave"), a.optStringOrNull("mood"), a.optStringOrNull("preset"), a.optStringOrNull("query"))
            }
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

            "device_info" -> withContext(Dispatchers.IO) { DeviceActions.report(context, a.optString("section", "all")) }
            "read_screen" -> ScreenAssist.ask(context, currentPrompt)
            "screen_guide" -> withContext(Dispatchers.Main) { ScreenGuide.start(context, a.optString("goal", ""), a.optBoolean("auto", false)) }
            "update_plan" -> "План принят"
            "teach" -> teach(a.optString("phrase"), a.optJSONArray("commands")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty())
            "learned" -> learnedList()
            "forget" -> {
                val what = normalize(a.optString("phrase"))
                if (what.isEmpty() || what == "все" || what == "всё") {
                    if (!confirmed) Confirm.ask("Забыть всё выученное (${patternDao.count()} фраз)? Команды снова будут понимать через модель.")
                    else "Забыл всё: ${patternDao.deleteAll()} фраз."
                } else {
                    val n = patternDao.delete(what) + (Learning.template(what)?.let { patternDao.delete(it) } ?: 0)
                    if (n > 0) "Забыл «$what»." else "Такой выученной фразы нет. Скажи «чему ты научился» — покажу список."
                }
            }
            "mini_apps" -> withContext(Dispatchers.Main) { MiniApps.command(context, a.optStringOrNull("name")) }
            "kb" -> withContext(Dispatchers.IO) {
                when (a.optString("action", "search")) {
                    "add" -> OfflineBrain.remember(context, a.optString("text"))
                    "list" -> OfflineBrain.listText(context)
                    "build" -> OfflineBrain.buildReference(context, client, a.optString("text"))
                    "delete" -> {
                        val hits = OfflineBrain.search(context, a.optString("text"), 3)
                        if (hits.isEmpty()) "В базе нет записи про «${a.optString("text")}»."
                        else if (!confirmed) Confirm.ask("Удалить из базы знаний: ${hits.joinToString("; ") { "«${it.fact.title}»" }}?")
                        else "Удалил записей: ${OfflineBrain.deleteFacts(context, hits.map { it.fact.id })}."
                    }
                    else -> {
                        val hits = OfflineBrain.search(context, a.optString("text"))
                        if (hits.isEmpty()) "В базе знаний про это ничего нет. Добавить: «запомни факт: …» или «собери справочник по …»."
                        else KnowledgeRank.answer(hits)
                    }
                }
            }
            "skills" -> withContext(Dispatchers.IO) {
                when (a.optString("action", "list")) {
                    "create" -> OfflineBrain.createSkill(context, client, a.optString("text"))
                    "on" -> OfflineBrain.toggleSkill(context, a.optString("text"), true)
                    "off" -> OfflineBrain.toggleSkill(context, a.optString("text"), false)
                    else -> OfflineBrain.skillsText(context)
                }
            }
            "brain" -> withContext(Dispatchers.IO) {
                when (a.optString("action", "stats")) {
                    "train" -> OfflineBrain.train(context, client, patternDao.top(80).filter { !it.phrase.startsWith(Learning.TEMPLATE_PREFIX) && it.functionName != Learning.TAUGHT }.map { it.phrase })
                    "offline", "online", "auto" -> { OfflineBrain.setMode(context, a.optString("action")); "Режим мозга: ${OfflineBrain.modeTitle(a.optString("action"))}." }
                    else -> OfflineBrain.stats(context) + " Режим: ${OfflineBrain.modeTitle(OfflineBrain.mode(context))}."
                }
            }
            "dev_report" -> withContext(Dispatchers.IO) { DevReport.share(context) }
            "screen_control" -> ScreenActions.control(context, a.optString("action", ""), a.optStringOrNull("text"), a.optStringOrNull("direction"))
            "screen_translate" -> ScreenAssist.translate(context, TranslateBatch.targetFrom(a.optString("language", "русский")))
            "live_screen" -> ScreenAssist.setLive(context, a.optBoolean("on", true))
            "files" -> withContext(Dispatchers.IO) {
                when (a.optString("action", "storage")) {
                    "organize" -> FileOrganizer.organize(context, a.optStringOrNull("folder"), a.optStringOrNull("by"), confirmed)
                    "undo" -> FileOrganizer.undo(context, confirmed)
                    "big" -> FileOrganizer.biggestFiles()
                    "duplicates" -> FileOrganizer.duplicates(a.optStringOrNull("folder"))
                    else -> FileOrganizer.storageReport()
                }
            }
            "notifications" -> NotificationActions.read(context, a.optStringOrNull("app"))
            "close_app" -> withContext(Dispatchers.IO) { ScreenActions.closeApp(context, a.optString("name", ""), confirmed) }
            "grant_permissions" -> withContext(Dispatchers.IO) {
                if (!Shell.available(context)) "Shizuku: ${ShizukuShell.state(context).text}. Включи консоль в настройках и выдай Shizuku разрешение для Assistant."
                else ShizukuPower.report(ShizukuPower.grantAll(context))
            }
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
}
