package com.example.assistant

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Все тексты ошибок для пользователя в одном месте. Чистый Kotlin, есть тесты. */
object FriendlyError {

    const val RATE_LIMIT = "Лимит запросов, попробуй через минуту."
    const val DAILY_LIMIT = "Дневной лимит ИИ закончился. Простые команды (музыка, фонарик, будильник, Wi-Fi) работают и без него, а вопросы к ИИ — позже."
    const val NO_INTERNET = "Нет интернета. Команды без ИИ (музыка, фонарик, будильник) работают и так."
    const val TIMEOUT = "ИИ долго не отвечает. Попробуй ещё раз."
    const val UNAUTHORIZED = "Сервер ассистента не принял доступ. Нужно проверить APP_TOKEN в сборке и в Worker."
    const val NOT_CONFIGURED = "Сервер ассистента не настроен: в сборке нет WORKER_URL или APP_TOKEN."
    const val TOO_BIG = "Запрос слишком большой."
    const val SERVER = "Сервер ИИ сейчас недоступен. Попробуй позже."

    fun text(e: Throwable): String = when {
        e is NotConfiguredException -> NOT_CONFIGURED
        e is GroqApiException -> fromCode(e.code, e.message.orEmpty(), e.retryAfterSec)
        e is SocketTimeoutException -> TIMEOUT
        e is UnknownHostException || e is ConnectException -> NO_INTERNET
        e is IOException -> NO_INTERNET
        else -> fromMessage(e.message.orEmpty())
    }

    private fun fromCode(code: Int, message: String, retryAfterSec: Long?): String {
        val m = message.lowercase()
        return when {
            code == 401 || code == 403 -> UNAUTHORIZED
            code == 413 -> TOO_BIG
            code == 429 && ("per day" in m || "(tpd)" in m || "(rpd)" in m) -> DAILY_LIMIT
            code == 429 -> if (retryAfterSec != null && retryAfterSec in 1..60) "Лимит запросов, попробуй через $retryAfterSec с." else RATE_LIMIT
            code >= 500 -> SERVER
            else -> "Не получилось: ${message.take(120).ifBlank { "ошибка $code" }}"
        }
    }

    internal fun fromMessage(message: String): String {
        val m = message.lowercase()
        return when {
            "429" in m && ("per day" in m || "(tpd)" in m || "(rpd)" in m) -> DAILY_LIMIT
            "429" in m || "rate limit" in m || "rate_limited" in m -> RATE_LIMIT
            "unable to resolve" in m -> NO_INTERNET
            "timeout" in m || "timed out" in m -> TIMEOUT
            else -> "Не получилось: ${message.take(120).ifBlank { "неизвестная ошибка" }}"
        }
    }
}
