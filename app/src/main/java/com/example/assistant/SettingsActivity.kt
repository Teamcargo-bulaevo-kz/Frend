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

    private val prefs by lazy { getSharedPreferences("assistant_prefs", MODE_PRIVATE) }
    private lateinit var content: LinearLayout

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
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }
        scroll.addView(content)
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
            else stopService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_STOP))
        }
        addSwitch("Голосовой ввод в чате", "Показывать кнопку микрофона рядом с полем ввода.", prefs.getBoolean("voice_input_enabled", true)) {
            prefs.edit().putBoolean("voice_input_enabled", it).apply()
        }
        addSwitch("Прикрепление файлов", "Показывать кнопку выбора файла в чате.", prefs.getBoolean("attachments_enabled", true)) {
            prefs.edit().putBoolean("attachments_enabled", it).apply()
        }
        addSwitch("Подсказки в пустом чате", "Примеры команд, когда переписка пустая.", prefs.getBoolean("suggestions_enabled", true)) {
            prefs.edit().putBoolean("suggestions_enabled", it).apply()
        }

        section("Звуковые сигналы", "Звуки начала и завершения команды настраиваются независимо")
        addSwitch("Сигнал начала прослушивания", "Короткий сигнал после обнаружения слова активации.", prefs.getBoolean("start_cue_enabled", true)) {
            prefs.edit().putBoolean("start_cue_enabled", it).apply()
        }
        addSeek("Громкость сигнала начала", prefs.getInt("start_cue_volume", 70), "%", 0, 100) {
            prefs.edit().putInt("start_cue_volume", it).apply()
        }
        addSwitch("Сигнал завершения", "Короткий сигнал после распознавания команды.", prefs.getBoolean("end_cue_enabled", true)) {
            prefs.edit().putBoolean("end_cue_enabled", it).apply()
        }
        addSeek("Громкость сигнала завершения", prefs.getInt("end_cue_volume", 70), "%", 0, 100) {
            prefs.edit().putInt("end_cue_volume", it).apply()
        }
        addSeek("Время на команду после активации", (prefs.getLong("command_window_ms", 10_000L) / 1000L).toInt(), " сек", 3, 30) {
            prefs.edit().putLong("command_window_ms", it * 1000L).apply()
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

        section("Дополнительно", "Настройки слова активации и голоса")
        addAction("Слово активации и батарея", "Изменить ключевую фразу и открыть настройки энергосбережения") {
            showWakePhraseDialog()
        }
        addAction("Голос и скорость озвучки", "Выбор установленного голоса и темпа речи") {
            showVoiceSettings()
        }
        addAction("Проверить фоновый микрофон", "Запустить диагностическое прослушивание") {
            if (WakeWordService.hasMicPermission(this)) WakeWordService.test(this)
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
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
        content.addView(note)
    }

    private fun applyTheme(value: String) {
        AppCompatDelegate.setDefaultNightMode(when (value) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    private fun section(title: String, subtitle: String) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.chat_text))
            setPadding(dp(2), dp(18), dp(2), dp(3))
        })
        content.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.chat_text_dim))
            setPadding(dp(2), 0, dp(2), dp(8))
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
            max = max - min
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
                    .setSingleChoiceItems(labels, values.indexOf(prefs.getString("app_theme", "system") ?: "system")) { dialog, which ->
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
