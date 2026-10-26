package com.example.assistant

import android.content.Context
import android.content.SharedPreferences

/**
 * Typed access to settings for the settings screen and runtime consumers.
 *
 * Existing preference file and legacy keys are intentionally preserved so upgrades
 * do not reset wake-word, speech, or screen-reading behavior.
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var speakReplies: Boolean
        get() = prefs.getBoolean(KEY_SPEAK_REPLIES, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEAK_REPLIES, value).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD, value).apply()

    var screenReadingAllowed: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_SCREEN_READING, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_SCREEN_READING, value).apply()

    var startCueEnabled: Boolean
        get() = prefs.getBoolean(KEY_START_CUE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_START_CUE_ENABLED, value).apply()

    var endCueEnabled: Boolean
        get() = prefs.getBoolean(KEY_END_CUE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_END_CUE_ENABLED, value).apply()

    var startCueVolume: Int
        get() = SettingsRanges.cueVolume(prefs.getInt(KEY_START_CUE_VOLUME, DEFAULT_CUE_VOLUME))
        set(value) = prefs.edit().putInt(KEY_START_CUE_VOLUME, SettingsRanges.cueVolume(value)).apply()

    var endCueVolume: Int
        get() = SettingsRanges.cueVolume(prefs.getInt(KEY_END_CUE_VOLUME, DEFAULT_CUE_VOLUME))
        set(value) = prefs.edit().putInt(KEY_END_CUE_VOLUME, SettingsRanges.cueVolume(value)).apply()

    var connectTimeoutSeconds: Int
        get() = SettingsRanges.connectTimeout(prefs.getInt(KEY_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT))
        set(value) = prefs.edit().putInt(KEY_CONNECT_TIMEOUT, SettingsRanges.connectTimeout(value)).apply()

    var readTimeoutSeconds: Int
        get() = SettingsRanges.readTimeout(prefs.getInt(KEY_READ_TIMEOUT, DEFAULT_READ_TIMEOUT))
        set(value) = prefs.edit().putInt(KEY_READ_TIMEOUT, SettingsRanges.readTimeout(value)).apply()

    var rateLimitMaxWaitSeconds: Int
        get() = SettingsRanges.rateLimitWait(prefs.getInt(KEY_RATE_LIMIT_WAIT, DEFAULT_RATE_LIMIT_WAIT))
        set(value) = prefs.edit().putInt(KEY_RATE_LIMIT_WAIT, SettingsRanges.rateLimitWait(value)).apply()

    var commandWindowSeconds: Int
        get() = SettingsRanges.commandWindow(prefs.getInt(KEY_COMMAND_WINDOW, DEFAULT_COMMAND_WINDOW))
        set(value) = prefs.edit().putInt(KEY_COMMAND_WINDOW, SettingsRanges.commandWindow(value)).apply()

    var afterReplyDelayMs: Int
        get() = SettingsRanges.afterReplyDelay(prefs.getInt(KEY_AFTER_REPLY_DELAY, DEFAULT_AFTER_REPLY_DELAY))
        set(value) = prefs.edit().putInt(KEY_AFTER_REPLY_DELAY, SettingsRanges.afterReplyDelay(value)).apply()

    var voiceCompleteSilenceMs: Int
        get() = SettingsRanges.voiceSilence(prefs.getInt(KEY_VOICE_COMPLETE_SILENCE, DEFAULT_VOICE_COMPLETE_SILENCE))
        set(value) = prefs.edit().putInt(KEY_VOICE_COMPLETE_SILENCE, SettingsRanges.voiceSilence(value)).apply()

    var theme: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM)?.takeIf { it in THEMES } ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value.takeIf { it in THEMES } ?: THEME_SYSTEM).apply()

    var textScalePercent: Int
        get() = SettingsRanges.textScale(prefs.getInt(KEY_TEXT_SCALE, DEFAULT_TEXT_SCALE))
        set(value) = prefs.edit().putInt(KEY_TEXT_SCALE, SettingsRanges.textScale(value)).apply()

    /** All feature switches default to enabled to preserve existing app behavior. */
    fun isFeatureEnabled(featureKey: String): Boolean =
        prefs.getBoolean(featurePreferenceKey(featureKey), true)

    fun setFeatureEnabled(featureKey: String, enabled: Boolean) {
        prefs.edit().putBoolean(featurePreferenceKey(featureKey), enabled).apply()
    }

    fun clearFeatureOverride(featureKey: String) {
        prefs.edit().remove(featurePreferenceKey(featureKey)).apply()
    }

    private fun featurePreferenceKey(featureKey: String): String =
        KEY_FEATURE_PREFIX + featureKey.trim().lowercase().replace(' ', '_')

    companion object {
        const val PREFS_NAME = "assistant_prefs"

        // Legacy keys: do not rename; existing installs already contain these values.
        const val KEY_SPEAK_REPLIES = "speak_replies"
        const val KEY_WAKE_WORD = "wake_word"
        const val KEY_ALLOW_SCREEN_READING = "allow_screen_reading"

        const val KEY_START_CUE_ENABLED = "settings_start_cue_enabled"
        const val KEY_END_CUE_ENABLED = "settings_end_cue_enabled"
        const val KEY_START_CUE_VOLUME = "settings_start_cue_volume"
        const val KEY_END_CUE_VOLUME = "settings_end_cue_volume"
        const val KEY_CONNECT_TIMEOUT = "settings_connect_timeout_seconds"
        const val KEY_READ_TIMEOUT = "settings_read_timeout_seconds"
        const val KEY_RATE_LIMIT_WAIT = "settings_rate_limit_wait_seconds"
        const val KEY_COMMAND_WINDOW = "settings_command_window_seconds"
        const val KEY_AFTER_REPLY_DELAY = "settings_after_reply_delay_ms"
        const val KEY_VOICE_COMPLETE_SILENCE = "settings_voice_complete_silence_ms"
        const val KEY_THEME = "settings_theme"
        const val KEY_TEXT_SCALE = "settings_text_scale_percent"
        const val KEY_FEATURE_PREFIX = "settings_feature_"

        const val DEFAULT_CUE_VOLUME = 70
        const val DEFAULT_CONNECT_TIMEOUT = 30
        const val DEFAULT_READ_TIMEOUT = 45
        const val DEFAULT_RATE_LIMIT_WAIT = 20
        const val DEFAULT_COMMAND_WINDOW = 10
        const val DEFAULT_AFTER_REPLY_DELAY = 1800
        const val DEFAULT_VOICE_COMPLETE_SILENCE = 1800
        const val DEFAULT_TEXT_SCALE = 100

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        val THEMES = setOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)
    }
}

/** Pure range validation kept separate so it can be unit-tested without Android runtime. */
internal object SettingsRanges {
    fun cueVolume(value: Int) = value.coerceIn(0, 100)
    fun connectTimeout(value: Int) = value.coerceIn(5, 120)
    fun readTimeout(value: Int) = value.coerceIn(5, 180)
    fun rateLimitWait(value: Int) = value.coerceIn(0, 120)
    fun commandWindow(value: Int) = value.coerceIn(3, 60)
    fun afterReplyDelay(value: Int) = value.coerceIn(0, 10_000)
    fun voiceSilence(value: Int) = value.coerceIn(500, 5_000)
    fun textScale(value: Int) = value.coerceIn(80, 150)
}
