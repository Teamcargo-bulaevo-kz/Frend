package com.example.assistant

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Мини-приложения: маленькие страницы с кодом (калькулятор, тренажёр, опрос, анимация), которые пишет модель.
 * Открываются в закрытой песочнице SandboxActivity: без интернета, без доступа к файлам и к телефону.
 * Понравившиеся сохраняются в «Исходящие/Мини-приложения» (или в папку приложения без доступа к файлам).
 */
object MiniApps {

    private const val FOLDER = "Мини-приложения"

    fun isApp(lang: String, code: String): Boolean =
        lang == "html" || lang == "app" || (lang.isEmpty() && code.trimStart().lowercase().let { it.startsWith("<!doctype html") || it.startsWith("<html") })

    fun dir(context: Context): File {
        val base = if (SharedFolder.hasAccess()) SharedFolder.outbox() else File(context.getExternalFilesDir(null) ?: context.filesDir, "Исходящие")
        return File(base, FOLDER).apply { mkdirs() }
    }

    /** Название из <title> или первого заголовка. */
    fun title(html: String): String =
        Regex("<title>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()?.takeIf { it.isNotBlank() }
            ?: "Мини-приложение"

    /** Неполный кусок (только script или только body) оборачиваем в страницу с удобными для телефона стилями. */
    fun wrap(code: String): String {
        val t = code.trim()
        if (t.lowercase().contains("<html")) return t
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;background:#171A2B;color:#F1F2F8;margin:16px;font-size:17px}button,input,select{font-size:17px;padding:10px;border-radius:10px;border:0;margin:4px}
button{background:#7C6CFF;color:#fff}input,select{background:#22263A;color:#fff}</style></head><body>$t</body></html>"""
    }

    fun open(context: Context, code: String) {
        context.startActivity(Intent(context, SandboxActivity::class.java).putExtra(SandboxActivity.EXTRA_HTML, wrap(code))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openFile(context: Context, file: File) {
        context.startActivity(Intent(context, SandboxActivity::class.java).putExtra(SandboxActivity.EXTRA_FILE, file.path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun save(context: Context, html: String): File {
        val safe = title(html).replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(40).trim().ifEmpty { "Мини-приложение" }
        val f = FileSort.freeName("$safe.html") { File(dir(context), it).exists() }
        return File(dir(context), f).also { it.writeText(html) }
    }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.extension.equals("html", true) }.orEmpty().sortedByDescending { it.lastModified() }

    /** «Открой мини-приложение калькулятор» / «мои мини-приложения». */
    fun command(context: Context, name: String?): String {
        val all = list(context)
        if (all.isEmpty()) return "Сохранённых мини-приложений пока нет. Попроси: «сделай калькулятор ипотеки» — и нажми «Сохранить» в нём."
        if (name.isNullOrBlank()) {
            context.startActivity(Intent(context, SandboxActivity::class.java).putExtra(SandboxActivity.EXTRA_LIST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Мои мини-приложения: ${all.size}. " + all.take(8).joinToString(", ") { it.nameWithoutExtension }
        }
        val key = TextMatch.normalize(name)
        val f = all.firstOrNull { TextMatch.normalize(it.nameWithoutExtension).contains(key) }
            ?: all.maxByOrNull { TextMatch.similarity(TextMatch.normalize(it.nameWithoutExtension), key) }!!
        openFile(context, f)
        return "Открываю «${f.nameWithoutExtension}»"
    }
}
