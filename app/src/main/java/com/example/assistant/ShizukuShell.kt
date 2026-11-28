package com.example.assistant

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

/**
 * Какие команды разрешено отдавать консоли. Только чтение: без `;`, `&`, `|`, `>`, подстановок и переводов строки,
 * только из белого списка. Всё остальное отклоняется до отправки в Shizuku.
 */
object ShellPolicy {

    private val forbidden = Regex("""[;&|<>`$(){}\\\n\r"']""")

    private val allowed = listOf(
        Regex("""dumpsys (meminfo|battery|batterystats|usagestats|netstats|cpuinfo|diskstats|connectivity|wifi)"""),
        Regex("""dumpsys meminfo [A-Za-z0-9._]+"""),
        Regex("""dumpsys batterystats( --checkin| --charged)?"""),
        Regex("""dumpsys activity (top|activities|processes|recents)"""),
        Regex("""dumpsys window (displays|windows)"""),
        Regex("""ps -A( -o [A-Za-z,_%]+)?"""),
        Regex("""top -b -n 1( -m \d{1,3})?"""),
        Regex("""cmd package list packages( -3| -s| -d| -e)?"""),
        Regex("""cat /proc/(meminfo|loadavg|uptime)"""),
        Regex("""cat /sys/class/power_supply/(battery|bms)/(cycle_count|charge_full|charge_full_design|health|capacity)"""),
        Regex("""df( -h)?"""),
        Regex("""getprop ro\.build\.version\.(release|sdk)""")
    )

    /** Настройки, которые консоли можно менять (без приватных данных и без опасного: только переключатели). */
    private val settingKeys = "airplane_mode_on|location_mode|mobile_data|enabled_accessibility_services|accessibility_enabled|" +
        "screen_off_timeout|stay_on_while_plugged_in|enabled_notification_listeners|zen_mode|bluetooth_on|wifi_on|nfc_on"

    /**
     * Команды управления: переключатели (Wi-Fi, Bluetooth, данные, режим полёта, геолокация, «Не беспокоить», NFC),
     * выдача разрешений ТОЛЬКО этому приложению, закрытие приложения, кнопки. Ничего не удаляют и не ставят.
     * [own] — имя пакета этого приложения: разрешения можно выдавать только ему.
     */
    fun isControl(command: String, own: String): Boolean {
        val c = command.trim()
        if (c.isEmpty() || c.length > 400) return false
        if (forbidden.containsMatchIn(c)) return false
        val pkg = Regex.escape(own)
        val control = listOf(
            Regex("""svc (wifi|data|bluetooth|nfc) (enable|disable)"""),
            Regex("""cmd phone data (enable|disable)"""),
            Regex("""svc power stayon (true|false|usb|ac)"""),
            Regex("""cmd bluetooth_manager (enable|disable)"""),
            Regex("""cmd wifi set-wifi-enabled (enabled|disabled)"""),
            Regex("""cmd connectivity airplane-mode (enable|disable)"""),
            Regex("""cmd location set-location-enabled (true|false)"""),
            Regex("""cmd notification set_dnd (on|off|priority|alarms|none)"""),
            Regex("""cmd notification allow_listener $pkg/[A-Za-z0-9._$]+"""),
            Regex("""cmd statusbar (expand-notifications|expand-settings|collapse)"""),
            Regex("""cmd deviceidle whitelist \+$pkg"""),
            Regex("""settings get (global|secure|system) [a-z_]+"""),
            Regex("""settings put (global|secure|system) ($settingKeys) [A-Za-z0-9._/:\-]{1,300}"""),
            Regex("""pm grant $pkg android\.permission\.[A-Z_]+"""),
            Regex("""appops set $pkg [A-Z_]+ (allow|deny|default|ignore)"""),
            Regex("""am force-stop (?!android$|com\.android\.systemui$|$pkg$|com\.android\.phone$)[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+"""),
            Regex("""am broadcast -a android\.intent\.action\.AIRPLANE_MODE --ez state (true|false)"""),
            Regex("""input keyevent \d{1,3}""")
        )
        return control.any { it.matches(c) }
    }

    /** Вывод команды похож на ошибку («Unknown command», «Exception», «not found», «Permission denial»). */
    fun looksLikeError(output: String): Boolean {
        val t = output.lowercase()
        return listOf("unknown command", "exception", "not found", "permission denial", "securityexception", "usage:", "error:",
            "failed", "invalid", "no shell command implementation", "can't find service", "not allowed").any { t.contains(it) }
    }

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
    private const val MAX_OUTPUT = 2_000_000

    override val name = "Shizuku"

    fun enabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

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

    /** Пошаговая проверка для экрана настроек: что именно не так (выключено, не запущен, нет разрешения, ошибка команды). */
    fun selfTest(context: Context): String {
        val sb = StringBuilder()
        sb.append("1. Консоль включена в настройках: ").append(if (enabled(context)) "да" else "НЕТ — включи").append('\n')
        sb.append("2. Приложение Shizuku установлено: ").append(if (installed(context)) "да" else "НЕТ").append('\n')
        val running = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        sb.append("3. Shizuku запущен: ").append(if (running) "да" else "НЕТ — открой Shizuku и нажми «Запустить»").append('\n')
        if (!running) return sb.toString()
        val granted = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }
        sb.append("4. Разрешение для Assistant: ").append(if (granted) "есть" else "НЕТ — нажми «Запросить разрешение»").append('\n')
        if (!granted) return sb.toString()
        val result = run("dumpsys battery", 8000)
        sb.append("5. Тестовая команда: ")
        if (result.ok && result.output.isNotBlank()) sb.append("работает (получено ${result.output.length} символов)")
        else sb.append("ОШИБКА: ").append(result.output.take(200).ifBlank { "нет ответа" })
        return sb.toString()
    }

    override fun available(context: Context) = state(context) == ShizukuState.READY

    override fun run(command: String, timeoutMs: Long): ShellResult {
        if (!ShellPolicy.isAllowed(command)) return ShellResult(false, "Команда не разрешена")
        return exec(command, timeoutMs, strict = false)
    }

    /** Команда управления (см. [ShellPolicy.isControl]). own — имя пакета приложения. */
    fun control(command: String, own: String, timeoutMs: Long = 15_000L): ShellResult {
        if (!ShellPolicy.isControl(command, own)) return ShellResult(false, "Команда не разрешена")
        return exec(command, timeoutMs, strict = true)
    }

    /** strict — для команд управления: успех только при коде 0 и без сообщения об ошибке в выводе. */
    private fun exec(command: String, timeoutMs: Long, strict: Boolean): ShellResult {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            val argv = command.trim().split(Regex("\\s+")).toTypedArray()
            val process = method.invoke(null, argv, null, null) as Process
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            fun pump(stream: java.io.InputStream, sink: ByteArrayOutputStream) = Thread {
                try {
                    val buffer = ByteArray(8192)
                    stream.use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0 || sink.size() >= MAX_OUTPUT) break
                            sink.write(buffer, 0, n)
                        }
                    }
                } catch (_: Exception) {}
            }.also { it.start() }
            val reader = pump(process.inputStream, out)
            val errReader = pump(process.errorStream, err)
            // Вывод читается до конца потока (процесс закончился). waitFor(timeout) у удалённого процесса Shizuku
            // бросает «process hasn't exited», поэтому ждём чтением и сами опрашиваем exitValue().
            reader.join(timeoutMs)
            val timedOut = reader.isAlive
            if (timedOut) { try { process.destroy() } catch (_: Throwable) {}; reader.join(1000) }
            errReader.join(1000)
            var exit: Int? = null
            val deadline = System.currentTimeMillis() + 2000L
            while (exit == null && System.currentTimeMillis() < deadline) {
                try { exit = process.exitValue() } catch (_: IllegalThreadStateException) { Thread.sleep(50) }
            }
            val text = out.toString("UTF-8")
            val errText = err.toString("UTF-8")
            val combined = (text + (if (errText.isNotBlank()) "\n" + errText else "")).trim()
            // Ошибка: ненулевой код выхода, а если код не узнали — сообщение об ошибке в выводе
            val ok = !timedOut && if (strict) when (exit) {
                0 -> !ShellPolicy.looksLikeError(combined)
                null -> errText.isBlank() && !ShellPolicy.looksLikeError(text)
                else -> false
            } else (exit == 0 || text.isNotBlank())
            ShellResult(ok, if (timedOut && combined.isBlank()) "Команда не завершилась за ${timeoutMs / 1000} с" else combined.ifBlank { if (exit != null) "код выхода $exit" else "" })
        } catch (e: Throwable) {
            ShellResult(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
