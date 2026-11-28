
package com.example.assistant

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CommandPatternDao {

    @Query("SELECT * FROM command_patterns WHERE phrase = :phrase LIMIT 1")
    suspend fun find(phrase: String): CommandPatternEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pattern: CommandPatternEntity)

    /** Самые частые выученные фразы — для неточного совпадения и списка «чему ты научился». */
    @Query("SELECT * FROM command_patterns ORDER BY useCount DESC, lastUsed DESC LIMIT :limit")
    suspend fun top(limit: Int): List<CommandPatternEntity>

    @Query("DELETE FROM command_patterns WHERE phrase = :phrase")
    suspend fun delete(phrase: String): Int

    @Query("DELETE FROM command_patterns")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM command_patterns")
    suspend fun count(): Int
}
