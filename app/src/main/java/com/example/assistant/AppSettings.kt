package com.example.assistant

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/** Числовая настройка: ключ, значение по умолчанию, границы и шаг. Любое значение приводится к допустимому. */
data class IntSpec(val key: String, val default: Int, val min: Int, val max: Int, val step: Int = 1) {
    fun clamp(value: Int): Int {
        val v = value.coerceIn(min, max)
        // Приводим к сетке шага от минимума, чтобы ползунок и сохранённое значение совпадали
        return (min + ((v - min + step / 2) / step) * step).coerceIn(min, max)
    }
}

/** Приложение-функция, которую можно включить или выключить в настройках. */
data class Feature(val key: String, val title: String, val description: String)

/**
 * Единый источник настроек. Хранятся в том же файле и под теми же ключами, что и раньше
 * (ключи озвучки, слова активации и чтения экрана не менялись), новые ключи имеют безопасные значения по умолчанию.
 */
object AppSettings {

    const val PREFS = "assistant_prefs"

    // --- звуки: громкость — внутренний уровень сигнала (0–100%), системная громкость не меняется
    val CUE_START_VOLUME = IntSpec("cue_start_vol", 100, 0, 100, 5)
    val CUE_END_VOLUME = IntSpec("cue_end_vol", 100, 0, 100, 5)
    const val CUE_START_ON = "cue_start_on"
    const val CUE_END_ON = "cue_end_on"

    // --- запросы и ожидание (секунды, если не сказано иначе)
    val NET_CONNECT_S = IntSpec("net_connect_s", 30, 5, 60, 5)
    val NET_READ_S = IntSpec("net_read_s", 45, 10, 120, 5)
    /** Сколько максимум ждать самому после лимита Groq, прежде чем показать ошибку; 0 — не ждать. */
    val RETRY_MAX_S = IntSpec("retry_max_s", 20, 0, 60, 5)
    /** Сколько ждать команду после слова активации. */
    val COMMAND_WINDOW_S = IntSpec("command_window_s", 10, 3, 30, 1)
    /** Пауза после ответа ассистента, прежде чем снова слушать (мс). */
    val AFTER_ANSWER_MS = IntSpec("after_answer_ms", 1800, 500, 5000, 100)
    /** Сколько тишины считать концом фразы при голосовом вводе (мс). */
    val VOICE_SILENCE_MS = IntSpec("voice_silence_ms", 1800, 800, 3500, 100)

    const val AUTO_MUSIC_TAG = "auto_music_tag"

    // --- внешний вид
    const val THEME = "ui_theme" // system | light | dark
    val FONT_SCALE = IntSpec("ui_font_scale", 100, 80, 140, 5)

    /** Функции, которые можно выключить целиком. Подтверждения опасных действий выключить нельзя. */
    val FEATURES: List<Feature> = listOf(
        Feature("alarms", "Будильник", "Ставить будильники"),
        Feature("reminders", "Таймер и напоминания", "Таймеры и напоминания по времени"),
        Feature("calendar", "Календарь", "Читать и добавлять события"),
        Feature("music", "Музыка", "Поиск, управление, плейлисты, лайки, разметка"),
        Feature("notes", "Заметки", "Создавать, искать, удалять заметки"),
        Feature("phone", "Фонарик, камера, скриншот", "Простые действия телефона"),
        Feature("apps", "Приложения и сайты", "Открывать приложения, браузер, настройки, набор номера"),
        Feature("files", "Файлы и общая папка", "Искать файлы и фото, работать с папкой Assistant"),
        Feature("contacts", "Контакты, звонки, сообщения", "Звонить и писать по имени"),
        Feature("web", "Интернет и погода", "Поиск, погода, сводки"),
        Feature("system", "Шторка и яркость", "Wi-Fi, Bluetooth, режим полёта, яркость"),
        Feature("device", "Состояние телефона", "Заряд, сеть, память, приложения")
    )

    private val toolFeature: Map<String, String> = mapOf(
        "set_alarm" to "alarms",
        "set_timer" to "reminders", "set_reminder" to "reminders", "list_reminders" to "reminders", "cancel_reminder" to "reminders",
        "calendar_add" to "calendar", "calendar_list" to "calendar",
        "play_music" to "music", "music_control" to "music", "set_volume" to "music", "playlist" to "music",
        "music_library" to "music", "music_tags" to "music", "music_rate" to "music",
        "create_note" to "notes", "list_notes" to "notes", "search_notes" to "notes", "delete_note" to "notes",
        "flashlight" to "phone", "open_camera" to "phone", "take_screenshot" to "phone",
        "open_app" to "apps", "open_browser" to "apps", "open_settings" to "apps", "open_dialer" to "apps", "open_whatsapp" to "apps",
        "find_media" to "files", "find_files" to "files", "shared_folder" to "files",
        "call_contact" to "contacts", "message_contact" to "contacts", "find_contact" to "contacts",
        "web_search" to "web", "schedule_digest" to "web", "list_digests" to "web", "cancel_digest" to "web",
        "quick_setting" to "system", "set_brightness" to "system",
        "device_info" to "device"
    )

    /** К какой функции относится вызов (null — всегда разрешён). */
    fun featureOf(tool: String): String? = toolFeature[tool]

    fun feature(key: String): Feature? = FEATURES.firstOrNull { it.key == key }

    // ---------------------------------------------------------------- чтение и запись

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bool(context: Context, key: String, default: Boolean = true) = prefs(context).getBoolean(key, default)

    fun setBool(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun int(context: Context, spec: IntSpec): Int = spec.clamp(prefs(context).getInt(spec.key, spec.default))

    fun setInt(context: Context, spec: IntSpec, value: Int) {
        prefs(context).edit().putInt(spec.key, spec.clamp(value)).apply()
    }

    fun featureOn(context: Context, key: String) = bool(context, "feat_$key", true)

    fun setFeature(context: Context, key: String, on: Boolean) = setBool(context, "feat_$key", on)

    /** Ответ вместо выполнения, если функция выключена в настройках; null — можно выполнять. */
    fun blockedMessage(context: Context, tool: String): String? {
        val key = featureOf(tool) ?: return null
        if (featureOn(context, key)) return null
        return "Функция «${feature(key)?.title ?: key}» выключена в настройках."
    }

    // ---------------------------------------------------------------- оформление

    fun theme(context: Context): String =
        prefs(context).getString(THEME, "system").let { if (it == "light" || it == "dark") it!! else "system" }

    fun setTheme(context: Context, value: String) {
        prefs(context).edit().putString(THEME, if (value == "light" || value == "dark") value else "system").apply()
        applyTheme(context)
    }

    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme(context)) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** Контекст с учётом размера текста из настроек: вызывать в attachBaseContext экранов. */
    fun wrap(base: Context): Context {
        val percent = int(base, FONT_SCALE)
        if (percent == 100) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = base.resources.configuration.fontScale * percent / 100f
        return base.createConfigurationContext(config)
    }

    /** Возвращает все настройки этого экрана к значениям по умолчанию (данные, чаты и профиль не трогаются). */
    fun resetAll(context: Context) {
        val editor = prefs(context).edit()
        listOf(
            CUE_START_VOLUME, CUE_END_VOLUME, NET_CONNECT_S, NET_READ_S, RETRY_MAX_S,
            COMMAND_WINDOW_S, AFTER_ANSWER_MS, VOICE_SILENCE_MS, FONT_SCALE
        ).forEach { editor.remove(it.key) }
        listOf(CUE_START_ON, CUE_END_ON, AUTO_MUSIC_TAG, THEME).forEach { editor.remove(it) }
        FEATURES.forEach { editor.remove("feat_${it.key}") }
        editor.apply()
        applyTheme(context)
    }
}
