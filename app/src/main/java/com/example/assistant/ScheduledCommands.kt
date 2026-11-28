package com.example.assistant

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Запланированные действия: «через 5 минут напиши Максиму в WhatsApp доброе утро», «в 23:00 выключи Wi-Fi»,
 * «каждый день в 7:30 включи мою волну», «завтра в 9 позвони маме».
 *
 * В назначенное время будильник Android (точный, переживает сон и перезагрузку) будит приложение, и команда
 * выполняется так же, как сказанная вслух. Сообщения в мессенджер отправляются сами: служба специальных
 * возможностей нажимает «Отправить» ([AutoSend]). Если телефон заблокирован паролем, экран включается;
 * команда, которой нужен экран (сообщение, приложение), ждёт разблокировки — в уведомлении кнопка «Выполнить сейчас».
 */
object ScheduledCommands {

    data class Job(val id: Int, val command: String, val at: Long, val daily: Boolean = false)

    /** Разобранная фраза: что сделать и когда. */
    data class Parsed(val command: String, val at: Long, val daily: Boolean)

    private const val PREFS = "assistant_prefs"
    private const val KEY = "scheduled_commands"
    private const val KEY_WAITING = "scheduled_waiting"
    private val fmt = SimpleDateFormat("dd.MM HH:mm", Locale("ru"))
    private val hm = SimpleDateFormat("HH:mm", Locale("ru"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ---------------------------------------------------------------- разбор фразы (чистый Kotlin, тесты)

    private val actionVerbs = listOf(
        "напиши", "написать", "отправь", "отправить", "пришли", "прислать", "скинь", "скинуть", "позвони", "позвонить", "набери", "набрать",
        "включи", "включить", "выключи", "выключить", "отключи", "отключить", "открой", "открыть", "закрой", "закрыть", "запусти", "запустить",
        "поставь", "поставить", "сделай", "сделать", "скажи", "сказать", "прочитай", "прочитать", "проверь", "проверить", "переведи", "перевести",
        "найди", "найти", "покажи", "показать", "убавь", "прибавь", "останови", "остановить", "продолжи", "выруби", "врубай", "вруби", "убери",
        "сохрани", "сохранить", "запиши", "записать", "создай", "создать", "добавь", "добавить", "очисти", "посмотри", "узнай", "расскажи",
        "поставь", "громче", "тише", "пауза", "стоп", "отсортируй", "разбери", "сфоткай", "сделай"
    )
    private val excluded = listOf("будильник", "таймер", "напомни", "напомин", "разбуди")

    private const val NUM = """(\d+(?:[.,]\d+)?)"""
    private val inRe = Regex("""(?:^|\s)через\s+(?:$NUM\s*)?(полчаса|полтора часа|полторы минуты|секунд\p{L}*|сек|минут\p{L}*|мин|час\p{L}*|ч)(?:\s+(?:и\s+)?$NUM\s*(минут\p{L}*|мин))?(?=\s|$)""")
    private val clockRe = Regex("""(?:^|\s)(каждый день|ежедневно|завтра|сегодня|послезавтра)?\s*(?:в|на)\s+(\d{1,2})(?:[:.](\d{2})|\s*(?:час\p{L}*))?(?:\s+(утра|дня|вечера|ночи))?(?=\s|$)""")

    /** Как TextMatch.normalize, но сохраняет «8:30». */
    internal fun norm(text: String): String = text.lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("(?<=\\d)[.:](?=\\d{2}(?!\\d))"), ":")
        .replace(Regex("(?<=\\d),(?=\\d)"), ".")
        .replace(Regex("[^\\p{L}\\p{N}:.]+"), " ")
        .replace(Regex("(?<!\\d)[:.]|[:.](?!\\d)"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** «через 5 минут напиши …», «напиши … через полчаса», «в 8:30 включи …», «каждый день в 7 утра …». null — не расписание. */
    fun parse(phrase: String, now: Calendar = Calendar.getInstance()): Parsed? {
        val t0 = Calc.wordsToDigits(norm(phrase))
            .replace(Regex("""\bчерез\s+минуту\b"""), "через 1 минуту").replace(Regex("""\bчерез\s+час\b"""), "через 1 час")
        var t = t0
        var explicit = false
        for (p in listOf("запланируй", "запланировать", "по расписанию", "отложи", "отложенно", "сделай отложенно")) {
            if (t.startsWith("$p ")) { t = t.removePrefix("$p ").trim(); explicit = true }
        }
        var at: Long? = null
        var daily = false
        inRe.find(t)?.let { m ->
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull()
            val unit = m.groupValues[2]
            val minutes = when {
                unit == "полчаса" -> 30.0
                unit == "полтора часа" -> 90.0
                unit == "полторы минуты" -> 1.5
                unit.startsWith("сек") -> (n ?: 1.0) / 60.0
                unit.startsWith("мин") -> n ?: 1.0
                else -> (n ?: 1.0) * 60.0
            } + (m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: 0.0)
            if (minutes <= 0 || minutes > 60 * 24 * 7) return null
            at = now.timeInMillis + (minutes * 60_000).toLong()
            t = (t.substring(0, m.range.first) + " " + t.substring(m.range.last + 1)).trim()
        }
        if (at == null) {
            val m = clockRe.find(t) ?: return null
            val day = m.groupValues[1]
            val hasMinutes = m.groupValues[3].isNotEmpty()
            val part = m.groupValues[4]
            // «в 8» без минут и уточнений годится только в начале фразы: «напиши в 2 чата» — не время
            if (!hasMinutes && part.isEmpty() && !m.value.contains("час") && day.isEmpty() && m.range.first > 0 && !explicit) return null
            if (m.value.trim().startsWith("на") && !hasMinutes && part.isEmpty() && !explicit) return null
            var h = m.groupValues[2].toInt()
            val min = m.groupValues[3].toIntOrNull() ?: 0
            if (h > 23 || min > 59) return null
            if ((part == "вечера" || part == "дня") && h < 12) h += 12
            if (part == "ночи" && h == 12) h = 0
            val cal = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            when (day) {
                "завтра" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                "послезавтра" -> cal.add(Calendar.DAY_OF_YEAR, 2)
                else -> if (cal.timeInMillis <= now.timeInMillis) {
                    // «в 3» в 14:00 — это 15:00; иначе — завтра
                    if (part.isEmpty() && !hasMinutes && h < 12 && (cal.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 12) }.timeInMillis > now.timeInMillis && day != "каждый день" && day != "ежедневно")
                        cal.add(Calendar.HOUR_OF_DAY, 12)
                    else cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            daily = day == "каждый день" || day == "ежедневно"
            at = cal.timeInMillis
            t = (t.substring(0, m.range.first) + " " + t.substring(m.range.last + 1)).trim()
        }
        val command = t.replace(Regex("\\s+"), " ").trim().removePrefix("и ").removePrefix("пусть ").trim()
        if (command.length < 3) return null
        if (excluded.any { command.contains(it) }) return null
        // «выключи музыку через 20 минут» — это таймер сна плеера, у него своя команда
        if (Regex("^(выключи|останови|выруби|отключи) (музык|песн|плеер|трек)").containsMatchIn(command)) return null
        val first = command.split(' ').first()
        if (!explicit && actionVerbs.none { first == it || (it.length >= 6 && first.startsWith(it.dropLast(1))) }) return null
        return Parsed(command, at!!, daily)
    }

    /** «что запланировано» → list, «отмени запланированное 2» → cancel/«2». null — не про расписание. */
    fun manage(phrase: String): Pair<String, String?>? {
        val words = TextMatch.normalize(phrase).split(' ').filter { it.isNotEmpty() }
        val at = words.indexOfFirst { it.startsWith("запланирован") || it.startsWith("отложенн") || it.startsWith("расписани") }
        if (at < 0) return null
        val cancel = words.any { it in setOf("отмени", "отменить", "удали", "убери", "сотри", "отмена") }
        if (cancel) {
            val rest = words.drop(at + 1).filterNot { it.startsWith("действи") || it.startsWith("команд") || it == "по" }.joinToString(" ")
            val all = words.any { it == "все" }
            return "cancel" to (rest.ifBlank { if (all) "все" else "" }.ifBlank { null })
        }
        val show = words.any { it in setOf("что", "какие", "покажи", "список", "показать", "есть", "мои") }
        return if (show && words.size <= 6) "list" to null else null
    }

    /** Команде нужен разблокированный экран: сообщения, звонки, открытие приложений. */
    fun needsScreen(command: String): Boolean {
        val t = TextMatch.normalize(command)
        return listOf("напиш", "отправ", "пришли", "скинь", "сообщ", "ватсап", "whatsapp", "телеграм", "позвон", "набер", "открой", "запуст", "покажи", "сфоткай", "экран")
            .any { t.contains(it) }
    }

    fun describeWhen(at: Long, now: Long = System.currentTimeMillis()): String {
        val min = ((at - now) / 60_000.0).let { Math.round(it).toInt() }
        val clock = hm.format(at)
        return when {
            min < 1 -> "меньше чем через минуту ($clock)"
            min < 60 -> "через $min ${FileSort.plural(min, "минуту", "минуты", "минут")} ($clock)"
            else -> {
                val today = Calendar.getInstance().apply { timeInMillis = now }
                val c = Calendar.getInstance().apply { timeInMillis = at }
                val dayWord = when (c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)) { 0 -> "сегодня"; 1 -> "завтра"; else -> fmt.format(at).substringBefore(' ') }
                "$dayWord в $clock"
            }
        }
    }

    // ---------------------------------------------------------------- хранение и будильники

    fun jobs(context: Context): List<Job> = load(context, KEY)

    fun add(context: Context, command: String, at: Long, daily: Boolean): String {
        val list = jobs(context)
        val id = (list.maxOfOrNull { it.id } ?: 7000) + 1
        val job = Job(id, command.trim(), at, daily)
        save(context, KEY, list + job)
        schedule(context, job)
        val autoNote = if (needsScreen(command) && AssistantAccessibilityService.instance == null)
            " Чтобы сообщение ушло само, включи службу специальных возможностей Assistant." else ""
        return (if (daily) "Каждый день в ${hm.format(at)}" else "Запланировал ${describeWhen(at)}") + ": «${job.command}»." + autoNote
    }

    fun schedule(context: Context, command: String, inMinutes: Double?, time: String?, date: String?, daily: Boolean): String {
        val at: Long = when {
            inMinutes != null && inMinutes > 0 -> System.currentTimeMillis() + (inMinutes * 60_000).toLong()
            !time.isNullOrBlank() -> {
                val cal = ReminderActions.parseDateTime(date, time) ?: return "Не понял время «$time». Скажи, например: в 08:30 или через 10 минут."
                if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }
            else -> return "Когда выполнить? Скажи, например: «через 5 минут» или «в 8:30»."
        }
        if (command.isBlank()) return "Что сделать в это время?"
        return add(context, command, at, daily)
    }

    fun list(context: Context): String {
        val list = jobs(context).sortedBy { it.at }
        if (list.isEmpty()) return "Запланированных действий нет. Скажи, например: «через 5 минут напиши Максиму доброе утро»."
        val items = list.mapIndexed { i, j -> Visual.Item("${i + 1}. ${j.command}", if (j.daily) "каждый день в ${hm.format(j.at)}" else describeWhen(j.at), "⏰") }
        return "Запланировано: ${list.size}. Отменить: «отмени запланированное 1»." + VisualSpec.block(VisualSpec.listJson("Запланированные действия", "list", items))
    }

    fun cancel(context: Context, query: String?): String {
        val list = jobs(context).sortedBy { it.at }
        if (list.isEmpty()) return "Запланированных действий нет."
        val q = TextMatch.normalize(query.orEmpty())
        val victims = when {
            q.isEmpty() && list.size == 1 -> list
            q.isEmpty() -> return "Какое отменить? ${list.mapIndexed { i, j -> "${i + 1}) ${j.command}" }.joinToString("; ")}"
            q == "все" || q == "всё" || q.startsWith("все ") -> list
            q.toIntOrNull() != null -> listOfNotNull(list.getOrNull(q.toInt() - 1))
            else -> list.filter { TextMatch.normalize(it.command).contains(q) }
        }
        if (victims.isEmpty()) return "Не нашёл такого среди запланированного."
        victims.forEach { cancelAlarm(context, it) }
        save(context, KEY, list.filterNot { v -> victims.any { it.id == v.id } })
        return if (victims.size == 1) "Отменил: «${victims[0].command}»." else "Отменил ${victims.size}."
    }

    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val list = jobs(context)
        val (future, missed) = list.partition { it.at > now - 60_000 || it.daily }
        val fixed = future.map { j -> if (j.daily && j.at <= now) j.copy(at = nextDay(j.at, now)) else j }
        save(context, KEY, fixed)
        fixed.forEach { schedule(context, it) }
        missed.forEach { ReminderReceiver.notify(context, "Не выполнено вовремя (телефон был выключен): «${it.command}»", "Запланированное") }
    }

    private fun nextDay(at: Long, now: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        while (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pending(context: Context, job: Job): PendingIntent = PendingIntent.getBroadcast(
        context, job.id, Intent(context, ScheduledReceiver::class.java).putExtra("id", job.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun schedule(context: Context, job: Job) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = pending(context, job)
        try {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, job.at, intent)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, job.at, intent)
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, job.at, intent)
        }
    }

    private fun cancelAlarm(context: Context, job: Job) =
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context, job))

    private fun load(context: Context, key: String): List<Job> = try {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Job(it.getInt("id"), it.getString("command"), it.getLong("at"), it.optBoolean("daily")) }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    private fun save(context: Context, key: String, list: List<Job>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("command", it.command).put("at", it.at).put("daily", it.daily)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, a.toString()).apply()
    }

    // ---------------------------------------------------------------- выполнение

    /** Сработал будильник: будим экран, выполняем или ждём разблокировки. */
    fun fire(context: Context, id: Int, done: () -> Unit) {
        val app = context.applicationContext
        val job = jobs(app).firstOrNull { it.id == id } ?: run { done(); return }
        // убираем из списка (ежедневное — переносим на завтра)
        val rest = jobs(app).filter { it.id != id }
        if (job.daily) { val next = job.copy(at = nextDay(job.at, System.currentTimeMillis())); save(app, KEY, rest + next); schedule(app, next) }
        else save(app, KEY, rest)

        scope.launch {
            try {
                val needScreen = needsScreen(job.command)
                if (needScreen) wakeScreen(app)
                if (needScreen && locked(app)) {
                    save(app, KEY_WAITING, load(app, KEY_WAITING) + job)
                    notifyWaiting(app, job)
                } else execute(app, job.command)
            } finally { done() }
        }
    }

    /** Телефон разблокировали (или нажали «Выполнить сейчас»): выполняем ждущие команды. */
    fun runWaiting(context: Context) {
        val app = context.applicationContext
        val waiting = load(app, KEY_WAITING)
        if (waiting.isEmpty()) return
        save(app, KEY_WAITING, emptyList())
        val fresh = waiting.filter { System.currentTimeMillis() - it.at < 6 * 3600_000L }
        scope.launch { fresh.forEach { execute(app, it.command); delay(1500) } }
    }

    private suspend fun execute(context: Context, command: String) {
        val app = context.applicationContext
        AutoSend.arm(if (Prefs.autoSendScheduled(app)) 45_000L else 0L)
        val answer = try {
            AssistantRepository(app, BuildConfig.GROQ_API_KEY).handle(command)
        } catch (e: Exception) { FriendlyError.text(e) } finally { AutoSend.arm(0L) }
        ChatHistory.append(app, "user", "⏰ $command")
        ChatHistory.append(app, "assistant", answer)
        ChatHistory.onChanged?.invoke()
        ReminderReceiver.notify(app, VisualSpec.plain(answer).take(400), "Выполнено по расписанию: $command".take(80))
    }

    private fun locked(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    @Suppress("DEPRECATION")
    private suspend fun wakeScreen(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "assistant:scheduled").acquire(30_000L)
        } catch (_: Exception) {}
        if (!locked(context)) return
        // Простая блокировка (свайп) снимается через Shizuku; пароль и отпечаток — нет, это решает только хозяин
        withContext(Dispatchers.IO) {
            try {
                if (ShizukuShell.available(context)) {
                    ShizukuShell.control("input keyevent 224", context.packageName, 3000)
                    ShizukuShell.control("wm dismiss-keyguard", context.packageName, 3000)
                }
            } catch (_: Exception) {}
        }
        delay(1200)
    }

    private fun notifyWaiting(context: Context, job: Job) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.createNotificationChannel(android.app.NotificationChannel("assistant_reminders", "Напоминания", android.app.NotificationManager.IMPORTANCE_HIGH))
        val run = PendingIntent.getActivity(context, job.id, Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_RUN_WAITING, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(context, "assistant_reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Пора: ${job.command}".take(80))
            .setContentText("Разблокируй телефон — выполню сам")
            .setStyle(android.app.Notification.BigTextStyle().bigText("«${job.command}». Телефон заблокирован. Как только разблокируешь, выполню сам — или нажми «Выполнить сейчас»."))
            .setContentIntent(run)
            .addAction(android.app.Notification.Action.Builder(null, "Выполнить сейчас", run).build())
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(job.id, n)
    }
}

/** Будильник запланированного действия. */
class ScheduledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        ScheduledCommands.fire(context, intent.getIntExtra("id", -1)) { try { pending.finish() } catch (_: Exception) {} }
    }
}

/** Разблокировка экрана: выполняем то, что ждало (регистрируется в AssistantService). */
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) ScheduledCommands.runWaiting(context)
    }
}

/**
 * Автоотправка: открыли чат мессенджера с готовым текстом — служба специальных возможностей сама нажимает «Отправить».
 * Включается для запланированных действий (по умолчанию) и, по желанию, для обычных просьб «напиши …».
 */
object AutoSend {
    @Volatile private var armedUntil = 0L
    @Volatile private var requested = false

    fun arm(ms: Long) { armedUntil = if (ms > 0) System.currentTimeMillis() + ms else 0L }

    fun wanted(context: Context) = System.currentTimeMillis() < armedUntil || Prefs.autoSendAlways(context)

    /** Мессенджер открыт с текстом: после ответа нажмём «Отправить». */
    fun request() { requested = true }

    fun takeRequest(): Boolean { val r = requested; requested = false; return r }

    /** Ждём чат до 10 секунд и нажимаем «Отправить». */
    suspend fun press(): Boolean {
        val deadline = System.currentTimeMillis() + 10_000L
        delay(1200)
        while (System.currentTimeMillis() < deadline) {
            val service = AssistantAccessibilityService.instance ?: return false
            val ok = withContext(Dispatchers.Main) { try { service.pressSend() } catch (_: Exception) { false } }
            if (ok) return true
            delay(500)
        }
        return false
    }
}

/** Настройки планировщика и автоотправки. */
object Prefs {
    private fun p(context: Context) = context.getSharedPreferences("assistant_prefs", Context.MODE_PRIVATE)
    fun autoSendScheduled(context: Context) = p(context).getBoolean("auto_send_scheduled", true)
    fun setAutoSendScheduled(context: Context, v: Boolean) = p(context).edit().putBoolean("auto_send_scheduled", v).apply()
    fun autoSendAlways(context: Context) = p(context).getBoolean("auto_send_always", false)
    fun setAutoSendAlways(context: Context, v: Boolean) = p(context).edit().putBoolean("auto_send_always", v).apply()
}
