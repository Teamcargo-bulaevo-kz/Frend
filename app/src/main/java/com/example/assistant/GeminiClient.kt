```kotlin
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

/**
 * Результат ответа модели: либо она хочет вызвать функцию,
 * либо сразу вернула финальный текстовый ответ.
 */
sealed class GeminiResult {
    data class FunctionCall(
        val name: String,
        val arguments: JSONObject,
        val callId: String,
        val interactionId: String
    ) : GeminiResult()

    data class FinalText(val text: String) : GeminiResult()
}

/**
 * Простой клиент к Gemini Interactions API через REST (OkHttp),
 * без привязки к конкретной версии официального Kotlin-SDK.
 */
class GeminiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val endpoint = "https://generativelanguage.googleapis.com/v1beta/interactions"
    private val model = "gemini-3.8-flash" // смени здесь, если Google выпустит новую модель

    /** Отправляет текст пользователя вместе с описанием доступных функций. */
    suspend fun sendPrompt(prompt: String, tools: JSONArray): GeminiResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("model", model)
                put("input", prompt)
                put("tools", tools)
            }
            parseResponse(execute(body))
        }

    /** Отправляет результат выполнения функции обратно модели и получает финальный ответ. */
    suspend fun sendFunctionResult(
        previousInteractionId: String,
        functionName: String,
        callId: String,
        result: JSONObject,
        tools: JSONArray
    ): GeminiResult = withContext(Dispatchers.IO) {
        val resultStep = JSONObject().apply {
            put("type", "function_result")
            put("name", functionName)
            put("call_id", callId)
            put(
                "result",
                JSONArray().put(
                    JSONObject().apply {
                        put("type", "text")
                        put("text", result.toString())
                    }
                )
            )
        }
        val body = JSONObject().apply {
            put("model", model)
            put("previous_interaction_id", previousInteractionId)
            put("tools", tools)
            put("input", JSONArray().put(resultStep))
        }
        parseResponse(execute(body))
    }

    private fun execute(body: JSONObject): JSONObject {
        val requestBody = body.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-goog-api-key", apiKey)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Gemini API error ${resp.code}: $raw")
            }
            // Полезно на первых запусках: android.util.Log.d("GeminiClient", raw)
            return JSONObject(raw)
        }
    }

    private fun parseResponse(response: JSONObject): GeminiResult {
        val interactionId = response.optString("id")
        val steps = response.optJSONArray("steps") ?: JSONArray()

        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            if (step.optString("type") == "function_call") {
                return GeminiResult.FunctionCall(
                    name = step.getString("name"),
                    arguments = step.optJSONObject("arguments") ?: JSONObject(),
                    callId = step.getString("id"),
                    interactionId = interactionId
                )
            }
        }

        val text = response.optString("output_text", "Не удалось распознать ответ модели")
        return GeminiResult.FinalText(text)
    }
}
```
