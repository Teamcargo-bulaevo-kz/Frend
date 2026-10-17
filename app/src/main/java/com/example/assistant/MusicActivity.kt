package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Второе приложение «Музыка»: вся музыка телефона, настроения, жанры, исполнители, плейлисты и мини-плеер. */
class MusicActivity : AppCompatActivity() {

    /** Строка списка: трек или группа (настроение, жанр, исполнитель, плейлист). */
    private data class Row(
        val title: String, val subtitle: String, val track: MusicTrack? = null, val onClick: () -> Unit
    )

    private val tabNames = listOf("Треки", "Настроение", "Жанры", "Исполнители", "Плейлисты")
    private var allTracks: List<MusicTrack> = emptyList()
    private var shown: List<MusicTrack> = emptyList()   // треки на экране (для очереди при нажатии)
    private var drillTitle: String? = null
    private var drillTracks: List<MusicTrack>? = null
    private var rows: List<Row> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var list: RecyclerView
    private lateinit var tabs: TabLayout
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var screenTitle: TextView
    private lateinit var mini: LinearLayout
    private lateinit var miniProgress: ProgressBar
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: ImageButton

    private val adapter = RowAdapter()

    private val ticker = object : Runnable {
        override fun run() {
            updateMini()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music)
        list = findViewById(R.id.list)
        tabs = findViewById(R.id.tabs)
        search = findViewById(R.id.search)
        status = findViewById(R.id.status)
        screenTitle = findViewById(R.id.screenTitle)
        mini = findViewById(R.id.miniPlayer)
        miniProgress = findViewById(R.id.miniProgress)
        miniTitle = findViewById(R.id.miniTitle)
        miniArtist = findViewById(R.id.miniArtist)
        miniPlay = findViewById(R.id.miniPlay)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        tabNames.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { drillTracks = null; drillTitle = null; rebuild() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) { drillTracks = null; drillTitle = null; rebuild() }
        })
        search.doAfterTextChanged { rebuild() }

        findViewById<MaterialButton>(R.id.shuffleAll).setOnClickListener {
            if (allTracks.isEmpty()) return@setOnClickListener
            startQueue(allTracks.shuffled().take(500), 0)
        }
        findViewById<MaterialButton>(R.id.tagButton).setOnClickListener {
            if (MusicTagger.running) {
                Toast.makeText(this, MusicTagger.progressText(), Toast.LENGTH_LONG).show()
            } else {
                lifecycleScope.launch {
                    val msg = withContext(Dispatchers.IO) { MusicTagger.start(this@MusicActivity) }
                    Toast.makeText(this@MusicActivity, msg, Toast.LENGTH_LONG).show()
                    rebuild()
                }
            }
        }
        findViewById<View>(R.id.miniInfo).setOnClickListener { openPlayer() }
        mini.setOnClickListener { openPlayer() }
        miniPlay.setOnClickListener { MusicService.instance?.togglePlay() }
        findViewById<ImageButton>(R.id.miniPrev).setOnClickListener { MusicService.instance?.skipPrevious() }
        findViewById<ImageButton>(R.id.miniNext).setOnClickListener { MusicService.instance?.skipNext() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drillTracks != null) {
                    drillTracks = null; drillTitle = null; rebuild()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (hasAudioAccess()) loadLibrary() else requestAudioAccess()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        if (allTracks.isNotEmpty()) rebuild()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------- доступ и загрузка

    private fun audioPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioAccess() =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun requestAudioAccess() {
        requestPermissions(arrayOf(audioPermission()), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasAudioAccess()) loadLibrary() else {
            status.visibility = View.VISIBLE
            status.text = "Нет доступа к аудио. Разреши его в настройках приложения «Музыка»."
        }
    }

    private fun loadLibrary() {
        lifecycleScope.launch {
            allTracks = withContext(Dispatchers.IO) {
                MusicLibrary.index(this@MusicActivity, force = true).allMusic()
                    .sortedWith(compareBy({ it.title.lowercase() }, { it.artist.lowercase() }))
            }
            rebuild()
        }
    }

    // ---------------------------------------------------------------- построение списка

    private fun rebuild() {
        if (allTracks.isEmpty()) {
            rows = emptyList(); adapter.notifyDataSetChanged()
            status.visibility = View.VISIBLE
            status.text = if (hasAudioAccess()) "Музыки на телефоне не найдено" else "Нет доступа к аудио"
            return
        }
        val query = TextMatch.normalize(search.text.toString())
        val tab = tabs.selectedTabPosition.coerceAtLeast(0)
        val tagged = MusicTags.count(this)
        val statusParts = ArrayList<String>()
        if (MusicTagger.running) statusParts.add(MusicTagger.progressText())
        else if (tagged < allTracks.size && tab in 1..2) statusParts.add("Размечено $tagged из ${allTracks.size}. Нажми «Разметить», чтобы определить настроение и жанр остальных.")

        val drill = drillTracks
        screenTitle.text = drillTitle ?: "Музыка"
        rows = when {
            drill != null -> trackRows(applyQuery(drill, query))
            query.isNotEmpty() -> trackRows(applyQuery(allTracks, query))
            tab == 0 -> trackRows(allTracks)
            tab == 1 -> groupRows(allTracks.groupBy { MusicTags.get(this, it)?.mood }, MusicTags.moods)
            tab == 2 -> groupRows(allTracks.groupBy { MusicTags.get(this, it)?.genre }, MusicTags.genres)
            tab == 3 -> artistRows()
            else -> playlistRows()
        }
        status.visibility = if (statusParts.isEmpty()) View.GONE else View.VISIBLE
        status.text = statusParts.joinToString("\n")
        adapter.notifyDataSetChanged()
    }

    private fun applyQuery(tracks: List<MusicTrack>, query: String): List<MusicTrack> {
        if (query.isEmpty()) return tracks
        val tokens = query.split(' ')
        return tracks.filter { t ->
            val blob = TextMatch.normalize("${t.title} ${t.artist} ${t.album}")
            tokens.all { it in blob }
        }
    }

    private fun tagLine(t: MusicTrack): String {
        val tg = MusicTags.get(this, t)
        val base = t.artist.ifBlank { "Неизвестный исполнитель" }
        return if (tg == null) base else "$base · ${MusicTags.moods[tg.mood]?.lowercase()} · ${MusicTags.genres[tg.genre]?.lowercase()}"
    }

    private fun trackRows(tracks: List<MusicTrack>): List<Row> {
        shown = tracks
        return tracks.mapIndexed { i, t -> Row(t.title, tagLine(t), t) { startQueue(tracks, i) } }
    }

    private fun groupRows(groups: Map<String?, List<MusicTrack>>, names: Map<String, String>): List<Row> {
        val result = ArrayList<Row>()
        for ((key, label) in names) {
            val tracks = groups[key] ?: continue
            result.add(Row(label, "${tracks.size} песен") { openGroup(label, tracks) })
        }
        groups[null]?.let { result.add(Row("Без разметки", "${it.size} песен") { openGroup("Без разметки", it) }) }
        return result
    }

    private fun artistRows(): List<Row> =
        allTracks.groupBy { it.artist.ifBlank { "Неизвестный исполнитель" } }.entries
            .sortedBy { it.key.lowercase() }
            .map { (name, tracks) -> Row(name, "${tracks.size} песен") { openGroup(name, tracks) } }

    private fun playlistRows(): List<Row> {
        val lists = PlaylistStore.load(this)
        if (lists.isEmpty()) {
            return listOf(Row("Плейлистов пока нет", "Создай: долгое нажатие на песню → «В плейлист»") {})
        }
        return lists.map { (name, tracks) ->
            val resolved = tracks.mapNotNull { p -> allTracks.firstOrNull { it.id == p.id } }
            Row(name, "${resolved.size} песен") { openGroup(name, resolved) }
        }
    }

    private fun openGroup(title: String, tracks: List<MusicTrack>) {
        drillTitle = title
        drillTracks = tracks
        search.setText("")
        rebuild()
        list.scrollToPosition(0)
    }

    // ---------------------------------------------------------------- воспроизведение

    private fun startQueue(tracks: List<MusicTrack>, index: Int) {
        if (tracks.isEmpty()) return
        MusicQueue.tracks = tracks
        val intent = Intent(this, MusicService::class.java)
            .setAction(MusicService.ACTION_PLAY)
            .putExtra(MusicService.EXTRA_INDEX, index)
        ContextCompat.startForegroundService(this, intent)
        handler.postDelayed({ openPlayer() }, 150)
    }

    private fun openPlayer() {
        if (PlayerState.track != null || MusicService.isRunning) startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun updateMini() {
        val t = PlayerState.track
        if (t == null || !MusicService.isRunning) {
            mini.visibility = View.GONE
            return
        }
        mini.visibility = View.VISIBLE
        miniTitle.text = t.title
        miniArtist.text = t.artist
        miniPlay.setImageResource(if (PlayerState.playing) R.drawable.ic_pause else R.drawable.ic_play)
        val duration = PlayerState.durationMs
        miniProgress.max = if (duration > 0) duration else 1
        miniProgress.progress = PlayerState.position()
    }

    private fun showTrackMenu(anchor: View, track: MusicTrack) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Играть следующим")
        menu.menu.add(0, 2, 1, "В конец очереди")
        menu.menu.add(0, 3, 2, "В плейлист")
        menu.menu.add(0, 4, 3, "Характеристики")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1, 2 -> enqueue(track, item.itemId == 1)
                3 -> addToPlaylist(track)
                4 -> showTags(track)
            }
            true
        }
        menu.show()
    }

    private fun enqueue(track: MusicTrack, next: Boolean) {
        val service = MusicService.instance
        if (service == null) {
            startQueue(listOf(track), 0)
        } else {
            service.enqueue(track, next)
            Toast.makeText(this, if (next) "Сыграю следующей" else "Добавлено в очередь", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addToPlaylist(track: MusicTrack) {
        val lists = PlaylistStore.load(this)
        val names = lists.keys.toList()
        val input = EditText(this).apply { hint = "Новый плейлист" }
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("В плейлист")
        if (names.isNotEmpty()) {
            builder.setItems((names + "Новый плейлист…").toTypedArray()) { _, which ->
                if (which < names.size) savePlaylist(names[which], track) else askNewPlaylist(track)
            }
        } else {
            askNewPlaylist(track)
            return
        }
        builder.show()
    }

    private fun askNewPlaylist(track: MusicTrack) {
        val input = EditText(this).apply { hint = "Название плейлиста" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Новый плейлист").setView(input)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) savePlaylist(name, track)
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun savePlaylist(name: String, track: MusicTrack) {
        val lists = PlaylistStore.load(this)
        val key = PlaylistStore.findName(lists, name) ?: name
        val list = lists.getOrPut(key) { mutableListOf() }
        if (list.none { it.id == track.id }) list.add(track)
        PlaylistStore.save(this, lists)
        Toast.makeText(this, "Добавлено в «$key»", Toast.LENGTH_SHORT).show()
    }

    private fun showTags(track: MusicTrack) {
        val tg = MusicTags.get(this, track)
        val text = if (tg == null) "Ещё не размечена. Нажми «Разметить» вверху."
        else "Жанр: ${MusicTags.genres[tg.genre]}\nНастроение: ${MusicTags.moods[tg.mood]}\nЭнергичность: ${tg.energy} из 5"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("${track.artist} — ${track.title}").setMessage(text)
            .setPositiveButton("Ок", null).show()
    }

    // ---------------------------------------------------------------- адаптер

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val cover: ImageView = v.findViewById(R.id.cover)
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val menu: TextView = v.findViewById(R.id.menu)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_music, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.title.text = row.title
            holder.subtitle.text = row.subtitle
            holder.itemView.setOnClickListener { row.onClick() }
            val track = row.track
            if (track != null) {
                CoverLoader.load(lifecycleScope, this@MusicActivity, track, holder.cover, 96)
                holder.menu.visibility = View.VISIBLE
                holder.menu.setOnClickListener { showTrackMenu(it, track) }
                holder.itemView.setOnLongClickListener { showTrackMenu(holder.menu, track); true }
            } else {
                holder.cover.setImageResource(R.drawable.cover_placeholder)
                holder.menu.visibility = View.GONE
                holder.itemView.setOnLongClickListener(null)
            }
        }
    }
}
