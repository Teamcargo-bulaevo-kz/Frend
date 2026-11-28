package com.example.assistant

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Заметки и память ассистента в одном месте.
 *  • «Заметки» — то, что ассистент записал по просьбе («запиши…», «запомни…») и что добавлено вручную:
 *    поиск, правка, удаление, «Поделиться» и «Спросить ассистента» про заметку.
 *  • «Память» — база знаний (факты, коды, справки), из которой ассистент отвечает и без интернета.
 *    По умолчанию видны только свои записи; встроенный справочник — по переключателю.
 */
class NotesActivity : KitActivity() {

    override val screenTitle = "Заметки и память"

    private var tab = 0
    private var query = ""
    private var showBuiltIn = false
    private val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
    private val dao by lazy { NoteDatabase.getInstance(applicationContext).noteDao() }
    private lateinit var list: LinearLayout

    override fun build() {
        if (headerActions.childCount == 0) headerButton("+", "Добавить") { if (tab == 0) editNote(null) else editFact(null) }
        val tabs = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        listOf("Заметки", "Память ассистента").forEachIndexed { i, t ->
            tabs.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = 1000 + i; text = t; isAllCaps = false },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        tabs.check(1000 + tab)
        tabs.addOnButtonCheckedListener { _, id, checked -> if (checked && id - 1000 != tab) { tab = id - 1000; rebuild() } }
        content.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        val search = field(if (tab == 0) "Поиск по заметкам" else "Поиск в памяти", query)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); load() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        content.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (tab == 1) {
            content.addView(com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
                text = "Показывать встроенный справочник"; isChecked = showBuiltIn; setTextColor(color(R.color.chat_text))
                setOnCheckedChangeListener { _, v -> showBuiltIn = v; load() }
            })
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        load()
    }

    private fun load() {
        ui.launch {
            if (tab == 0) {
                val notes = withContext(Dispatchers.IO) { if (query.isBlank()) dao.getAll() else dao.search(query.trim()) }
                list.removeAllViews()
                list.addView(label(if (notes.isEmpty()) "" else "Заметок: ${notes.size}", 12.5f, dim = true).apply { setPadding(dp(2), dp(8), 0, dp(4)) })
                if (notes.isEmpty()) list.addView(label(if (query.isBlank()) "Заметок пока нет. Нажми «+» или скажи ассистенту: «запиши: купить хлеб»." else "Ничего не нашлось.", 14f, dim = true).apply { setPadding(dp(8), dp(30), dp(8), 0) })
                notes.forEach { n ->
                    row("", n.text.take(300), fmt.format(Date(n.createdAt)), list, onLong = { deleteNote(n) }) { editNote(n) }
                }
            } else {
                val facts = withContext(Dispatchers.IO) {
                    val all = OfflineBrain.facts(applicationContext)
                    val base = if (showBuiltIn) all else all.filter { it.source != "встроено" && it.source != "страны мира" }
                    if (query.isBlank()) base.take(300) else KnowledgeRank.search(query, base, 50).map { it.fact }
                }
                list.removeAllViews()
                list.addView(label("Записей: ${facts.size}. Ассистент берёт их в ответы сам; «запомни факт: …» — добавить голосом.", 12.5f, dim = true).apply { setPadding(dp(2), dp(8), 0, dp(4)) })
                if (facts.isEmpty()) list.addView(label("Своих записей пока нет. Нажми «+» или скажи: «запомни факт: код домофона 1234».", 14f, dim = true).apply { setPadding(dp(8), dp(30), dp(8), 0) })
                facts.forEach { f ->
                    row("", f.title, f.body.take(240) + if (f.source.isNotBlank()) "\n· ${f.source}" else "", list, onLong = { deleteFact(f) }) { editFact(f) }
                }
            }
        }
    }

    private fun editNote(n: NoteEntity?) {
        val text = field("Текст заметки", n?.text.orEmpty(), multiLine = true)
        formDialog(if (n == null) "Новая заметка" else "Заметка", listOf(text), extra = if (n == null) null else ("Ещё…" to { noteMenu(n) })) { (t) ->
            if (t.isBlank()) return@formDialog
            ui.launch {
                withContext(Dispatchers.IO) { if (n == null) dao.insert(NoteEntity(text = t)) else dao.update(n.copy(text = t)) }
                load()
            }
        }
    }

    private fun noteMenu(n: NoteEntity) {
        val actions = arrayOf("Поделиться", "Спросить ассистента", "Удалить")
        androidx.appcompat.app.AlertDialog.Builder(this).setItems(actions) { _, i ->
            when (i) {
                0 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, n.text), "Поделиться"))
                1 -> { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_PREFILL, "Про заметку «${n.text.take(200)}»: ")); finish() }
                2 -> deleteNote(n)
            }
        }.show()
    }

    private fun deleteNote(n: NoteEntity) = confirm("Удалить заметку «${n.text.take(60)}»?") {
        ui.launch { withContext(Dispatchers.IO) { dao.delete(n) }; load() }
    }

    private fun editFact(f: Fact?) {
        val title = field("Название: код домофона", f?.title.orEmpty())
        val body = field("Текст: код домофона 1234, подъезд 2", f?.body.orEmpty(), multiLine = true)
        formDialog(if (f == null) "Запомнить" else "Запись в памяти", listOf(title, body), extra = if (f == null) null else ("Удалить" to { deleteFact(f) })) { (t, b) ->
            if (b.isBlank() && t.isBlank()) return@formDialog
            ui.launch {
                withContext(Dispatchers.IO) {
                    val tt = t.ifBlank { b.take(40) }; val bb = b.ifBlank { t }
                    if (f == null) OfflineBrain.addFact(applicationContext, tt, bb) else OfflineBrain.updateFact(applicationContext, f.id, tt, bb)
                }
                load()
            }
        }
    }

    private fun deleteFact(f: Fact) = confirm("Забыть «${f.title.take(60)}»?", "Забыть") {
        ui.launch { withContext(Dispatchers.IO) { OfflineBrain.deleteFacts(applicationContext, listOf(f.id)) }; load() }
    }
}
