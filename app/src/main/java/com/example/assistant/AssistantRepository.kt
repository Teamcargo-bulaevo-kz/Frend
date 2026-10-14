package com.example.assistant

import android.content.Context
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

    private val tools: JSONArray = JSONArray().apply {
        put(functionTool(
            name = "set_alarm",
            description = "Ставит будильник на телефоне на указанное время.",
            properties = JSONObject().apply {
                put("hour", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Час в 24-часовом формате, от 0 до 23")
                })
                put("minute", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Минуты, от 0 до 59")
                })
                put("label", JSONObject().apply {
                    put("type", "string")
                    put("description", "Необязательная подпись будильника, например 'тренировка'")
                })
            },
            required = JSONArray().put("hour").put("minute")
        ))
        put(functionTool(
            name = "play_music",
            description = "Ищет и включает локальный музыкальный файл на телефоне по названию трека или исполнителю.",
            properties = JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "Название трека, исполнитель или плейлист, который нужно включить")
                })
            },
            required = JSONArray().put("query")
        ))
        put(functionTool(
            name = "create_note",
            description = "Сохраняет новую текстовую заметку.",
            properties = JSONObject().apply {
                put("text", JSONObject().apply {
                    put("type", "string")
                    put("description", "Текст заметки, который нужно сохранить")
                })
            },
            required = JSONArray().put("text")
        ))
        put(functionTool(
            name = "list_notes",
            description = "Показывает все сохранённые заметки.",
            properties = JSONObject(),
            required = JSONArray()
        ))
        put(functionTool(
            name = "search_notes",
            description = "Ищет заметки по тексту.",
            properties = JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "Текст или ключевое слово для поиска среди заметок")
                })
            },
            required = JSONArray().put("query")
        ))
        put(functionTool(
            name = "delete_note",
            description = "Удаляет заметку (или несколько), которая подходит под поисковый запрос.",
            properties = JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "Текст или ключевое слово, по которому нужно найти и удалить заметку")
                })
            },
            required = JSONArray().put("query")
        ))
    }

    private fun functionTool(
        name: String,
        description: String,
        properties: JSONObject,
        required: JSONArray
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", required)
            })
        })
    }

    private fun normalize(text: String): String =
        text.trim().lowercase(Locale.getDefault())

    /**
     * В кэш попадают только действия, которые не зависят от контекста разговора.
     * Заметки зависят от контекста ("запомни" после предыдущего сообщения), поэтому их
     * всегда обрабатывает модель, которая видит всю историю чата.
     */
    private val cacheableFunctions = setOf("set_alarm", "play_music")

    /** Главная точка входа. Сначала смотрим в локальный кэш выученных команд. */
    suspend fun handle(prompt: String): String {
        val normalized = normalize(prompt)
        val cached = patternDao.find(normalized)?.takeIf { it.functionName in cacheableFunctions }
        if (cached != null) {
            val args = try { JSONObject(cached.argumentsJson) } catch (e: Exception) { JSONObject() }
            val result = executeFunction(cached.functionName, args)
            patternDao.upsert(cached.copy(useCount = cached.useCount + 1, lastUsed = System.currentTimeMillis()))
            return result
        }

        return try {
            val history = ChatHistory.load(context)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", client.systemPrompt)
                })
                for (m in history) {
                    put(JSONObject().apply {
                        put("role", if (m.role == "assistant") "assistant" else "user")
                        put("content", m.text)
                    })
                }
            }
            when (val result = client.sendConversation(messages, tools)) {
                is GroqResult.FinalText -> result.text
                is GroqResult.FunctionCall -> executeAndRespond(messages, normalized, result)
            }
        } catch (e: Exception) {
            "Ошибка: ${e.message}"
        }
    }

    private fun executeFunction(name: String, arguments: JSONObject): String = when (name) {
        "set_alarm" -> {
            val hour = arguments.optInt("hour", 0)
            val minute = arguments.optInt("minute", 0)
            val label = if (arguments.has("label")) arguments.optString("label") else null
            PhoneActions.setAlarm(context, hour, minute, label)
        }
        "play_music" -> {
            val query = arguments.optString("query", "")
            PhoneActions.playMusic(context, query)
        }
        else -> "Неизвестная функция: $name"
    }

    private suspend fun executeFunctionSuspend(name: String, arguments: JSONObject): String = when (name) {
        "create_note" -> NoteActions.createNote(context, arguments.optString("text", ""))
        "list_notes" -> NoteActions.listNotes(context)
        "search_notes" -> NoteActions.searchNotes(context, arguments.optString("query", ""))
        "delete_note" -> NoteActions.deleteNote(context, arguments.optString("query", ""))
        else -> executeFunction(name, arguments)
    }

    private suspend fun executeAndRespond(
        messages: JSONArray,
        normalizedPhrase: String,
        call: GroqResult.FunctionCall
    ): String {
        val actionResult = executeFunctionSuspend(call.name, call.arguments)

        if (call.name in cacheableFunctions) {
            patternDao.upsert(
                CommandPatternEntity(
                    phrase = normalizedPhrase,
                    functionName = call.name,
                    argumentsJson = call.arguments.toString()
                )
            )
        }

        messages.put(call.assistantMessage)
        messages.put(JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", call.toolCallId)
            put("name", call.name)
            put("content", actionResult)
        })

        return when (val finalResponse = client.sendConversation(messages, tools)) {
            is GroqResult.FinalText -> finalResponse.text
            is GroqResult.FunctionCall -> actionResult
        }
    }
}
