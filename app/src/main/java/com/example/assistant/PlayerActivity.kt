package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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

    private lateinit var aura: AuraView
    private lateinit var spectrum: SpectrumGlowView
    private lateinit var moodProfile: MoodProfileView
    private lateinit var tags: TextView
    private lateinit var shuffle: ImageButton
    private lateinit var repeat: ImageButton
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var shownTrackId = -1L

    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeTotal: TextView
    private lateinit var play: ImageButton
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
        edgeToEdge()
        setContentView(R.layout.activity_player)
        findViewById<View>(R.id.content).padForSystemBars()
        aura = findViewById(R.id.aura)
        spectrum = findViewById(R.id.spectrum)
        moodProfile = findViewById(R.id.moodProfile)
        tags = findViewById(R.id.tags)
        shuffle = findViewById(R.id.shuffle)
        repeat = findViewById(R.id.repeat)
        shuffle.setOnClickListener { pulse(it); MusicService.instance?.toggleShuffle() }
        repeat.setOnClickListener { pulse(it); MusicService.instance?.cycleRepeat() }
        cover = findViewById(R.id.cover)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        seek = findViewById(R.id.seek)
        timeNow = findViewById(R.id.timeNow)
        timeTotal = findViewById(R.id.timeTotal)
        play = findViewById(R.id.play)

        // Обложка небольшая и по центру, вокруг много воздуха
        val coverBox = findViewById<android.view.View>(R.id.coverBox)
        coverBox.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val side = (minOf(r - l - v.paddingLeft - v.paddingRight, b - t) * MusicSettings.coverFactor).toInt().coerceAtMost((240 * resources.displayMetrics.density).toInt())
            aura.centerYPx = (t + b) / 2f
            aura.coverRadiusPx = (minOf(r - l - v.paddingLeft - v.paddingRight, b - t) * MusicSettings.coverFactor).coerceAtMost(240 * resources.displayMetrics.density) / 2f
            val lp = cover.layoutParams as android.widget.FrameLayout.LayoutParams
            if (side > 0 && (lp.width != side || lp.height != side)) {
                lp.width = side; lp.height = side; lp.gravity = android.view.Gravity.CENTER
                cover.layoutParams = lp
            }
        }
        play.setOnClickListener { pulse(it); MusicService.instance?.togglePlay() }
        findViewById<ImageButton>(R.id.next).setOnClickListener { pulse(it); MusicService.instance?.skipNext() }
        findViewById<ImageButton>(R.id.prev).setOnClickListener { pulse(it); MusicService.instance?.skipPrevious() }
        likeButton = findViewById(R.id.likeButton)
        dislikeButton = findViewById(R.id.dislikeButton)
        likeButton.setOnClickListener { pulse(it); rate(1) }
        dislikeButton.setOnClickListener { pulse(it); rate(-1) }
        findViewById<ImageButton>(R.id.moreButton).setOnClickListener { pulse(it); showMore(it) }

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
        applySettings()
        // Спектр приходит от общего анализатора звука; разрешение просит главный экран с пояснением
        if ((MusicSettings.auraEnabled || MusicSettings.debugView) && !BeatSource.isLive && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            BeatSource.attach(MusicService.instance?.audioSessionId ?: 0)
        }
        BeatSource.fftListener = if (MusicSettings.debugView) { data -> spectrum.setFftData(data) } else null
        handler.post(ticker)
    }

    /** Применяет настройки вида: размеры, видимость элементов, отладочный вид. */
    private fun applySettings() {
        val debug = MusicSettings.debugView
        aura.visibility = if (MusicSettings.auraEnabled) View.VISIBLE else View.INVISIBLE
        title.textSize = MusicSettings.titleSp
        artist.textSize = MusicSettings.artistSp
        findViewById<View>(R.id.seek).visibility = if (MusicSettings.showSeek) View.VISIBLE else View.GONE
        findViewById<View>(R.id.timeRow).visibility = if (MusicSettings.showSeek) View.VISIBLE else View.GONE
        likeButton.visibility = if (MusicSettings.showRating) View.VISIBLE else View.GONE
        dislikeButton.visibility = if (MusicSettings.showRating) View.VISIBLE else View.GONE
        findViewById<View>(R.id.buttonRow).apply { scaleX = MusicSettings.buttonScale; scaleY = MusicSettings.buttonScale }
        spectrum.visibility = if (debug) View.VISIBLE else View.GONE
        tags.visibility = if (debug) View.VISIBLE else View.GONE
        findViewById<View>(R.id.debugButtons).visibility = if (debug) View.VISIBLE else View.GONE
        if (!debug) moodProfile.visibility = View.GONE
        findViewById<View>(R.id.coverBox).requestLayout()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.player_pop_enter, R.anim.player_pop_exit)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        BeatSource.fftListener = null
        spectrum.setFftData(null)
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
            title.animate().cancel()
            artist.animate().cancel()
            cover.animate().cancel()
            title.alpha = 0f
            artist.alpha = 0f
            title.translationY = 8f
            artist.translationY = 5f
            cover.alpha = 0.72f
            cover.scaleX = 0.97f
            cover.scaleY = 0.97f
            title.text = track.title
            artist.text = track.artist.ifBlank { "Неизвестный исполнитель" }
            title.animate().alpha(1f).translationY(0f).setDuration(240).start()
            artist.animate().alpha(1f).translationY(0f).setDuration(300).start()
            cover.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            CoverLoader.load(lifecycleScope, this, track, cover, 800)
            val tagged = MusicTags.get(this, track)?.takeIf { MusicTags.isTagged(it) }
            moodProfile.setScores(tagged?.moodScores.orEmpty())
            moodProfile.visibility = if (MusicSettings.debugView && tagged?.moodScores?.isNotEmpty() == true) View.VISIBLE else View.GONE
            tags.text = tagged?.let { trackSummary(it, "\n") }.orEmpty()
            updateAuraForPosition(tagged)
        }
        updateAuraForPosition(MusicTags.get(this, track)?.takeIf { MusicTags.isTagged(it) })
        showRating(MusicTags.likedOf(this, track))
        play.setImageResource(if (PlayerState.playing) R.drawable.ic_pause else R.drawable.ic_play)
        shuffle.alpha = if (PlayerState.shuffle) 1f else 0.4f
        repeat.alpha = if (PlayerState.repeat == PlayerState.REPEAT_OFF) 0.4f else 1f
        val duration = PlayerState.durationMs
        seek.max = if (duration > 0) duration else 1
        if (!userSeeking) {
            seek.progress = PlayerState.position()
            timeNow.text = PlayerState.formatTime(PlayerState.position())
        }
        timeTotal.text = PlayerState.formatTime(duration)
    }

    /** Палитра меняется по временным сегментам композиции; BPM управляет скоростью перелива. */
    private fun updateAuraForPosition(tags: TrackTags?) {
        if (tags == null) {
            aura.setAura(MoodPalette.DEFAULT)
            return
        }
        val seconds = PlayerState.position() / 1000
        val segment = if (!MusicSettings.segmentColors) null
            else tags.segments.firstOrNull { seconds >= it.startSeconds && seconds < it.endSeconds }
                ?: tags.segments.lastOrNull { seconds >= it.startSeconds }
        val scores = segment?.scores?.takeIf { it.isNotEmpty() }
            ?: tags.moodScores.ifEmpty { mapOf((segment?.mood ?: tags.mood) to 100) }
        aura.setAura(MoodPalette.from(scores, segment?.arousal ?: tags.arousal, tags.bpm))
    }

    private fun pulse(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(75)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(150)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
            }.start()
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
        menu.menu.add(0, 8, 0, "О треке")
        menu.menu.add(0, 9, 0, "Обложка")
        menu.menu.add(0, 1, 0, "Изменить характеристики")
        menu.menu.add(0, 5, 1, "Карта настроения по фрагментам")
        menu.menu.add(0, 2, 2, "Очередь")
        menu.menu.add(0, 3, 3, "Ещё такое")
        menu.menu.add(0, 4, 4, "Таймер сна")
        menu.menu.add(0, 6, 5, if (PlayerState.shuffle) "Вперемешку: включено" else "Вперемешку: выключено")
        menu.menu.add(0, 7, 6, when (PlayerState.repeat) {
            PlayerState.REPEAT_ONE -> "Повтор: один трек"
            PlayerState.REPEAT_ALL -> "Повтор: очередь"
            else -> "Повтор: выключен"
        })
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> PlayerState.track?.let {
                    startActivity(Intent(this, TrackEditActivity::class.java).putExtra(TrackEditActivity.EXTRA_TRACK_ID, it.id))
                }
                8 -> showTrackInfo()
                9 -> PlayerState.track?.let { startActivity(Intent(this, CoverEditorActivity::class.java).putExtra(CoverEditorActivity.EXTRA_TRACK_ID, it.id)) }
                5 -> showMoodTimeline()
                2 -> showQueue()
                3 -> playSimilar()
                4 -> showSleepTimer()
                6 -> MusicService.instance?.toggleShuffle()
                7 -> MusicService.instance?.cycleRepeat()
            }
            true
        }
        menu.show()
    }

    /** Строка характеристик: настроение, ритм, энергия, танцевальность, точность. */
    private fun trackSummary(it: TrackTags, sep: String): String {
        val mood = it.moodScores.entries.sortedByDescending { e -> e.value }.take(2)
            .joinToString(" · ") { e -> "${MusicTags.moodShort[e.key] ?: e.key} ${e.value}%" }
        val rhythm = if (it.bpm > 0) "${it.bpm} BPM" else "ритм не определён"
        val dance = if (it.danceability > 0) " · танцевальность ${it.danceability}%" else ""
        return "$mood$sep$rhythm · энергия ${it.energy * 20}%$dance · точность оценки ${it.confidence}%"
    }

    /** «О треке»: всё, что известно о песне, одним окном. */
    private fun showTrackInfo() {
        val track = PlayerState.track ?: return
        val tg = MusicTags.get(this, track)?.takeIf { MusicTags.isTagged(it) }
        val sb = StringBuilder()
        sb.append("Название: ${track.title}\n")
        sb.append("Исполнитель: ${track.artist.ifBlank { "неизвестен" }}\n")
        if (track.album.isNotBlank()) sb.append("Альбом: ${track.album}\n")
        sb.append("Длительность: ${PlayerState.formatTime(PlayerState.durationMs)}\n")
        if (track.path.isNotBlank()) sb.append("Файл: ${track.path}\n")
        if (tg == null) {
            sb.append("\nПесня ещё не размечена: настроение и ритм появятся после анализа (Настройки → Разметка).")
        } else {
            sb.append("\nЖанр: ${tg.genreText}\n")
            sb.append("Настроение: ${tg.moodScores.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { (MusicTags.moodShort[it.key] ?: it.key) + " " + it.value + "%" }}\n")
            sb.append("Ритм: ${if (tg.bpm > 0) "${tg.bpm} BPM" else "не определён"}\n")
            sb.append("Энергия: ${tg.energy * 20}% · танцевальность: ${tg.danceability}%\n")
            sb.append("Позитивность: ${tg.valence}% · возбуждение: ${tg.arousal}% · напряжение: ${tg.tension}%\n")
            sb.append("Точность оценки: ${tg.confidence}%")
        }
        AlertDialog.Builder(this).setTitle("О треке").setMessage(sb.toString())
            .setPositiveButton("Закрыть", null).show()
    }

    private fun showMoodTimeline() {
        val track = PlayerState.track ?: return
        val tags = MusicTags.get(this, track)
        if (tags == null || tags.segments.isEmpty()) {
            Toast.makeText(this, "Для этой песни пока нет анализа по фрагментам. Запусти разметку музыки.", Toast.LENGTH_LONG).show()
            return
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val intro = TextView(this).apply {
            text = "Распределение оценок настроения. В каждом блоке сумма долей — 100%; это относительная оценка алгоритма, а не вероятность."
            textSize = 13f
            setTextColor(getColor(R.color.music_text_dim))
            setPadding(0, 0, 0, dp(12))
        }
        content.addView(intro)
        if (tags.moodScores.isNotEmpty()) {
            val heading = TextView(this).apply {
                text = "Вся композиция"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.music_text))
                setPadding(0, 0, 0, dp(8))
            }
            content.addView(heading)
            addMoodDistribution(content, tags.moodScores, dp(10))
        }
        tags.segments.forEachIndexed { index, segment ->
            val startTime = PlayerState.formatTime(segment.startSeconds * 1000)
            val endTime = PlayerState.formatTime(segment.endSeconds * 1000)
            val block = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            val title = TextView(this).apply {
                text = "${index + 1}. ${startTime} – ${endTime} · ${MusicTags.moods[segment.mood] ?: segment.mood}"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.music_text))
            }
            block.addView(title)
            if (segment.scores.isNotEmpty()) {
                addMoodDistribution(block, segment.scores, dp(7))
            } else {
                val fallback = TextView(this).apply {
                    text = "Позитивность ${segment.valence}% · возбуждение ${segment.arousal}% · напряжение ${segment.tension}%"
                    textSize = 12f
                    setTextColor(getColor(R.color.music_text_dim))
                }
                block.addView(fallback)
            }
            content.addView(block)
            if (index < tags.segments.lastIndex) {
                val divider = View(this).apply {
                    setBackgroundColor(getColor(R.color.music_text_dim))
                    alpha = 0.20f
                }
                content.addView(divider, android.widget.LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this).setTitle("Карта настроения · ${track.title}").setView(scroll)
            .setPositiveButton("Закрыть", null).show()
    }

    /** Полная шкала девяти настроений: цветная полоса и точные доли, суммарно 100%. */
    private fun addMoodDistribution(parent: android.widget.LinearLayout, scores: Map<String, Int>, gap: Int) {
        val ordered = MusicTags.moods.keys.map { it to (scores[it] ?: 0).coerceAtLeast(0) }
        val total = ordered.sumOf { it.second }
        if (total <= 0) return
        val colors = intArrayOf(
            0xFFFF8A4C.toInt(), 0xFF55C9B5.toInt(), 0xFFFFD166.toInt(),
            0xFF6C9EFF.toInt(), 0xFFEC8FC4.toInt(), 0xFF9B7BFF.toInt(),
            0xFF69B8E8.toInt(), 0xFFFFA45B.toInt(), 0xFF8C879F.toInt()
        )
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.music_stroke))
            clipToOutline = true
        }
        ordered.forEachIndexed { i, entry ->
            if (entry.second <= 0) return@forEachIndexed
            val segment = View(this).apply { setBackgroundColor(colors[i]) }
            bar.addView(segment, android.widget.LinearLayout.LayoutParams(0, (10 * resources.displayMetrics.density).toInt(), entry.second.toFloat()))
        }
        parent.addView(bar, android.widget.LinearLayout.LayoutParams(-1, (10 * resources.displayMetrics.density).toInt()).apply {
            bottomMargin = gap
        })
        val lines = ordered.chunked(3).map { group ->
            group.joinToString("   ·   ") { (key, value) ->
                "${MusicTags.moodShort[key] ?: key} ${value}%"
            }
        }
        lines.forEach { line ->
            val text = TextView(this).apply {
                this.text = line
                textSize = 11f
                setTextColor(getColor(R.color.music_text_dim))
                setPadding(0, 0, 0, (2 * resources.displayMetrics.density).toInt())
            }
            parent.addView(text)
        }
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
