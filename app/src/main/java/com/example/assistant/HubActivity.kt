package com.example.assistant

import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView

/**
 * Меню возможностей: всё, что умеет ассистент, — кнопками по разделам. Одни открывают свои экраны
 * (заметки, проводник, промпт и навыки, мини-приложения, музыку), другие сразу выполняют команду в чате
 * («что тратит заряд»), третьи вставляют начало просьбы («Объясни на доске: …»), чтобы дописать своё.
 */
class HubActivity : KitActivity() {

    override val screenTitle = "Меню"

    companion object { const val EXTRA_SCHEDULED = "scheduled" }

    private class Tile(val icon: String, val title: String, val action: () -> Unit)

    override fun build() {
        group("Помощник", listOf(
            Tile("📝", "Заметки и память") { open(NotesActivity::class.java) },
            Tile("📂", "Проводник") { open(ExplorerActivity::class.java) },
            Tile("🧠", "Промпт и навыки") { open(PromptSkillsActivity::class.java) },
            Tile("⏰", "Запланировано") { scheduled() },
            Tile("🗣", "Разговор голосом") { WakeWordService.startDialog(this); finish() },
            Tile("🧩", "Мини-приложения") { startActivity(Intent(this, SandboxActivity::class.java).putExtra(SandboxActivity.EXTRA_LIST, true)) }
        ))
        group("Доска и чертёж", listOf(
            Tile("🧑‍🏫", "Объясни на доске") { prefill("Объясни на доске по шагам: ") },
            Tile("📐", "Начерти чертёж") { prefill("Начерти чертёж с размерами: ") },
            Tile("📈", "График функции") { prefill("Построй на доске график функции y = ") },
            Tile("🔺", "Задача по геометрии") { prefill("Реши задачу по геометрии с чертежом на доске: ") },
            Tile("🗺", "Схема / план") { prefill("Нарисуй схему: ") },
            Tile("📊", "Диаграмма") { prefill("Покажи диаграммой: ") }
        ))
        group("Экран", listOf(
            Tile("👁", "Что на экране") { finish(); ScreenQuick.ask(this, 900L) },
            Tile("Aあ", "Перевести экран") { finish(); ScreenQuick.translate(this, 900L) },
            Tile("📡", "Живой экран") { ScreenQuick.toggleLive(this) },
            Tile("🖐", "Сделай за меня") { prefill("Сделай на экране: ") }
        ))
        group("Телефон", listOf(
            Tile("📱", "Сводка телефона") { run("состояние телефона") },
            Tile("🔋", "Кто тратит заряд") { run("какие приложения тратят заряд") },
            Tile("💾", "Что занимает место") { run("что занимает место") },
            Tile("🗂", "Разложить загрузки") { run("разложи загрузки по папкам") },
            Tile("🔔", "Уведомления") { run("прочитай уведомления") },
            Tile("🎛", "Переключатели") { prefill("Выключи ") }
        ))
        group("Музыка", listOf(
            Tile("🎵", "Плеер") { open(MusicActivity::class.java) },
            Tile("🌊", "Моя волна") { run("включи мою волну") },
            Tile("✨", "Под настроение") { prefill("Включи музыку под настроение: ") }
        ))
        group("Настройки", listOf(
            Tile("⚙️", "Настройки") { open(SettingsActivity::class.java) },
            Tile("📚", "Мои навыки") { run("мои навыки") },
            Tile("🧾", "Что ты выучил") { run("чему ты научился") }
        ))
        if (intent.getBooleanExtra(EXTRA_SCHEDULED, false)) { intent.removeExtra(EXTRA_SCHEDULED); scheduled() }
    }

    private fun group(title: String, tiles: List<Tile>) {
        section(title)
        tiles.chunked(3).forEach { rowTiles ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowTiles.forEach { t -> row.addView(tile(t), LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginEnd = dp(6); bottomMargin = dp(6) }) }
            repeat(3 - rowTiles.size) { row.addView(android.view.View(this), LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginEnd = dp(6) }) }
            content.addView(row)
        }
    }

    private fun tile(t: Tile): MaterialCardView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            addView(label(t.icon, 24f).apply { gravity = Gravity.CENTER })
            addView(label(t.title, 12.5f).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(4), 0, 0) })
        }
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = 0f
            setCardBackgroundColor(color(R.color.chat_surface))
            strokeWidth = dp(1); setStrokeColor(color(R.color.chat_stroke))
            isClickable = true; isFocusable = true
            contentDescription = t.title
            addView(inner)
            setOnClickListener { t.action() }
        }
    }

    private fun open(c: Class<*>) = startActivity(Intent(this, c))

    private fun chat(): Intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Выполнить команду в чате. */
    private fun run(command: String) { startActivity(chat().putExtra(MainActivity.EXTRA_RUN, command)); finish() }

    /** Вставить начало просьбы в поле ввода чата. */
    private fun prefill(text: String) { startActivity(chat().putExtra(MainActivity.EXTRA_PREFILL, text)); finish() }

    /** Запланированные действия: список с отменой и добавление вручную. */
    private fun scheduled() {
        val jobs = ScheduledCommands.jobs(this).sortedBy { it.at }
        val items = jobs.map { (if (it.daily) "каждый день · " else "") + ScheduledCommands.describeWhen(it.at) + "\n" + it.command }.toTypedArray()
        val b = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(if (jobs.isEmpty()) "Ничего не запланировано" else "Запланировано (нажми, чтобы отменить)")
        if (jobs.isEmpty()) b.setMessage("Скажи или напиши: «через 5 минут напиши Максиму в WhatsApp доброе утро», «в 23:00 выключи Wi-Fi», «каждый день в 7:30 включи мою волну».")
        else b.setItems(items) { _, i ->
            confirm("Отменить «${jobs[i].command}»?", "Отменить") { toast(ScheduledCommands.cancel(this, jobs[i].command)) }
        }
        b.setPositiveButton("Добавить") { _, _ -> addScheduled() }.setNegativeButton("Закрыть", null).show()
    }

    private fun addScheduled() {
        val cmd = field("Что сделать: напиши Максиму в WhatsApp доброе утро")
        val whenField = field("Когда: через 5 минут · в 8:30 · завтра в 9 · каждый день в 7:30")
        formDialog("Запланировать", listOf(cmd, whenField), "Запланировать") { (c, w) ->
            val parsed = ScheduledCommands.parse("$w $c".trim()) ?: ScheduledCommands.parse("запланируй $w $c")
            if (c.isBlank() || parsed == null) toast("Не понял время. Пример: «через 10 минут» или «в 8:30»")
            else toast(ScheduledCommands.add(this, parsed.command, parsed.at, parsed.daily))
        }
    }
}
