package com.example.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** В назначенное время ищет в интернете свежие данные по теме и присылает уведомление. */
class DigestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val digest = DigestActions.get(app, intent.getIntExtra("id", -1)) ?: return
        // Следующее срабатывание ставим сразу, чтобы расписание не потерялось, даже если поиск не удастся
        DigestActions.schedule(app, digest)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val text = try {
                    GroqClient(BuildConfig.GROQ_API_KEY).webSearch(digest.topic)
                } catch (e: Exception) {
                    "Не получилось найти: ${e.message?.take(120)}"
                }
                ReminderReceiver.notify(app, text, title = digest.topic)
            } finally {
                pending.finish()
            }
        }
    }
}
