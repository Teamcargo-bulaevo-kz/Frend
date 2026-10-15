package com.example.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Один вызов функции, который запросила модель. */
data class ToolCall(val id: String, val name: String, val arguments: JSONObject)

sealed class GroqResult {
    /** Модель просит выполнить одну или несколько функций; assistantMessage надо вернуть ей в историю. */
    data class FunctionCalls(
        val calls: List<ToolCall>,
        val assistantMessage: JSONObject
    ) : GroqResult()

    data class FinalText(val text: String) : GroqResult()
}

class GroqApiException(val code: Int, body: String) : Exception("Groq API error $code: $body")

class GroqClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val model = "openai/gpt-oss-120b"
    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"

    /** Короткий системный промпт (экономит токены). Собирается при каждом запросе, чтобы модель знала время. */
    fun systemPrompt(): String {
        val now = SimpleDateFormat("dd.MM.yyyy HH:mm, EEEE", Locale("ru")).format(Date())
        return "Ты голосовой помощник на телефоне Максима. Отвечай по-русски, кратко. Сейчас: $now (${TimeZone.getDefault().id}). " +
            "Действия выполняй вызовом функций (можно несколько сразу), на обычные вопросы отвечай текстом. " +
            "Короткие просьбы вроде «запомни» или «а в метрах?» относятся к предыдущим сообщениям: не переспрашивай то, что уже сказано. " +
            "Результат функции — единственный источник правды: не говори, что действие выполнено, если функция вернула ошибку. " +
            "Камеру, звонок и отправку сообщения WhatsApp пользователь запускает сам. " +
            "Чего нет в функциях (например, включить Wi-Fi или позвонить контакту по имени), сделать нельзя: скажи об этом и предложи ближайшее действие."
    }

    /**
     * effort — глубина рассуждения модели ("low" для команд, "medium" для беседы), maxTokens — лимит ответа.
     * Если API не принял эти необязательные параметры (ошибка 400), запрос повторяется без них.
     */
    suspend fun sendConversation(
        messages: JSONArray,
        tools: JSONArray?,
        effort: String? = null,
        maxTokens: Int? = null
    ): GroqResult = withContext(Dispatchers.IO) {
        fun body(withExtras: Boolean) = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            if (withExtras) {
                if (effort != null) put("reasoning_effort", effort)
                if (maxTokens != null) put("max_completion_tokens", maxTokens)
            }
        }
        val extras = effort != null || maxTokens != null
        try {
            parseResponse(execute(body(extras)))
        } catch (e: GroqApiException) {
            if (extras && e.code == 400) parseResponse(execute(body(false))) else throw e
        }
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
                throw GroqApiException(resp.code, raw)
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
            val calls = mutableListOf<ToolCall>()
            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val function = call.getJSONObject("function")
                val argsString = function.optString("arguments", "{}")
                val arguments = try {
                    JSONObject(argsString.ifBlank { "{}" })
                } catch (e: Exception) {
                    JSONObject()
                }
                calls += ToolCall(
                    id = call.getString("id"),
                    name = function.getString("name"),
                    arguments = arguments
                )
            }
            return GroqResult.FunctionCalls(calls, message)
        }

        val text = message.optString("content", "")
        return GroqResult.FinalText(if (text.isBlank()) "Пустой ответ от модели" else text)
    }
}
