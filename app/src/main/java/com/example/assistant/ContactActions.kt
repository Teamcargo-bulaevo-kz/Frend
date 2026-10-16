package com.example.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class Contact(val name: String, val numbers: List<String>)

/** Звонки и сообщения по имени из телефонной книги. Блокирующие: вызывать с IO-потока. */
object ContactActions {

    private const val NEED_PERMISSION =
        "Нужен доступ к контактам. Открой приложение и разреши его (или: Настройки → Приложения → Assistant → Разрешения → Контакты)."

    fun hasContactsPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun hasCallPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** Все контакты с номерами. Номера одного человека собраны вместе, мобильный первым. */
    fun load(context: Context): List<Contact> {
        val result = LinkedHashMap<Long, Pair<String, MutableList<Pair<Int, String>>>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val number = c.getString(2)?.trim() ?: continue
                if (number.isEmpty()) continue
                val type = c.getInt(3)
                val entry = result.getOrPut(id) { name to mutableListOf() }
                if (entry.second.none { digits(it.second) == digits(number) }) entry.second.add(type to number)
            }
        }
        return result.values.map { (name, numbers) ->
            // Мобильный (TYPE_MOBILE = 2) первым
            Contact(name, numbers.sortedBy { if (it.first == 2) 0 else 1 }.map { it.second })
        }
    }

    private fun digits(s: String) = s.filter { it.isDigit() }.takeLast(10)

    private sealed class Pick {
        data class One(val contact: Contact) : Pick()
        data class Many(val contacts: List<Contact>) : Pick()
        object None : Pick()
    }

    private fun pick(contacts: List<Contact>, query: String): Pick {
        val found = contacts.filter { ContactMatch.matches(query, it.name) }
        if (found.isEmpty()) return Pick.None
        if (found.size == 1) return Pick.One(found[0])
        val exact = found.filter { ContactMatch.exact(query, it.name) }
        return if (exact.size == 1) Pick.One(exact[0]) else Pick.Many(found)
    }

    private fun describeMany(found: List<Contact>) =
        found.take(6).joinToString("; ") { "${it.name} (${it.numbers.first()})" } +
            if (found.size > 6) "; ещё ${found.size - 6}" else ""

    /** Показывает найденные контакты и номера. */
    fun info(context: Context, query: String): String {
        if (!hasContactsPermission(context)) return NEED_PERMISSION
        val found = load(context).filter { ContactMatch.matches(query, it.name) }
        if (found.isEmpty()) return "Контакт «$query» не нашёл."
        return found.take(6).joinToString("\n") { "${it.name}: ${it.numbers.joinToString(", ")}" } +
            if (found.size > 6) "\n… и ещё ${found.size - 6}" else ""
    }

    /** Звонит. Если разрешён прямой вызов, набирает сразу, иначе открывает набор номера. */
    fun call(context: Context, query: String): String {
        if (!hasContactsPermission(context)) return NEED_PERMISSION
        return when (val p = pick(load(context), query)) {
            Pick.None -> "Контакт «$query» не нашёл."
            is Pick.Many -> "Нашёл несколько: ${describeMany(p.contacts)}. Скажи точнее, кому звонить."
            is Pick.One -> {
                val number = p.contact.numbers.first()
                if (hasCallPermission(context)) {
                    val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                    if (PhoneActions.launch(context, intent)) "Звоню: ${p.contact.name} ($number)"
                    else "Не получилось позвонить ${p.contact.name}"
                } else {
                    "${p.contact.name}: " + PhoneActions.openDialer(context, number)
                }
            }
        }
    }

    /** Готовит сообщение контакту в WhatsApp (по умолчанию) или SMS. Отправляет сам пользователь. */
    fun message(context: Context, query: String, text: String?, app: String?): String {
        if (!hasContactsPermission(context)) return NEED_PERMISSION
        return when (val p = pick(load(context), query)) {
            Pick.None -> "Контакт «$query» не нашёл."
            is Pick.Many -> "Нашёл несколько: ${describeMany(p.contacts)}. Скажи точнее, кому писать."
            is Pick.One -> {
                val number = p.contact.numbers.first()
                if (app.equals("sms", ignoreCase = true)) {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).apply {
                        if (!text.isNullOrBlank()) putExtra("sms_body", text)
                    }
                    if (PhoneActions.launch(context, intent)) "SMS для ${p.contact.name} готово, нажми «Отправить»"
                    else "Не нашёл приложение для SMS"
                } else {
                    "${p.contact.name}: " + PhoneActions.openWhatsApp(context, number, text)
                }
            }
        }
    }
}
