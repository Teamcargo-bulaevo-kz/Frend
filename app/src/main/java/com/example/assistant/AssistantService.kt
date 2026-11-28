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
        const val ACTION_TRANSLATE = "com.example.assistant.background.TRANSLATE"
        const val ACTION_ASK_SCREEN = "com.example.assistant.background.ASK_SCREEN"

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
            lastText = "«$request»\n${VisualSpec.plain(answer)}".take(400)
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
            fun service(code: Int, action: String) = PendingIntent.getService(
                context, code, Intent(context, AssistantService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            fun action(icon: Int, title: String, intent: PendingIntent) =
                Notification.Action.Builder(Icon.createWithResource(context, icon), title, intent).build()

            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Ассистент")
                .setContentText(lastText.substringAfter('\n').lineSequence().first())
                .setStyle(Notification.BigTextStyle().bigText(lastText))
                .setContentIntent(chat)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(action(android.R.drawable.ic_btn_speak_now, "Говорить", voice))
                // Перевод и вопрос про экран — в один тап из шторки (выключить уведомление: Настройки → «Кнопка ассистента в шторке»)
                .addAction(action(android.R.drawable.ic_menu_sort_alphabetically, "Перевести", service(4, ACTION_TRANSLATE)))
                .addAction(action(android.R.drawable.ic_menu_view, "Экран", service(5, ACTION_ASK_SCREEN)))
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Разблокировка экрана: запланированные сообщения, ждавшие разблокировки, уходят сразу. */
    private var unlock: UnlockReceiver? = null

    override fun onCreate() {
        super.onCreate()
        try {
            unlock = UnlockReceiver().also { registerReceiver(it, android.content.IntentFilter(Intent.ACTION_USER_PRESENT)) }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRANSLATE || intent?.action == ACTION_ASK_SCREEN) {
            // Закрываем шторку и работаем с приложением под ней
            AssistantAccessibilityService.instance?.globalAction("dismiss_shade")
            if (intent.action == ACTION_TRANSLATE) ScreenQuick.translate(this, 900L) else ScreenQuick.ask(this, 900L)
        }
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
        unlock?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        unlock = null
        super.onDestroy()
    }
}
