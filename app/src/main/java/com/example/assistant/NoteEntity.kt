package com.example.assistant

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Одна заметка. */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)
