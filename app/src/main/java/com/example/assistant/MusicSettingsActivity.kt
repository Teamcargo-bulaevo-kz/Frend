package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Настройки: главный экран с разделами. Каждый раздел открывается отдельным окном:
 * переключатели, дизайн, папки с музыкой, расширенные.
 */
class MusicSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SECTION = "section"
        const val S_SWITCHES = "switches"
        const val S_DESIGN = "design"
        const val S_ANIMATION = "animation"
        const val S_PALETTE = "palette"
        const val S_SOUND = "sound"
        const val S_TABLE = "table"
        const val S_FOLDERS = "folders"
        const val S_ADVANCED = "advanced"
    }

    private lateinit var root: LinearLayout
    private var statusText: TextView? = null
    private var rhythmStatus: TextView? = null
    private val section: String? by lazy { intent.getStringExtra(EXTRA_SECTION) }
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        val scroll = ScrollView(this).apply { setBackgroundResource(R.drawable.harmonia_background) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(32))
        }
        scroll.addView(root)
        setContentView(scroll)
        root.padForSystemBars()
        when (section) {
            S_SWITCHES -> buildSwitches()
            S_DESIGN -> buildDesign()
            S_ANIMATION -> buildAnimation()
            S_PALETTE -> buildPalette()
            S_SOUND -> buildSound()
            S_TABLE -> buildTable()
            S_FOLDERS -> buildFolders()
            S_ADVANCED -> buildAdvanced()
            else -> buildHome()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- Экраны ----------

    private fun buildHome() {
        header("Настройки", big = true)
        statusText = text("", 13f, R.color.harmonia_muted).also { root.addView(it) }
        navRow("Переключатели", "Свечение, перемотка, оценки") { open(S_SWITCHES) }
        navRow("Дизайн", "Яркость, размеры обложки, текста и кнопок") { open(S_DESIGN) }
        navRow("Анимация", "Размер, пульс, плавность, темп") { open(S_ANIMATION) }
        navRow("Палитра", "Цвета свечения: по настроению или свои") { open(S_PALETTE) }
        navRow("Звук", "Эквалайзер и усиление баса") { open(S_SOUND) }
        navRow("Таблица для ИИ", "Таблица песен с характеристиками в папке Документы/Harmonia") { open(S_TABLE) }
        navRow("Папки с музыкой", "Какие папки показывать и сканировать") { open(S_FOLDERS) }
        navRow("Расширенные", "Ритм, разметка, обмен с ИИ, отладка, сброс") { open(S_ADVANCED) }
        section("Музыка")
        actionRow("Разметить музыку", "Определить настроение и ритм новых песен на телефоне") { runInMain("tag") }
        section("О приложении")
        text("Harmonia ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TAG}", 13f, R.color.harmonia_muted).also { root.addView(it) }
    }

    private fun buildSwitches() {
        header("Переключатели", big = true)
        switchRow("Свечение в плеере", "Цвет зависит от настроения песни, пульс идёт в такт", MusicSettings.auraEnabled) { MusicSettings.auraEnabled = it }
        switchRow("Свечение в списке", "Мягкий цветной фон под списком песен", MusicSettings.listAura) { MusicSettings.listAura = it }
        switchRow("Пауза при отключении наушников", "Музыка встанет на паузу, если отключились наушники или Bluetooth", MusicSettings.pauseOnUnplug) { MusicSettings.pauseOnUnplug = it }
        switchRow("Полоса перемотки", "Показывать время и перемотку в плеере", MusicSettings.showSeek) { MusicSettings.showSeek = it }
        switchRow("Нравится и не нравится", "Кнопки оценки в плеере, по ним строятся подборки", MusicSettings.showRating) { MusicSettings.showRating = it }
    }

    private fun buildDesign() {
        header("Дизайн", big = true)
        section("Плеер")
        choiceRow("Размер обложки", listOf("Маленькая", "Средняя", "Большая"), { MusicSettings.coverSize }) { MusicSettings.coverSize = it }
        choiceRow("Размер названия", listOf("Мелкий", "Обычный", "Крупный"), { MusicSettings.textSize }) { MusicSettings.textSize = it }
        choiceRow("Размер кнопок", listOf("Маленькие", "Обычные", "Крупные"), { MusicSettings.buttonSize }) { MusicSettings.buttonSize = it }
        section("Обложки")
        choiceRow("Источник обложек", listOf("Как в системе", "Только из файла песни", "Не показывать"), { MusicSettings.coverSource }) {
            MusicSettings.coverSource = it; CoverLoader.clear()
        }
        text("У отдельной песни обложку можно сменить или убрать: меню песни, пункт «Обложка».", 12f, R.color.harmonia_muted).also { root.addView(it) }
    }

    /** Небольшой предпросмотр свечения: цвет текущей песни (или нейтральный) и текущие настройки. */
    private fun addPreview() {
        val box = android.widget.FrameLayout(this)
        val aura = AuraView(this).apply {
            intensity = 1f; showRim = true
            applyTrack(this@MusicSettingsActivity, PlayerState.track)
            coverRadiusPx = 36 * dp
        }
        box.addView(aura, android.widget.FrameLayout.LayoutParams(-1, -1))
        aura.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> aura.centerYPx = v.height / 2f }
        val disc = View(this).apply { setBackgroundResource(R.drawable.cover_placeholder) }
        box.addView(disc, android.widget.FrameLayout.LayoutParams(px(72), px(72), Gravity.CENTER))
        root.addView(box, LinearLayout.LayoutParams(-1, px(200)).apply { bottomMargin = px(8) })
    }

    private fun buildAnimation() {
        header("Анимация", big = true)
        addPreview()
        choiceRow("Готовый стиль", listOf("Спокойный", "Обычный", "Резкий", "Максимум"), { -1 }) { i ->
            // порядок: размах, плавность, нарастание, затухание, порог, пульсов/с, низкие, средние, высокие,
            // яркость, дыхание, живость, дрожь, игра оттенка, источник, чувствительность
            val p = listOf(
                intArrayOf(60, 80, 30, 30, 120, 1, 100, 30, 10, 70, 40, 30, 20, 20, 0, 100),
                intArrayOf(100, 50, 50, 50, 100, 3, 100, 50, 25, 100, 20, 70, 40, 40, 2, 100),
                intArrayOf(130, 20, 80, 70, 90, 4, 100, 70, 40, 120, 0, 100, 60, 60, 2, 130),
                intArrayOf(160, 20, 90, 60, 70, 5, 120, 80, 60, 130, 10, 120, 80, 70, 2, 160)
            )[i]
            MusicSettings.pulseAmplitude = p[0]; MusicSettings.pulseSmooth = p[1]; MusicSettings.pulseAttack = p[2]
            MusicSettings.pulseRelease = p[3]; MusicSettings.onsetThreshold = p[4]; MusicSettings.maxPulsesPerSec = p[5]
            MusicSettings.bandLow = p[6]; MusicSettings.bandMid = p[7]; MusicSettings.bandHigh = p[8]
            MusicSettings.brightnessPulse = p[9]; MusicSettings.idleBreath = p[10]; MusicSettings.liveliness = p[11]
            MusicSettings.shimmer = p[12]; MusicSettings.hueSway = p[13]; MusicSettings.pulseSource = p[14]
            MusicSettings.beatSensitivity = p[15]
            recreate()
        }
        sliderRow("Яркость свечения", 30, 100, 10, MusicSettings.auraBrightness, "%") { MusicSettings.auraBrightness = it }
        sliderRow("Размер свечения", 60, 150, 10, MusicSettings.glowSize, "%") { MusicSettings.glowSize = it }
        sliderRow("Размах пульса", 30, 200, 10, MusicSettings.pulseAmplitude, "%") { MusicSettings.pulseAmplitude = it }
        section("Ритм: вклад частот")
        sliderRow("Низкие (удары, бас)", 0, 200, 10, MusicSettings.bandLow, "%") { MusicSettings.bandLow = it }
        sliderRow("Средние (голос, гитары)", 0, 200, 10, MusicSettings.bandMid, "%") { MusicSettings.bandMid = it }
        sliderRow("Высокие (тарелки, шипящие)", 0, 200, 10, MusicSettings.bandHigh, "%") { MusicSettings.bandHigh = it }
        section("Ритм: пульс")
        choiceRow("Чем пульсировать", listOf("Только удары бочки", "Весь бас", "Любые удары (живее всего)"), { MusicSettings.pulseSource }) { MusicSettings.pulseSource = it }
        sliderRow("Пульсов в секунду не больше", 1, 6, 1, MusicSettings.maxPulsesPerSec, "") { MusicSettings.maxPulsesPerSec = it }
        sliderRow("Плавность пульса", 0, 100, 10, MusicSettings.pulseSmooth, "%") { MusicSettings.pulseSmooth = it }
        sliderRow("Порог срабатывания (больше, тем реже)", 50, 150, 10, MusicSettings.onsetThreshold, "%") { MusicSettings.onsetThreshold = it }
        sliderRow("Скорость нарастания", 0, 100, 10, MusicSettings.pulseAttack, "%") { MusicSettings.pulseAttack = it }
        sliderRow("Скорость затухания", 0, 100, 10, MusicSettings.pulseRelease, "%") { MusicSettings.pulseRelease = it }
        sliderRow("Размах яркости", 0, 150, 10, MusicSettings.brightnessPulse, "%") { MusicSettings.brightnessPulse = it }
        sliderRow("Дыхание между ударами", 0, 100, 10, MusicSettings.idleBreath, "%") { MusicSettings.idleBreath = it }
        sliderRow("Чувствительность к ритму", 50, 250, 10, MusicSettings.beatSensitivity, "%") { MusicSettings.beatSensitivity = it }
        section("Живость между ударами")
        sliderRow("Живость (набухание от баса и громкости)", 0, 150, 10, MusicSettings.liveliness, "%") { MusicSettings.liveliness = it }
        sliderRow("Дрожь верхов (мерцание яркости)", 0, 100, 10, MusicSettings.shimmer, "%") { MusicSettings.shimmer = it }
        sliderRow("Игра оттенка (от средних частот)", 0, 100, 10, MusicSettings.hueSway, "%") { MusicSettings.hueSway = it }
        section("Диапазон частот")
        sliderRow("Нижняя граница", 20, 200, 10, MusicSettings.freqLow, " Гц") { MusicSettings.freqLow = it }
        sliderRow("Верхняя граница", 4000, 16000, 1000, MusicSettings.freqHigh, " Гц") { MusicSettings.freqHigh = it }
        switchRow("Пульс по темпу песни", "Держит дыхание в такт ударам и BPM песни", MusicSettings.tempoLock) { MusicSettings.tempoLock = it }
        choiceRow("Частота кадров", listOf("20", "30", "60"), { listOf(20, 30, 60).indexOf(MusicSettings.fps).coerceAtLeast(1) }) { MusicSettings.fps = listOf(20, 30, 60)[it] }
        text("Пульс настраивается здесь, цвета в разделе «Палитра». Предпросмотр показывает цвет текущей песни.", 12f, R.color.harmonia_muted).also { root.addView(it) }
    }

    private fun buildPalette() {
        header("Палитра", big = true)
        addPreview()
        val names = listOf("По настроению песни") + MoodPalette.fixed.map { it.first } + "Свой цвет"
        choiceRow("Палитра", names, { MusicSettings.paletteMode }) { MusicSettings.paletteMode = it }
        sliderRow("Свой оттенок", 0, 350, 10, MusicSettings.customHue, "°") { MusicSettings.customHue = it }
        sliderRow("Ширина палитры", 0, 100, 10, MusicSettings.paletteSpread, "%") { MusicSettings.paletteSpread = it }
        sliderRow("Насыщенность", 30, 100, 10, MusicSettings.paletteSaturation, "%") { MusicSettings.paletteSaturation = it }
        sliderRow("Скорость смены цвета", 0, 100, 10, MusicSettings.colorSpeed, "%") { MusicSettings.colorSpeed = it }
        switchRow("Цвет по частям песни", "Куплет и припев могут иметь разный оттенок", MusicSettings.segmentColors) { MusicSettings.segmentColors = it }
        text("«Свой оттенок» работает при выборе «Свой цвет».", 12f, R.color.harmonia_muted).also { root.addView(it) }
    }

    private fun buildSound() {
        header("Звук", big = true)
        text("Работает с плеером приложения, звук других программ не меняется.", 13f, R.color.harmonia_muted).also { root.addView(it) }
        switchRow("Эквалайзер", "Включить настройки ниже", MusicSettings.eqEnabled) { MusicSettings.eqEnabled = it; EqualizerManager.apply() }
        val info = EqualizerManager.info
        choiceRow("Готовая настройка", EqualizerManager.presets.map { it.first }, { -1 }) { i ->
            MusicSettings.eqLevels = EqualizerManager.presets[i].second.map { it * 100 }
            EqualizerManager.apply(); recreate()
        }
        val levels = EqualizerManager.resample(MusicSettings.eqLevels, info.bands).toMutableList()
        for (i in 0 until info.bands) {
            val hz = info.centerHz.getOrElse(i) { 0 }
            val label = if (hz >= 1000) "${hz / 1000} кГц" else "$hz Гц"
            sliderRow(label, info.minMb / 100, info.maxMb / 100, 1, levels[i] / 100, " дБ") { db ->
                levels[i] = db * 100
                MusicSettings.eqLevels = levels.toList()
                EqualizerManager.apply()
            }
        }
        sliderRow("Усиление баса", 0, 100, 10, MusicSettings.bassBoost, "%") { MusicSettings.bassBoost = it; EqualizerManager.apply() }
        text("Число полос и диапазон уточняются после первого запуска песни.", 12f, R.color.harmonia_muted).also { root.addView(it) }
    }

    private fun buildTable() {
        header("Таблица для ИИ", big = true)
        text("Таблица со всеми песнями и их характеристиками (готовыми или пустыми) лежит в папке ${PublicFolder.DISPLAY_PATH}, файл «Музыка.xlsx».", 13f, R.color.harmonia_muted).also { root.addView(it) }
        val status = text("", 13f, R.color.harmonia_cyan).also { root.addView(it) }
        fun refresh() {
            val t = PublicFolder.lastMirrorMs(this)
            status.text = when {
                !PublicFolder.supported() -> "Публичная папка работает на Android 10 и новее. Здесь доступна только отправка файла."
                t == 0L -> "Файл ещё не создан. Нажми «Обновить таблицу»."
                else -> "Песен в таблице: ${PublicFolder.lastRows(this)} · обновлено: " +
                    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
            }
        }
        refresh()
        section("Как пользоваться")
        text("1. Обнови таблицу.\n2. Отправь файл ИИ и попроси заполнить пустые характеристики, не меняя названия колонок.\n3. Сохрани исправленный файл на телефон.\n4. Нажми «Импортировать таблицу» и выбери его. Приложение применит данные и заменит файл в папке обновлённым.", 13f, R.color.harmonia_muted).also { root.addView(it) }
        section("Действия")
        actionRow("Обновить таблицу", "Записать свежую таблицу в папку ${PublicFolder.DISPLAY_PATH}") { runInMain("export") }
        actionRow("Отправить ИИ", "Поделиться файлом «Музыка.xlsx» в другое приложение") { runInMain("share") }
        actionRow("Отправить по частям", "Текстовые части, если ИИ не принимает файл целиком") { runInMain("send") }
        actionRow("Импортировать таблицу", "Выбрать исправленный файл (откроется папка ${PublicFolder.DISPLAY_PATH})") { runInMain("pick") }
        actionRow("Вставить ответ ИИ", "Если ИИ ответил текстом: скопируй его и нажми сюда") { runInMain("paste") }
    }

    private fun buildFolders() {
        header("Папки с музыкой", big = true)
        text("Выключенные папки не показываются в списке и не сканируются.", 13f, R.color.harmonia_muted).also { root.addView(it) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) { MusicLibrary.folders(this@MusicSettingsActivity) }
            if (folders.isEmpty()) {
                box.addView(text("Папки не найдены. Проверь доступ к музыке в настройках телефона.", 13f, R.color.harmonia_muted))
                return@launch
            }
            val primary = android.os.Environment.getExternalStorageDirectory().path
            val all = folders.map { it.first }
            val total = folders.sumOf { it.second }
            statusLine("Папок: ${folders.size} · песен: $total")
            box.addView(actionRowView("Включить все", null) {
                MusicSettings.disabledFolders = emptySet(); MusicLibrary.invalidate(); recreate()
            })
            box.addView(actionRowView("Выключить все", null) {
                MusicSettings.disabledFolders = all.toSet(); MusicLibrary.invalidate(); recreate()
            })
            folders.forEach { (dir, count) ->
                val shown = dir.removePrefix(primary).trimStart('/').ifEmpty { "Корень памяти" }
                box.addView(switchView(shown, "$count песен", MusicSettings.isFolderEnabled(dir)) { on ->
                    MusicSettings.disabledFolders = if (on) MusicSettings.disabledFolders - dir else MusicSettings.disabledFolders + dir
                    MusicLibrary.invalidate()
                })
            }
        }
        root.addView(box)
    }

    private fun statusLine(t: String) { root.addView(text(t, 13f, R.color.harmonia_cyan), 2) }

    private fun buildAdvanced() {
        header("Расширенные", big = true)

        section("Ритм")
        rhythmStatus = text("", 13f, R.color.harmonia_muted).also { root.addView(it) }
        actionRow("Доступ к ритму", "Нужен микрофон, чтобы свечение пульсировало в такт. Звук не записывается") { askRhythmAccess() }

        section("Обложки")
        actionRow("Сбросить свои обложки", "Вернуть все обложки, которые ты менял или убирал") {
            AlertDialog.Builder(this).setTitle("Сбросить все свои обложки?")
                .setPositiveButton("Сбросить") { _, _ ->
                    val n = CoverStore.resetAll(this); CoverLoader.clear()
                    Toast.makeText(this, if (n > 0) "Сброшено" else "Своих обложек не было", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Отмена", null).show()
        }

        section("Разметка")
        choiceRow("Потоков анализа", listOf("Авто", "1", "2", "3", "4"), { MusicSettings.analysisWorkers }) { MusicSettings.analysisWorkers = it }
        actionRow("Проверить сомнительные песни", "Песни, в которых разметка не уверена") { runInMain("review") }
        actionRow("Разметить заново", "Автоматическая разметка сотрётся, ручные правки сохранятся") { confirmRedo() }
        actionRow("Повторить неопознанные", null) { runInMain("retry") }

        section("Отладка")
        switchRow("Отладочный вид плеера", "Показать спектр, карту настроения, строку характеристик, вперемешку и повтор", MusicSettings.debugView) { MusicSettings.debugView = it }
        if (BuildConfig.DEBUG) actionRow("Размер окна", "Для поиска проблем с версткой") { runInMain("window") }

        section("Работа в фоне")
        actionRow("Не выгружать из фона", "На Xiaomi и других оболочках разреши автозапуск и убери ограничения батареи для приложения") {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }
        }
        actionRow("Журнал воспроизведения", "Что делал плеер: старты, остановки, команды от системы. Поможет найти причину") { showPlayLog() }

        section("Сброс")
        actionRow("Сбросить настройки вида", "Папки, размеры и свечение вернутся к исходным") {
            AlertDialog.Builder(this).setTitle("Сбросить настройки?")
                .setPositiveButton("Сбросить") { _, _ -> MusicSettings.resetAll(); EqualizerManager.apply(); MusicLibrary.invalidate(); recreate() }
                .setNegativeButton("Отмена", null).show()
        }
    }

    // ---------- Статус ----------

    private fun refreshStatus() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rhythm = if (granted) "доступ к ритму есть" else "доступа к ритму нет, фон пульсирует условно"
        rhythmStatus?.text = "Сейчас: $rhythm"
        val st = statusText ?: return
        lifecycleScope.launch {
            val (total, tagged) = withContext(Dispatchers.IO) {
                val music = MusicLibrary.index(this@MusicSettingsActivity).allMusic()
                music.size to music.count { t -> MusicTags.get(this@MusicSettingsActivity, t)?.let { MusicTags.isTagged(it) } == true }
            }
            st.text = "Песен: $total · размечено: $tagged · $rhythm"
        }
    }

    private fun askRhythmAccess() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Доступ уже есть", Toast.LENGTH_SHORT).show()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 3)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun showPlayLog() {
        val log = PlayLog.read().ifBlank { "Пока пусто. Включи музыку, журнал начнёт заполняться." }
        AlertDialog.Builder(this).setTitle("Журнал воспроизведения")
            .setMessage(log.lines().takeLast(25).joinToString("\n"))
            .setPositiveButton("Поделиться") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), "Отправить журнал"))
            }
            .setNeutralButton("Очистить") { _, _ -> PlayLog.clear() }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun confirmRedo() {
        AlertDialog.Builder(this).setTitle("Разметить заново?")
            .setMessage("Автоматическая разметка сотрётся и будет сделана снова. Ручные правки и оценки сохранятся.")
            .setPositiveButton("Разметить") { _, _ -> runInMain("redo") }
            .setNegativeButton("Отмена", null).show()
    }

    private fun open(sectionName: String) {
        startActivity(Intent(this, MusicSettingsActivity::class.java).putExtra(EXTRA_SECTION, sectionName))
    }

    /** Действия живут в главном экране: открываем его с командой. */
    private fun runInMain(action: String) {
        startActivity(Intent(this, MusicActivity::class.java).putExtra(MusicActivity.EXTRA_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    // ---------- Элементы ----------

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(top) }

    private fun text(t: String, size: Float, color: Int) = TextView(this).apply {
        text = t; textSize = size; setTextColor(getColor(color)); setPadding(0, px(4), 0, px(4))
    }

    private fun header(t: String, big: Boolean = false) {
        root.addView(TextView(this).apply {
            text = t; textSize = if (big) 26f else 14f; setTextColor(getColor(R.color.harmonia_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, px(8))
        }, lp())
    }

    private fun section(t: String) {
        root.addView(TextView(this).apply {
            text = t.uppercase(); textSize = 12f; letterSpacing = 0.08f
            setTextColor(getColor(R.color.harmonia_violet)); setPadding(0, px(24), 0, px(4))
        }, lp())
    }

    private fun rowLayout(title: String, subtitle: String?): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(10), 0, px(10)); minimumHeight = px(52)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(getColor(R.color.harmonia_text)) })
        if (subtitle != null) col.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(getColor(R.color.harmonia_muted)) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row to col
    }

    private fun switchView(title: String, subtitle: String?, on: Boolean, change: (Boolean) -> Unit): LinearLayout {
        val (row, _) = rowLayout(title, subtitle)
        val sw = SwitchCompat(this).apply { isChecked = on; setOnCheckedChangeListener { _, v -> change(v) } }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun switchRow(title: String, subtitle: String?, on: Boolean, change: (Boolean) -> Unit) {
        root.addView(switchView(title, subtitle, on, change))
    }

    private fun actionRowView(title: String, subtitle: String?, click: () -> Unit): LinearLayout {
        val (row, _) = rowLayout(title, subtitle)
        row.setOnClickListener { click() }
        return row
    }

    private fun actionRow(title: String, subtitle: String?, click: () -> Unit) {
        root.addView(actionRowView(title, subtitle, click))
    }

    /** Строка перехода в раздел со стрелкой справа. */
    private fun navRow(title: String, subtitle: String, click: () -> Unit) {
        val row = actionRowView(title, subtitle, click)
        row.addView(TextView(this).apply { text = "›"; textSize = 24f; setTextColor(getColor(R.color.harmonia_muted)) })
        row.setPadding(0, px(14), 0, px(14))
        root.addView(row)
    }

    private fun choiceRow(title: String, options: List<String>, get: () -> Int, set: (Int) -> Unit) {
        val (row, _) = rowLayout(title, null)
        val value = TextView(this).apply { textSize = 14f; setTextColor(getColor(R.color.harmonia_cyan)); text = options[get().coerceIn(0, options.lastIndex)] }
        row.addView(value)
        row.setOnClickListener {
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), get()) { d, which -> set(which); value.text = options[which]; d.dismiss() }
                .show()
        }
        root.addView(row)
    }

    private fun sliderRow(title: String, min: Int, max: Int, step: Int, now: Int, unit: String, set: (Int) -> Unit) {
        val (row, col) = rowLayout(title, null)
        val value = TextView(this).apply { textSize = 14f; setTextColor(getColor(R.color.harmonia_cyan)); text = "$now$unit" }
        row.addView(value)
        var touching = false
        val bar = SeekBar(this).apply {
            this.max = (max - min) / step; progress = (now - min) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) value.text = "${min + p * step}$unit"
                    // клавиатура, джойстик и TalkBack: касания нет, сохраняем сразу
                    if (fromUser && !touching) set(min + p * step)
                }
                override fun onStartTrackingTouch(b: SeekBar) { touching = true }
                override fun onStopTrackingTouch(b: SeekBar) { touching = false; set(min + b.progress * step) }
            })
        }
        col.addView(bar)
        root.addView(row)
    }
}
