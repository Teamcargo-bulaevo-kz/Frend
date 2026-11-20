package com.example.assistant

import android.content.Context
import android.content.SharedPreferences

/** Все настройки приложения в одном месте. Значения по умолчанию: «чистый» минималистичный вид. */
object MusicSettings {

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("harmonia_settings", Context.MODE_PRIVATE)
    }

    private fun ready() = ::sp.isInitialized

    // --- Основные ---
    /** Свечение за обложкой, реагирующее на ритм и настроение. */
    var auraEnabled: Boolean
        get() = !ready() || sp.getBoolean("aura", true)
        set(v) = sp.edit().putBoolean("aura", v).apply()

    /** Яркость свечения, 30..100 %. */
    var auraBrightness: Int
        get() = if (ready()) sp.getInt("aura_brightness", 100).coerceIn(30, 100) else 100
        set(v) = sp.edit().putInt("aura_brightness", v.coerceIn(30, 100)).apply()

    /** Размер обложки: 0 маленькая, 1 средняя, 2 большая. */
    var coverSize: Int
        get() = if (ready()) sp.getInt("cover_size", 1).coerceIn(0, 2) else 1
        set(v) = sp.edit().putInt("cover_size", v.coerceIn(0, 2)).apply()

    /** Размер кнопок управления: 0 маленькие, 1 обычные, 2 крупные. */
    var buttonSize: Int
        get() = if (ready()) sp.getInt("button_size", 0).coerceIn(0, 2) else 0
        set(v) = sp.edit().putInt("button_size", v.coerceIn(0, 2)).apply()

    /** Размер текста названия: 0 мелкий, 1 обычный, 2 крупный. */
    var textSize: Int
        get() = if (ready()) sp.getInt("text_size", 1).coerceIn(0, 2) else 1
        set(v) = sp.edit().putInt("text_size", v.coerceIn(0, 2)).apply()

    /** Живое свечение на фоне списка песен. */
    var listAura: Boolean
        get() = !ready() || sp.getBoolean("list_aura", true)
        set(v) = sp.edit().putBoolean("list_aura", v).apply()

    // --- Анимация свечения ---
    /** Размер свечения, 60..150 %. */
    var glowSize: Int
        get() = if (ready()) sp.getInt("glow_size", 100).coerceIn(60, 150) else 100
        set(v) = sp.edit().putInt("glow_size", v.coerceIn(60, 150)).apply()

    /** Размах пульса (насколько свечение расширяется и сжимается), 30..150 %. */
    var pulseAmplitude: Int
        get() = if (ready()) sp.getInt("pulse_amp", 100).coerceIn(30, 200) else 100
        set(v) = sp.edit().putInt("pulse_amp", v.coerceIn(30, 200)).apply()

    /** Плавность пульса, 0..100: больше значит мягче и медленнее затухает. */
    var pulseSmooth: Int
        get() = if (ready()) sp.getInt("pulse_smooth", 50).coerceIn(0, 100) else 50
        set(v) = sp.edit().putInt("pulse_smooth", v.coerceIn(0, 100)).apply()

    /** Источник пульса: 0 только удары бочки (спокойно, рекомендуется), 1 весь бас (чаще и резче). */
    var pulseSource: Int
        get() = if (ready()) sp.getInt("pulse_source", 2).coerceIn(0, 2) else 2
        set(v) = sp.edit().putInt("pulse_source", v.coerceIn(0, 2)).apply()

    /** Не чаще стольких пульсов в секунду (1..4). */
    var maxPulsesPerSec: Int
        get() = if (ready()) sp.getInt("max_pulses", 3).coerceIn(1, 6) else 3
        set(v) = sp.edit().putInt("max_pulses", v.coerceIn(1, 6)).apply()

    /** Вклад частот в пульс, 0..150 %: низкие (удары, бас), средние (голос, гитары), высокие (тарелки). */
    var bandLow: Int
        get() = if (ready()) sp.getInt("band_low", 100).coerceIn(0, 200) else 100
        set(v) = sp.edit().putInt("band_low", v.coerceIn(0, 200)).apply()
    var bandMid: Int
        get() = if (ready()) sp.getInt("band_mid", 50).coerceIn(0, 200) else 50
        set(v) = sp.edit().putInt("band_mid", v.coerceIn(0, 200)).apply()
    var bandHigh: Int
        get() = if (ready()) sp.getInt("band_high", 25).coerceIn(0, 200) else 25
        set(v) = sp.edit().putInt("band_high", v.coerceIn(0, 200)).apply()

    /** Подстраивать пульс под темп песни (BPM) и держать его в такт ударам. */
    var tempoLock: Boolean
        get() = !ready() || sp.getBoolean("tempo_lock", true)
        set(v) = sp.edit().putBoolean("tempo_lock", v).apply()

    // --- Палитра ---
    /** 0 по настроению песни, 1..7 готовые палитры, 8 своя. */
    var paletteMode: Int
        get() = if (ready()) sp.getInt("palette_mode", 0).coerceIn(0, 8) else 0
        set(v) = sp.edit().putInt("palette_mode", v.coerceIn(0, 8)).apply()

    var customHue: Int
        get() = if (ready()) sp.getInt("custom_hue", 270).coerceIn(0, 359) else 270
        set(v) = sp.edit().putInt("custom_hue", v.coerceIn(0, 359)).apply()

    /** Ширина палитры: насколько соседние цвета расходятся от центра, 0..100 %. */
    var paletteSpread: Int
        get() = if (ready()) sp.getInt("palette_spread", 50).coerceIn(0, 100) else 50
        set(v) = sp.edit().putInt("palette_spread", v.coerceIn(0, 100)).apply()

    var paletteSaturation: Int
        get() = if (ready()) sp.getInt("palette_sat", 90).coerceIn(30, 100) else 90
        set(v) = sp.edit().putInt("palette_sat", v.coerceIn(30, 100)).apply()

    /** Скорость смены цвета при смене песни или части песни, 0..100 (больше быстрее). */
    var colorSpeed: Int
        get() = if (ready()) sp.getInt("color_speed", 40).coerceIn(0, 100) else 40
        set(v) = sp.edit().putInt("color_speed", v.coerceIn(0, 100)).apply()

    /** Цвет меняется по частям песни (куплет, припев), а не один на всю песню. */
    var segmentColors: Boolean
        get() = !ready() || sp.getBoolean("segment_colors", true)
        set(v) = sp.edit().putBoolean("segment_colors", v).apply()

    // --- Фон и разметка ---
    /** Пауза при отключении наушников или Bluetooth. */
    var pauseOnUnplug: Boolean
        get() = !ready() || sp.getBoolean("pause_unplug", true)
        set(v) = sp.edit().putBoolean("pause_unplug", v).apply()

    /** Сколько песен анализировать одновременно: 0 авто, 1..4. */
    var analysisWorkers: Int
        get() = if (ready()) sp.getInt("analysis_workers", 0).coerceIn(0, 4) else 0
        set(v) = sp.edit().putInt("analysis_workers", v.coerceIn(0, 4)).apply()

    // --- Звук ---
    var eqEnabled: Boolean
        get() = ready() && sp.getBoolean("eq_on", false)
        set(v) = sp.edit().putBoolean("eq_on", v).apply()

    /** Уровни полос эквалайзера в милли-децибелах через запятую. */
    var eqLevels: List<Int>
        get() = if (ready()) sp.getString("eq_levels", "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() } else emptyList()
        set(v) = sp.edit().putString("eq_levels", v.joinToString(",")).apply()

    /** Усиление баса, 0..100 %. */
    var bassBoost: Int
        get() = if (ready()) sp.getInt("bass_boost", 0).coerceIn(0, 100) else 0
        set(v) = sp.edit().putInt("bass_boost", v.coerceIn(0, 100)).apply()

    // --- Обложки ---
    /** Откуда брать обложки: 0 как в системе, 1 только из файла песни, 2 не показывать. */
    var coverSource: Int
        get() = if (ready()) sp.getInt("cover_source", 0).coerceIn(0, 2) else 0
        set(v) = sp.edit().putInt("cover_source", v.coerceIn(0, 2)).apply()

    var showSeek: Boolean
        get() = !ready() || sp.getBoolean("show_seek", true)
        set(v) = sp.edit().putBoolean("show_seek", v).apply()

    var showRating: Boolean
        get() = !ready() || sp.getBoolean("show_rating", true)
        set(v) = sp.edit().putBoolean("show_rating", v).apply()

    // --- Продвинутые ---
    /** Отладочный вид плеера: спектр, карта настроения, строка характеристик, вперемешку/повтор. */
    var debugView: Boolean
        get() = ready() && sp.getBoolean("debug_view", false)
        set(v) = sp.edit().putBoolean("debug_view", v).apply()

    /** Частота кадров фона: 20, 30 или 60. */
    var fps: Int
        get() = if (ready()) sp.getInt("fps", 30).let { if (it in setOf(20, 30, 60)) it else 30 } else 30
        set(v) = sp.edit().putInt("fps", v).apply()

    /** Чувствительность к ритму, 50..150 %. */
    var beatSensitivity: Int
        get() = if (ready()) sp.getInt("beat_sens", 100).coerceIn(50, 250) else 100
        set(v) = sp.edit().putInt("beat_sens", v.coerceIn(50, 250)).apply()

    /** Папки, которые приложение не показывает и не сканирует. */
    var disabledFolders: Set<String>
        get() = if (ready()) sp.getStringSet("disabled_folders", emptySet()).orEmpty().toSet() else emptySet()
        set(v) = sp.edit().putStringSet("disabled_folders", v).apply()

    fun isFolderEnabled(dir: String) = dir !in disabledFolders

    /** Путь файла лежит в выключенной папке? */
    fun isPathHidden(path: String): Boolean {
        if (path.isEmpty()) return false
        val off = disabledFolders
        if (off.isEmpty()) return false
        val dir = path.substringBeforeLast('/', "")
        return dir in off
    }

    /** Порог срабатывания пульса, %: больше значение, тем реже пульс. */
    var onsetThreshold: Int
        get() = if (ready()) sp.getInt("onset_thr", 100).coerceIn(50, 150) else 100
        set(v) = sp.edit().putInt("onset_thr", v.coerceIn(50, 150)).apply()

    /** Скорость нарастания свечения на ударе, 50 = как раньше. */
    var pulseAttack: Int
        get() = if (ready()) sp.getInt("pulse_attack", 50).coerceIn(0, 100) else 50
        set(v) = sp.edit().putInt("pulse_attack", v.coerceIn(0, 100)).apply()

    /** Скорость затухания свечения после удара, 50 = как раньше. */
    var pulseRelease: Int
        get() = if (ready()) sp.getInt("pulse_release", 50).coerceIn(0, 100) else 50
        set(v) = sp.edit().putInt("pulse_release", v.coerceIn(0, 100)).apply()

    /** Насколько яркость следует за пульсом, %. */
    var brightnessPulse: Int
        get() = if (ready()) sp.getInt("bright_pulse", 100).coerceIn(0, 150) else 100
        set(v) = sp.edit().putInt("bright_pulse", v.coerceIn(0, 150)).apply()

    /** Медленное дыхание свечения между ударами, %. */
    var idleBreath: Int
        get() = if (ready()) sp.getInt("idle_breath", 20).coerceIn(0, 100) else 20
        set(v) = sp.edit().putInt("idle_breath", v.coerceIn(0, 100)).apply()

    /** Живость: плавная реакция свечения на громкость и бас между ударами, %. */
    var liveliness: Int
        get() = if (ready()) sp.getInt("liveliness", 70).coerceIn(0, 150) else 70
        set(v) = sp.edit().putInt("liveliness", v.coerceIn(0, 150)).apply()

    /** Дрожь верхов: лёгкое мерцание яркости от высоких частот, %. */
    var shimmer: Int
        get() = if (ready()) sp.getInt("shimmer", 40).coerceIn(0, 100) else 40
        set(v) = sp.edit().putInt("shimmer", v.coerceIn(0, 100)).apply()

    /** Игра оттенка: небольшое смещение цвета от средних частот (до 18 градусов), %. */
    var hueSway: Int
        get() = if (ready()) sp.getInt("hue_sway", 40).coerceIn(0, 100) else 40
        set(v) = sp.edit().putInt("hue_sway", v.coerceIn(0, 100)).apply()

    /** Нижняя граница анализируемых частот, Гц. */
    var freqLow: Int
        get() = if (ready()) sp.getInt("freq_low", 30).coerceIn(20, 200) else 30
        set(v) = sp.edit().putInt("freq_low", v.coerceIn(20, 200)).apply()

    /** Верхняя граница анализируемых частот, Гц. */
    var freqHigh: Int
        get() = if (ready()) sp.getInt("freq_high", 12000).coerceIn(4000, 16000) else 12000
        set(v) = sp.edit().putInt("freq_high", v.coerceIn(4000, 16000)).apply()

    fun resetAll() { if (ready()) sp.edit().clear().apply() }

    val coverFactor get() = floatArrayOf(0.45f, 0.6f, 0.8f)[coverSize]
    val buttonScale get() = floatArrayOf(0.85f, 1.0f, 1.2f)[buttonSize]
    val titleSp get() = floatArrayOf(14f, 17f, 20f)[textSize]
    val artistSp get() = titleSp * 0.72f
}
