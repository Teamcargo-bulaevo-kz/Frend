package com.example.assistant

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Одно уведомление, как его видит пользователь. */
data class NoticeItem(val key: String, val app: String, val pkg: String, val title: String, val text: String, val at: Long)

/**
 * Чтение уведомлений по просьбе: «что пришло», «прочитай уведомления», «что написали в Телеграме».
 * Хранит в памяти только последние 60 уведомлений, на диск ничего не пишет. Свои, постоянные (музыка, загрузки)
 * и системные пропускает. Включается в настройках доступа к уведомлениям (или через Shizuku одной кнопкой).
 */
class NotificationReader : NotificationListenerService() {

    companion object {
        private const val MAX = 60
        private val items = ArrayList<NoticeItem>()
        @Volatile var connected = false
            private set

        fun enabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, NotificationReader::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) }
        }

        fun snapshot(): List<NoticeItem> = synchronized(items) { items.toList() }

        internal fun add(item: NoticeItem) = synchronized(items) {
            items.removeAll { it.key == item.key }
            items.add(0, item)
            while (items.size > MAX) items.removeAt(items.size - 1)
        }

        internal fun remove(key: String) = synchronized(items) { items.removeAll { it.key == key } }
    }

    override fun onListenerConnected() {
        connected = true
        try { activeNotifications?.forEach { onNotificationPosted(it) } } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName || n.isOngoing || n.packageName == "android" || n.packageName == "com.android.systemui") return
        val extras = n.notification.extras
        if ((n.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.packageName, 0)).toString() } catch (e: Exception) { n.packageName }
        add(NoticeItem(n.key, app, n.packageName, title.take(120), text.take(400), n.postTime))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { remove(it) }
    }
}

/** Ответы про уведомления. Чистая часть (форматирование и фильтр) — в [NoticeText]. */
object NotificationActions {
    fun read(context: Context, app: String?): String {
        if (!NotificationReader.enabled(context)) {
            PhoneActions.launch(context, android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return "Нужен доступ к уведомлениям: открыл настройки — включи «Assistant». Или «Выдать все разрешения через Shizuku»."
        }
        return NoticeText.format(NoticeText.filter(NotificationReader.snapshot(), app), app)
    }
}

object NoticeText {
    fun filter(items: List<NoticeItem>, app: String?): List<NoticeItem> {
        val q = TextMatch.normalize(app.orEmpty())
        if (q.isEmpty()) return items
        // «в телеграме», «ватсап», «WhatsApp»: сравниваем «на слух» и без падежных окончаний
        val qk = TextMatch.key(q.removePrefix("в ").removePrefix("из "))
        val stem = if (qk.length > 6) qk.dropLast(2) else qk
        return items.filter {
            val ak = TextMatch.key(it.app)
            ak.contains(stem) || it.pkg.contains(stem) || TextMatch.similarity(ak.take(qk.length), qk) >= 0.65
        }
    }

    fun format(items: List<NoticeItem>, app: String?, limit: Int = 8, now: Long = System.currentTimeMillis()): String {
        if (items.isEmpty()) return if (app.isNullOrBlank()) "Новых уведомлений нет." else "От «$app» уведомлений нет."
        val shown = items.take(limit)
        val head = if (shown.size < items.size) "Последние ${shown.size} из ${items.size}:" else "Уведомлений: ${items.size}."
        return head + "\n" + shown.joinToString("\n") { n ->
            val min = ((now - n.at) / 60_000).toInt()
            val ago = when { min < 1 -> "только что"; min < 60 -> "$min мин назад"; else -> "${min / 60} ч назад" }
            "• ${n.app}: ${listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(" — ").take(200)} ($ago)"
        }
    }
}
