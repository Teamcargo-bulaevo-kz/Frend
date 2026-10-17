package com.example.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Автообновление: «присылай каждое утро в 8 курс доллара и погоду». В это время ассистент сам ищет
 * свежую информацию в интернете и присылает уведомлением. Расписание переживает перезагрузку.
 */
object DigestActions {

    private const val PREFS = "assistant_prefs"
    private const val KEY = "digests"

    data class Digest(val id: Int, val topic: String, val hour: Int, val minute: Int)

    fun add(context: Context, topic: String, time: String?): String {
        val what = topic.trim()
        if (what.isEmpty()) return "О чём присылать сводку?"
        val t = Regex("^(\\d{1,2})[:.](\\d{2})$").matchEntire(time?.trim().orEmpty())
            ?: return "Во сколько присылать? Скажи, например: «каждое утро в 8:00»."
        val h = t.groupValues[1].toInt()
        val m = t.groupValues[2].toInt()
        if (h > 23 || m > 59) return "Не понял время."
        val digest = Digest((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), what, h, m)
        save(context, load(context) + digest)
        schedule(context, digest)
        return "Буду присылать «$what» каждый день в %02d:%02d".format(h, m)
    }

    fun list(context: Context): String {
        val items = load(context)
        if (items.isEmpty()) return "Регулярных сводок нет."
        return "Регулярные сводки:\n" + items.joinToString("\n") { "• %02d:%02d — %s".format(it.hour, it.minute, it.topic) }
    }

    fun cancel(context: Context, query: String?): String {
        val all = load(context)
        if (all.isEmpty()) return "Регулярных сводок нет."
        val q = TextMatch.normalize(query.orEmpty())
        val remove = if (q.isEmpty() || q == "все" || q == "всё") all else all.filter { TextMatch.normalize(it.topic).contains(q) }
        if (remove.isEmpty()) return "Не нашёл сводку «$query»."
        remove.forEach { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context, it)) }
        save(context, all - remove.toSet())
        return "Отменил сводок: ${remove.size}"
    }

    fun get(context: Context, id: Int): Digest? = load(context).firstOrNull { it.id == id }

    /** Ставит ближайшее срабатывание (сегодня или завтра). После каждого срабатывания ставится следующее. */
    fun schedule(context: Context, d: Digest) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, d.hour); set(Calendar.MINUTE, d.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 1000) add(Calendar.DAY_OF_YEAR, 1)
        }
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = pending(context, d)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, intent)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, intent)
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, intent)
        }
    }

    fun rescheduleAll(context: Context) = load(context).forEach { schedule(context, it) }

    private fun pending(context: Context, d: Digest): PendingIntent =
        PendingIntent.getBroadcast(
            context, d.id,
            Intent(context, DigestReceiver::class.java).putExtra("id", d.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun load(context: Context): List<Digest> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Digest(o.getInt("id"), o.getString("topic"), o.getInt("hour"), o.getInt("minute"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(context: Context, items: List<Digest>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("topic", it.topic).put("hour", it.hour).put("minute", it.minute)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }
}
