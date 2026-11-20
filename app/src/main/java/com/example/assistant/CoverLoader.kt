package com.example.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Обложки треков с кэшем в памяти. Порядок: своя картинка из редактора, пометка «без обложки»,
 * затем источник по настройке (система, только из файла песни или без обложек). Нет картинки: заглушка.
 */
object CoverLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val missing = HashSet<String>()
    // Одна загрузка на картинку: при переиспользовании строки списка прошлая отменяется (вызовы только с главного потока).
    private val jobs = java.util.WeakHashMap<ImageView, kotlinx.coroutines.Job>()

    fun clear() { cache.evictAll(); synchronized(missing) { missing.clear() } }

    fun load(scope: CoroutineScope, context: Context, track: MusicTrack, view: ImageView, px: Int) {
        val key = "${track.id}_${px}_${CoverStore.version}_${MusicSettings.coverSource}"
        view.tag = key
        jobs.remove(view)?.cancel()
        cache.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageResource(R.drawable.cover_placeholder)
        if (synchronized(missing) { key in missing }) return
        val app = context.applicationContext
        jobs[view] = scope.launch {
            val bmp = withContext(Dispatchers.IO) { decode(app, track, px) }
            if (bmp == null) {
                synchronized(missing) { missing.add(key) }
            } else {
                cache.put(key, bmp)
                if (view.tag == key) view.setImageBitmap(bmp)
            }
        }
    }

    private fun decode(app: Context, track: MusicTrack, px: Int): Bitmap? {
        return when (CoverStore.state(app, track)) {
            CoverStore.State.CUSTOM -> try { decodeScaled(CoverStore.fileFor(app, track).readBytes(), px) } catch (e: Exception) { null }
            CoverStore.State.NONE -> null
            CoverStore.State.DEFAULT -> when (MusicSettings.coverSource) {
                2 -> null
                1 -> embedded(app, track, px)
                // loadThumbnail появился в Android 10; на Android 8-9 берём картинку из самого файла
                else -> if (android.os.Build.VERSION.SDK_INT >= 29) {
                    try { app.contentResolver.loadThumbnail(track.uri, Size(px, px), null) } catch (e: Exception) { null }
                } else embedded(app, track, px)
            }
        }
    }

    /** Картинка, вшитая в сам файл песни (без догадок системы по соседним файлам). */
    private fun embedded(app: Context, track: MusicTrack, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(app, track.uri)
            r.embeddedPicture?.let { decodeScaled(it, px) }
        } catch (e: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }

    fun decodeScaled(bytes: ByteArray, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
