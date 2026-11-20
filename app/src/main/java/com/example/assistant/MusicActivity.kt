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
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
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
    companion object {
        const val EXTRA_ACTION = "do"
    }


    /** Строка списка: трек или группа (настроение, жанр, исполнитель, папка). */
    private data class Row(
        val title: String, val subtitle: String, val track: MusicTrack? = null,
        val onLong: ((View) -> Unit)? = null, val badge: String? = null, val badgeColor: Int = 0,
        val tags: String = "", val header: Boolean = false, val onClick: () -> Unit
    )

    private val palette = intArrayOf(
        0xFFFF6B4A.toInt(), 0xFF7C5CFF.toInt(), 0xFF2EC4B6.toInt(), 0xFFF4A261.toInt(),
        0xFFE76F9A.toInt(), 0xFF4D9DE0.toInt(), 0xFF7FB069.toInt(), 0xFFB5838D.toInt()
    )
    private fun colorFor(text: String) = palette[Math.abs(text.hashCode()) % palette.size]

    private val moodEmoji = mapOf(
        "energetic" to "⚡", "calm" to "🌿", "happy" to "😊", "sad" to "🌧", "romantic" to "💗",
        "dance" to "💃", "focus" to "🎧", "epic" to "🔥", "dark" to "🖤"
    )
    private var lastMiniId = -1L
    private lateinit var miniCover: android.widget.ImageView

    private enum class Sort(val label: String) {
        NAME("по названию"), ARTIST("по исполнителю"), NEWEST("сначала новые"), OLDEST("сначала старые"), LIKED("любимые сверху"),
        ENERGY("по энергичности"), BPM("по темпу BPM"), DANCE("по танцевальности"), CONFIDENCE("по точности анализа")
    }

    private val tabNames = listOf("Треки", "Подборки", "Исполнители")
    private var reviewMode = false
    private lateinit var aura: AuraView
    private var lastAuraId = -2L
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
    private val searchRun = Runnable { rebuild() }
    private lateinit var status: TextView
    private lateinit var loadingHint: TextView
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
            updateAura()
            updateTaggingState()
            handler.postDelayed(this, 500)
        }
    }

    private fun prefs() = getSharedPreferences("music_ui", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        setContentView(R.layout.activity_music)
        findViewById<View>(R.id.content).padForSystemBars()
        showLastCrash()
        list = findViewById(R.id.list)
        tabs = findViewById(R.id.tabs)
        search = findViewById(R.id.search)
        status = findViewById(R.id.status)
        loadingHint = findViewById(R.id.loadingHint)
        screenTitle = findViewById(R.id.screenTitle)
        mini = findViewById(R.id.miniPlayer)
        miniProgress = findViewById(R.id.miniProgress)
        miniTitle = findViewById(R.id.miniTitle)
        miniArtist = findViewById(R.id.miniArtist)
        miniPlay = findViewById(R.id.miniPlay)
        miniCover = findViewById(R.id.miniCover)
        aura = findViewById(R.id.aura)
        aura.intensity = 0.55f
        aura.showRim = false
        aura.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            aura.centerYPx = v.height * 0.15f
            aura.coverRadiusPx = 56 * resources.displayMetrics.density
        }

        sort = Sort.values().firstOrNull { it.name == prefs().getString("sort", null) } ?: Sort.NAME

        list.layoutManager = LinearLayoutManager(this)
        list.layoutAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.music_list_enter)
        list.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 190
            removeDuration = 150
            moveDuration = 190
            changeDuration = 140
        }
        list.adapter = adapter
        tabNames.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { leaveDrill(); rebuild() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) { leaveDrill(); rebuild() }
        })
        // пересборка списка с задержкой, чтобы не тормозить на каждую букву
        search.doAfterTextChanged { handler.removeCallbacks(searchRun); handler.postDelayed(searchRun, 250L) }

        findViewById<View>(R.id.moreButton).setOnClickListener { showMainMenu(it) }
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
        if (MusicSettings.disabledFolders != foldersSeen) {
            foldersSeen = MusicSettings.disabledFolders
            if (allTracks.isNotEmpty()) loadLibrary()
        } else if (allTracks.isNotEmpty()) rebuild()
        else if (hasAudioAccess()) { status.visibility = View.GONE; loadLibrary() }
    }

    private var foldersSeen: Set<String> = emptySet()

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun updateAura() {
        val on = MusicSettings.listAura
        aura.visibility = if (on) View.VISIBLE else View.INVISIBLE
        if (!on) return
        val id = PlayerState.track?.id ?: -1L
        if (id != lastAuraId) { lastAuraId = id; aura.applyTrack(this, PlayerState.track) }
    }

    private fun leaveDrill() {
        reviewMode = false
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

    /** Разрешение «Запись звука» нужно только анализатору, который двигает фон в такт музыке. Спрашиваем один раз и объясняем. */
    private fun offerVisualizerAccess() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        if (prefs().getBoolean("visualizer_asked", false)) return
        prefs().edit().putBoolean("visualizer_asked", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Фон в такт музыке")
            .setMessage("Чтобы цвета и пламя двигались в ритм песни, Android требует разрешение «Запись звука». " +
                "Микрофон при этом не включается и ничего не записывается: приложение смотрит только на звук собственного плеера.\n\n" +
                "Без разрешения фон тоже живёт, но по условному ритму.")
            .setPositiveButton("Разрешить") { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }
            .setNegativeButton("Не нужно", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            // анализатор подключится при ближайшем запуске песни; если песня уже играет, подключаем сразу
            if (MusicService.isRunning && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                BeatSource.attach(MusicService.instance?.audioSessionId ?: 0)
            }
            return
        }
        if (hasAudioAccess()) loadLibrary() else {
            status.visibility = View.VISIBLE
            status.text = "Нет доступа к аудио. Разреши его в настройках приложения Harmonia (Настройки телефона, Приложения, Harmonia, Разрешения)."
        }
    }

    private fun loadLibrary() {
        foldersSeen = MusicSettings.disabledFolders
        loadingHint.visibility = View.VISIBLE
        loadingHint.text = "Загружаю музыку…"
        lifecycleScope.launch {
            allTracks = withContext(Dispatchers.IO) {
                MusicLibrary.index(this@MusicActivity, force = true).allMusic()
            }
            loadingHint.visibility = View.GONE
            rebuild()
            offerVisualizerAccess()
            // Первый запуск и переустановка: сам подхватит таблицу и начнёт разметку того, что осталось
            MusicTagger.autoStart(this@MusicActivity)
            // Таблица могла подхватиться из файла: через пару секунд обновим список
            handler.postDelayed({ rebuild() }, 3_000)
            handler.postDelayed({ rebuild() }, 10_000)
        }
    }

    // ---------------------------------------------------------------- главное меню

    /** Меню ⋮ наверху: только самое нужное, остальное в настройках. */
    private fun showMainMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Сортировка: ${sort.label}")
        menu.menu.add(0, 10, 1, "Настройки")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> chooseSort()
                10 -> startActivity(Intent(this, MusicSettingsActivity::class.java))
            }
            true
        }
        menu.show()
    }

    /** Если приложение упало в прошлый раз, показываем причину и даём отправить. */
    private fun showLastCrash() {
        val f = java.io.File(filesDir, "last_crash.txt")
        if (!f.exists()) return
        val text = try { f.readText() } catch (_: Exception) { return }
        f.delete()
        AlertDialog.Builder(this).setTitle("Приложение упало в прошлый раз")
            .setMessage(text.take(1500))
            .setPositiveButton("Поделиться") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Отправить"))
            }
            .setNegativeButton("Закрыть", null).show()
    }

    /** Команды из экрана настроек. */
    private fun handleAction(action: String?) {
        when (action) {
            "review" -> {
                val r = reviewList()
                leaveDrill(); reviewMode = true
                drillTitle = "Проверить"; drillTracks = r
                search.setText(""); rebuild()
            }
            "tag" -> runInBackground { MusicTagger.start(this) }
            "redo" -> runInBackground { MusicTagger.start(this, MusicTagger.Mode.REDO) }
            "retry" -> runInBackground { MusicTagger.start(this, MusicTagger.Mode.RETRY) }
            "send" -> sendToOtherAi()
            "paste" -> pasteAiAnswer()
            "share" -> shareTable()
            "pick" -> pickTableLauncher.launch(arrayOf("*/*"))
            "export" -> runInBackground {
                val ok = try { MusicCatalog.exportNow(this) } catch (e: Exception) { false }
                if (ok && PublicFolder.supported()) "Таблица обновлена: ${PublicFolder.DISPLAY_PATH}, файл Музыка.xlsx"
                else if (ok) "Таблица обновлена, но публичная папка есть только на Android 10 и новее. Используй «Отправить ИИ»."
                else "Не получилось подготовить таблицу."
            }
            "window" -> showWindowInfo()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAction(intent.getStringExtra(EXTRA_ACTION))
        intent.removeExtra(EXTRA_ACTION)
    }

    /** Подборки строятся из рассчитанных признаков; анализ и запуск музыки не вызываются. */
    private fun showSmartPlaylists() {
        val presets: List<Pair<String, (TrackTags) -> Boolean>> = listOf(
            "Спокойствие · вечер" to { t -> (t.moods.any { it in setOf("calm", "romantic") } || (t.moodScores["calm"] ?: 0) >= 18) && t.energy <= 3 && (t.bpm == 0 || t.bpm <= 110) },
            "Фокус · работа" to { t -> (t.moods.contains("focus") || (t.moodScores["focus"] ?: 0) >= 18) && t.energy <= 3 && (t.bpm == 0 || t.bpm <= 125) },
            "Заряд · тренировка" to { t -> (t.moods.any { it in setOf("energetic", "epic") } || (t.moodScores["energetic"] ?: 0) >= 18) && t.energy >= 3 && (t.bpm == 0 || t.bpm >= 105) },
            "Танцевальный ритм" to { t -> (t.moods.contains("dance") || (t.moodScores["dance"] ?: 0) >= 18) && t.danceability >= 45 && (t.bpm == 0 || t.bpm in 95..160) },
            "Светлое настроение" to { t -> (t.moods.contains("happy") || (t.moodScores["happy"] ?: 0) >= 18) && t.valence >= 55 && t.energy >= 2 },
            "Мягкая энергия" to { t -> t.energy in 2..3 && t.tension <= 55 && (t.moodScores["calm"] ?: 0) >= 10 && (t.moodScores["energetic"] ?: 0) >= 10 }
        )
        val available = presets.map { (name, predicate) ->
            name to allTracks.filter { track ->
                val tags = MusicTags.get(this, track)
                tags != null && MusicTags.isTagged(tags) && tags.liked >= 0 && predicate(tags)
            }
        }.filter { it.second.isNotEmpty() }
        val dialog = AlertDialog.Builder(this).setTitle("Умные подборки")
        if (available.isNotEmpty()) {
            dialog.setItems(available.map { it.first + " · " + it.second.size }.toTypedArray()) { _, which ->
                val (name, tracks) = available[which]
                openGroup(name, tracks)
            }
        } else {
            dialog.setMessage("Готовые подборки пока пусты. Можно задать свои условия.")
        }
        dialog.setNeutralButton("Своя подборка") { _, _ -> showCustomPlaylist() }
            .setNegativeButton("Закрыть", null).show()
    }

    /** Пользователь сам задаёт настроение, нижнюю границу энергии и диапазон BPM. */
    private fun showCustomPlaylist() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFFE8E8F0.toInt())
            setPadding(0, 12, 0, 4)
        }
        val moodKeys = listOf("Любое настроение") + MusicTags.moods.keys.toList()
        val moodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MusicActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Любое настроение") + MusicTags.moods.values.toList())
        }
        val energyLabel = label("Минимальная энергичность: 1 из 5")
        val energySeek = SeekBar(this).apply {
            max = 4
            progress = 0
        }
        val minBpm = EditText(this).apply {
            hint = "От BPM (например, 90)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val maxBpm = EditText(this).apply {
            hint = "До BPM (например, 150)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        content.addView(label("Настроение"))
        content.addView(moodSpinner)
        content.addView(energyLabel)
        content.addView(energySeek)
        content.addView(label("Темп — необязательно"))
        content.addView(minBpm)
        content.addView(maxBpm)
        energySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                energyLabel.text = "Минимальная энергичность: ${progress + 1} из 5"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        AlertDialog.Builder(this).setTitle("Своя подборка")
            .setView(content)
            .setPositiveButton("Показать") { _, _ ->
                val moodIndex = moodSpinner.selectedItemPosition
                val mood = moodKeys.getOrNull(moodIndex)
                val low = minBpm.text.toString().toIntOrNull()?.coerceIn(40, 240) ?: 0
                val high = maxBpm.text.toString().toIntOrNull()?.coerceIn(40, 240) ?: 0
                if (low > 0 && high > 0 && low > high) {
                    Toast.makeText(this, "Минимальный BPM не может быть выше максимального", Toast.LENGTH_LONG).show()
                    showCustomPlaylist()
                    return@setPositiveButton
                }
                val minimumEnergy = energySeek.progress + 1
                val selected = allTracks.filter { track ->
                    val t = MusicTags.get(this, track)
                    t != null && MusicTags.isTagged(t) && t.liked >= 0 &&
                        MusicTags.matchesPlaylistFilter(t, if (moodIndex == 0) null else mood,
                            minimumEnergy, low, high)
                }
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Подходящих размеченных песен не найдено", Toast.LENGTH_LONG).show()
                } else {
                    val moodName = if (moodIndex == 0) "любое настроение" else MusicTags.moods[mood].orEmpty()
                    val title = "Своя: $moodName · энергия от $minimumEnergy" +
                        if (low > 0 || high > 0) " · ${low.takeIf { it > 0 } ?: 40}–${high.takeIf { it > 0 } ?: 240} BPM" else ""
                    AlertDialog.Builder(this)
                        .setTitle("Найдено песен: ${selected.size}")
                        .setMessage("Открыть подборку или сохранить её в папки?")
                        .setPositiveButton("Открыть") { _, _ -> openGroup(title, selected) }
                        .setNeutralButton("Сохранить") { _, _ ->
                            askFolderName("Сохранить подборку", title) { name ->
                                val lists = PlaylistStore.load(this)
                                val key = PlaylistStore.findName(lists, name) ?: name
                                val folder = lists.getOrPut(key) { mutableListOf() }
                                selected.forEach { track ->
                                    if (folder.none { it.id == track.id }) folder.add(track)
                                }
                                PlaylistStore.save(this, lists)
                                MusicCatalog.scheduleExport(this)
                                openGroup(key, selected, folder = key)
                                Toast.makeText(this, "Сохранено песен: ${selected.size}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Отмена", null).show()
                }
            }.setNegativeButton("Отмена", null).show()
    }
    /** Готовит таблицу Музыка.xlsx и открывает системное меню «Поделиться» (почта, диск, мессенджеры). */
    private fun shareTable() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { try { MusicCatalog.exportNow(this@MusicActivity) } catch (e: Exception) { false } }
            if (ok) shareFiles(listOf(MusicCatalog.file()), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            else AlertDialog.Builder(this@MusicActivity).setMessage("Не получилось подготовить таблицу.").setPositiveButton("Ок", null).show()
        }
    }

    /** Делит песни на части для другого ИИ и предлагает отправить файлы. */
    private fun sendToOtherAi() {
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) { try { MusicAiPack.build(this@MusicActivity) } catch (e: Exception) { "Ошибка: ${e.message}" } }
            val files = MusicAiPack.partFiles()
            val dialog = AlertDialog.Builder(this@MusicActivity).setMessage(msg)
            if (files.isEmpty()) dialog.setPositiveButton("Ок", null)
            else dialog.setPositiveButton("Отправить") { _, _ -> shareFiles(files, "text/plain") }.setNegativeButton("Позже", null)
            dialog.show()
            rebuild()
        }
    }

    private fun shareFiles(files: List<java.io.File>, mime: String) {
        val uris = ArrayList<Uri>(files.map { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType(mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris[0]) else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        startActivity(Intent.createChooser(intent, "Отправить"))
    }

    /** Отладка: настоящие размеры экрана, окна и списка. Помогает понять, почему список занимает не всю высоту. */
    private fun showWindowInfo() {
        val dm = resources.displayMetrics
        val decor = window.decorView
        val loc = IntArray(2)
        list.getLocationOnScreen(loc)
        val contentLoc = IntArray(2)
        findViewById<View>(R.id.content).getLocationOnScreen(contentLoc)
        val text = "Экран: ${dm.widthPixels}×${dm.heightPixels} px, ${dm.densityDpi} dpi\n" +
            "Масштаб шрифта: ${resources.configuration.fontScale}\n" +
            "Окно: ${decor.width}×${decor.height} px\n" +
            "Содержимое: ${findViewById<View>(R.id.content).height} px высотой, начинается на y=${contentLoc[1]}\n" +
            "Список: ${list.width}×${list.height} px, начинается на y=${loc[1]}\n" +
            "Режим нескольких окон: ${isInMultiWindowMode}\n" +
            "Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}"
        AlertDialog.Builder(this).setTitle("Размер окна").setMessage(text).setPositiveButton("Ок", null).show()
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
    private val pickTableLauncher = registerForActivityResult(object : androidx.activity.result.contract.ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>) =
            super.createIntent(context, input).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, PublicFolder.initialUri())
    }) { uri ->
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
        if (sort == Sort.NAME) return tracks.sortedWith(byName)
        if (sort == Sort.ARTIST) return tracks.sortedWith(compareBy<MusicTrack>({ it.artist.lowercase() }, { it.title.lowercase() }))
        if (sort == Sort.NEWEST) return tracks.sortedWith(compareByDescending<MusicTrack> { it.dateAdded }.then(byName))
        if (sort == Sort.OLDEST) return tracks.sortedWith(compareBy<MusicTrack> { it.dateAdded }.then(byName))
        // Читаем теги один раз на трек: компаратор не должен повторно обращаться к хранилищу.
        val tagged = tracks.associateWith { MusicTags.get(this, it) }
        return when (sort) {
            Sort.LIKED -> tracks.sortedWith(compareByDescending<MusicTrack> { MusicTags.likedOf(this, it) }.then(byName))
            Sort.ENERGY -> tracks.sortedWith(compareByDescending<MusicTrack> { tagged[it]?.energy ?: 0 }.then(byName))
            Sort.BPM -> tracks.sortedWith(compareByDescending<MusicTrack> { tagged[it]?.bpm?.takeIf { bpm -> bpm > 0 } ?: -1 }.then(byName))
            Sort.DANCE -> tracks.sortedWith(compareByDescending<MusicTrack> { tagged[it]?.danceability ?: -1 }.then(byName))
            Sort.CONFIDENCE -> tracks.sortedWith(compareByDescending<MusicTrack> { tagged[it]?.confidence ?: -1 }.then(byName))
            else -> tracks.sortedWith(byName)
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
        val drill = drillTracks
        screenTitle.text = drillTitle ?: "Harmonia"
        rows = when {
            drill != null && reviewMode -> reviewRows(drill)
            drill != null -> trackListRows(applyQuery(sortTracks(drill), query), query.isEmpty())
            query.isNotEmpty() -> trackRows(applyQuery(sortTracks(allTracks), query))
            tab == 0 -> trackListRows(sortTracks(allTracks), true)
            tab == 1 -> collectionRows()
            else -> artistRows()
        }
        loadingHint.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        loadingHint.text = when {
            query.isNotEmpty() -> "Ничего не найдено"
            reviewMode -> "Сомнительных песен нет. Вся разметка уверенная."
            drillTitle == "Понравившиеся" -> "Нажми сердце у песни, и она появится здесь."
            else -> "Здесь пока пусто"
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
        if (running) parts.add(MusicTagger.progressText())
        else if (tagged < allTracks.size && tab == 1 && drillTracks == null) parts.add("Размечено $tagged из ${allTracks.size}. Разметка запускается в Настройки.")
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

    private fun artistLine(t: MusicTrack) = t.artist.ifBlank { "Неизвестный исполнитель" }

    /** Настроение и жанр песни одной строкой, если она размечена. */
    private fun tagsLine(t: MusicTrack): String {
        val tg = MusicTags.get(this, t)
        if (tg == null || !MusicTags.isTagged(tg)) return ""
        val moods = if (tg.moodScores.isNotEmpty()) {
            tg.moodScores.entries.sortedByDescending { it.value }.take(2)
                .joinToString(", ") { MusicTags.moodShort[it.key]?.lowercase() ?: it.key }
        } else tg.moods.joinToString(", ") { MusicTags.moodShort[it]?.lowercase() ?: it }
        return listOfNotNull(
            moods.takeIf { it.isNotBlank() },
            tg.bpm.takeIf { it > 0 }?.let { "$it BPM" }
        ).joinToString(" · ")
    }

    private fun trackRows(tracks: List<MusicTrack>): List<Row> =
        tracks.mapIndexed { i, t -> Row(t.title, artistLine(t), t, tags = tagsLine(t)) { startQueue(tracks, i) } }

    private fun letterOf(text: String): String {
        val c = text.trim().firstOrNull() ?: return "#"
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }

    /** Список треков; сверху строка «Играть вперемешку», если треков больше одного. */
    private fun trackListRows(tracks: List<MusicTrack>, withShuffle: Boolean): List<Row> {
        val base = trackRows(tracks)
        if (!withShuffle || tracks.size < 2) return base
        // Буквы-разделы: только при сортировке по названию или исполнителю и в длинных списках
        val byLetter = sort == Sort.NAME || sort == Sort.ARTIST
        val list: List<Row> = if (!byLetter || tracks.size < 12) base else {
            val out = ArrayList<Row>(base.size + 30)
            var last = ""
            tracks.forEachIndexed { i, t ->
                val key = letterOf(if (sort == Sort.NAME) t.title else t.artist)
                if (key != last) { out.add(Row(key, "", header = true) {}); last = key }
                out.add(base[i])
            }
            out
        }
        val pool = tracks.filter { MusicTags.likedOf(this, it) >= 0 }.ifEmpty { tracks }
        val shuffleRow = Row("Играть вперемешку", "${tracks.size} песен", badge = "▶", badgeColor = 0xFF6B5CFF.toInt()) {
            startQueue(pool.shuffled().take(500), 0)
        }
        return listOf(shuffleRow) + list
    }

    /** Вкладка «Подборки»: понравившиеся, настроения, свои папки и умные подборки в одном списке. */
    private fun collectionRows(): List<Row> {
        val liked = allTracks.filter { MusicTags.likedOf(this, it) > 0 }
        val result = ArrayList<Row>()
        result.add(Row("Понравившиеся", "${liked.size} песен", badge = "♥", badgeColor = 0xFFE76F9A.toInt()) { openGroup("Понравившиеся", liked) })
        result += groupRows(allTracks.groupBy { MusicTags.get(this, it)?.takeIf { t -> MusicTags.isTagged(t) }?.mood }, MusicTags.moods, moodEmoji, multiMood = true)
        result += folderRows()
        result.add(Row("Умные подборки", "Вечер, работа, тренировка, танцы", badge = "★", badgeColor = 0xFF7C5CFF.toInt()) { showSmartPlaylists() })
        return result
    }

    private fun groupRows(groups: Map<String?, List<MusicTrack>>, names: Map<String, String>, emoji: Map<String, String>, multiMood: Boolean = false): List<Row> {
        val result = ArrayList<Row>()
        for ((key, label) in names) {
            val tracks = if (multiMood) {
                allTracks.filter { t -> MusicTags.get(this, t)?.let { MusicTags.isTagged(it) && key in it.moods } == true }
            } else groups[key] ?: continue
            if (tracks.isEmpty()) continue
            result.add(Row(label, "${tracks.size} песен", badge = emoji[key] ?: "🎵", badgeColor = colorFor(label)) { openGroup(label, tracks) })
        }
        groups[null]?.let { result.add(Row("Без разметки", "${it.size} песен", badge = "❔", badgeColor = 0xFF55556A.toInt()) { openGroup("Без разметки", it) }) }
        return result
    }

    private fun artistRows(): List<Row> =
        allTracks.groupBy { it.artist.ifBlank { "Неизвестный исполнитель" } }.entries
            .sortedBy { it.key.lowercase() }
            .map { (name, tracks) -> Row(name, "${tracks.size} песен", badge = name.trim().take(1).uppercase().ifEmpty { "?" }, badgeColor = colorFor(name)) { openGroup(name, tracks) } }

    private fun reviewRows(review: List<MusicTrack>): List<Row> = review.map { t ->
        val tg = MusicTags.get(this, t)
        val guess = tg?.let { "${it.moods.joinToString(", ") { m -> MusicTags.moodShort[m]?.lowercase() ?: m }} · ${it.genreText.lowercase()}" }.orEmpty()
        Row(t.title, artistLine(t), t, tags = guess) { openEditor(t, review = true) }
    }

    private fun folderRows(): List<Row> {
        val lists = PlaylistStore.load(this)
        val result = ArrayList<Row>()
        for ((name, tracks) in lists) {
            val resolved = tracks.mapNotNull { p -> allTracks.firstOrNull { it.id == p.id } ?: allTracks.firstOrNull { MusicTags.trackKey(it) == MusicTags.trackKey(p) } }
            result.add(Row(name, "${resolved.size} песен", onLong = { showFolderMenu(it, name) }, badge = "📁", badgeColor = colorFor(name)) { openGroup(name, resolved, folder = name) })
        }
        result.add(Row("Новая папка", "Своя подборка для бега, прогулки", badge = "＋", badgeColor = 0xFF6B5CFF.toInt()) { askFolderName("Новая папка", "") { createFolder(it) } })
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
        if (PlayerState.track != null || MusicService.isRunning) {
            startActivity(Intent(this, PlayerActivity::class.java))
            overridePendingTransition(R.anim.player_enter, R.anim.player_exit)
        }
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
            if (lastMiniId != -1L) { lastMiniId = -1L; adapter.notifyDataSetChanged() }
            return
        }
        if (mini.visibility != View.VISIBLE) {
            mini.visibility = View.VISIBLE
            mini.alpha = 0f
            mini.translationY = mini.height.coerceAtLeast(24).toFloat() * 0.25f
            mini.animate().alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        miniTitle.text = t.title
        miniArtist.text = t.artist.ifBlank { "Неизвестный исполнитель" }
        if (t.id != lastMiniId) {
            lastMiniId = t.id
            miniCover.animate().cancel()
            miniCover.alpha = 0.55f
            miniCover.scaleX = 0.94f
            miniCover.scaleY = 0.94f
            miniCover.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
            CoverLoader.load(lifecycleScope, this, t, miniCover, 96)
            adapter.notifyDataSetChanged() // подсветка играющей песни
        }
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
        menu.menu.add(0, 6, 3, "В подборку")
        menu.menu.add(0, 8, 5, "Обложка")
        if (drillFolder != null) menu.menu.add(0, 7, 4, "Убрать из папки «${drillFolder}»")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1, 2 -> enqueue(track, item.itemId == 1)
                3 -> { MusicTags.setLiked(this, track, if (liked > 0) 0 else 1); rebuild() }
                6 -> addToFolder(track)
                8 -> startActivity(Intent(this, CoverEditorActivity::class.java).putExtra(CoverEditorActivity.EXTRA_TRACK_ID, track.id))
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

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
            val label: TextView = v as TextView
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val cover: ImageView = v.findViewById(R.id.cover)
            val badge: TextView = v.findViewById(R.id.badge)
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val tags: TextView = v.findViewById(R.id.tags)
            val heart: ImageButton = v.findViewById(R.id.heart)
            val menu: ImageButton = v.findViewById(R.id.menu)
        }

        override fun getItemViewType(position: Int) = if (rows[position].header) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) HeaderHolder(inflater.inflate(R.layout.item_header, parent, false))
            else Holder(inflater.inflate(R.layout.item_music, parent, false))
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(vh: RecyclerView.ViewHolder, position: Int) {
            val ctx = this@MusicActivity
            val row = rows[position]
            if (row.header) { (vh as HeaderHolder).label.text = row.title; return }
            val holder = vh as Holder
            val track = row.track
            holder.title.text = row.title
            holder.subtitle.text = row.subtitle
            holder.tags.text = row.tags
            holder.tags.visibility = if (row.tags.isEmpty()) View.GONE else View.VISIBLE
            val liked = if (track != null) MusicTags.likedOf(ctx, track) else 0
            holder.itemView.alpha = if (liked < 0) 0.5f else 1f
            val playing = track != null && MusicService.isRunning && PlayerState.track?.id == track.id
            holder.itemView.setBackgroundResource(
                if (playing) R.drawable.harmonia_row_playing else R.drawable.harmonia_row_bg
            )
            holder.title.setTextColor(ContextCompat.getColor(ctx, if (playing) R.color.music_accent else R.color.music_text))
            holder.itemView.setOnClickListener { row.onClick() }
            holder.itemView.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                false
            }
            if (track != null) {
                holder.badge.visibility = View.GONE
                holder.cover.visibility = View.VISIBLE
                CoverLoader.load(lifecycleScope, ctx, track, holder.cover, 108)
                holder.heart.visibility = View.VISIBLE
                holder.heart.setImageResource(if (liked > 0) R.drawable.ic_heart_filled else R.drawable.ic_heart)
                holder.heart.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, if (liked > 0) R.color.music_accent else R.color.music_text_dim)
                )
                holder.heart.setOnClickListener {
                    val now = MusicTags.likedOf(ctx, track)
                    MusicTags.setLiked(ctx, track, if (now > 0) 0 else 1)
                    val pos = holder.adapterPosition
                    if (pos >= 0) notifyItemChanged(pos)
                }
                holder.menu.visibility = View.VISIBLE
                holder.menu.setOnClickListener { showTrackMenu(it, track) }
                holder.itemView.setOnLongClickListener { showTrackMenu(holder.menu, track); true }
            } else {
                holder.cover.visibility = View.GONE
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = row.badge.orEmpty()
                holder.badge.setBackgroundResource(R.drawable.badge_bg)
                holder.badge.background.mutate().setTint(row.badgeColor)
                holder.heart.visibility = View.GONE
                val onLong = row.onLong
                if (onLong != null) {
                    holder.menu.visibility = View.VISIBLE
                    holder.menu.setOnClickListener { onLong(it) }
                    holder.itemView.setOnLongClickListener { onLong(holder.itemView); true }
                } else {
                    holder.menu.visibility = View.GONE
                    holder.itemView.setOnLongClickListener(null)
                }
            }
        }
    }
}
