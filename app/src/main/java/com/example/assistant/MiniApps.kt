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

    /** Встроенные мини-приложения (assets/miniapps): калькулятор, конвертеры, таймеры, пароли, жребий, список покупок… */
    fun builtIn(context: Context): List<String> = try {
        context.assets.list("miniapps").orEmpty().filter { it.endsWith(".html") }.map { it.removeSuffix(".html") }.sorted()
    } catch (e: Exception) { emptyList() }

    fun openBuiltIn(context: Context, name: String) {
        val html = try { context.assets.open("miniapps/$name.html").bufferedReader().use { it.readText() } } catch (e: Exception) { return }
        context.startActivity(Intent(context, SandboxActivity::class.java).putExtra(SandboxActivity.EXTRA_HTML, html)
            .putExtra(SandboxActivity.EXTRA_BUILTIN, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.extension.equals("html", true) }.orEmpty().sortedByDescending { it.lastModified() }

    /** «Открой мини-приложение калькулятор» / «мои мини-приложения». */
    fun command(context: Context, name: String?): String {
        val saved = list(context)
        val built = builtIn(context)
        if (name.isNullOrBlank()) {
            context.startActivity(Intent(context, SandboxActivity::class.java).putExtra(SandboxActivity.EXTRA_LIST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Встроенные: ${built.joinToString(", ")}." + if (saved.isNotEmpty()) " Твои: ${saved.take(8).joinToString(", ") { it.nameWithoutExtension }}." else ""
        }
        val key = TextMatch.normalize(name)
        fun score(title: String): Double { val t = TextMatch.normalize(title); return if (t.contains(key) || key.contains(t)) 1.0 else TextMatch.similarity(t, key) }
        val bestBuilt = built.maxByOrNull { score(it) }
        val bestSaved = saved.maxByOrNull { score(it.nameWithoutExtension) }
        val sb = bestBuilt?.let { score(it) } ?: 0.0
        val ss = bestSaved?.let { score(it.nameWithoutExtension) } ?: 0.0
        if (maxOf(sb, ss) < 0.5) return "Нет мини-приложения «$name». Есть: ${built.joinToString(", ")}. Или попроси: «сделай $name»."
        return if (ss > sb && bestSaved != null) { openFile(context, bestSaved); "Открываю «${bestSaved.nameWithoutExtension}»" }
        else { openBuiltIn(context, bestBuilt!!); "Открываю «$bestBuilt»" }
    }
}
