package com.example.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Обработка запроса вне экрана чата (голос из шторки): записывает обмен в историю,
 * чтобы он появился в чате, и выполняет запрос так же, как MainActivity.
 */
object AssistantRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun process(context: Context, text: String, onDone: (String) -> Unit) {
        val app = context.applicationContext
        ChatHistory.append(app, "user", text)
        scope.launch {
            val answer = try {
                AssistantRepository(app, BuildConfig.GROQ_API_KEY).handle(text)
            } catch (e: Exception) {
                "Ошибка: ${e.message}"
            }
            ChatHistory.append(app, "assistant", answer)
            onDone(answer)
        }
    }
}
