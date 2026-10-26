package com.example.assistant

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Полноэкранный плеер: обложка, перемотка, режимы, очередь, «ещё такое», таймер сна. */
class PlayerActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppSettings.wrap(newBase))
    }

    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var shownTrackId = -1L

    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var tags: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeTotal: TextView
    private lateinit var play: ImageButton
    private lateinit var shuffle: ImageButton
    private lateinit var repeat: ImageButton
    private lateinit var likeButton: ImageButton
    private lateinit var dislikeButton: ImageButton

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        cover = findViewById(R.id.cover)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        tags = findViewById(R.id.tags)
        seek = findViewById(R.id.seek)
        timeNow = findViewById(R.id.timeNow)
        timeTotal = findViewById(R.id.timeTotal)
        play = findViewById(R.id.play)
        shuffle = findViewById(R.id.shuffle)
        repeat = findViewById(R.id.repeat)

        // Обложка квадратная и по центру: размер по меньшей стороне свободного места
        val coverBox = findViewById<android.view.View>(R.id.coverBox)
        coverBox.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val side = minOf(r - l, b - t)
            val lp = cover.layoutParams as android.widget.FrameLayout.LayoutParams
            if (side > 0 && (lp.width != side || lp.height != side)) {
                lp.width = side; lp.height = side; lp.gravity = android.view.Gravity.CENTER
                cover.layoutParams = lp
            }
        }
        play.setOnClickListener { MusicService.instance?.togglePlay() }
        findViewById<ImageButton>(R.id.next).setOnClickListener { MusicService.instance?.skipNext() }
        findViewById<ImageButton>(R.id.prev).setOnClickListener { MusicService.instance?.skipPrevious() }
        shuffle.setOnClickListener { MusicService.instance?.toggleShuffle() }
        repeat.setOnClickListener { MusicService.instance?.cycleRepeat() }
        likeButton = findViewById(R.id.likeButton)
        dislikeButton = findViewById(R.id.dislikeButton)
        likeButton.setOnClickListener { rate(1) }
        dislikeButton.setOnClickListener { rate(-1) }
        findViewById<ImageButton>(R.id.moreButton).setOnClickListener { showMore(it) }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeNow.text = PlayerState.formatTime(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                userSeeking = false
                MusicService.instance?.seekTo(bar.progress)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        shownTrackId = -1L // характеристики могли поменять в редакторе
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        val track = PlayerState.track
        if (track == null || !MusicService.isRunning) {
            finish()
            return
        }
        if (track.id != shownTrackId) {
            shownTrackId = track.id
            title.text = track.title
            artist.text = track.artist.ifBlank { "Неизвестный исполнитель" }
            CoverLoader.load(lifecycleScope, this, track, cover, 800)
            val tg = MusicTags.get(this, track)
            tags.text = tg?.takeIf { MusicTags.isTagged(it) }?.let {
                "${it.moods.joinToString(", ") { m -> MusicTags.moodShort[m] ?: m }} · ${it.genreText} · энергия ${it.energy}/5"
            }.orEmpty()
        }
        showRating(MusicTags.likedOf(this, track))
        play.setImageResource(if (PlayerState.playing) R.drawable.ic_pause else R.drawable.ic_play)
        shuffle.alpha = if (PlayerState.shuffle) 1f else 0.4f
        repeat.alpha = if (PlayerState.repeat == PlayerState.REPEAT_OFF) 0.4f else 1f
        repeat.contentDescription = when (PlayerState.repeat) {
            PlayerState.REPEAT_ONE -> "Повтор одного трека"
            PlayerState.REPEAT_ALL -> "Повтор очереди"
            else -> "Повтор выключен"
        }
        val duration = PlayerState.durationMs
        seek.max = if (duration > 0) duration else 1
        if (!userSeeking) {
            seek.progress = PlayerState.position()
            timeNow.text = PlayerState.formatTime(PlayerState.position())
        }
        timeTotal.text = PlayerState.formatTime(duration)
    }

    /** Нажатие на ♥ или 👎: повторное нажатие снимает оценку. */
    private fun rate(value: Int) {
        val track = PlayerState.track ?: return
        val now = MusicTags.likedOf(this, track)
        val new = if (now == value) 0 else value
        MusicTags.setLiked(this, track, new)
        showRating(new)
    }

    private fun showRating(liked: Int) {
        likeButton.setImageResource(if (liked > 0) R.drawable.ic_heart_filled else R.drawable.ic_heart)
        likeButton.imageTintList = android.content.res.ColorStateList.valueOf(getColor(if (liked > 0) R.color.music_accent else R.color.music_text))
        dislikeButton.imageTintList = android.content.res.ColorStateList.valueOf(getColor(if (liked < 0) R.color.music_accent else R.color.music_text_dim))
        dislikeButton.alpha = if (liked < 0) 1f else 0.7f
    }

    /** Всё остальное спрятано в меню ⋮, чтобы внизу не было лишних кнопок. */
    private fun showMore(anchor: android.view.View) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Характеристики")
        menu.menu.add(0, 2, 1, "Очередь")
        menu.menu.add(0, 3, 2, "Ещё такое")
        menu.menu.add(0, 4, 3, "Таймер сна")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> PlayerState.track?.let {
                    startActivity(Intent(this, TrackEditActivity::class.java).putExtra(TrackEditActivity.EXTRA_TRACK_ID, it.id))
                }
                2 -> showQueue()
                3 -> playSimilar()
                4 -> showSleepTimer()
            }
            true
        }
        menu.show()
    }

    private fun showQueue() {
        val queue = PlayerState.queue
        if (queue.isEmpty()) return
        val current = PlayerState.index
        val labels = queue.mapIndexed { i, t ->
            (if (i == current) "▶ " else "${i + 1}. ") + t.title + (if (t.artist.isBlank()) "" else " — ${t.artist}")
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Очередь (${queue.size})")
            .setItems(labels) { _, which -> MusicService.instance?.playIndex(which) }
            .setNegativeButton("Закрыть", null).show()
    }

    /** «Ещё такое»: вперемешку треки с тем же настроением (или жанром), что у текущего. */
    private fun playSimilar() {
        val track = PlayerState.track ?: return
        val tg = MusicTags.get(this, track)
        if (tg == null || !MusicTags.isTagged(tg)) {
            Toast.makeText(this, "Эта песня ещё не размечена. Разметь музыку в приложении «Музыка».", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val similar = withContext(Dispatchers.IO) {
                val music = MusicLibrary.index(this@PlayerActivity).allMusic()
                val byMood = MusicTags.filter(this@PlayerActivity, music, tg.mood, null)
                (if (byMood.size >= 5) byMood else MusicTags.filter(this@PlayerActivity, music, null, tg.genre))
                    .filter { it.id != track.id }.shuffled().take(200)
            }
            if (similar.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "Похожих песен не нашёл", Toast.LENGTH_SHORT).show()
                return@launch
            }
            MusicQueue.tracks = listOf(track) + similar
            ContextCompat.startForegroundService(
                this@PlayerActivity, Intent(this@PlayerActivity, MusicService::class.java).setAction(MusicService.ACTION_PLAY)
            )
            Toast.makeText(this@PlayerActivity, "Дальше похожие: ${MusicTags.moods[tg.mood]?.lowercase()}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSleepTimer() {
        val options = arrayOf("Выключить", "15 минут", "30 минут", "45 минут", "1 час", "2 часа")
        val minutes = intArrayOf(0, 15, 30, 45, 60, 120)
        AlertDialog.Builder(this).setTitle("Таймер сна")
            .setItems(options) { _, which ->
                MusicService.instance?.setSleepTimer(minutes[which])
                Toast.makeText(
                    this, if (minutes[which] == 0) "Таймер выключен" else "Музыка остановится через ${options[which]}", Toast.LENGTH_SHORT
                ).show()
            }.show()
    }
}
