package com.example.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class GroqResult {
    data class FunctionCall(
        val name: String,
        val arguments: JSONObject,
        val toolCallId: String,
        val assistantMessage: JSONObject
    ) : GroqResult()

    data class FinalText(val text: String) : GroqResult()
}

class GroqClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val model = "llama-3.3-70b-versatile"
    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"

    val systemPrompt = """
        Ты — личный голосовой помощник на телефоне Максима.
        Отвечай по-русски, кратко и по делу, без лишних вступлений.
        Если просьба требует одного из доступных действий (будильник, музыка) — вызывай соответствующую функцию.
        Если действие не требуется — просто отвечай текстом.
    """.trimIndent()

    suspend fun sendConversation(messages: JSONArray, tools: JSONArray): GroqResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("tools", tools)
                put("tool_choice", "auto")
            }
            parseResponse(execute(body))
        }

    private fun execute(body: JSONObject): JSONObject {
        val requestBody = body.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Groq API error ${resp.code}: $raw")
            }
            return JSONObject(raw)
        }
    }

    private fun parseResponse(response: JSONObject): GroqResult {
        val choices = response.optJSONArray("choices") ?: JSONArray()
        if (choices.length() == 0) {
            return GroqResult.FinalText("Модель не вернула ответ")
        }
        val message = choices.getJSONObject(0).optJSONObject("message") ?: JSONObject()
        val toolCalls = message.optJSONArray("tool_calls")

        if (toolCalls != null && toolCalls.length() > 0) {
            val call = toolCalls.getJSONObject(0)
            val function = call.getJSONObject("function")
            val argsString = function.optString("arguments", "{}")
            val arguments = try {
                JSONObject(argsString)
            } catch (e: Exception) {
                JSONObject()
            }
            return GroqResult.FunctionCall(
                name = function.getString("name"),
                arguments = arguments,
                toolCallId = call.getString("id"),
                assistantMessage = message
            )
        }

        val text = message.optString("content", "")
        return GroqResult.FinalText(if (text.isBlank()) "Пустой ответ от модели" else text)
    }
}
