package com.example.assistant

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

/** Сообщения чата: свои справа, ответы слева, блоки кода отдельными окошками, «печатает…» точками. */
class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<ChatMessage>()
    private var typing = false
    private var animateFrom = Int.MAX_VALUE

    private class MessageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: LinearLayout = view.findViewById(R.id.bubble)
        val content: LinearLayout = view.findViewById(R.id.content)
        val time: TextView = view.findViewById(R.id.timeText)
        val copy: ImageButton = view.findViewById(R.id.copyButton)
    }

    private class TypingHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dots: List<View> = listOf(R.id.dot1, R.id.dot2, R.id.dot3).map { view.findViewById(it) }
        var animators: List<ObjectAnimator> = emptyList()
    }

    fun setAll(messages: List<ChatMessage>) {
        items.clear()
        items.addAll(messages)
        typing = false
        animateFrom = Int.MAX_VALUE
        notifyDataSetChanged()
    }

    fun add(message: ChatMessage) {
        hideTyping()
        items.add(message)
        animateFrom = items.size - 1
        notifyItemInserted(items.size - 1)
    }

    fun showTyping() {
        if (typing) return
        typing = true
        notifyItemInserted(items.size)
    }

    fun hideTyping() {
        if (!typing) return
        typing = false
        notifyItemRemoved(items.size)
    }

    override fun getItemCount() = items.size + if (typing) 1 else 0

    override fun getItemViewType(position: Int) = if (position < items.size) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 1) {
            TypingHolder(inflater.inflate(R.layout.item_typing, parent, false))
        } else {
            MessageHolder(inflater.inflate(R.layout.item_message, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is TypingHolder) {
            bindTyping(holder)
            return
        }
        holder as MessageHolder
        val context = holder.itemView.context
        val m = items[position]
        val mine = m.role == "user"
        val maxWidth = (context.resources.displayMetrics.widthPixels * 0.84f).toInt()
        val pad = holder.bubble.paddingStart + holder.bubble.paddingEnd
        val textColor = ContextCompat.getColor(context, if (mine) R.color.chat_on_bubble_user else R.color.chat_text)
        val dim = ContextCompat.getColor(context, if (mine) R.color.chat_on_bubble_user else R.color.chat_text_dim)

        holder.bubble.setBackgroundResource(if (mine) R.drawable.bubble_user else R.drawable.bubble_assistant)
        (holder.bubble.layoutParams as FrameLayout.LayoutParams).gravity = if (mine) Gravity.END else Gravity.START

        holder.content.removeAllViews()
        val segments = MessageParser.split(m.text)
        val hasCode = segments.any { it is Segment.Code }
        holder.bubble.layoutParams.width = if (hasCode) maxWidth else ViewGroup.LayoutParams.WRAP_CONTENT
        holder.content.layoutParams.width = if (hasCode) maxWidth - pad else ViewGroup.LayoutParams.WRAP_CONTENT

        val inflater = LayoutInflater.from(context)
        for (seg in segments) {
            when (seg) {
                is Segment.Text -> holder.content.addView(textView(context, seg.text, textColor, maxWidth - pad))
                is Segment.Code -> {
                    val box = inflater.inflate(R.layout.item_code, holder.content, false)
                    box.findViewById<TextView>(R.id.codeLang).text = seg.lang.ifEmpty { "текст" }
                    box.findViewById<TextView>(R.id.codeText).text = seg.code
                    box.findViewById<TextView>(R.id.codeCopy).setOnClickListener { copy(context, seg.code, "Скопировано") }
                    holder.content.addView(box)
                }
            }
        }

        holder.time.setTextColor(dim)
        holder.time.alpha = 0.7f
        holder.time.text = DateFormat.getTimeFormat(context).format(Date(m.timestamp))
        holder.copy.visibility = if (mine) View.GONE else View.VISIBLE
        holder.copy.setOnClickListener { copy(context, m.text, "Сообщение скопировано") }
        holder.itemView.setOnLongClickListener { copy(context, m.text, "Сообщение скопировано"); true }

        // Новое сообщение плавно выезжает снизу
        if (position == animateFrom) {
            animateFrom = Int.MAX_VALUE
            holder.itemView.alpha = 0f
            holder.itemView.translationY = 28f
            holder.itemView.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else {
            holder.itemView.alpha = 1f
            holder.itemView.translationY = 0f
        }
    }

    private fun textView(context: Context, raw: String, color: Int, maxWidth: Int): TextView {
        val styled = MessageParser.styled(raw)
        val sb = SpannableStringBuilder(styled.text)
        styled.bold.forEach { sb.setSpan(StyleSpan(Typeface.BOLD), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        styled.mono.forEach {
            sb.setSpan(TypefaceSpan("monospace"), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(0x33808098), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return TextView(context).apply {
            text = sb
            setTextColor(color)
            setLinkTextColor(color)
            textSize = 15.5f
            setLineSpacing(0f, 1.12f)
            setTextIsSelectable(true)
            this.maxWidth = maxWidth
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }.also { android.text.util.Linkify.addLinks(it, android.text.util.Linkify.WEB_URLS) }
    }

    private fun copy(context: Context, text: String, message: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Assistant", text))
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun bindTyping(holder: TypingHolder) {
        holder.animators.forEach { it.cancel() }
        holder.animators = holder.dots.mapIndexed { i, dot ->
            ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, 0f, -10f, 0f).apply {
                duration = 700
                startDelay = i * 160L
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is TypingHolder) holder.animators.forEach { it.cancel() }
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is TypingHolder) bindTyping(holder)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is TypingHolder) holder.animators.forEach { it.cancel() }
        super.onViewDetachedFromWindow(holder)
    }
}
