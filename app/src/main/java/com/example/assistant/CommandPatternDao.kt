
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
}
