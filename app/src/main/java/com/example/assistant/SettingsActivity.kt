package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Central settings screen. Every control here is connected to a persisted preference
 * or an existing application feature; unavailable features are not presented as active controls.
 */
class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppSettings.wrap(newBase))
    }

    private val prefs by lazy { getSharedPreferences("assistant_prefs", MODE_PRIVATE) }
    /** Тело текущего раздела: в него добавляются карточки настроек. */
    private lateinit var content: LinearLayout
    /** Весь список разделов на экране. */
    private lateinit var root: LinearLayout
    private val openSections by lazy { prefs.getStringSet("settings_open", emptySet())!!.toMutableSet() }
    private var fontChanged = false

    override fun onStop() {
        super.onStop()
        if (fontChanged) {
            fontChanged = false
            recreate()
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            WakeWordService.setEnabled(this, true)
            Toast.makeText(this, "Слово активации включено", Toast.LENGTH_SHORT).show()
        } else {
            prefs.edit().putBoolean("wake_word", false).apply()
            Toast.makeText(this, "Для слова активации нужен доступ к микрофону", Toast.LENGTH_LONG).show()
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.chat_bg))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        val back = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "‹"
            textSize = 26f
            minWidth = dp(48)
            contentDescription = "Назад"
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(52), dp(48)))
        header.addView(TextView(this).apply {
            text = "Настройки"
            textSize = 23f
            setTextColor(ContextCompat.getColor(context, R.color.chat_text))
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        val scroll = android.widget.ScrollView(this).apply { clipToPadding = false }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }
        content = root
        scroll.addView(root)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        section("Основные функции", "Включай только то, чем пользуешься")
        addSwitch("Фоновое слово активации", "Постоянно слушает ключевую фразу; расходует заряд и требует микрофон.", WakeWordService.enabled(this)) { enabled ->
            if (!enabled) WakeWordService.setEnabled(this, false)
            else if (WakeWordService.hasMicPermission(this)) WakeWordService.setEnabled(this, true)
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        addSwitch("Озвучивать ответы", "Текстовые ответы ассистента будут произноситься вслух.", Speaker.enabled(this)) { Speaker.setEnabled(this, it) }
        addSwitch("Кнопка ассистента в шторке", "Постоянное уведомление с кнопками «Говорить», «Чат» и «Выкл».", prefs.getBoolean("quick_access_enabled", true)) {
            prefs.edit().putBoolean("quick_access_enabled", it).apply()
            if (it) AssistantService.start(this)
            else startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_STOP))
        }
        addSwitch("Голосовой ввод в чате", "Показывать кнопку микрофона рядом с полем ввода.", prefs.getBoolean("voice_input_enabled", true)) {
            prefs.edit().putBoolean("voice_input_enabled", it).apply()
        }
        addSwitch("Прикрепление файлов", "Показывать кнопку выбора файла в чате.", prefs.getBoolean("attachments_enabled", true)) {
            prefs.edit().putBoolean("attachments_enabled", it).apply()
        }
        addSwitch("Быстрые кнопки в чате", "Над полем ввода: «Перевести экран», «Что на экране», «Живой экран». В шторке — плитки с теми же названиями (добавь их, изменив набор плиток).", prefs.getBoolean("quick_bar_enabled", true)) {
            prefs.edit().putBoolean("quick_bar_enabled", it).apply()
        }
        addSwitch("Подсказки в пустом чате", "Примеры команд, когда переписка пустая.", prefs.getBoolean("suggestions_enabled", true)) {
            prefs.edit().putBoolean("suggestions_enabled", it).apply()
        }

        section("Планировщик и сообщения", "«Через 5 минут напиши Максиму в WhatsApp доброе утро», «в 23:00 выключи Wi-Fi», «каждый день в 7:30 включи мою волну». В назначенное время команда выполняется сама. Сообщение отправляет служба специальных возможностей: она нажимает «Отправить». Если телефон заблокирован паролем, команда ждёт разблокировки.")
        addSwitch("Отправлять запланированные сообщения сами", "Чат мессенджера откроется с текстом, и «Отправить» нажмётся автоматически.", Prefs.autoSendScheduled(this)) { Prefs.setAutoSendScheduled(this, it) }
        addSwitch("Отправлять и обычные «напиши …» сразу", "Без запланированного времени: ассистент сам нажмёт «Отправить». Выключено — текст подставляется, отправляешь ты.", Prefs.autoSendAlways(this)) { Prefs.setAutoSendAlways(this, it) }
        addAction("Запланированное", "Список, отмена и добавление") { startActivity(Intent(this, HubActivity::class.java).putExtra(HubActivity.EXTRA_SCHEDULED, true)) }

        section("Память, промпт и навыки", "Заметки, база знаний, свои указания модели, режимы, навыки и выученные фразы — каждое в своём экране.")
        addAction("Заметки и память", "Записи ассистента и твои: поиск, правка, удаление") { startActivity(Intent(this, NotesActivity::class.java)) }
        addAction("Промпт и навыки", "Мои указания, встроенные инструкции, режимы, навыки, выученное") { startActivity(Intent(this, PromptSkillsActivity::class.java)) }
        addAction("Проводник", "Файлы телефона, папка Assistant и внутренние данные — только просмотр") { startActivity(Intent(this, ExplorerActivity::class.java)) }

        section("Доска и чертёж", "Объяснения мелом на чёрной доске и технические чертежи. Шаги дорисовываются по очереди, пояснение звучит голосом.")
        addSwitch("Озвучивать шаги доски", "Каждый шаг объяснения проговаривается; дальше — когда договорит.", prefs.getBoolean("board_voice", Speaker.enabled(this))) {
            prefs.edit().putBoolean("board_voice", it).apply()
        }
        addSwitch("Инструкция «Доска и чертёж» для модели", "Выключи, если не нужны рисунки по шагам.", PromptStore.builtInEnabled(this, PromptStore.BOARD)) { PromptStore.setBuiltIn(this, PromptStore.BOARD, it) }

        section("Звуковые сигналы", "Высокий «пик» — слушаю, говори. Двойной — команда принята. Тихий щелчок — думаю дольше обычного. Низкий мягкий — перестал слушать. Низкий двойной — не получилось.")
        addAction("Прослушать сигналы", "По очереди: слушаю → принял → думаю → перестал слушать → ошибка") {
            val kinds = listOf(Cues.Kind.LISTEN, Cues.Kind.ACCEPT, Cues.Kind.THINKING, Cues.Kind.CLOSE, Cues.Kind.ERROR)
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            kinds.forEachIndexed { i, k -> h.postDelayed({ Cues.play(this, k) }, i * 900L) }
        }
        addSwitch("Сигнал начала прослушивания", "«Пик» после слова активации и когда ассистент ждёт ответа.", prefs.getBoolean("start_cue_enabled", true)) {
            prefs.edit().putBoolean("start_cue_enabled", it).apply()
        }
        addSeek("Громкость сигнала начала", prefs.getInt("start_cue_volume", 70), "%", 0, 100) {
            prefs.edit().putInt("start_cue_volume", it).apply()
        }
        addSwitch("Остальные сигналы", "Команда принята, думаю, перестал слушать, ошибка.", prefs.getBoolean("end_cue_enabled", true)) {
            prefs.edit().putBoolean("end_cue_enabled", it).apply()
        }
        addSeek("Громкость сигнала завершения", prefs.getInt("end_cue_volume", 70), "%", 0, 100) {
            prefs.edit().putInt("end_cue_volume", it).apply()
        }
        addSeek("Пауза конца фразы", AppSettings.int(this, AppSettings.END_SILENCE_MS), " мс",
            AppSettings.END_SILENCE_MS.min, AppSettings.END_SILENCE_MS.max) {
            AppSettings.setInt(this, AppSettings.END_SILENCE_MS, it)
        }
        addSeek("Время на команду после активации", (prefs.getLong("command_window_ms", 10_000L) / 1000L).toInt(), " сек", 3, 30) {
            prefs.edit().putLong("command_window_ms", it * 1000L).apply()
        }
        addSeek("Тайм-аут ответа ИИ", prefs.getInt("request_timeout_seconds", 45), " сек", 15, 120) {
            prefs.edit().putInt("request_timeout_seconds", it).apply()
        }
        addSeek("Пауза перед запуском голосового ввода", prefs.getInt("voice_input_delay_ms", 350), " мс", 0, 1500) {
            prefs.edit().putInt("voice_input_delay_ms", it).apply()
        }
        addSeek("Максимальное ожидание повтора после лимита API", prefs.getInt("retry_wait_seconds", 20), " сек", 0, 30) {
            prefs.edit().putInt("retry_wait_seconds", it).apply()
        }

        addSeek("Пауза после ответа ассистента", AppSettings.int(this, AppSettings.AFTER_ANSWER_MS), " мс",
            AppSettings.AFTER_ANSWER_MS.min, AppSettings.AFTER_ANSWER_MS.max) {
            AppSettings.setInt(this, AppSettings.AFTER_ANSWER_MS, it)
        }
        addSeek("Тишина, после которой голосовой ввод заканчивается", AppSettings.int(this, AppSettings.VOICE_SILENCE_MS), " мс",
            AppSettings.VOICE_SILENCE_MS.min, AppSettings.VOICE_SILENCE_MS.max) {
            AppSettings.setInt(this, AppSettings.VOICE_SILENCE_MS, it)
        }
        addSeek("Ожидание соединения с сервером", AppSettings.int(this, AppSettings.CONNECT_TIMEOUT_S), " сек",
            AppSettings.CONNECT_TIMEOUT_S.min, AppSettings.CONNECT_TIMEOUT_S.max) {
            AppSettings.setInt(this, AppSettings.CONNECT_TIMEOUT_S, it)
        }

        section("Микрофон", "Чтобы ассистент слышал тихо и шёпотом. Применяется при следующем запуске слушания (выключи и включи «Фоновое слово активации»)")
        addSwitch("Режим шёпота", "Тихая речь усиливается автоматически, шум и тишина — нет. Возможны ложные срабатывания в шумном месте.",
            AppSettings.bool(this, AppSettings.WHISPER, true)) {
            getSharedPreferences(AppSettings.PREFS, MODE_PRIVATE).edit().putBoolean(AppSettings.WHISPER, it).apply()
        }
        addSeek("Усиление микрофона (1 = без усиления)", AppSettings.int(this, AppSettings.MIC_GAIN), " ×",
            AppSettings.MIC_GAIN.min, AppSettings.MIC_GAIN.max) {
            AppSettings.setInt(this, AppSettings.MIC_GAIN, it)
        }

        section("Диалог", "Разговор без слова активации и перебивание голосом. Долгое нажатие на микрофон в чате начинает разговор, «давай поговорим» — тоже")
        addSwitch("Режим диалога", "После ответа можно сразу говорить дальше, без «${WakeWordService.phraseTitle(this)}». Молчание или «хватит» — конец.",
            AppSettings.bool(this, AppSettings.DIALOG, true)) {
            getSharedPreferences(AppSettings.PREFS, MODE_PRIVATE).edit().putBoolean(AppSettings.DIALOG, it).apply()
        }
        addSwitch("Перебивать голосом", "Микрофон слушает и пока ассистент говорит: скажи «стоп» или новую просьбу — он замолчит и выслушает. Лучше работает в наушниках.",
            AppSettings.bool(this, AppSettings.BARGE_IN, true)) {
            getSharedPreferences(AppSettings.PREFS, MODE_PRIVATE).edit().putBoolean(AppSettings.BARGE_IN, it).apply()
            // Применяем сразу: перезапуск слушания
            if (WakeWordService.enabled(this)) { stopService(Intent(this, WakeWordService::class.java)); WakeWordService.start(this) }
        }
        addSeek("Сколько ждать следующую реплику", AppSettings.int(this, AppSettings.DIALOG_WINDOW_S), " сек",
            AppSettings.DIALOG_WINDOW_S.min, AppSettings.DIALOG_WINDOW_S.max) {
            AppSettings.setInt(this, AppSettings.DIALOG_WINDOW_S, it)
        }

        section("Офлайн-мозг", "Работа без интернета: понималка команд, навыки и база знаний. Всё хранится в папке Assistant (База, Навыки)")
        addChoice("Режим", arrayOf("Авто: онлайн, а без сети — офлайн", "Только онлайн (Groq)", "Только офлайн"), OfflineBrain.mode(this),
            arrayOf("auto", "online", "offline")) { OfflineBrain.setMode(this, it) }
        addAction("Обучить понималку сейчас", "Groq придумает разговорные варианты команд — потом они понимаются без интернета. Само — раз в 5 дней") {
            Toast.makeText(this, "Обучаю… это займёт до минуты", Toast.LENGTH_SHORT).show()
            Thread {
                val r = try { kotlinx.coroutines.runBlocking { OfflineBrain.train(this@SettingsActivity, GroqClient(BuildConfig.GROQ_API_KEY, applicationContext)) } } catch (e: Exception) { "Ошибка: ${e.message}" }
                runOnUiThread { androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Понималка").setMessage(r).setPositiveButton("Ок", null).show() }
            }.start()
        }
        addAction("Навыки", "Сценарии на одну фразу: «я дома», «доброе утро», «спокойной ночи»… Включить и выключить") {
            Thread {
                val list = OfflineBrain.skills(this)
                runOnUiThread {
                    val checked = list.map { it.enabled }.toBooleanArray()
                    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Навыки (${list.size})")
                        .setMultiChoiceItems(list.map { "${it.name} — «${it.triggers.firstOrNull().orEmpty()}»" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                        .setPositiveButton("Сохранить") { _, _ ->
                            Thread { list.forEachIndexed { i, sk -> if (sk.enabled != checked[i]) OfflineBrain.saveSkill(this, sk.copy(enabled = checked[i])) } }.start()
                        }
                        .setNegativeButton("Отмена", null).show()
                }
            }.start()
        }
        addAction("База знаний", "Факты и справки, доступные без интернета: «запомни факт: …», «собери справочник по …», «что ты знаешь про …»") {
            Thread {
                val facts = OfflineBrain.facts(this)
                val stats = OfflineBrain.stats(this)
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("База знаний: ${facts.size}")
                        .setMessage(stats + "\n\n" + facts.take(60).joinToString("\n") { "• " + it.title + if (it.source == "встроено") "" else "  (${it.source})" })
                        .setPositiveButton("Ок", null).show()
                }
            }.start()
        }

        section("Экран", "Живой экран, переводчик и управление голосом. Нужна служба специальных возможностей «Assistant» (её можно включить через Shizuku)")
        addSwitch("Живой экран", "Вопросы о том, что видно («что тут?», «помоги ответить»), идут модели вместе со снимком экрана. У края экрана кнопки «?» и «Aあ».",
            ScreenAssist.live(this)) {
            Toast.makeText(this, ScreenAssist.setLive(this, it), Toast.LENGTH_LONG).show()
        }
        addSwitch("Чтение экрана по просьбе", "Разрешить смотреть на экран и нажимать по названию, когда просишь сам. Пароли не читаются.", Privacy.screenAllowed(this)) {
            Privacy.setScreenAllowed(this, it)
        }
        addAction("Перевести экран", "Сверни настройки и скажи «переведи экран» или нажми «Aあ» у края экрана. Можно «переведи экран на английский».") {
            Toast.makeText(this, if (AssistantAccessibilityService.instance == null) "Сначала включи службу специальных возможностей «Assistant»" else "Открой нужное приложение и нажми «Aあ»", Toast.LENGTH_LONG).show()
        }

        section("Функции команд", "Каждую возможность можно отключить отдельно. Выключенные функции не выполняются даже по голосовой команде.")
        val featureGroups = listOf(
            "Телефон и приложения" to listOf(
                "set_alarm" to "Будильники",
                "flashlight" to "Фонарик",
                "open_camera" to "Открытие камеры",
                "take_screenshot" to "Скриншоты",
                "open_app" to "Открытие приложений",
                "open_browser" to "Браузер и поиск сайтов",
                "open_settings" to "Системные настройки",
                "open_dialer" to "Телефон и набор номера",
                "open_whatsapp" to "WhatsApp"
            ),
            "Музыка" to listOf(
                "play_music" to "Воспроизведение музыки",
                "music_wave" to "Моя волна, похожие и подборки",
                "music_control" to "Управление плеером",
                "music_rate" to "Подбор музыки по настроению",
                "music_tags" to "Теги музыкальных файлов",
                "music_library" to "Поиск в музыкальной библиотеке",
                "playlist" to "Создание и изменение плейлистов",
                "set_volume" to "Громкость телефона/медиа"
            ),
            "Файлы и контакты" to listOf(
                "find_media" to "Поиск фото и видео",
                "find_files" to "Поиск файлов",
                "shared_folder" to "Общая папка и работа с файлами",
                "call_contact" to "Звонки контактам",
                "message_contact" to "Сообщения контактам",
                "find_contact" to "Поиск контактов"
            ),
            "Система и интернет" to listOf(
                "device_info" to "Состояние телефона",
                "read_screen" to "Чтение экрана",
                "kb" to "База знаний",
                "skills" to "Навыки",
                "screen_control" to "Управление экраном (назад, нажать, листать, ввести)",
                "screen_translate" to "Переводчик на экране",
                "live_screen" to "Живой экран",
                "files" to "Порядок в файлах (без удаления)",
                "notifications" to "Чтение уведомлений",
                "close_app" to "Закрытие приложений (Shizuku)",
                "grant_permissions" to "Выдача разрешений через Shizuku",
                "set_brightness" to "Яркость экрана",
                "quick_setting" to "Быстрые настройки телефона",
                "web_search" to "Поиск информации в интернете"
            ),
            "Планирование и заметки" to listOf(
                "set_timer" to "Таймеры",
                "set_reminder" to "Напоминания",
                "list_reminders" to "Просмотр напоминаний",
                "cancel_reminder" to "Отмена напоминаний",
                "schedule_command" to "Запланированные действия («через 5 минут напиши…»)",
                "calendar_add" to "Создание событий календаря",
                "calendar_list" to "Просмотр календаря",
                "create_note" to "Создание заметок",
                "list_notes" to "Просмотр заметок",
                "search_notes" to "Поиск заметок",
                "delete_note" to "Удаление заметок"
            ),
            "Профиль и сводки" to listOf(
                "set_profile" to "Сохранение данных профиля",
                "schedule_digest" to "Запланированные сводки",
                "list_digests" to "Просмотр сводок",
                "cancel_digest" to "Отмена сводок"
            )
        )
        featureGroups.forEach { (groupTitle, features) ->
            subsection(groupTitle)
            features.forEach { (key, label) ->
                addSwitch(label, "Разрешить ассистенту использовать эту команду.", prefs.getBoolean("feature_$key", true)) { enabled ->
                    prefs.edit().putBoolean("feature_$key", enabled).apply()
                }
            }
        }

        section("Внешний вид", "Изменения сохраняются на устройстве")
        addChoice("Тема приложения", arrayOf("Как в системе", "Светлая", "Тёмная"), prefs.getString("app_theme", "system") ?: "system",
            arrayOf("system", "light", "dark")) { selected ->
            prefs.edit().putString("app_theme", selected).apply()
            applyTheme(selected)
            recreate()
        }
        addSeek("Размер текста сообщений", prefs.getInt("chat_text_size", 16), " sp", 13, 23) {
            prefs.edit().putInt("chat_text_size", it).apply()
        }

        addSeek("Размер текста во всём приложении (откроется заново)", AppSettings.int(this, AppSettings.FONT_SCALE), " %",
            AppSettings.FONT_SCALE.min, AppSettings.FONT_SCALE.max) {
            AppSettings.setInt(this, AppSettings.FONT_SCALE, it)
            fontChanged = true
        }

        section("Голос и слово активации", "Слово активации, голос и скорость озвучки, проверка распознавания")
        addAction("Слово активации и батарея", "Изменить ключевую фразу и открыть настройки энергосбережения") {
            showWakePhraseDialog()
        }
        addAction("Голос и скорость озвучки", "Выбор установленного голоса и темпа речи") {
            showVoiceSettings()
        }
        addSwitch("Записывать всё услышанное (для отладки)", "Только на телефоне. Помогает найти, что именно распознаётся неверно. Выключи, когда не нужно.",
            ListenLog.allEnabled(this)) { ListenLog.setAllEnabled(this, it) }
        addAction("Журнал слушания", "Что услышал ассистент и что сделал: слово активации, команды") {
            val text = ListenLog.recent(this)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Журнал слушания")
                .setMessage(text)
                .setPositiveButton("Копировать") { _, _ ->
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("listen_log", text))
                    Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Очистить") { _, _ -> ListenLog.clear(this) }
                .setNegativeButton("Закрыть", null)
                .show()
        }
        addAction("Проверить фоновый микрофон", "Запустить диагностическое прослушивание") {
            if (WakeWordService.hasMicPermission(this)) WakeWordService.test(this)
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        addAction("Консоль (Shizuku)", "Процессы, выдача разрешений, переключатели без шторки. Статус: ${ShizukuShell.state(this).text}") {
            showShizuku()
        }
        addAction("Отчёт для разработчика", "Собрать диагностику (без переписок, контактов и файлов) и отправить — для разбора проблем") {
            Thread { val r = DevReport.share(this); runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() } }.start()
        }
        addAction("Выученные команды", "Фразы, которые ассистент выполняет без модели и интернета. Голосом: «чему ты научился», «забудь фразу …»") {
            Thread {
                val dao = NoteDatabase.getInstance(this).commandPatternDao()
                val list = kotlinx.coroutines.runBlocking { dao.top(200) }
                runOnUiThread {
                    val text = if (list.isEmpty()) "Пока ничего не выучено." else list.joinToString("\n") { p ->
                        "• " + p.phrase.removePrefix(Learning.TEMPLATE_PREFIX).replace("#", "N") + " → " +
                            when (p.functionName) { Learning.TAUGHT -> "твоя связка"; Learning.MACRO -> "связка"; else -> p.functionName } + " (${p.useCount})"
                    }
                    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Выучено: ${list.size}").setMessage(text)
                        .setPositiveButton("Ок", null)
                        .setNegativeButton("Забыть всё") { _, _ -> Thread { kotlinx.coroutines.runBlocking { dao.deleteAll() } }.start() }.show()
                }
            }.start()
        }
        addAction("Проверить переключатели", "Почему ассистент не может включить или выключить Wi-Fi, интернет, Bluetooth: Shizuku, службы, разрешения") {
            Thread {
                val report = try { SystemToggle.diagnose(this) } catch (e: Exception) { "Ошибка: ${e.message}" }
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Переключатели").setMessage(report)
                        .setPositiveButton("Ок", null)
                        .setNeutralButton("Выдать разрешения") { _, _ -> grantAllViaShizuku() }.show()
                }
            }.start()
        }
        addAction("Выдать все разрешения через Shizuku", "Файлы, микрофон, контакты, уведомления, специальные возможности, без ограничений батареи — одной кнопкой") {
            grantAllViaShizuku()
        }
        addAction("Сбросить настройки", "Вернуть значения по умолчанию (чаты, заметки и профиль не затрагиваются)") {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Сбросить настройки?")
                .setMessage("Переключатели, громкость, время ожидания, тема и размер текста вернутся к значениям по умолчанию.")
                .setPositiveButton("Сбросить") { _, _ ->
                    AppSettings.resetAll(this)
                    recreate()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        addAction("Настройки приватности", "Разрешения для чтения экрана и истории использования") {
            AlertDialogBridge.showPrivacy(this)
        }

        val note = TextView(this).apply {
            text = "Настройки сохраняются автоматически. Переключатели управляют соответствующими функциями; разрешения Android по-прежнему настраиваются отдельно."
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(ContextCompat.getColor(context, R.color.chat_text_dim))
            setPadding(dp(4), dp(14), dp(4), 0)
        }
        root.addView(note)
    }

    private fun applyTheme(value: String) {
        AppCompatDelegate.setDefaultNightMode(when (value) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    /**
     * Раздел настроек: в списке видна строка с названием и коротким описанием, по нажатию раскрываются
     * подробности и все параметры раздела. Какие разделы открыты, запоминается.
     */
    private fun section(title: String, subtitle: String) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(6)) }
        val open = title in openSections
        val arrow = TextView(this).apply { text = if (open) "▾" else "▸"; textSize = 20f; setTextColor(ContextCompat.getColor(context, R.color.chat_text_dim)) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                text = title; textSize = 16.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.chat_text))
            })
            texts.addView(TextView(context).apply {
                text = shortText(subtitle); textSize = 12.5f; maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.chat_text_dim))
            })
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(arrow)
        }
        val headCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.chat_surface))
            strokeWidth = dp(1); setStrokeColor(ContextCompat.getColor(context, R.color.chat_stroke))
            isClickable = true; isFocusable = true
            addView(head)
        }
        root.addView(headCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        // Полное описание — первым в раскрытом разделе
        body.addView(TextView(this).apply {
            text = subtitle; textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(ContextCompat.getColor(context, R.color.chat_text_dim))
            setPadding(dp(4), dp(8), dp(4), dp(6))
        })
        body.visibility = if (open) View.VISIBLE else View.GONE
        root.addView(body, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(6) })
        headCard.setOnClickListener {
            val nowOpen = body.visibility != View.VISIBLE
            body.visibility = if (nowOpen) View.VISIBLE else View.GONE
            arrow.text = if (nowOpen) "▾" else "▸"
            if (nowOpen) openSections += title else openSections -= title
            prefs.edit().putStringSet("settings_open", openSections.toSet()).apply()
        }
        content = body
    }

    /** Первое предложение описания — для свёрнутого вида. */
    private fun shortText(s: String): String {
        val first = s.split(Regex("(?<=[.!?])\\s|\\. ")).first().trim().trimEnd('.')
        return if (first.length > 90) first.take(88).trimEnd() + "…" else first
    }

    /** Подзаголовок внутри раскрытого раздела. */
    private fun subsection(title: String) {
        content.addView(TextView(this).apply {
            text = title; textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.chat_text))
            setPadding(dp(4), dp(12), dp(4), dp(4))
        })
    }

    private fun card(): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.chat_surface))
            strokeWidth = dp(1)
            setStrokeColor(ContextCompat.getColor(context, R.color.chat_stroke))
            addView(inner)
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        return inner
    }

    private fun addSwitch(title: String, summary: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
        val box = card()
        box.addView(SwitchMaterial(this).apply {
            text = title
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.chat_text))
            isChecked = checked
            minHeight = dp(48)
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        })
        box.addView(TextView(this).apply {
            text = summary
            textSize = 12.5f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(ContextCompat.getColor(context, R.color.chat_text_dim))
            setPadding(0, 0, dp(2), dp(8))
        })
    }

    private fun addSeek(title: String, initial: Int, suffix: String, min: Int, max: Int, onChanged: (Int) -> Unit) {
        val box = card()
        val valueLabel = TextView(this).apply {
            textSize = 14f
            text = "$initial$suffix"
            setTextColor(ContextCompat.getColor(context, R.color.chat_text))
            setPadding(0, dp(4), 0, 0)
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.chat_text))
        })
        box.addView(valueLabel)
        box.addView(SeekBar(this).apply {
            this.max = max - min
            progress = (initial.coerceIn(min, max) - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + min
                    valueLabel.text = "$value$suffix"
                    if (fromUser) onChanged(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
    }

    private fun addChoice(title: String, labels: Array<String>, current: String, values: Array<String>, onChosen: (String) -> Unit) {
        val box = card()
        box.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.chat_text))
            setPadding(0, dp(6), 0, dp(8))
        })
        val currentIndex = values.indexOf(current).coerceAtLeast(0)
        box.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = labels[currentIndex]
            isAllCaps = false
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, values.indexOf(current)) { dialog, which ->
                        onChosen(values[which])
                        dialog.dismiss()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        })
    }

    private fun addAction(title: String, summary: String, action: () -> Unit) {
        val box = card()
        box.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = title
            isAllCaps = false
            setOnClickListener { action() }
        })
        box.addView(TextView(this).apply {
            text = summary
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(context, R.color.chat_text_dim))
            setPadding(0, 0, 0, dp(6))
        })
    }

    /** Все разрешения одной кнопкой: файлы, микрофон, контакты, специальные возможности, уведомления, батарея. */
    private fun grantAllViaShizuku() {
        if (!ShizukuShell.enabled(this)) ShizukuShell.setEnabled(this, true)
        if (!Shell.available(this)) {
            Toast.makeText(this, "Shizuku: ${ShizukuShell.state(this).text}. Запусти Shizuku и выдай ему разрешение для Assistant.", Toast.LENGTH_LONG).show()
            ShizukuShell.requestPermission(this)
            return
        }
        Toast.makeText(this, "Выдаю разрешения…", Toast.LENGTH_SHORT).show()
        Thread {
            val report = try { ShizukuPower.report(ShizukuPower.grantAll(this)) } catch (e: Exception) { "Ошибка: ${e.message}" }
            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Разрешения через Shizuku").setMessage(report)
                    .setPositiveButton("Ок") { _, _ -> recreate() }.show()
            }
        }.start()
    }

    private fun showShizuku() {
        val on = ShizukuShell.enabled(this)
        val state = ShizukuShell.state(this)
        val labels = arrayOf(
            if (on) "Выключить консоль" else "Включить консоль",
            "Запросить разрешение у Shizuku",
            "Открыть Shizuku",
            "Проверить консоль (покажет точную причину)",
            "Выдать ассистенту все разрешения через Shizuku"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Консоль (Shizuku): ${state.text}")
            .setItems(labels) { dialog, which ->
                when (which) {
                    0 -> {
                        ShizukuShell.setEnabled(this, !on)
                        recreate()
                    }
                    1 -> {
                        if (!ShizukuShell.enabled(this)) ShizukuShell.setEnabled(this, true)
                        val ok = ShizukuShell.requestPermission(this)
                        Toast.makeText(this, if (ok) "Подтверди запрос в Shizuku и открой этот экран снова" else "Сначала запусти Shizuku", Toast.LENGTH_LONG).show()
                    }
                    4 -> grantAllViaShizuku()
                    3 -> {
                        val report = ShizukuShell.selfTest(this)
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Проверка консоли")
                            .setMessage(report)
                            .setPositiveButton("Ок", null)
                            .show()
                    }
                    else -> {
                        val intent = packageManager.getLaunchIntentForPackage(ShizukuShell.PACKAGE)
                        if (intent != null) startActivity(intent)
                        else Toast.makeText(this, "Shizuku не установлен", Toast.LENGTH_LONG).show()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showWakePhraseDialog() {
        val field = android.widget.EditText(this).apply {
            setText(WakeWordService.phraseTitle(this@SettingsActivity))
            setSingleLine()
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Слово активации")
            .setMessage("Используй фразу не короче четырёх букв. Для устойчивой работы на Xiaomi может потребоваться разрешить автозапуск и работу без ограничений батареи.")
            .setView(field)
            .setPositiveButton("Сохранить") { _, _ ->
                WakeWordService.setPhrase(this, field.text.toString())
                Toast.makeText(this, "Слово активации сохранено", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Настройки батареи") { _, _ ->
                try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showVoiceSettings() {
        Speaker.voices(applicationContext) { voices ->
            runOnUiThread {
                val rates = floatArrayOf(0.8f, 0.95f, 1.1f, 1.3f)
                val names = arrayOf("Медленно", "Спокойно", "Быстрее", "Быстро")
                var rateIndex = rates.indexOfFirst { kotlin.math.abs(it - Speaker.rate(this)) < 0.03f }.let { if (it < 0) 1 else it }
                var brief = Speaker.brief(this)
                val labels = (listOf("Скорость: ${names[rateIndex]}", "Длинные ответы: ${if (brief) "кратко" else "полностью"}") + voices.map { it.label } + "Проверить голос").toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Голос озвучки")
                    .setItems(labels) { dialog, which ->
                        when {
                            which == 0 -> {
                                rateIndex = (rateIndex + 1) % rates.size
                                Speaker.setRate(this, rates[rateIndex])
                                Speaker.sample(this)
                            }
                            which == 1 -> {
                                brief = !brief
                                Speaker.setBrief(this, brief)
                            }
                            which == labels.size - 1 -> Speaker.sample(this)
                            else -> Speaker.setVoice(this, voices[which - 2].name).also { Speaker.sample(this) }
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Закрыть", null)
                    .show()
            }
        }
    }
}

/** Reuse the existing privacy settings flow without duplicating its permission semantics. */
private object AlertDialogBridge {
    fun showPrivacy(activity: SettingsActivity) {
        val screenOn = Privacy.screenAllowed(activity)
        val usageOn = DeviceStatus.hasUsageAccess(activity)
        val labels = arrayOf(
            "Чтение экрана: ${if (screenOn) "включено" else "выключено"}",
            "История использования приложений: ${if (usageOn) "разрешено" else "не разрешено"}",
            "Служба специальных возможностей",
            "Shizuku (пока не подключён)"
        )
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Приватность")
            .setItems(labels) { dialog, which ->
                when (which) {
                    0 -> {
                        Privacy.setScreenAllowed(activity, !screenOn)
                        Toast.makeText(activity, if (!screenOn) "Чтение экрана включено" else "Чтение экрана выключено", Toast.LENGTH_SHORT).show()
                    }
                    1 -> activity.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    2 -> activity.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    else -> Toast.makeText(activity, "Shizuku пока не подключён", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
