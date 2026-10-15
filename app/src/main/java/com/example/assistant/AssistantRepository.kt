package com.example.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Связывает модель (GroqClient) с реальными действиями (PhoneActions, NoteActions):
 * описывает доступные функции, сначала проверяет локальный кэш выученных команд,
 * и только если фразы там нет — идёт в Groq с полной историей чата.
 */
class AssistantRepository(private val context: Context, apiKey: String) {

    private val client = GroqClient(apiKey)
    private val patternDao = NoteDatabase.getInstance(context).commandPatternDao()
    private val tools: JSONArray = Tools.all

    private fun normalize(text: String): String =
        text.trim().lowercase(Locale.getDefault())

    /**
     * В кэш попадают только действия, которые не зависят от контекста разговора и от текущего
     * состояния. Заметки, плейлисты и поиск файлов зависят от них, поэтому их всегда
     * обрабатывает модель, которая видит всю историю чата.
     */
    private val cacheableFunctions = setOf(
        "set_alarm", "play_music", "music_control", "set_volume", "flashlight", "open_camera",
        "take_screenshot", "open_app", "open_browser", "open_settings", "open_dialer"
    )

    /** Сколько раз подряд модель может вызвать функции, прежде чем мы принудительно остановимся. */
    private val maxToolRounds = 4

    /** Главная точка входа. Сначала смотрим в локальный кэш выученных команд. */
    suspend fun handle(prompt: String): String {
        val normalized = normalize(prompt)
        val cached = patternDao.find(normalized)?.takeIf { it.functionName in cacheableFunctions }
        if (cached != null) {
            val args = try { JSONObject(cached.argumentsJson) } catch (e: Exception) { JSONObject() }
            val result = execute(cached.functionName, args)
            patternDao.upsert(cached.copy(useCount = cached.useCount + 1, lastUsed = System.currentTimeMillis()))
            return result
        }

        return try {
            val history = ChatHistory.load(context)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", client.systemPrompt())
                })
                for (m in history) {
                    put(JSONObject().apply {
                        put("role", if (m.role == "assistant") "assistant" else "user")
                        put("content", m.text)
                    })
                }
            }
            converse(messages, normalized)
        } catch (e: Exception) {
            "Ошибка: ${e.message}"
        }
    }

    /**
     * Разговор с моделью: она может несколько раз подряд попросить выполнить функции
     * (и несколько функций за раз), пока не вернёт обычный текст.
     */
    private suspend fun converse(messages: JSONArray, normalizedPhrase: String): String {
        var lastResults = ""
        for (round in 1..maxToolRounds) {
            when (val result = client.sendConversation(messages, tools)) {
                is GroqResult.FinalText -> return result.text
                is GroqResult.FunctionCalls -> {
                    messages.put(result.assistantMessage)
                    val outputs = mutableListOf<String>()
                    for (call in result.calls) {
                        val output = execute(call.name, call.arguments)
                        outputs += output
                        messages.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", call.id)
                            put("name", call.name)
                            put("content", output)
                        })
                    }
                    lastResults = outputs.joinToString("\n")

                    // Запоминаем фразу только если это было одно простое действие
                    val only = result.calls.singleOrNull()
                    if (round == 1 && only != null && only.name in cacheableFunctions) {
                        patternDao.upsert(
                            CommandPatternEntity(
                                phrase = normalizedPhrase,
                                functionName = only.name,
                                argumentsJson = only.arguments.toString()
                            )
                        )
                    }
                }
            }
        }
        // Модель так и не дала текстовый ответ: показываем результаты действий как есть
        return lastResults.ifBlank { "Не удалось получить ответ от модели" }
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
