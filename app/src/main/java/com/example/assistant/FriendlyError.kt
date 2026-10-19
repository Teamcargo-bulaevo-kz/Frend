package com.example.assistant

/** Понятные сообщения вместо «Groq API error 429: {...}». Чистый Kotlin, есть тесты. */
object FriendlyError {

    fun text(e: Throwable): String = fromMessage(e.message.orEmpty(), e)

    internal fun fromMessage(message: String, e: Throwable? = null): String {
        val m = message.lowercase()
        return when {
            "429" in m && ("per day" in m || "(tpd)" in m || "(rpd)" in m) ->
                "Дневной лимит ИИ закончился. Простые команды (музыка, фонарик, будильник, Wi-Fi) работают и без него, а вопросы к ИИ — позже."
            "429" in m || "rate limit" in m -> {
                val wait = MusicTagger.retryDelayMs(message)?.let { (it / 1000.0).toInt() + 1 }
                "ИИ сейчас занят: лимит запросов в минуту." + (wait?.let { " Повтори примерно через $it с." } ?: " Повтори через минуту.")
            }
            "401" in m || "403" in m -> "ИИ не принял ключ доступа. Нужно проверить ключ в настройках сборки."
            "timeout" in m || e is java.net.SocketTimeoutException -> "ИИ долго не отвечает. Попробуй ещё раз."
            e is java.net.UnknownHostException || e is java.net.ConnectException || "unable to resolve" in m ->
                "Нет интернета. Команды без ИИ (музыка, фонарик, будильник) работают и так."
            else -> "Не получилось: ${message.take(120).ifBlank { "неизвестная ошибка" }}"
        }
    }
}
