package com.example.assistant

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Обложки треков из системы (MediaStore), с кэшем в памяти. Нет обложки — остаётся заглушка. */
object CoverLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val missing = HashSet<String>()

    fun load(scope: CoroutineScope, context: Context, track: MusicTrack, view: ImageView, px: Int) {
        val key = "${track.id}_$px"
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageResource(R.drawable.cover_placeholder)
        if (key in missing) return
        val app = context.applicationContext
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try { app.contentResolver.loadThumbnail(track.uri, Size(px, px), null) } catch (e: Exception) { null }
            }
            if (bmp == null) {
                synchronized(missing) { missing.add(key) }
            } else {
                cache.put(key, bmp)
                if (view.tag == key) view.setImageBitmap(bmp)
            }
        }
    }
}
