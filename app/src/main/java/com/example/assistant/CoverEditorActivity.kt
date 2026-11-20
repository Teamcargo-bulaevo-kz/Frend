package com.example.assistant

import android.graphics.Bitmap
import android.graphics.Outline
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Редактор обложки песни: своя картинка из галереи, убрать обложку или вернуть как было.
 * Картинка хранится внутри приложения, файл песни не меняется.
 */
class CoverEditorActivity : AppCompatActivity() {

    companion object { const val EXTRA_TRACK_ID = "track_id" }

    private enum class Pending { NONE_CHANGE, PICK, HIDE, RESET }

    private var track: MusicTrack? = null
    private var pending = Pending.NONE_CHANGE
    private var pickedBitmap: Bitmap? = null
    private lateinit var image: ImageView
    private lateinit var info: TextView
    private lateinit var save: Button
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try { contentResolver.openInputStream(uri)?.use { CoverLoader.decodeScaled(it.readBytes(), 1000) } } catch (e: Exception) { null }
            }
            if (bmp == null) { Toast.makeText(this@CoverEditorActivity, "Не удалось открыть картинку", Toast.LENGTH_SHORT).show(); return@launch }
            pickedBitmap = bmp; pending = Pending.PICK
            image.setImageBitmap(bmp); updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        val id = intent.getLongExtra(EXTRA_TRACK_ID, -1L)
        track = MusicLibrary.index(this).tracks.firstOrNull { it.id == id } ?: PlayerState.track?.takeIf { it.id == id }
        val t = track
        if (t == null) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.harmonia_background)
            setPadding(px(24), px(16), px(24), px(24))
        }
        setContentView(root)
        root.padForSystemBars()

        root.addView(TextView(this).apply {
            text = "Обложка"; textSize = 24f; setTextColor(getColor(R.color.harmonia_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "${t.title}\n${t.artist.ifBlank { "Неизвестный исполнитель" }}"; textSize = 13f
            setTextColor(getColor(R.color.harmonia_muted)); setPadding(0, px(4), 0, px(16))
        })
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
            }
        }
        root.addView(image, LinearLayout.LayoutParams(px(240), px(240)))
        CoverLoader.load(lifecycleScope, this, t, image, 600)
        info = TextView(this).apply { textSize = 13f; setTextColor(getColor(R.color.harmonia_muted)); gravity = Gravity.CENTER; setPadding(0, px(16), 0, px(16)) }
        root.addView(info)

        fun btn(text: String, click: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; setOnClickListener { click() }
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) }) }

        btn("Выбрать из галереи") { pickImage.launch("image/*") }
        btn("Убрать обложку") {
            pending = Pending.HIDE; pickedBitmap = null
            image.setImageResource(R.drawable.cover_placeholder); updateUi()
        }
        btn("Вернуть как было") {
            pending = Pending.RESET; pickedBitmap = null
            CoverStore.reset(this, t)
            CoverLoader.clear(); CoverLoader.load(lifecycleScope, this, t, image, 600); updateUi()
        }
        save = btn("Сохранить") { commit(t) }
        btn("Закрыть") { finish() }
        updateUi()
    }

    private fun commit(t: MusicTrack) {
        when (pending) {
            Pending.PICK -> pickedBitmap?.let { CoverStore.saveBitmap(this, t, it) }
            Pending.HIDE -> CoverStore.hide(this, t)
            Pending.RESET -> CoverStore.reset(this, t)
            Pending.NONE_CHANGE -> {}
        }
        CoverLoader.clear()
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateUi() {
        val t = track ?: return
        info.text = when (pending) {
            Pending.PICK -> "Новая обложка выбрана. Нажми «Сохранить»."
            Pending.HIDE -> "Обложка будет убрана. Нажми «Сохранить»."
            Pending.RESET -> "Возвращено как было."
            Pending.NONE_CHANGE -> when (CoverStore.state(this, t)) {
                CoverStore.State.CUSTOM -> "Сейчас стоит твоя картинка."
                CoverStore.State.NONE -> "Сейчас обложка убрана."
                CoverStore.State.DEFAULT -> "Обложка берётся из файла песни или из системы."
            }
        } + "\nКартинка хранится в приложении, файл песни не меняется."
        save.isEnabled = pending == Pending.PICK || pending == Pending.HIDE
    }
}
