package com.example.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Связывает модель (GroqClient) с реальными действиями (PhoneActions, MusicActions, NoteActions...).
 * Сначала пробует обойтись без модели (правила и выученные фразы), потом идёт в Groq.
 */
class AssistantRepository(private val context: Context, apiKey: String) {

    private val client = GroqClient(apiKey)
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
            askModel(prompt, normalized)
        } catch (e: Exception) {
            "Ошибка: ${e.message}"
        }
    }

    private suspend fun runLocal(command: LocalCommand): String? {
        if (command.name == "say") return command.args["text"] as? String
        val args = JSONObject()
        for ((key, value) in command.args) args.put(key, value)
        val result = execute(command.name, args)
        // Приложение не нашлось по названию: пусть разберётся модель (она знает, как оно называется на значке)
        if (command.name == "open_app" && result.startsWith("Не нашёл")) return null
        return result
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
            is GroqResult.FinalText -> result.text
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

    /** Выполняет одну функцию. Никогда не бросает исключений: ошибка возвращается текстом. */
    private suspend fun execute(name: String, a: JSONObject): String = try {
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
                MusicActions.playMusic(context, a.optString("query", ""), a.optBoolean("shuffle", false))
            }
            "music_control" -> MusicActions.control(context, a.optString("action", ""))
            "set_volume" -> MusicActions.setVolume(
                context, a.optString("action", ""), a.optIntOrNull("percent"), a.optStringOrNull("stream")
            )
            "music_library" -> withContext(Dispatchers.IO) {
                MusicActions.library(context, a.optStringOrNull("query"))
            }
            "playlist" -> withContext(Dispatchers.IO) {
                MusicActions.playlist(
                    context, a.optString("action", ""), a.optStringOrNull("name"),
                    a.optStringList("queries"), a.optBoolean("shuffle", false)
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

            "create_note" -> NoteActions.createNote(context, a.optString("text", ""))
            "list_notes" -> NoteActions.listNotes(context)
            "search_notes" -> NoteActions.searchNotes(context, a.optString("query", ""))
            "delete_note" -> NoteActions.deleteNote(context, a.optString("query", ""))
            else -> "Неизвестная функция: $name"
        }
    } catch (e: Exception) {
        "Не получилось выполнить «$name»: ${e.message}"
    }
}
