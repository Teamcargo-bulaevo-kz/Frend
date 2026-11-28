package com.example.assistant

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Общий каркас маленьких экранов приложения (меню, заметки, проводник, промпт и навыки):
 * шапка с «‹ Назад», заголовком и кнопками справа, прокручиваемое содержимое, карточки, строки, поля ввода.
 * Те же цвета, что у чата, — светлая и тёмная тема работают сами.
 */
abstract class KitActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppSettings.wrap(newBase))
    }

    protected val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    protected lateinit var content: LinearLayout
    protected lateinit var headerActions: LinearLayout
    private lateinit var titleView: TextView

    abstract val screenTitle: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.chat_bg))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        header.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "‹"; textSize = 26f; minWidth = dp(48); contentDescription = "Назад"
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        titleView = TextView(this).apply {
            text = screenTitle; textSize = 21f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(color(R.color.chat_text)); setPadding(dp(10), 0, 0, 0)
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        headerActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(headerActions)
        root.addView(header)
        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(2), dp(14), dp(28)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        build()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    /** Заполнить content. Вызывается при создании и при [rebuild]. */
    abstract fun build()

    open fun onBack() = finish()

    protected fun rebuild() { content.removeAllViews(); build() }

    protected fun setScreenTitle(t: String) { titleView.text = t }

    // ---------------------------------------------------------------- кирпичики

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    protected fun color(id: Int) = ContextCompat.getColor(this, id)

    protected fun headerButton(label: String, desc: String, onClick: () -> Unit) {
        headerActions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; textSize = 17f; minWidth = dp(46); contentDescription = desc
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-2, dp(46)).apply { marginStart = dp(4) })
    }

    protected fun label(text: String, size: Float = 14f, dim: Boolean = false, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = size
        setTextColor(color(if (dim) R.color.chat_text_dim else R.color.chat_text))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    protected fun section(title: String, subtitle: String? = null) {
        content.addView(label(title, 17f, bold = true).apply { setPadding(dp(2), dp(16), dp(2), dp(2)) })
        if (!subtitle.isNullOrBlank()) content.addView(label(subtitle, 13f, dim = true).apply { setPadding(dp(2), 0, dp(2), dp(6)) })
    }

    protected fun card(parent: LinearLayout = content): LinearLayout {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = 0f
            setCardBackgroundColor(color(R.color.chat_surface))
            strokeWidth = dp(1); setStrokeColor(color(R.color.chat_stroke))
            addView(inner)
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        return inner
    }

    /** Строка-карточка: значок, заголовок, пояснение; нажатие и долгое нажатие. */
    protected fun row(icon: String, title: String, subtitle: String?, parent: LinearLayout = content,
                      onLong: (() -> Unit)? = null, onClick: (() -> Unit)?): View {
        val box = card(parent)
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (icon.isNotEmpty()) line.addView(label(icon, 22f).apply { setPadding(0, 0, dp(12), 0) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15.5f).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (!subtitle.isNullOrBlank()) texts.addView(label(subtitle, 12.5f, dim = true).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END })
        line.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(line)
        val card = box.parent as View
        onClick?.let { c -> card.setOnClickListener { c() } }
        onLong?.let { l -> card.setOnLongClickListener { l(); true } }
        return card
    }

    protected fun button(text: String, parent: LinearLayout = content, outlined: Boolean = true, onClick: () -> Unit): MaterialButton {
        val b = if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) else MaterialButton(this)
        b.text = text; b.isAllCaps = false; b.textSize = 14.5f
        b.setOnClickListener { onClick() }
        parent.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return b
    }

    protected fun field(hint: String, value: String = "", multiLine: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint; setText(value); textSize = 15f
        setTextColor(color(R.color.chat_text)); setHintTextColor(color(R.color.chat_text_dim))
        inputType = if (multiLine) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (multiLine) { minLines = 3; maxLines = 12; gravity = Gravity.TOP or Gravity.START }
    }

    /** Диалог с несколькими полями: onSave получает их значения по порядку. */
    protected fun formDialog(title: String, fields: List<EditText>, positive: String = "Сохранить", extra: Pair<String, () -> Unit>? = null,
                             onSave: (List<String>) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fields.forEach { box.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
        val b = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(positive) { _, _ -> onSave(fields.map { it.text.toString().trim() }) }
            .setNegativeButton("Отмена", null)
        extra?.let { (t, a) -> b.setNeutralButton(t) { _, _ -> a() } }
        b.show()
    }

    protected fun confirm(message: String, yes: String = "Удалить", action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton(yes) { _, _ -> action() }.setNegativeButton("Отмена", null).show()
    }

    protected fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    protected fun empty(text: String) {
        content.addView(label(text, 14f, dim = true).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(40), dp(12), dp(20)) })
    }
}
