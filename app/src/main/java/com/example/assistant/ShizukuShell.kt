package com.example.assistant

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Какие команды разрешено отдавать консоли. Только чтение: без `;`, `&`, `|`, `>`, подстановок и переводов строки,
 * только из белого списка. Всё остальное отклоняется до отправки в Shizuku.
 */
object ShellPolicy {

    private val forbidden = Regex("""[;&|<>`$(){}\\\n\r"']""")

    private val allowed = listOf(
        Regex("""dumpsys (meminfo|battery|batterystats|usagestats|netstats|cpuinfo|diskstats|connectivity|wifi)"""),
        Regex("""dumpsys meminfo [A-Za-z0-9._]+"""),
        Regex("""dumpsys activity (top|activities|processes|recents)"""),
        Regex("""dumpsys window (displays|windows)"""),
        Regex("""ps -A( -o [A-Za-z,_%]+)?"""),
        Regex("""top -b -n 1( -m \d{1,3})?"""),
        Regex("""cmd package list packages( -3| -s| -d| -e)?"""),
        Regex("""cat /proc/(meminfo|loadavg|uptime)"""),
        Regex("""df( -h)?"""),
        Regex("""getprop ro\.build\.version\.(release|sdk)""")
    )

    fun isAllowed(command: String): Boolean {
        val c = command.trim()
        if (c.isEmpty() || c.length > 120) return false
        if (forbidden.containsMatchIn(c)) return false
        return allowed.any { it.matches(c) }
    }
}

/** Состояние консоли для экрана настроек и ответов ассистента. */
enum class ShizukuState(val text: String) {
    OFF("Выключено в настройках"),
    NOT_INSTALLED("Приложение Shizuku не установлено"),
    NOT_RUNNING("Shizuku установлен, но не запущен"),
    NO_PERMISSION("Shizuku работает, нет разрешения для Assistant"),
    READY("Работает")
}

/** Консоль с правами оболочки через Shizuku. По умолчанию выключена; выполняет только команды из [ShellPolicy]. */
object ShizukuShell : ShellBackend {

    private const val PREFS = "assistant_prefs"
    const val KEY_ENABLED = "shizuku_enabled"
    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val MAX_OUTPUT = 200_000

    override val name = "Shizuku"

    fun enabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private fun installed(context: Context) = try {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (e: Exception) { false }

    fun state(context: Context): ShizukuState {
        if (!enabled(context)) return ShizukuState.OFF
        return try {
            when {
                !Shizuku.pingBinder() ->
                    if (installed(context)) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
                else -> ShizukuState.READY
            }
        } catch (e: Throwable) {
            if (installed(context)) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        }
    }

    /** Показывает системный запрос разрешения Shizuku. Возвращает false, если запрос сейчас невозможен. */
    fun requestPermission(@Suppress("UNUSED_PARAMETER") activity: Activity): Boolean = try {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            Shizuku.requestPermission(4711)
            true
        } else false
    } catch (e: Throwable) { false }

    override fun available(context: Context) = state(context) == ShizukuState.READY

    override fun run(command: String, timeoutMs: Long): ShellResult {
        if (!ShellPolicy.isAllowed(command)) return ShellResult(false, "Команда не разрешена")
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            val argv = command.trim().split(Regex("\\s+")).toTypedArray()
            val process = method.invoke(null, argv, null, null) as Process
            val out = ByteArrayOutputStream()
            val reader = Thread {
                try {
                    val buffer = ByteArray(8192)
                    process.inputStream.use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0 || out.size() >= MAX_OUTPUT) break
                            out.write(buffer, 0, n)
                        }
                    }
                } catch (_: Exception) {}
            }
            reader.start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroy()
            reader.join(1000)
            ShellResult(finished, out.toString("UTF-8"))
        } catch (e: Throwable) {
            ShellResult(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
