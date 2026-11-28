package com.example.assistant

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

/** Наглядный блок на весь экран: крупнее, можно сохранить картинкой в «Галерею» или отправить. */
object VisualDialog {

    fun show(context: Context, visual: Visual) {
        val view = VisualView(context, visual, 1.12f)
        val scroll = ScrollView(context).apply { setPadding(24, 24, 24, 24); addView(view) }
        AlertDialog.Builder(context)
            .setView(scroll)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Сохранить") { _, _ -> save(context, visual) }
            .setNegativeButton("Поделиться") { _, _ -> share(context, visual) }
            .show()
    }

    private fun render(context: Context, visual: Visual): Bitmap =
        VisualView(context, visual, 1.1f).toBitmap((context.resources.displayMetrics.widthPixels * 1.2f).toInt().coerceIn(720, 1600))

    private fun name(visual: Visual) = (visual.title.ifBlank { "схема" }.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(40).trim().ifEmpty { "схема" }) +
        " " + System.currentTimeMillis() % 100000 + ".png"

    fun save(context: Context, visual: Visual) {
        try {
            val bmp = render(context, visual)
            val fileName = name(visual)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Assistant")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("нет места")
                context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else {
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Assistant").apply { mkdirs() }
                File(dir, fileName).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            Toast.makeText(context, "Сохранено в Галерею: Pictures/Assistant", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Не получилось сохранить: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun share(context: Context, visual: Visual) {
        try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "share").apply { mkdirs() }
            val file = File(dir, name(visual))
            file.outputStream().use { render(context, visual).compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Поделиться"))
        } catch (e: Exception) {
            Toast.makeText(context, "Не получилось: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
