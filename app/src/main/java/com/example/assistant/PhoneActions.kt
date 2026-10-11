package com.example.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore

/** Реальные действия на телефоне, которые вызывает ассистент по решению модели. */
object PhoneActions {

    /** Открывает системный экран установки будильника с заполненными полями. */
    fun setAlarm(context: Context, hour: Int, minute: Int, label: String?): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                // false — пользователь видит и подтверждает создание будильника,
                // а не чтобы он тихо создавался в фоне без его ведома
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Открываю будильник на ${"%02d".format(hour)}:${"%02d".format(minute)}"
        } catch (e: ActivityNotFoundException) {
            "На телефоне не нашлось приложения для будильников"
        }
    }

    /**
     * Ищет локальный аудиофайл по названию/исполнителю и открывает его
     * через системный плеер, если файл найден.
     */
    fun playMusic(context: Context, query: String): String {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        val selection =
            "${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?"
        val likeQuery = "%$query%"
        val selectionArgs = arrayOf(likeQuery, likeQuery)

        context.contentResolver.query(
            collection, projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                val trackUri: Uri = Uri.withAppendedPath(collection, id.toString())

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(trackUri, "audio/*")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                return try {
                    context.startActivity(intent)
                    "Включаю «$title»"
                } catch (e: ActivityNotFoundException) {
                    "Нашёл трек «$title», но на телефоне нет плеера, чтобы его открыть"
                }
            }
        }
        return "Не нашёл на телефоне ничего похожего на «$query»"
    }
}
