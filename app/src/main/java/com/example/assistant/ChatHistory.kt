package com.example.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChatMessage(val role: String, val text: String, val timestamp: Long)

data class ChatInfo(val id: String, val title: String, val updated: Long, val empty: Boolean)

/**
 * Несколько чатов, как в ChatGPT. Каждый чат — отдельный файл chats/<id>.json во внутренней памяти.
 * Функции load/append/clear работают с текущим чатом, поэтому остальной код про чаты может не знать.
 */
object ChatHistory {

    const val DEFAULT_TITLE = "Новый чат"

    private const val OLD_FILE = "chat_history.json"
    private const val MAX_MESSAGES = 200
    private const val PREFS = "assistant_prefs"
    private const val KEY_CURRENT = "current_chat"

    private fun dir(context: Context): File = File(context.filesDir, "chats").also { it.mkdirs() }
    private fun file(context: Context, id: String) = File(dir(context), "$id.json")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- список чатов

    @Synchronized
    fun list(context: Context): List<ChatInfo> {
        migrateOldHistory(context)
        return dir(context).listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f ->
                try {
                    val o = JSONObject(f.readText())
                    ChatInfo(
                        id = o.getString("id"),
                        title = o.optString("title", DEFAULT_TITLE),
                        updated = o.optLong("updated", f.lastModified()),
                        empty = (o.optJSONArray("messages")?.length() ?: 0) == 0
                    )
                } catch (e: Exception) {
                    null
                }
            }
            .sortedByDescending { it.updated }
    }

    /** Id текущего чата. Если его нет, берёт последний или создаёт новый. */
    @Synchronized
    fun currentId(context: Context): String {
        val saved = prefs(context).getString(KEY_CURRENT, null)
        if (saved != null && file(context, saved).exists()) return saved
        val id = list(context).firstOrNull()?.id ?: createInternal(context)
        prefs(context).edit().putString(KEY_CURRENT, id).apply()
        return id
    }

    fun currentTitle(context: Context): String {
        val id = currentId(context)
        return list(context).firstOrNull { it.id == id }?.title ?: DEFAULT_TITLE
    }

    /** Новый чат. Если текущий чат пустой, нового не плодим, возвращаем его. */
    @Synchronized
    fun create(context: Context): String {
        val current = currentId(context)
        if (readChat(context, current)?.second?.isEmpty() == true) return current
        val id = createInternal(context)
        prefs(context).edit().putString(KEY_CURRENT, id).apply()
        return id
    }

    @Synchronized
    fun select(context: Context, id: String) {
        if (file(context, id).exists()) prefs(context).edit().putString(KEY_CURRENT, id).apply()
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        file(context, id).delete()
        summaryFile(context, id).delete()
        if (prefs(context).getString(KEY_CURRENT, null) == id) {
            prefs(context).edit().remove(KEY_CURRENT).apply()
        }
        currentId(context) // выберет другой чат или создаст пустой
    }

    @Synchronized
    fun rename(context: Context, id: String, title: String) {
        val (_, messages) = readChat(context, id) ?: return
        writeChat(context, id, title.trim().take(60).ifEmpty { DEFAULT_TITLE }, messages)
    }

    // ---------------------------------------------------------------- сообщения

    fun load(context: Context, chatId: String? = null): MutableList<ChatMessage> =
        readChat(context, chatId ?: currentId(context))?.second?.toMutableList() ?: mutableListOf()

    /** Дописывает сообщение. chatId нужен, если ответ приходит позже и пользователь мог переключить чат. */
    @Synchronized
    fun append(context: Context, role: String, text: String, chatId: String? = null): List<ChatMessage> {
        val id = chatId?.takeIf { file(context, it).exists() } ?: currentId(context)
        val (title, messages) = readChat(context, id) ?: (DEFAULT_TITLE to emptyList<ChatMessage>())
        val updated = (messages + ChatMessage(role, text, System.currentTimeMillis())).takeLast(MAX_MESSAGES)
        val newTitle =
            if (role == "user" && title == DEFAULT_TITLE && messages.none { it.role == "user" }) makeTitle(text) else title
        writeChat(context, id, newTitle, updated)
        return updated
    }

    @Synchronized
    fun clear(context: Context) {
        val id = currentId(context)
        writeChat(context, id, DEFAULT_TITLE, emptyList())
        summaryFile(context, id).delete()
    }

    // ---------------------------------------------------------------- сводка старых сообщений

    /** Краткое содержание ранней части чата и время последнего сообщения, которое в него вошло. */
    data class Summary(val text: String, val upTo: Long)

    private fun summaryFile(context: Context, id: String) = File(dir(context), "$id.sum")

    fun summary(context: Context, chatId: String? = null): Summary? {
        val f = summaryFile(context, chatId ?: currentId(context))
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            Summary(o.getString("text"), o.getLong("upTo"))
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun setSummary(context: Context, chatId: String, summary: Summary) {
        if (!file(context, chatId).exists()) return
        summaryFile(context, chatId).writeText(JSONObject().put("text", summary.text).put("upTo", summary.upTo).toString())
    }

    // ---------------------------------------------------------------- хранение

    private fun makeTitle(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 36) oneLine else oneLine.take(36).trimEnd() + "…"
    }

    private fun createInternal(context: Context): String {
        val id = UUID.randomUUID().toString().take(8)
        writeChat(context, id, DEFAULT_TITLE, emptyList())
        return id
    }

    private fun readChat(context: Context, id: String): Pair<String, List<ChatMessage>>? {
        val f = file(context, id)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val array = o.optJSONArray("messages") ?: JSONArray()
            val messages = (0 until array.length()).map { i ->
                val m = array.getJSONObject(i)
                ChatMessage(m.optString("role"), m.optString("text"), m.optLong("timestamp"))
            }
            o.optString("title", DEFAULT_TITLE) to messages
        } catch (e: Exception) {
            null
        }
    }

    private fun writeChat(context: Context, id: String, title: String, messages: List<ChatMessage>) {
        val array = JSONArray()
        for (m in messages) {
            array.put(JSONObject().apply {
                put("role", m.role)
                put("text", m.text)
                put("timestamp", m.timestamp)
            })
        }
        val o = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("updated", System.currentTimeMillis())
            put("messages", array)
        }
        val target = file(context, id)
        val tmp = File(target.parentFile, "$id.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(o.toString())
            tmp.delete()
        }
    }

    /** Старая единственная история становится первым чатом. */
    private fun migrateOldHistory(context: Context) {
        val old = File(context.filesDir, OLD_FILE)
        if (!old.exists()) return
        try {
            val array = JSONArray(old.readText())
            val messages = (0 until array.length()).map { i ->
                val m = array.getJSONObject(i)
                ChatMessage(m.optString("role"), m.optString("text"), m.optLong("timestamp"))
            }
            if (messages.isNotEmpty()) {
                val id = UUID.randomUUID().toString().take(8)
                val firstUser = messages.firstOrNull { it.role == "user" }?.text
                writeChat(context, id, firstUser?.let { makeTitle(it) } ?: DEFAULT_TITLE, messages)
                prefs(context).edit().putString(KEY_CURRENT, id).apply()
            }
        } catch (e: Exception) {
            // повреждённый файл просто пропускаем
        }
        old.delete()
    }
}
