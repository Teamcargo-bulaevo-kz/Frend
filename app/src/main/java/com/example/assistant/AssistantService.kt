package com.example.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Фоновая работа: постоянное уведомление в шторке с кнопкой «Говорить».
 * Нажал, сказал команду, ассистент выполнил её, не открывая приложение.
 */
class AssistantService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.assistant.background.STOP"

        private const val CHANNEL_ID = "assistant_quick_access"
        private const val NOTIFICATION_ID = 2001
        private const val HINT = "Нажми «Говорить» и скажи команду"

        @Volatile private var running = false
        @Volatile private var lastText = HINT

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AssistantService::class.java))
            } catch (e: Exception) {
                // Android не разрешил запуск из фона — попробуем при следующем открытии приложения
            }
        }

        /** Показывает последний запрос и ответ прямо в шторке. */
        fun showResult(context: Context, request: String, answer: String) {
            if (!running) return
            lastText = "«$request»\n$answer".take(400)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, build(context))
        }

        private fun activity(context: Context, code: Int, target: Class<*>): PendingIntent =
            PendingIntent.getActivity(
                context, code, Intent(context, target),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun build(context: Context): Notification {
            val voice = activity(context, 1, VoiceActivity::class.java)
            val chat = activity(context, 2, MainActivity::class.java)
            val stop = PendingIntent.getService(
                context, 3, Intent(context, AssistantService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            fun action(icon: Int, title: String, intent: PendingIntent) =
                Notification.Action.Builder(Icon.createWithResource(context, icon), title, intent).build()

            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Ассистент")
                .setContentText(lastText.substringAfter('\n').lineSequence().first())
                .setStyle(Notification.BigTextStyle().bigText(lastText))
                .setContentIntent(voice)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(action(android.R.drawable.ic_btn_speak_now, "Говорить", voice))
                .addAction(action(android.R.drawable.ic_menu_edit, "Чат", chat))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Выкл", stop))
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Быстрый доступ к ассистенту", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
