
package com.example.assistant

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Запомненная команда: точная фраза -> какая функция и с какими аргументами была вызвана. */
@Entity(tableName = "command_patterns")
data class CommandPatternEntity(
    @PrimaryKey val phrase: String,
    val functionName: String,
    val argumentsJson: String,
    val useCount: Int = 1,
    val lastUsed: Long = System.currentTimeMillis()
)
