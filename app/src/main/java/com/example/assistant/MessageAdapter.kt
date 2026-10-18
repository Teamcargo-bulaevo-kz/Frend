package com.example.assistant

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Сообщения чата пузырями: свои справа, ответы ассистента слева. */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {

    private val items = ArrayList<ChatMessage>()

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text.parent as ViewGroup)

    fun setAll(messages: List<ChatMessage>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
    }

    fun add(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val row = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return Holder(row.findViewById(R.id.messageText))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val m = items[position]
        val mine = m.role == "user"
        holder.text.text = m.text
        holder.text.setBackgroundResource(if (mine) R.drawable.bubble_user else R.drawable.bubble_assistant)
        holder.text.setTextColor(if (mine) Color.WHITE else Color.parseColor("#1B1B1F"))
        (holder.text.layoutParams as FrameLayout.LayoutParams).gravity = if (mine) Gravity.END else Gravity.START
    }
}
