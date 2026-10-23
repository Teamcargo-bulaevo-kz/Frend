package com.example.assistant

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Офлайн-модель распознавания русской речи (Vosk, около 45 МБ). В APK не вшита: скачивается один раз
 * при первом включении слушания и лежит во внутренней памяти приложения.
 */
object VoskModelStore {

    const val NAME = "vosk-model-small-ru-0.22"
    private const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"

    private fun root(context: Context) = File(context.filesDir, "vosk")
    fun dir(context: Context) = File(root(context), NAME)
    private fun marker(context: Context) = File(dir(context), ".ok")

    fun ready(context: Context) = marker(context).exists()

    /** Скачивает и распаковывает модель. onProgress получает проценты 0..100. Блокирующая, вызывать не из главного потока. */
    fun download(context: Context, onProgress: (Int) -> Unit) {
        if (ready(context)) return
        val root = root(context).apply { mkdirs() }
        val zip = File(root, "$NAME.zip.part")
        val unpack = File(root, "unpack").apply { deleteRecursively(); mkdirs() }
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url(URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("Не удалось скачать модель: код ${resp.code}")
                val body = resp.body ?: throw java.io.IOException("Пустой ответ при скачивании модели")
                val total = body.contentLength().coerceAtLeast(1L)
                var done = 0L
                var last = -1
                body.byteStream().use { input ->
                    FileOutputStream(zip).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            // Скачивание занимает 90% шкалы, остальное — распаковка
                            val p = (done * 90 / total).toInt().coerceIn(0, 90)
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            unzip(zip, unpack)
            onProgress(97)
            // Архив содержит одну папку с моделью; переносим её на постоянное место
            val inner = unpack.listFiles()?.singleOrNull { it.isDirectory } ?: unpack
            dir(context).deleteRecursively()
            if (!inner.renameTo(dir(context))) {
                inner.copyRecursively(dir(context), overwrite = true)
            }
            marker(context).writeText("ok")
            onProgress(100)
        } finally {
            zip.delete()
            unpack.deleteRecursively()
        }
    }

    private fun unzip(zip: File, target: File) {
        val base = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(target, entry.name)
                // Защита от записи за пределы папки
                if (!out.canonicalPath.startsWith(base)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it) }
                }
            }
        }
    }

    fun delete(context: Context) {
        root(context).deleteRecursively()
    }
}
