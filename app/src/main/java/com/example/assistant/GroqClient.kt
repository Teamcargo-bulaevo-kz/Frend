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

class GroqClient(private val apiKey: String, private val appContext: android.content.Context? = null) {

    private fun promptContext(): PromptContext = appContext?.let { DeviceInfo.promptContext(it) } ?: PromptContext()

    private val client: OkHttpClient
        get() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(
                appContext?.getSharedPreferences("assistant_prefs", android.content.Context.MODE_PRIVATE)
                    ?.getInt("request_timeout_seconds", 45)?.coerceIn(15, 120) ?: 45,
                TimeUnit.SECONDS
            )
            .build()
    private val model = "openai/gpt-oss-120b"
    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"

    /** Короткий системный промпт (экономит токены). Собирается при каждом запросе, чтобы модель знала время. */
    fun systemPrompt(): String {
        val now = SimpleDateFormat("dd.MM.yyyy HH:mm, EEEE", Locale("ru")).format(Date())
        return AssistantPrompt.build(now, TimeZone.getDefault().id, promptContext())
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

    /**
     * Поиск свежей информации в интернете: встроенный инструмент browser_search моделей gpt-oss на Groq
     * (тот же ключ). Возвращает короткий ответ с названиями источников.
     */
    suspend fun webSearch(query: String): String {
        val system = AssistantPrompt.search(promptContext())
        return webAsk(system, query, 2000)
    }

    /**
     * Запрос с обязательным поиском в интернете. В system нужно попросить начать итоговый ответ со слова «ИТОГ:»;
     * возвращается текст после этого маркера.
     */
    suspend fun webAsk(system: String, user: String, maxTokens: Int): String = withContext(Dispatchers.IO) {
        fun body(extras: Boolean) = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system + " Сейчас: " +
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date()) + " (${TimeZone.getDefault().id})."))
                .put(JSONObject().put("role", "user").put("content", user)))
            put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
            put("tool_choice", "required")
            if (extras) {
                put("reasoning_effort", "low")
                put("max_completion_tokens", maxTokens)
            }
        }
        val response = try {
            execute(body(true))
        } catch (e: GroqApiException) {
            if (e.code == 400) execute(body(false)) else throw e
        }
        val content = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content", "").orEmpty().trim()
        if (content.isEmpty()) return@withContext "Поиск ничего не вернул."
        // В content бывают выдержки из найденных страниц, а итог идёт после маркера
        val marker = content.lastIndexOf("ИТОГ:")
        (if (marker >= 0) content.substring(marker + 5) else content.takeLast(maxOf(1500, maxTokens / 2))).trim()
    }

    /** Модели Groq, которые понимают картинки. Если первая недоступна, пробуем следующую. */
    private val visionModels = listOf("qwen/qwen3.8-27b", "meta-llama/llama-4-scout-17b-16e-instruct")

    /** Распознаёт картинку (JPEG в base64) по заданию, например «перепиши весь текст». */
    suspend fun vision(imageBase64: String, task: String): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (visionModel in visionModels) {
            try {
                val content = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", task))
                    .put(JSONObject().put("type", "image_url").put("image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")))
                val body = JSONObject().apply {
                    put("model", visionModel)
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                    put("temperature", 0)
                    put("max_completion_tokens", 3000)
                }
                val message = execute(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val text = message?.optString("content", "").orEmpty().trim()
                if (text.isNotEmpty()) return@withContext text
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Модель не вернула текст")
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
