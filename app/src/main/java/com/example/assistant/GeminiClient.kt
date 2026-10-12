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

sealed class GeminiResult {
    data class FunctionCall(
        val name: String,
        val arguments: JSONObject,
        val modelContent: JSONObject
    ) : GeminiResult()

    data class FinalText(val text: String) : GeminiResult()
}

class GeminiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val model = "gemini-2.5-flash"
    private val endpoint =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

    suspend fun sendConversation(contents: JSONArray, tools: JSONArray): GeminiResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("contents", contents)
                put("tools", tools)
            }
            parseResponse(execute(body))
        }

    private fun execute(body: JSONObject): JSONObject {
        val requestBody = body.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Gemini API error ${resp.code}: $raw")
            }
            return JSONObject(raw)
        }
    }

    private fun parseResponse(response: JSONObject): GeminiResult {
        val candidates = response.optJSONArray("candidates") ?: JSONArray()
        if (candidates.length() == 0) {
            return GeminiResult.FinalText("Модель не вернула ответ")
        }
        val content = candidates.getJSONObject(0).optJSONObject("content") ?: JSONObject()
        val parts = content.optJSONArray("parts") ?: JSONArray()

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("functionCall")) {
                val fc = part.getJSONObject("functionCall")
                return GeminiResult.FunctionCall(
                    name = fc.getString("name"),
                    arguments = fc.optJSONObject("args") ?: JSONObject(),
                    modelContent = content
                )
            }
        }

        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("text")) text.append(part.getString("text"))
        }
        return GeminiResult.FinalText(if (text.isEmpty()) "Пустой ответ от модели" else text.toString())
    }
}
