package com.example.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Связывает модель (GeminiClient) с реальными действиями (PhoneActions):
 * описывает доступные функции, отправляет запрос, выполняет то, что решит модель,
 * и возвращает финальный текстовый ответ для UI.
 */
class AssistantRepository(private val context: Context, apiKey: String) {

    private val client = GeminiClient(apiKey)

    private val tools: JSONArray = JSONArray().apply {
        put(
            functionDeclaration(
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
            )
        )
        put(
            functionDeclaration(
                name = "play_music",
                description = "Ищет и включает локальный музыкальный файл на телефоне по названию трека или исполнителю.",
                properties = JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "Название трека, исполнитель или плейлист, который нужно включить")
                    })
                },
                required = JSONArray().put("query")
            )
        )
        put(
            functionDeclaration(
                name = "create_note",
                description = "Сохраняет новую текстовую заметку.",
                properties = JSONObject().apply {
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "Текст заметки, который нужно сохранить")
                    })
                },
                required = JSONArray().put("text")
            )
        )
        put(
            functionDeclaration(
                name = "list_notes",
                description = "Показывает все сохранённые заметки.",
                properties = JSONObject(),
                required = JSONArray()
            )
        )
        put(
            functionDeclaration(
                name = "search_notes",
                description = "Ищет заметки по тексту.",
                properties = JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "Текст или ключевое слово для поиска среди заметок")
                    })
                },
                required = JSONArray().put("query")
            )
        )
        put(
            functionDeclaration(
                name = "delete_note",
                description = "Удаляет заметку (или несколько), которая подходит под поисковый запрос.",
                properties = JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "Текст или ключевое слово, по которому нужно найти и удалить заметку")
                    })
                },
                required = JSONArray().put("query")
            )
        )
    }

    private fun functionDeclaration(
        name: String,
        description: String,
        properties: JSONObject,
        required: JSONArray
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put("name", name)
        put("description", description)
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            put("required", required)
        })
    }

    /** Главная точка входа: отправляет запрос пользователя и выполняет то, что решит модель. */
    suspend fun handle(prompt: String): String {
        return try {
            when (val result = client.sendPrompt(prompt, tools)) {
                is GeminiResult.FinalText -> result.text
                is GeminiResult.FunctionCall -> executeAndRespond(result)
            }
        } catch (e: Exception) {
            "Ошибка: ${e.message}"
        }
    }

    private suspend fun executeAndRespond(call: GeminiResult.FunctionCall): String {
        val actionResult: String = when (call.name) {
            "set_alarm" -> {
                val hour = call.arguments.optInt("hour", 0)
                val minute = call.arguments.optInt("minute", 0)
                val label = if (call.arguments.has("label")) call.arguments.optString("label") else null
                PhoneActions.setAlarm(context, hour, minute, label)
            }
            "play_music" -> {
                val query = call.arguments.optString("query", "")
                PhoneActions.playMusic(context, query)
            }
            "create_note" -> {
                val text = call.arguments.optString("text", "")
                NoteActions.createNote(context, text)
            }
            "list_notes" -> {
                NoteActions.listNotes(context)
            }
            "search_notes" -> {
                val query = call.arguments.optString("query", "")
                NoteActions.searchNotes(context, query)
            }
            "delete_note" -> {
                val query = call.arguments.optString("query", "")
                NoteActions.deleteNote(context, query)
            }
            else -> "Неизвестная функция: ${call.name}"
        }

        val resultJson = JSONObject().put("status", actionResult)
        val finalResponse = client.sendFunctionResult(
            previousInteractionId = call.interactionId,
            functionName = call.name,
            callId = call.callId,
            result = resultJson,
            tools = tools
        )

        return when (finalResponse) {
            is GeminiResult.FinalText -> finalResponse.text
            is GeminiResult.FunctionCall -> actionResult // на случай, если модель запросит ещё один вызов подряд
        }
    }
}
