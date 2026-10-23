package com.example.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Обработка запроса вне экрана чата (голос из шторки): записывает обмен в историю,
 * чтобы он появился в чате, и выполняет запрос так же, как MainActivity.
 */
object AssistantRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var activeJob: kotlinx.coroutines.Job? = null

    @Synchronized
    fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }

    @Synchronized
    fun process(context: Context, text: String, onDone: (String) -> Unit) {
        activeJob?.cancel()
        val app = context.applicationContext
        val chatId = ChatHistory.currentId(app)
        ChatHistory.append(app, "user", text, chatId)
        activeJob = scope.launch {
            try {
                val answer = try {
                    AssistantRepository(app, BuildConfig.GROQ_API_KEY).handle(text)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FriendlyError.text(e)
                }
                ChatHistory.append(app, "assistant", answer, chatId)
                // Голосовая команда вне экрана чата: отвечаем голосом
                Speaker.speak(app, answer)
                onDone(answer)
            } finally {
                synchronized(this@AssistantRunner) {
                    if (activeJob === coroutineContext[kotlinx.coroutines.Job]) activeJob = null
                }
            }
        }
    }
}
