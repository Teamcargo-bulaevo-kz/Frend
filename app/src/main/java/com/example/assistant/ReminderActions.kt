package com.example.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Таймер и напоминания. Напоминания хранятся в телефоне и переживают перезагрузку. */
object ReminderActions {

    private const val PREFS = "assistant_prefs"
    private const val KEY = "reminders"

    private data class Reminder(val id: Int, val text: String, val at: Long)

    private val timeFormat = SimpleDateFormat("dd.MM HH:mm", Locale("ru"))

    // ---------------------------------------------------------------- таймер

    fun setTimer(context: Context, seconds: Int, label: String?): String {
        val s = seconds.coerceIn(1, 24 * 3600)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, s)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return if (PhoneActions.launch(context, intent)) "Таймер на ${humanDuration(s)} запущен"
        else "На телефоне не нашлось приложения с таймером"
    }

    fun humanDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            if (h > 0) "$h ч" else null,
            if (m > 0) "$m мин" else null,
            if (s > 0) "$s сек" else null
        ).joinToString(" ")
    }

    // ---------------------------------------------------------------- напоминания

    /**
     * Ставит напоминание: через inMinutes минут либо на дату (yyyy-MM-dd, по умолчанию сегодня или завтра) и время HH:mm.
     */
    fun setReminder(context: Context, text: String, inMinutes: Int?, date: String?, time: String?): String {
        val what = text.trim().ifEmpty { "Напоминание" }
        val at: Long = when {
            inMinutes != null && inMinutes > 0 -> System.currentTimeMillis() + inMinutes * 60_000L
            !time.isNullOrBlank() -> {
                val cal = parseDateTime(date, time) ?: return "Не понял время напоминания. Скажи, например: «через 2 часа» или «в 15:30»."
                if (date.isNullOrBlank() && cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }
            else -> return "Когда напомнить? Скажи, например: «через 2 часа» или «завтра в 9:00»."
        }
        if (at <= System.currentTimeMillis()) return "Это время уже прошло."

        val reminder = Reminder((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), what, at)
        save(context, load(context) + reminder)
        schedule(context, reminder)
        return "Напомню «$what» ${timeFormat.format(Date(at))}"
    }

    fun list(context: Context): String {
        val items = load(context).sortedBy { it.at }
        if (items.isEmpty()) return "Напоминаний нет."
        return "Напоминания:\n" + items.joinToString("\n") { "• ${timeFormat.format(Date(it.at))} — ${it.text}" }
    }

    /** Отменяет напоминания, в тексте которых есть query; «все» или пусто — все. */
    fun cancel(context: Context, query: String?): String {
        val all = load(context)
        if (all.isEmpty()) return "Напоминаний нет."
        val q = TextMatch.normalize(query.orEmpty())
        val remove = if (q.isEmpty() || q == "все" || q == "всё") all
        else all.filter { TextMatch.normalize(it.text).contains(q) }
        if (remove.isEmpty()) return "Не нашёл напоминание «$query»."
        remove.forEach { cancelAlarm(context, it) }
        save(context, all - remove.toSet())
        return "Отменил напоминаний: ${remove.size}"
    }

    /** После перезагрузки: заново ставит будущие, о пропущенных сообщает сразу. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val (future, missed) = load(context).partition { it.at > now }
        save(context, future)
        future.forEach { schedule(context, it) }
        missed.forEach { ReminderReceiver.notify(context, "Пропущено: ${it.text}") }
    }

    /** Вызывается из ReminderReceiver, когда напоминание сработало. */
    fun markDone(context: Context, id: Int) = save(context, load(context).filter { it.id != id })

    private fun pending(context: Context, r: Reminder): PendingIntent =
        PendingIntent.getBroadcast(
            context, r.id,
            Intent(context, ReminderReceiver::class.java)
                .putExtra("id", r.id).putExtra("text", r.text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun schedule(context: Context, r: Reminder) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = pending(context, r)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, intent)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, intent)
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, intent)
        }
    }

    private fun cancelAlarm(context: Context, r: Reminder) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context, r))
    }

    // ---------------------------------------------------------------- хранение

    private fun load(context: Context): List<Reminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Reminder(o.getInt("id"), o.getString("text"), o.getLong("at"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(context: Context, items: List<Reminder>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    // ---------------------------------------------------------------- разбор даты и времени

    /** date: yyyy-MM-dd или пусто (сегодня), time: HH:mm. */
    internal fun parseDateTime(date: String?, time: String?): Calendar? {
        val t = Regex("^(\\d{1,2})[:.](\\d{2})$").matchEntire(time?.trim().orEmpty()) ?: return null
        val cal = Calendar.getInstance()
        if (!date.isNullOrBlank()) {
            val d = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(date.trim()) ?: return null
            cal.set(d.groupValues[1].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[3].toInt())
        }
        val h = t.groupValues[1].toInt()
        val m = t.groupValues[2].toInt()
        if (h > 23 || m > 59) return null
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }
}
