package com.example.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** События календаря. Блокирующие: вызывать с IO-потока. */
object CalendarActions {

    private fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Добавляет событие сразу в календарь телефона; без разрешения открывает экран создания события. */
    fun add(context: Context, title: String, date: String?, time: String?, durationMinutes: Int?, description: String?): String {
        val name = title.trim().ifEmpty { "Событие" }
        val allDay = time.isNullOrBlank()
        val start = ReminderActions.parseDateTime(date, time ?: "00:00")
            ?: return "Не понял дату или время. Скажи, например: «в пятницу в 10:00»."
        val minutes = (durationMinutes ?: 60).coerceIn(5, 24 * 60)
        val startMs = start.timeInMillis
        val endMs = if (allDay) startMs + 24 * 3600_000L else startMs + minutes * 60_000L
        val whenText = SimpleDateFormat(if (allDay) "d MMMM" else "d MMMM, HH:mm", Locale("ru")).format(start.time)

        if (!has(context, Manifest.permission.WRITE_CALENDAR) || !has(context, Manifest.permission.READ_CALENDAR)) {
            val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, name)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            if (!description.isNullOrBlank()) intent.putExtra(CalendarContract.Events.DESCRIPTION, description)
            return if (PhoneActions.launch(context, intent)) "Открыл создание события «$name» на $whenText, нажми «Сохранить»"
            else "Нет приложения календаря"
        }

        val calendarId = writableCalendarId(context) ?: return "Не нашёл календарь для записи. Открой приложение «Календарь» и войди в аккаунт."
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, name)
            if (!description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, description)
            if (allDay) {
                // Событие на весь день в календаре хранится в UTC
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear(); set(start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH))
                }
                put(CalendarContract.Events.DTSTART, utc.timeInMillis)
                put(CalendarContract.Events.DTEND, utc.timeInMillis + 24 * 3600_000L)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return "Календарь не принял событие."
        if (!allDay) {
            try {
                context.contentResolver.insert(
                    CalendarContract.Reminders.CONTENT_URI,
                    ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
                        put(CalendarContract.Reminders.MINUTES, 15)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    }
                )
            } catch (e: Exception) {
                // событие уже создано, просто без напоминания
            }
        }
        return "Добавил в календарь: «$name», $whenText" + if (allDay) "" else " (за 15 минут напомнит)"
    }

    private fun writableCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        var best: Long? = null
        var bestPrimary = false
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || c.getInt(3) == 0) continue
                val primary = c.getInt(1) == 1
                if (best == null || (primary && !bestPrimary)) {
                    best = c.getLong(0)
                    bestPrimary = primary
                }
            }
        }
        return best
    }

    /** События на days дней начиная с date (yyyy-MM-dd, по умолчанию сегодня). */
    fun list(context: Context, date: String?, days: Int?): String {
        if (!has(context, Manifest.permission.READ_CALENDAR)) {
            return "Нужен доступ к календарю. Открой приложение и разреши его."
        }
        val from = (ReminderActions.parseDateTime(date, "00:00") ?: Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        })
        val count = (days ?: 1).coerceIn(1, 31)
        val startMs = from.timeInMillis
        val endMs = startMs + count * 24 * 3600_000L

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)
        val lines = ArrayList<String>()
        val timeFmt = SimpleDateFormat("HH:mm", Locale("ru"))
        val dayFmt = SimpleDateFormat("EEE d MMM", Locale("ru"))
        context.contentResolver.query(
            builder.build(),
            arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION
            ),
            null, null, CalendarContract.Instances.BEGIN + " ASC"
        )?.use { c ->
            while (c.moveToNext() && lines.size < 40) {
                val title = c.getString(0)?.ifBlank { null } ?: "Без названия"
                val begin = c.getLong(1)
                val end = c.getLong(2)
                val allDay = c.getInt(3) == 1
                val place = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                val prefix = if (count > 1) {
                    val f = SimpleDateFormat("EEE d MMM", Locale("ru"))
                    if (allDay) f.timeZone = TimeZone.getTimeZone("UTC")
                    f.format(java.util.Date(begin)) + " "
                } else ""
                val whenText = if (allDay) "весь день" else "${timeFmt.format(java.util.Date(begin))}–${timeFmt.format(java.util.Date(end))}"
                lines.add("$prefix$whenText  $title$place")
            }
        }
        val label = if (count == 1) dayFmt.format(from.time) else "${dayFmt.format(from.time)} + ${count - 1} дн."
        return if (lines.isEmpty()) "На $label событий нет." else "События ($label):\n" + lines.joinToString("\n")
    }
}
