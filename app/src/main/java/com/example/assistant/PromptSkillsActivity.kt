package com.example.assistant

import android.content.Intent
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Промпт и навыки: как ассистент думает и что умеет делать по одной фразе.
 *  • «Промпт» — свои указания (уходят в каждый разговор), встроенные инструкции с переключателями
 *    (наглядные ответы, доска и чертёж, план, голос) и свои режимы-инструкции со словами-триггерами.
 *  • «Навыки» — команды из нескольких шагов по одной фразе («доброе утро» → погода, заряд, моя волна):
 *    включить, выключить, изменить, создать, удалить, проверить.
 *  • «Выученное» — фразы, которые ассистент выучил и выполняет без модели; лишнее можно забыть.
 */
class PromptSkillsActivity : KitActivity() {

    override val screenTitle = "Промпт и навыки"

    private var tab = 0

    override fun build() {
        headerActions.removeAllViews()
        headerButton("+", "Добавить") { if (tab == 1) editSkill(null) else if (tab == 0) editInstruction(null) else toast("Выученное пополняется само") }
        val tabs = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        listOf("Промпт", "Навыки", "Выученное").forEachIndexed { i, t ->
            tabs.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = 2000 + i; text = t; isAllCaps = false },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        tabs.check(2000 + tab)
        tabs.addOnButtonCheckedListener { _, id, checked -> if (checked && id - 2000 != tab) { tab = id - 2000; rebuild() } }
        content.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        when (tab) { 0 -> prompt(); 1 -> skills(); else -> learned() }
    }

    // ---------------------------------------------------------------- промпт

    private fun prompt() {
        section("Мои указания", "Уходят в каждый разговор с моделью. Например: «обращайся на ты», «я учусь в 9 классе», «отвечай подробнее».")
        val box = card()
        val field = field("Пиши как другу, коротко…", PromptStore.custom(this), multiLine = true)
        box.addView(field)
        button("Сохранить указания", box, outlined = false) { PromptStore.setCustom(this, field.text.toString()); toast("Сохранено") }

        section("Встроенные инструкции", "Подключаются к запросу, когда нужны. Выключи — и модель перестанет так делать. Нажми на название, чтобы прочитать текст.")
        PromptStore.builtIns.forEach { b ->
            val c = card()
            c.addView(SwitchMaterial(this).apply {
                text = b.title; textSize = 15f; setTextColor(color(R.color.chat_text)); isChecked = PromptStore.builtInEnabled(this@PromptSkillsActivity, b.id)
                setOnCheckedChangeListener { _, v -> PromptStore.setBuiltIn(this@PromptSkillsActivity, b.id, v) }
            })
            c.addView(label(b.summary + " · показать текст", 12.5f, dim = true).apply {
                setOnClickListener { showText(b.title, b.text) }
            })
        }

        section("Мои режимы", "Своя инструкция включается словами-триггерами. «Репетитор»: триггеры «объясни, реши», текст «объясняй по шагам на доске, задавай наводящие вопросы». Без триггеров — всегда.")
        val list = PromptStore.instructions(this)
        if (list.isEmpty()) content.addView(label("Режимов пока нет — нажми «+».", 13.5f, dim = true).apply { setPadding(dp(4), dp(4), 0, dp(4)) })
        list.forEach { item ->
            row(if (item.enabled) "✅" else "⏸", item.name, (if (item.triggers.isEmpty()) "всегда" else "когда: " + item.triggers.joinToString(", ")) + "\n" + item.text.take(140),
                onLong = { confirm("Удалить режим «${item.name}»?") { PromptStore.remove(this, item.name); rebuild() } }) { editInstruction(item) }
        }
        button("Шаблон: Репетитор") {
            PromptStore.upsert(this, PromptStore.Instruction("Репетитор", listOf("объясни", "реши", "задач", "урок", "домашк"),
                "Объясняй как учитель: по шагам, на доске (стиль chalk), с простыми примерами; в конце задай 1 проверочный вопрос."))
            rebuild()
        }
        button("Шаблон: Инженер") {
            PromptStore.upsert(this, PromptStore.Instruction("Инженер", listOf("чертеж", "чертёж", "деталь", "размер", "схем"),
                "Отвечай как инженер-конструктор: точные размеры с единицами, чертёж в стиле blueprint по ГОСТ, перечень допущений."))
            rebuild()
        }
    }

    private fun editInstruction(item: PromptStore.Instruction?) {
        val name = field("Название: Репетитор", item?.name.orEmpty())
        val triggers = field("Триггеры через запятую (пусто — всегда)", item?.triggers?.joinToString(", ").orEmpty())
        val text = field("Что делать в этом режиме", item?.text.orEmpty(), multiLine = true)
        val toggle = item?.let { (if (it.enabled) "Выключить" else "Включить") to { PromptStore.upsert(this, it.copy(enabled = !it.enabled)); rebuild() } }
        formDialog(item?.name ?: "Новый режим", listOf(name, triggers, text), extra = toggle) { (n, t, x) ->
            if (n.isBlank() || x.isBlank()) { toast("Нужны название и текст"); return@formDialog }
            PromptStore.upsert(this, PromptStore.Instruction(n, PromptStore.splitTriggers(t), x, item?.enabled ?: true), item?.name)
            rebuild()
        }
    }

    // ---------------------------------------------------------------- навыки

    private fun skills() {
        section("Навыки", "Одна фраза — несколько действий. Шаги — обычные команды, как сказал бы ты. Файлы навыков лежат в папке Assistant/Навыки.")
        ui.launch {
            val list = withContext(Dispatchers.IO) { OfflineBrain.skills(applicationContext) }
            if (list.isEmpty()) empty("Навыков нет — нажми «+» или скажи: «создай навык „утро“: погода, заряд и моя волна».")
            list.forEach { s ->
                val c = card()
                c.addView(SwitchMaterial(this@PromptSkillsActivity).apply {
                    text = s.name; textSize = 15.5f; setTextColor(color(R.color.chat_text)); isChecked = s.enabled
                    setOnCheckedChangeListener { _, v -> OfflineBrain.saveSkill(applicationContext, s.copy(enabled = v)) }
                })
                c.addView(label("Фразы: " + s.triggers.joinToString(", ").ifBlank { "—" }, 12.5f, dim = true))
                c.addView(label("Шаги: " + s.steps.joinToString(" → ").ifBlank { "—" } + if (s.answer.isNotBlank()) "\nОтвет: ${s.answer}" else "", 12.5f, dim = true))
                val actions = LinearLayout(this@PromptSkillsActivity).apply { orientation = LinearLayout.HORIZONTAL }
                fun small(t: String, a: () -> Unit) = actions.addView(MaterialButton(this@PromptSkillsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = t; isAllCaps = false; textSize = 13.5f; setOnClickListener { a() }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                small("Изменить") { editSkill(s) }
                small("Запустить") { run(s.triggers.firstOrNull() ?: s.name) }
                small("Удалить") { confirm("Удалить навык «${s.name}»?") { OfflineBrain.deleteSkill(applicationContext, s.name); rebuild() } }
                c.addView(actions)
            }
            button("Создать навык по описанию (ИИ)") {
                startActivity(Intent(this@PromptSkillsActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_PREFILL, "Создай навык: ")); finish()
            }
        }
    }

    private fun editSkill(s: Skill?) {
        val name = field("Название: Доброе утро", s?.name.orEmpty())
        val triggers = field("Фразы через запятую: доброе утро, я проснулся", s?.triggers?.joinToString(", ").orEmpty())
        val steps = field("Шаги — по одному в строке:\nкакая погода\nсколько заряда\nвключи мою волну", s?.steps?.joinToString("\n").orEmpty(), multiLine = true)
        val answer = field("Что сказать в конце (необязательно)", s?.answer.orEmpty())
        formDialog(s?.name ?: "Новый навык", listOf(name, triggers, steps, answer)) { (n, t, st, a) ->
            if (n.isBlank() || st.isBlank()) { toast("Нужны название и хотя бы один шаг"); return@formDialog }
            if (s != null && s.name != n) OfflineBrain.deleteSkill(applicationContext, s.name)
            OfflineBrain.saveSkill(applicationContext, Skill(n, PromptStore.splitTriggers(t).ifEmpty { listOf(n.lowercase()) },
                st.lines().map { it.trim().trimStart('-', '•', ' ') }.filter { it.isNotEmpty() }, a, s?.enabled ?: true))
            rebuild()
        }
    }

    // ---------------------------------------------------------------- выученное

    private fun learned() {
        section("Выученные фразы", "Ассистент запомнил, что делать по этим фразам, и выполняет их сразу, без модели. Неверное — забудь (долгое нажатие).")
        ui.launch {
            val dao = NoteDatabase.getInstance(applicationContext).commandPatternDao()
            val list = withContext(Dispatchers.IO) { dao.top(400) }
            if (list.isEmpty()) { empty("Пока ничего не выучено. Пользуйся командами — ассистент будет запоминать."); return@launch }
            content.addView(label("Всего: ${list.size}", 12.5f, dim = true).apply { setPadding(dp(2), dp(4), 0, dp(4)) })
            list.forEach { p ->
                val what = when {
                    p.functionName == Learning.MACRO -> "несколько действий"
                    p.functionName.startsWith("__") -> p.functionName.trim('_')
                    else -> p.functionName
                }
                val title = p.phrase.removePrefix(Learning.TEMPLATE_PREFIX).replace("#", "N")
                row("", title, "$what · ${p.useCount} ${FileSort.plural(p.useCount, "раз", "раза", "раз")}\n${p.argumentsJson.take(120)}",
                    onLong = { forget(p.phrase) }) { forget(p.phrase) }
            }
            button("Забыть всё выученное") {
                confirm("Забыть все ${list.size} фраз? Команды снова будут понимать через модель.", "Забыть всё") {
                    ui.launch { withContext(Dispatchers.IO) { dao.deleteAll() }; rebuild() }
                }
            }
        }
    }

    private fun forget(phrase: String) = confirm("Забыть «${phrase.removePrefix(Learning.TEMPLATE_PREFIX)}»?", "Забыть") {
        ui.launch { withContext(Dispatchers.IO) { NoteDatabase.getInstance(applicationContext).commandPatternDao().delete(phrase) }; OfflineBrain.invalidate(); rebuild() }
    }

    // ---------------------------------------------------------------- общее

    private fun run(command: String) {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_RUN, command))
        finish()
    }

    private fun showText(title: String, text: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Закрыть", null).show()
    }
}
