package com.example.assistant

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest

/**
 * Свои обложки песен. Хранятся только внутри приложения, файлы песен не меняются.
 * У песни может быть своя картинка, пометка «без обложки» или ничего (тогда работает общий источник).
 */
object CoverStore {
    enum class State { DEFAULT, CUSTOM, NONE }

    /** Растёт при каждом изменении, чтобы кэш картинок обновился. */
    @Volatile var version = 0
        private set

    private fun dir(context: Context) = File(context.filesDir, "covers").also { it.mkdirs() }

    private fun name(track: MusicTrack): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(MusicTags.trackKey(track).toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun fileFor(context: Context, track: MusicTrack) = File(dir(context), name(track) + ".jpg")
    private fun markerFor(context: Context, track: MusicTrack) = File(dir(context), name(track) + ".none")

    fun state(context: Context, track: MusicTrack): State = when {
        fileFor(context, track).exists() -> State.CUSTOM
        markerFor(context, track).exists() -> State.NONE
        else -> State.DEFAULT
    }

    fun saveBitmap(context: Context, track: MusicTrack, bmp: Bitmap) {
        markerFor(context, track).delete()
        fileFor(context, track).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        version++
    }

    fun hide(context: Context, track: MusicTrack) {
        fileFor(context, track).delete()
        markerFor(context, track).writeText("none")
        version++
    }

    fun reset(context: Context, track: MusicTrack) {
        fileFor(context, track).delete()
        markerFor(context, track).delete()
        version++
    }

    /** Удаляет все свои обложки и пометки. Возвращает, сколько было. */
    fun resetAll(context: Context): Int {
        val files = dir(context).listFiles().orEmpty()
        files.forEach { it.delete() }
        version++
        return files.size
    }
}
