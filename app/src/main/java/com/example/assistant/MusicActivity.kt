package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.appcompat.app.AlertDialog
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

/** Второе приложение «Музыка»: вся музыка телефона, настроения, жанры, исполнители, папки, проверка разметки и мини-плеер. */
class MusicActivity : AppCompatActivity() {

    /** Строка списка: трек или группа (настроение, жанр, исполнитель, папка). */
    private data class Row(
        val title: String, val subtitle: String, val track: MusicTrack? = null,
        val onLong: ((View) -> Unit)? = null, val onClick: () -> Unit
    )

    private enum class Sort(val label: String) {
        NAME("по названию"), ARTIST("по исполнителю"), NEWEST("сначала новые"), OLDEST("сначала старые"), LIKED("любимые сверху")
    }

    private val tabNames = listOf("Треки", "Настроение", "Жанры", "Исполнители", "Папки", "Проверить")
    private val reviewTab = 5
    private var allTracks: List<MusicTrack> = emptyList()
    private var drillTitle: String? = null
    private var drillTracks: List<MusicTrack>? = null
    private var drillFolder: String? = null
    private var rows: List<Row> = emptyList()
    private var sort = Sort.NAME
    private var wasTagging = false
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
            updateTaggingState()
            handler.postDelayed(this, 500)
        }
    }

    private fun prefs() = getSharedPreferences("music_ui", MODE_PRIVATE)

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

        sort = Sort.values().firstOrNull { it.name == prefs().getString("sort", null) } ?: Sort.NAME
        status.setOnClickListener {
            if (!SharedFolder.hasAccess() && Build.VERSION.SDK_INT >= 30) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        tabNames.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { leaveDrill(); rebuild() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) { leaveDrill(); rebuild() }
        })
        search.doAfterTextChanged { rebuild() }

        findViewById<TextView>(R.id.shuffleAll).setOnClickListener { shuffleAll() }
        findViewById<TextView>(R.id.moreButton).setOnClickListener { showMainMenu(it) }
        findViewById<View>(R.id.miniInfo).setOnClickListener { openPlayer() }
        mini.setOnClickListener { openPlayer() }
        miniPlay.setOnClickListener { MusicService.instance?.togglePlay() }
        findViewById<ImageButton>(R.id.miniPrev).setOnClickListener { MusicService.instance?.skipPrevious() }
        findViewById<ImageButton>(R.id.miniNext).setOnClickListener { MusicService.instance?.skipNext() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drillTracks != null) {
                    leaveDrill(); rebuild()
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

    private fun leaveDrill() {
        drillTracks = null
        drillTitle = null
        drillFolder = null
    }

    // ---------------------------------------------------------------- доступ и загрузка

    private fun audioPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioAccess() =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun requestAudioAccess() {
        val wanted = ArrayList<String>()
        wanted.add(audioPermission())
        // Уведомление «музыка размечена» не покажется без этого разрешения
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(wanted.toTypedArray(), 1)
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
            }
            rebuild()
            // Первый запуск и переустановка: сам подхватит таблицу и начнёт разметку того, что осталось
            MusicTagger.autoStart(this@MusicActivity)
            // Таблица могла подхватиться из файла: через пару секунд обновим список
            handler.postDelayed({ rebuild() }, 3_000)
            handler.postDelayed({ rebuild() }, 10_000)
        }
    }

    // ---------------------------------------------------------------- главное меню

    private fun shuffleAll() {
        val pool = allTracks.filter { MusicTags.likedOf(this, it) >= 0 }.ifEmpty { allTracks }
        if (pool.isEmpty()) return
        startQueue(pool.shuffled().take(500), 0)
    }

    /** Меню ⋮ наверху: разметка, сортировка и работа с другими ИИ. */
    private fun showMainMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Сортировка: ${sort.label}")
        menu.menu.add(0, 2, 1, "Разметить музыку")
        menu.menu.add(0, 3, 2, "Подготовить для другого ИИ")
        menu.menu.add(0, 4, 3, "Вставить ответ другого ИИ")
        menu.menu.add(0, 5, 4, "Обновить таблицу Музыка.xlsx")
        menu.menu.add(0, 6, 5, "Выбрать файл с разметкой…")
        menu.menu.add(0, 7, 6, "Разметить заново (правки сохранятся)")
        menu.menu.add(0, 8, 7, "Повторить неопознанные")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> chooseSort()
                2 -> runInBackground { MusicTagger.start(this) }
                3 -> runInBackground { MusicAiPack.build(this) }
                4 -> pasteAiAnswer()
                6 -> pickTableLauncher.launch("*/*")
                7 -> AlertDialog.Builder(this).setTitle("Разметить заново?")
                    .setMessage("Автоматическая разметка сотрётся и ИИ сделает её снова. Твои ручные правки и оценки сохранятся. Это тратит лимит ИИ.")
                    .setPositiveButton("Разметить") { _, _ -> runInBackground { MusicTagger.start(this, MusicTagger.Mode.REDO) } }
                    .setNegativeButton("Отмена", null).show()
                8 -> runInBackground { MusicTagger.start(this, MusicTagger.Mode.RETRY) }
                5 -> runInBackground {
                    if (MusicCatalog.exportNow(this)) "Таблица обновлена: Assistant/${MusicCatalog.FILE_NAME}"
                    else "Нет доступа к папке Assistant. Разреши доступ ко всем файлам."
                }
            }
            true
        }
        menu.show()
    }

    /** Выполняет блокирующее действие в фоне и показывает его результат. */
    private fun runInBackground(action: () -> String) {
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) { try { action() } catch (e: Exception) { "Ошибка: ${e.message}" } }
            AlertDialog.Builder(this@MusicActivity).setMessage(msg).setPositiveButton("Ок", null).show()
            rebuild()
        }
    }

    /** Файл с разметкой из любого места телефона: копируется во временную папку и разбирается как таблица. */
    private val pickTableLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        runInBackground {
            var name = "table.csv"
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val tmp = java.io.File(cacheDir, "import_" + safe)
            try {
                contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: return@runInBackground "Не получилось открыть файл."
                val n = MusicCatalog.importFrom(this, tmp)
                if (n == 0) "Разметку из файла не взял.\n" + MusicCatalog.lastReport + "\n" + MusicCatalog.EXPECTED
                else {
                    MusicCatalog.scheduleExport(this)
                    "Применил разметку: $n песен.\n" + MusicCatalog.lastReport
                }
            } finally {
                tmp.delete()
            }
        }
    }

    /** Берёт из буфера обмена ответ другого ИИ (CSV или таблицу) и применяет разметку. */
    private fun pasteAiAnswer() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "В буфере обмена пусто. Скопируй ответ другого ИИ и попробуй снова.", Toast.LENGTH_LONG).show()
            return
        }
        runInBackground {
            val n = MusicCatalog.importText(this, text)
            if (n == 0) "Не нашёл в тексте новую разметку.\n" + MusicCatalog.lastReport + "\n" + MusicCatalog.EXPECTED + " Скопируй ответ ИИ целиком."
            else "Применил разметку: $n песен.\n" + MusicCatalog.lastReport
        }
    }

    // ---------------------------------------------------------------- сортировка

    private fun chooseSort() {
        val all = Sort.values()
        AlertDialog.Builder(this).setTitle("Сортировка")
            .setItems(all.map { it.label }.toTypedArray()) { _, which ->
                sort = all[which]
                prefs().edit().putString("sort", sort.name).apply()
                rebuild()
            }.show()
    }

    private fun sortTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        val byName = compareBy<MusicTrack>({ it.title.lowercase() }, { it.artist.lowercase() })
        return when (sort) {
            Sort.NAME -> tracks.sortedWith(byName)
            Sort.ARTIST -> tracks.sortedWith(compareBy<MusicTrack>({ it.artist.lowercase() }, { it.title.lowercase() }))
            Sort.NEWEST -> tracks.sortedWith(compareByDescending<MusicTrack> { it.dateAdded }.then(byName))
            Sort.OLDEST -> tracks.sortedWith(compareBy<MusicTrack> { it.dateAdded }.then(byName))
            Sort.LIKED -> tracks.sortedWith(compareByDescending<MusicTrack> { MusicTags.likedOf(this, it) }.then(byName))
        }
    }

    // ---------------------------------------------------------------- построение списка

    private fun reviewList(): List<MusicTrack> = allTracks.filter { t ->
        val tg = MusicTags.get(this, t)
        tg != null && MusicTags.isTagged(tg) && tg.needsReview
    }.sortedBy { MusicTags.get(this, it)?.confidence ?: 0 }

    private fun rebuild() {
        if (allTracks.isEmpty()) {
            rows = emptyList(); adapter.notifyDataSetChanged()
            status.visibility = View.VISIBLE
            status.text = if (hasAudioAccess()) "Музыки на телефоне не найдено" else "Нет доступа к аудио"
            return
        }
        val query = TextMatch.normalize(search.text.toString())
        val tab = tabs.selectedTabPosition.coerceAtLeast(0)
        val review = reviewList()
        tabs.getTabAt(reviewTab)?.text = if (review.isEmpty()) "Проверить" else "Проверить (${review.size})"

        val drill = drillTracks
        screenTitle.text = drillTitle ?: "Музыка"
        rows = when {
            drill != null -> trackRows(applyQuery(sortTracks(drill), query))
            query.isNotEmpty() -> trackRows(applyQuery(sortTracks(allTracks), query))
            tab == 0 -> trackRows(sortTracks(allTracks))
            tab == 1 -> groupRows(allTracks.groupBy { MusicTags.get(this, it)?.takeIf { t -> MusicTags.isTagged(t) }?.mood }, MusicTags.moods, multiMood = true)
            tab == 2 -> groupRows(allTracks.groupBy { MusicTags.get(this, it)?.takeIf { t -> MusicTags.isTagged(t) }?.genre }, MusicTags.genres)
            tab == 3 -> artistRows()
            tab == 4 -> folderRows()
            else -> reviewRows(review)
        }
        updateTaggingState(force = true)
        adapter.notifyDataSetChanged()
    }

    /** Строка состояния: ход разметки или подсказка по вкладке. */
    private fun updateTaggingState(force: Boolean = false) {
        val running = MusicTagger.running
        if (wasTagging && !running) {
            wasTagging = false
            if (allTracks.isNotEmpty()) { rebuild(); return }
        }
        wasTagging = running
        if (allTracks.isEmpty() && !force) return
        val tab = tabs.selectedTabPosition.coerceAtLeast(0)
        val tagged = MusicTags.count(this)
        val parts = ArrayList<String>()
        if (!SharedFolder.hasAccess()) parts.add("⚠ Нет доступа ко всем файлам: таблица Музыка.xlsx не сохранится при переустановке. Нажми сюда, чтобы разрешить.")
        if (running) parts.add("⏳ " + MusicTagger.progressText())
        else if (tagged < allTracks.size && tab in 1..2) parts.add("Размечено $tagged из ${allTracks.size}. Нажми «Разметить», чтобы определить остальные.")
        if (tab == reviewTab && drillTracks == null) parts.add("Здесь песни, в которых я не уверен. Открой, поправь или нажми «Всё верно».")
        status.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        status.text = parts.joinToString("\n")
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
        val mark = when {
            tg != null && tg.liked > 0 -> "♥ "
            tg != null && tg.liked < 0 -> "👎 "
            else -> ""
        }
        if (tg == null || !MusicTags.isTagged(tg)) return mark + base
        val moods = tg.moods.joinToString(", ") { MusicTags.moodShort[it]?.lowercase() ?: it }
        return "$mark$base · $moods · ${tg.genreText.lowercase()}"
    }

    private fun trackRows(tracks: List<MusicTrack>): List<Row> =
        tracks.mapIndexed { i, t -> Row(t.title, tagLine(t), t) { startQueue(tracks, i) } }

    private fun groupRows(groups: Map<String?, List<MusicTrack>>, names: Map<String, String>, multiMood: Boolean = false): List<Row> {
        val result = ArrayList<Row>()
        for ((key, label) in names) {
            val tracks = if (multiMood) {
                allTracks.filter { t -> MusicTags.get(this, t)?.let { MusicTags.isTagged(it) && key in it.moods } == true }
            } else groups[key] ?: continue
            if (tracks.isEmpty()) continue
            result.add(Row(label, "${tracks.size} песен") { openGroup(label, tracks) })
        }
        groups[null]?.let { result.add(Row("Без разметки", "${it.size} песен") { openGroup("Без разметки", it) }) }
        return result
    }

    private fun artistRows(): List<Row> =
        allTracks.groupBy { it.artist.ifBlank { "Неизвестный исполнитель" } }.entries
            .sortedBy { it.key.lowercase() }
            .map { (name, tracks) -> Row(name, "${tracks.size} песен") { openGroup(name, tracks) } }

    private fun reviewRows(review: List<MusicTrack>): List<Row> = review.map { t ->
        val tg = MusicTags.get(this, t)
        val guess = tg?.let { "${it.moods.joinToString(", ") { m -> MusicTags.moodShort[m]?.lowercase() ?: m }} · ${it.genreText.lowercase()}" }.orEmpty()
        Row(t.title, "${t.artist.ifBlank { "Неизвестный исполнитель" }} · $guess", t) { openEditor(t, review = true) }
    }

    private fun folderRows(): List<Row> {
        val lists = PlaylistStore.load(this)
        val result = ArrayList<Row>()
        result.add(Row("＋ Новая папка", "Для бега, для прогулки, любая своя") { askFolderName("Новая папка", "") { createFolder(it) } })
        for ((name, tracks) in lists) {
            val resolved = tracks.mapNotNull { p -> allTracks.firstOrNull { it.id == p.id } ?: allTracks.firstOrNull { MusicTags.trackKey(it) == MusicTags.trackKey(p) } }
            result.add(Row(name, "${resolved.size} песен", onLong = { showFolderMenu(it, name) }) { openGroup(name, resolved, folder = name) })
        }
        return result
    }

    private fun openGroup(title: String, tracks: List<MusicTrack>, folder: String? = null) {
        drillTitle = title
        drillTracks = tracks
        drillFolder = folder
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

    private fun openEditor(track: MusicTrack, review: Boolean = false) {
        startActivity(
            Intent(this, TrackEditActivity::class.java)
                .putExtra(TrackEditActivity.EXTRA_TRACK_ID, track.id)
                .putExtra(TrackEditActivity.EXTRA_REVIEW, review)
        )
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

    // ---------------------------------------------------------------- меню песни и папок

    private fun showTrackMenu(anchor: View, track: MusicTrack) {
        val liked = MusicTags.likedOf(this, track)
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Играть следующим")
        menu.menu.add(0, 2, 1, "В конец очереди")
        menu.menu.add(0, 3, 2, if (liked > 0) "Убрать ♥" else "Нравится ♥")
        menu.menu.add(0, 4, 3, if (liked < 0) "Вернуть в подборки" else "Не нравится")
        menu.menu.add(0, 5, 4, "Изменить характеристики")
        menu.menu.add(0, 6, 5, "В папку")
        if (drillFolder != null) menu.menu.add(0, 7, 6, "Убрать из папки «${drillFolder}»")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1, 2 -> enqueue(track, item.itemId == 1)
                3 -> { MusicTags.setLiked(this, track, if (liked > 0) 0 else 1); rebuild() }
                4 -> { MusicTags.setLiked(this, track, if (liked < 0) 0 else -1); rebuild() }
                5 -> openEditor(track)
                6 -> addToFolder(track)
                7 -> removeFromCurrentFolder(track)
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

    private fun addToFolder(track: MusicTrack) {
        val names = PlaylistStore.load(this).keys.toList()
        if (names.isEmpty()) {
            askFolderName("Новая папка", "") { name -> createFolder(name, track) }
            return
        }
        AlertDialog.Builder(this).setTitle("В папку")
            .setItems((names + "Новая папка…").toTypedArray()) { _, which ->
                if (which < names.size) saveToFolder(names[which], track)
                else askFolderName("Новая папка", "") { name -> createFolder(name, track) }
            }.show()
    }

    private fun askFolderName(title: String, initial: String, onName: (String) -> Unit) {
        val input = EditText(this).apply { hint = "Название"; setText(initial) }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Ок") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun createFolder(name: String, first: MusicTrack? = null) {
        val lists = PlaylistStore.load(this)
        val key = PlaylistStore.findName(lists, name) ?: name
        val folder = lists.getOrPut(key) { mutableListOf() }
        if (first != null && folder.none { it.id == first.id }) folder.add(first)
        PlaylistStore.save(this, lists)
        MusicCatalog.scheduleExport(this)
        Toast.makeText(this, if (first != null) "Добавлено в «$key»" else "Папка «$key» создана", Toast.LENGTH_SHORT).show()
        rebuild()
    }

    private fun saveToFolder(name: String, track: MusicTrack) {
        val lists = PlaylistStore.load(this)
        val folder = lists.getOrPut(name) { mutableListOf() }
        if (folder.none { it.id == track.id }) folder.add(track)
        PlaylistStore.save(this, lists)
        MusicCatalog.scheduleExport(this)
        Toast.makeText(this, "Добавлено в «$name»", Toast.LENGTH_SHORT).show()
    }

    private fun removeFromCurrentFolder(track: MusicTrack) {
        val name = drillFolder ?: return
        val lists = PlaylistStore.load(this)
        val folder = lists[name] ?: return
        folder.removeAll { it.id == track.id || MusicTags.trackKey(it) == MusicTags.trackKey(track) }
        PlaylistStore.save(this, lists)
        MusicCatalog.scheduleExport(this)
        drillTracks = drillTracks?.filter { it.id != track.id }
        rebuild()
    }

    private fun showFolderMenu(anchor: View, name: String) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Переименовать")
        menu.menu.add(0, 2, 1, "Удалить папку")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> askFolderName("Переименовать", name) { renameFolder(name, it) }
                2 -> AlertDialog.Builder(this).setTitle("Удалить «$name»?")
                    .setMessage("Сами песни останутся на телефоне.")
                    .setPositiveButton("Удалить") { _, _ -> deleteFolder(name) }
                    .setNegativeButton("Отмена", null).show()
            }
            true
        }
        menu.show()
    }

    private fun renameFolder(old: String, new: String) {
        val lists = PlaylistStore.load(this)
        if (lists.keys.any { it.equals(new, true) && it != old }) {
            Toast.makeText(this, "«$new» уже есть", Toast.LENGTH_SHORT).show()
            return
        }
        val renamed = LinkedHashMap<String, MutableList<MusicTrack>>()
        for ((k, v) in lists) renamed[if (k == old) new else k] = v
        PlaylistStore.save(this, renamed)
        MusicCatalog.scheduleExport(this)
        rebuild()
    }

    private fun deleteFolder(name: String) {
        val lists = PlaylistStore.load(this)
        lists.remove(name)
        PlaylistStore.save(this, lists)
        MusicCatalog.scheduleExport(this)
        rebuild()
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
            holder.itemView.alpha = if (row.track != null && MusicTags.likedOf(this@MusicActivity, row.track) < 0) 0.5f else 1f
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
                val onLong = row.onLong
                if (onLong != null) holder.itemView.setOnLongClickListener { onLong(holder.itemView); true }
                else holder.itemView.setOnLongClickListener(null)
            }
        }
    }
}
