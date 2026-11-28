package com.example.assistant

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Порядок в файлах: разложить папку по подпапкам (по типу, месяцу или расширению), вернуть как было,
 * что занимает место, самые большие файлы, одинаковые файлы. НИЧЕГО НЕ УДАЛЯЕТ: только перемещение внутри
 * той же папки, после подтверждения, с журналом для отката. Блокирующий: вызывать с IO-потока.
 * Нужен доступ «Все файлы» (выдаётся через Shizuku или в настройках).
 */
object FileOrganizer {

    private const val UNDO_FILE = "file_moves_undo.json"

    private fun storage(): File = Environment.getExternalStorageDirectory()

    private fun needAccess(): String? =
        if (SharedFolder.hasAccess()) null else "Нужен доступ «Все файлы»: Настройки → Приложения → Assistant → Разрешения, или «Выдать все разрешения через Shizuku»."

    /** «загрузки», «документы», «скриншоты» или путь от корня памяти. null — такой папки нет или её трогать нельзя. */
    fun folderFor(name: String?): File? {
        val n = TextMatch.normalize(name.orEmpty())
        val root = storage()
        val dir = when {
            n.isEmpty() || n.startsWith("загруз") || n.startsWith("скачан") || n == "download" || n == "downloads" -> File(root, Environment.DIRECTORY_DOWNLOADS)
            n.startsWith("документ") || n == "documents" -> File(root, Environment.DIRECTORY_DOCUMENTS)
            n.startsWith("скрин") -> listOf(File(root, "Pictures/Screenshots"), File(root, "DCIM/Screenshots")).firstOrNull { it.isDirectory }
            n.startsWith("входящ") -> SharedFolder.inbox()
            n.startsWith("исходящ") -> SharedFolder.outbox()
            else -> File(root, name!!.trim().trim('/'))
        } ?: return null
        val canonical = try { dir.canonicalFile } catch (e: Exception) { return null }
        val rootPath = root.canonicalPath
        // Системные папки приложений и сам корень памяти не трогаем
        if (!canonical.path.startsWith("$rootPath/") || canonical.path.startsWith("$rootPath/Android")) return null
        return canonical.takeIf { it.isDirectory }
    }

    private fun mode(name: String?): FileSort.Mode = when (TextMatch.normalize(name.orEmpty())) {
        "date", "дата", "дате", "месяц", "месяцам", "датам" -> FileSort.Mode.DATE
        "extension", "расширение", "расширениям" -> FileSort.Mode.EXTENSION
        else -> FileSort.Mode.TYPE
    }

    /** Раскладывает папку. Без [confirmed] только показывает план и спрашивает «да/нет». */
    fun organize(context: Context, folder: String?, by: String?, confirmed: Boolean): String {
        needAccess()?.let { return it }
        val dir = folderFor(folder) ?: return "Не нашёл папку «${folder ?: "Загрузки"}» (папки в Android/ не трогаю)."
        val mode = mode(by)
        val files = dir.listFiles().orEmpty().filter { it.isFile }
            .map { FileEntry(it.name, it.length(), it.lastModified()) }
        val moves = FileSort.plan(files, mode)
        if (moves.isEmpty()) return "В «${dir.name}» раскладывать нечего."
        val how = when (mode) { FileSort.Mode.TYPE -> "по типам"; FileSort.Mode.DATE -> "по месяцам"; FileSort.Mode.EXTENSION -> "по расширениям" }
        if (!confirmed) return Confirm.ask("Разложить «${dir.name}» $how? ${FileSort.summary(moves)}. Ничего не удаляю, можно вернуть как было.")
        val log = JSONArray()
        val scanned = ArrayList<String>()
        var moved = 0
        var failed = 0
        for (m in moves) {
            val src = File(dir, m.name)
            if (!src.exists()) continue
            val target = File(dir, m.folder).apply { mkdirs() }
            val name = FileSort.freeName(m.name) { File(target, it).exists() }
            val dst = File(target, name)
            if (src.renameTo(dst)) {
                moved++
                log.put(JSONObject().put("from", src.path).put("to", dst.path))
                scanned += src.path; scanned += dst.path
            } else failed++
        }
        File(context.filesDir, UNDO_FILE).writeText(JSONObject().put("dir", dir.path).put("at", System.currentTimeMillis()).put("moves", log).toString())
        rescan(context, scanned)
        return "Разложил $moved ${FileSort.plural(moved, "файл", "файла", "файлов")} в «${dir.name}» $how." +
            (if (failed > 0) " Не получилось переместить: $failed." else "") + " Чтобы вернуть, скажи «верни файлы как было»."
    }

    /** Отменяет последнюю сортировку: файлы возвращаются на прежние места (если там свободно). */
    fun undo(context: Context, confirmed: Boolean): String {
        needAccess()?.let { return it }
        val f = File(context.filesDir, UNDO_FILE)
        if (!f.exists()) return "Нечего возвращать: сортировок ещё не было."
        val root = try { JSONObject(f.readText()) } catch (e: Exception) { return "Журнал сортировки повреждён." }
        val moves = root.optJSONArray("moves") ?: JSONArray()
        if (!confirmed) return Confirm.ask("Вернуть ${moves.length()} файлов в «${File(root.optString("dir")).name}» как было до сортировки?")
        var back = 0
        val scanned = ArrayList<String>()
        val touched = HashSet<File>()
        for (i in moves.length() - 1 downTo 0) {
            val m = moves.optJSONObject(i) ?: continue
            val from = File(m.optString("from")); val to = File(m.optString("to"))
            if (!to.exists() || from.exists()) continue
            if (to.renameTo(from)) { back++; scanned += from.path; scanned += to.path; to.parentFile?.let { touched += it } }
        }
        // Пустые подпапки, которые создала сортировка, убираем (они пустые — это не удаление файлов)
        touched.forEach { d -> if (d.isDirectory && d.listFiles().isNullOrEmpty()) d.delete() }
        f.delete()
        rescan(context, scanned)
        return "Вернул $back ${FileSort.plural(back, "файл", "файла", "файлов")} на место."
    }

    private fun rescan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try { MediaScannerConnection.scanFile(context.applicationContext, paths.distinct().toTypedArray(), null, null) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- что занимает место

    private class Scan(val byCategory: Map<String, Long>, val counts: Map<String, Int>, val biggest: List<File>, val topFolders: List<Pair<String, Long>>, val files: Int, val truncated: Boolean)

    private fun scan(root: File, limitFiles: Int = 150_000, timeMs: Long = 12_000L): Scan {
        val started = System.currentTimeMillis()
        val byCat = HashMap<String, Long>(); val counts = HashMap<String, Int>()
        val folders = HashMap<String, Long>()
        val biggest = java.util.PriorityQueue<File>(compareBy { it.length() })
        var n = 0
        var truncated = false
        val rootPath = root.path
        val stack = ArrayDeque<File>(); stack.add(root)
        while (stack.isNotEmpty()) {
            if (n >= limitFiles || System.currentTimeMillis() - started > timeMs) { truncated = true; break }
            val d = stack.removeLast()
            val list = d.listFiles() ?: continue
            for (f in list) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (f.path == "$rootPath/Android/data" || f.path == "$rootPath/Android/obb") continue
                    stack.add(f); continue
                }
                n++
                val size = f.length()
                val cat = FileSort.categoryOf(f.name)
                byCat[cat] = (byCat[cat] ?: 0L) + size
                counts[cat] = (counts[cat] ?: 0) + 1
                val top = f.path.removePrefix("$rootPath/").substringBefore('/')
                if (f.parentFile?.path != rootPath) folders[top] = (folders[top] ?: 0L) + size
                biggest.add(f); if (biggest.size > 10) biggest.poll()
            }
        }
        return Scan(byCat, counts, biggest.sortedByDescending { it.length() }, folders.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }, n, truncated)
    }

    /** Что занимает место в памяти: по типам файлов и по папкам. */
    fun storageReport(): String {
        needAccess()?.let { return it }
        val st = DeviceStatus.storage()
        val s = scan(storage())
        val cats = s.byCategory.entries.sortedByDescending { it.value }.take(8)
            .joinToString("\n") { "• ${it.key}: ${DeviceReport.bytes(it.value)} (${s.counts[it.key]} шт.)" }
        val dirs = s.topFolders.joinToString("\n") { "• ${it.first}: ${DeviceReport.bytes(it.second)}" }
        return "Память: занято ${DeviceReport.bytes(st.totalBytes - st.freeBytes)} из ${DeviceReport.bytes(st.totalBytes)}, свободно ${DeviceReport.bytes(st.freeBytes)}.\n" +
            "Файлы по типам:\n$cats\nБольше всего в папках:\n$dirs" +
            (if (s.truncated) "\n(Посчитал не всё — файлов очень много.)" else "") +
            "\nОстальное (~${DeviceReport.bytes(((st.totalBytes - st.freeBytes) - s.byCategory.values.sum()).coerceAtLeast(0))}) — приложения, их данные и сама система." +
            "\nПриложения и их данные сюда не входят: «какие приложения занимают место»." + DeviceVisuals.files(s.byCategory)
    }

    /** Самые большие файлы. */
    fun biggestFiles(limit: Int = 10): String {
        needAccess()?.let { return it }
        val root = storage().path
        val s = scan(storage())
        if (s.biggest.isEmpty()) return "Файлов не нашёл."
        return "Самые большие файлы:\n" + s.biggest.take(limit).mapIndexed { i, f ->
            "${i + 1}. ${f.name} — ${DeviceReport.bytes(f.length())} (${f.parent?.removePrefix(root)?.trimStart('/')?.ifEmpty { "корень" }})"
        }.joinToString("\n") + "\nУдалять или нет — решаешь сам: я файлы не удаляю."
    }

    /** Одинаковые файлы (больше 512 КБ): по размеру, потом по отпечатку начала и конца файла. Только отчёт. */
    fun duplicates(folder: String? = null): String {
        needAccess()?.let { return it }
        val root = if (folder.isNullOrBlank()) storage() else folderFor(folder) ?: return "Не нашёл папку «$folder»."
        val all = ArrayList<File>()
        val stack = ArrayDeque<File>(); stack.add(root)
        val started = System.currentTimeMillis()
        while (stack.isNotEmpty() && all.size < 100_000 && System.currentTimeMillis() - started < 10_000L) {
            val d = stack.removeLast()
            for (f in d.listFiles().orEmpty()) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) { if (!f.path.contains("/Android/data") && !f.path.contains("/Android/obb")) stack.add(f) }
                else if (f.length() >= 512 * 1024) all.add(f)
            }
        }
        val groups = all.groupBy { it.length() }.values.filter { it.size > 1 }
            .flatMap { same -> same.groupBy { fingerprint(it) }.values.filter { it.size > 1 } }
            .sortedByDescending { it.first().length() * (it.size - 1) }
        if (groups.isEmpty()) return "Одинаковых файлов не нашёл."
        val wasted = groups.sumOf { it.first().length() * (it.size - 1) }
        val base = storage().path
        return "Нашёл ${groups.size} групп одинаковых файлов, лишние копии занимают ${DeviceReport.bytes(wasted)}:\n" +
            groups.take(8).joinToString("\n") { g ->
                "• ${g.first().name} (${DeviceReport.bytes(g.first().length())}) × ${g.size}: " + g.take(3).joinToString(", ") { it.parent.orEmpty().removePrefix(base).trimStart('/') }
            } + "\nЯ ничего не удаляю — лишние копии можно убрать в файловом менеджере."
    }

    private fun fingerprint(f: File): String = try {
        val md = MessageDigest.getInstance("MD5")
        RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(64 * 1024)
            val n1 = r.read(buf); if (n1 > 0) md.update(buf, 0, n1)
            if (r.length() > buf.size * 2) { r.seek(r.length() - buf.size); val n2 = r.read(buf); if (n2 > 0) md.update(buf, 0, n2) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { f.path }
}
