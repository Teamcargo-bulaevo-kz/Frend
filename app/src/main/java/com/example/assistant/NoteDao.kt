package com.example.assistant

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface NoteDao {

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    suspend fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE text LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun search(query: String): List<NoteEntity>

    @Delete
    suspend fun delete(note: NoteEntity)

    /** Правка текста заметки (схема таблицы не меняется). */
    @Update
    suspend fun update(note: NoteEntity)
}
