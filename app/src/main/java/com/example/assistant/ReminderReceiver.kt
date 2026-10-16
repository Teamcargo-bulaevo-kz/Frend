package com.example.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Срабатывает в назначенное время и показывает уведомление с текстом напоминания. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text").orEmpty().ifEmpty { "Напоминание" }
        ReminderActions.markDone(context, intent.getIntExtra("id", -1))
        notify(context, text)
    }

    companion object {
        private const val CHANNEL_ID = "assistant_reminders"

        fun notify(context: Context, text: String) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Напоминания", NotificationManager.IMPORTANCE_HIGH)
            )
            val open = PendingIntent.getActivity(
                context, 10, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Напоминание")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()
            manager.notify((System.currentTimeMillis() % 100000).toInt() + 3000, notification)
        }
    }
}
