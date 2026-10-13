
package com.example.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMessage(val role: String, val text: String, val timestamp: Long)

object ChatHistory {

    private const val FILE_NAME = "chat_history.json"
    private const val MAX_MESSAGES = 30

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): MutableList<ChatMessage> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val array = JSONArray(f.readText())
            val result = mutableListOf<ChatMessage>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    ChatMessage(
                        role = obj.optString("role"),
                        text = obj.optString("text"),
                        timestamp = obj.optLong("timestamp")
                    )
                )
            }
            result
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun append(context: Context, role: String, text: String): List<ChatMessage> {
        val messages = load(context)
        messages.add(ChatMessage(role, text, System.currentTimeMillis()))

        val trimmed = if (messages.size > MAX_MESSAGES) {
            messages.takeLast(MAX_MESSAGES).toMutableList()
        } else {
            messages
        }

        save(context, trimmed)
        return trimmed
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, messages: List<ChatMessage>) {
        val array = JSONArray()
        for (m in messages) {
            array.put(JSONObject().apply {
                put("role", m.role)
                put("text", m.text)
                put("timestamp", m.timestamp)
            })
        }
        file(context).writeText(array.toString())
    }
}
