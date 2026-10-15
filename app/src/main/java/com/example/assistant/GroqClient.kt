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

class GroqClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val model = "openai/gpt-oss-120b"
    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"

    /** Системный промпт собирается при каждом запросе, чтобы модель знала текущее время. */
    fun systemPrompt(): String {
        val now = SimpleDateFormat("dd.MM.yyyy HH:mm, EEEE", Locale("ru")).format(Date())
        val zone = TimeZone.getDefault().id
        return """
            Ты — личный голосовой помощник на телефоне Максима.
            Отвечай по-русски, кратко и по делу, без лишних вступлений.
            Сейчас: $now (часовой пояс $zone). Используй это для будильников вроде "через полчаса" или "завтра в 7".

            Ты видишь всю историю этого разговора. Короткие просьбы вроде "запомни" или "а в метрах?" относятся к предыдущим сообщениям — используй их как контекст и не переспрашивай то, что уже было сказано.

            Что ты умеешь через функции: будильник, музыка (включить, пауза, дальше, назад, громкость, плейлисты), заметки (создать, показать, найти, удалить), фонарик, камера, скриншот, открыть приложение или сайт или настройки, набор номера, WhatsApp, поиск фото, видео и файлов.
            Если просьба требует одного из этих действий — вызывай функцию. Если несколько действий сразу, вызывай все нужные функции. Если действие не требуется — просто отвечай текстом.

            Правила:
            - Результат функции — главный источник правды. Не говори, что действие выполнено, если функция вернула ошибку или просьбу что-то включить вручную.
            - Ты не можешь сам нажимать кнопки в других приложениях: камеру, звонок и отправку сообщения WhatsApp пользователь запускает сам. Не обещай обратного.
            - Если просят то, чего нет в списке выше (включить Wi-Fi, позвонить сразу, написать контакту по имени), честно скажи об этом и предложи ближайшее действие.
        """.trimIndent()
    }

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
