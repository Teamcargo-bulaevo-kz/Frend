package com.example.assistant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale

/** Действия с заметками, которые вызывает ассистент по решению модели. */
object NoteActions {

    private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    suspend fun createNote(context: Context, text: String): String {
        if (text.isBlank()) return "Текст заметки пустой — нечего сохранять"
        val dao = NoteDatabase.getInstance(context).noteDao()
        dao.insert(NoteEntity(text = text.trim()))
        return "Записал: «${text.trim()}»"
    }

    suspend fun listNotes(context: Context): String {
        val dao = NoteDatabase.getInstance(context).noteDao()
        val notes = dao.getAll()
        if (notes.isEmpty()) return "Заметок пока нет"
        return notes.joinToString("\n") { "• ${it.text} (${dateFormat.format(it.createdAt)})" }
    }

    suspend fun searchNotes(context: Context, query: String): String {
        if (query.isBlank()) return "Не указано, что искать"
        val dao = NoteDatabase.getInstance(context).noteDao()
        val notes = dao.search(query.trim())
        if (notes.isEmpty()) return "Ничего не нашлось по запросу «$query»"
        return notes.joinToString("\n") { "• ${it.text} (${dateFormat.format(it.createdAt)})" }
    }

    suspend fun deleteNote(context: Context, query: String): String {
        if (query.isBlank()) return "Не указано, какую заметку удалить"
        val dao = NoteDatabase.getInstance(context).noteDao()
        val matches = dao.search(query.trim())
        if (matches.isEmpty()) return "Не нашёл заметку по запросу «$query», ничего не удалено"
        matches.forEach { dao.delete(it) }
        return if (matches.size == 1) {
            "Удалил заметку: «${matches.first().text}»"
        } else {
            "Удалил ${matches.size} заметки(ок), подходящие под «$query»"
        }
    }
}
