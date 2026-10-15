package com.example.assistant

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import java.util.Locale

/** Реальные действия на телефоне, которые вызывает ассистент по решению модели. */
object PhoneActions {

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** Запускает экран/приложение. false — если подходящего приложения на телефоне нет. */
    internal fun launch(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    // ---------------------------------------------------------------- будильник

    /** Ставит будильник сразу, без экрана подтверждения. */
    fun setAlarm(context: Context, hour: Int, minute: Int, label: String?): String {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val time = "%02d:%02d".format(h, m)
        return if (launch(context, intent)) {
            "Будильник поставлен на $time"
        } else {
            "На телефоне не нашлось приложения для будильников"
        }
    }

    // ---------------------------------------------------------------- фонарик

    @Volatile private var torchCameraId: String? = null

    fun setFlashlight(context: Context, on: Boolean): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = torchCameraId ?: manager.cameraIdList.firstOrNull { cameraId ->
                val characteristics = manager.getCameraCharacteristics(cameraId)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }?.also { torchCameraId = it } ?: return "На этом телефоне нет фонарика"
            manager.setTorchMode(id, on)
            if (on) "Фонарик включён" else "Фонарик выключен"
        } catch (e: Exception) {
            "Не получилось управлять фонариком: ${e.message}"
        }
    }

    // ---------------------------------------------------------------- камера

    /** Открывает приложение камеры сразу в нужном режиме. Затвор нажимает пользователь. */
    fun openCamera(context: Context, mode: String?): String {
        val video = mode.equals("video", ignoreCase = true)
        val action = if (video) {
            MediaStore.INTENT_ACTION_VIDEO_CAMERA
        } else {
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        }
        if (launch(context, Intent(action))) {
            return if (video) {
                "Открыл камеру в режиме видео, нажми кнопку записи"
            } else {
                "Открыл камеру, нажми кнопку затвора"
            }
        }
        return openByLabel(context, "камера").takeIf { !it.startsWith("Не нашёл") }
            ?: openByLabel(context, "camera")
    }

    // ---------------------------------------------------------------- скриншот

    fun takeScreenshot(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "Скриншот программно доступен только на Android 9 и новее"
        }
        val service = AssistantAccessibilityService.instance
        if (service == null) {
            launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return "Для скриншотов нужно один раз включить службу «Assistant» в специальных " +
                "возможностях. Открыл настройки: найди «Assistant» и включи. Если пункт серый: " +
                "Настройки → Приложения → Assistant → меню ⋮ → «Разрешить ограниченные настройки»."
        }
        service.screenshotAfterHome()
        return "Сворачиваюсь и делаю скриншот экрана"
    }

    // ---------------------------------------------------------------- приложения

    /**
     * Ключ — признаки в названии, "слово*" значит "начинается с". Значение — что открыть.
     * Сначала проверяются эти основные приложения, потом общий поиск по названию.
     */
    private val appAliases: List<Pair<List<String>, String>> = listOf(
        listOf("телефон*", "звонилк*", "dialer", "phone", "набор*") to "dialer",
        listOf("камер*", "camera", "фотоаппарат*") to "camera",
        listOf("настройк*", "settings") to "settings",
        listOf("файл*", "проводник*", "explorer", "files") to "files",
        listOf("браузер*", "browser") to "browser",
        listOf("галере*", "gallery") to "gallery",
        listOf("контакт*", "contacts") to "contacts",
        listOf("калькулятор*", "calculator") to "calculator",
        listOf("календар*", "calendar") to "calendar",
        listOf("whatsapp", "ватсап*", "вацап*", "вотсап*", "уотсап*") to "whatsapp",
    )

    private fun words(text: String): List<String> =
        text.lowercase(Locale.getDefault()).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun aliasFor(name: String): String? {
        val tokens = words(name)
        for ((keys, target) in appAliases) {
            val hit = keys.any { key ->
                if (key.endsWith("*")) {
                    tokens.any { it.startsWith(key.dropLast(1)) }
                } else {
                    tokens.any { it == key }
                }
            }
            if (hit) return target
        }
        return null
    }

    private fun launchCategory(context: Context, category: String): Boolean =
        launch(context, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category))

    fun openApp(context: Context, rawName: String): String {
        val name = rawName.trim()
        if (name.isEmpty()) return "Не указано, какое приложение открыть"

        val opened = when (aliasFor(name)) {
            "dialer" -> return openDialer(context, null)
            "camera" -> return openCamera(context, "photo")
            "settings" -> return openSettings(context, "main")
            "files" -> return openFiles(context)
            "browser" -> launchCategory(context, Intent.CATEGORY_APP_BROWSER)
            "gallery" -> launchCategory(context, Intent.CATEGORY_APP_GALLERY)
            "contacts" -> launchCategory(context, Intent.CATEGORY_APP_CONTACTS)
            "calculator" -> launchCategory(context, Intent.CATEGORY_APP_CALCULATOR)
            "calendar" -> launchCategory(context, Intent.CATEGORY_APP_CALENDAR)
            "whatsapp" -> return openWhatsApp(context, null, null)
            else -> false
        }
        if (opened) return "Открываю «$name»"
        return openByLabel(context, name)
    }

    /** Ищет установленное приложение по названию на значке и запускает его. */
    private fun openByLabel(context: Context, rawName: String): String {
        val name = rawName.trim().lowercase(Locale.getDefault())
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        var bestScore = 0
        var bestLabel = ""
        var bestPackage = ""
        var bestActivity = ""
        for (info in pm.queryIntentActivities(launcherIntent, 0)) {
            val label = info.loadLabel(pm).toString()
            val l = label.lowercase(Locale.getDefault())
            val score = when {
                l == name -> 3
                l.startsWith(name) -> 2
                l.contains(name) || (l.length >= 3 && name.contains(l)) -> 1
                else -> 0
            }
            if (score > bestScore || (score == bestScore && score > 0 && label.length < bestLabel.length)) {
                bestScore = score
                bestLabel = label
                bestPackage = info.activityInfo.packageName
                bestActivity = info.activityInfo.name
            }
        }
        if (bestScore == 0) return "Не нашёл приложение «$rawName»"

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(bestPackage, bestActivity)
        return if (launch(context, intent)) "Открываю «$bestLabel»" else "Не получилось открыть «$bestLabel»"
    }

    fun openFiles(context: Context): String {
        for (label in listOf("файлы", "files", "проводник", "file manager")) {
            val result = openByLabel(context, label)
            if (!result.startsWith("Не нашёл")) return result
        }
        val root = DocumentsContract.buildRootUri("com.android.externalstorage.documents", "primary")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(root, DocumentsContract.Root.MIME_TYPE_ITEM)
        return if (launch(context, intent)) "Открываю файлы" else "Не нашёл файловый менеджер"
    }

    // ---------------------------------------------------------------- телефон

    /** Открывает набор номера. Сам звонок пользователь начинает кнопкой вызова. */
    fun openDialer(context: Context, number: String?): String {
        val clean = number?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }.orEmpty()
        val intent = if (clean.isNotEmpty()) {
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", clean, null))
        } else {
            Intent(Intent.ACTION_DIAL)
        }
        return when {
            !launch(context, intent) -> "Не нашёл приложение телефона"
            clean.isNotEmpty() -> "Открыл набор номера $clean, нажми кнопку вызова"
            else -> "Открыл телефон"
        }
    }

    // ---------------------------------------------------------------- браузер

    fun openBrowser(context: Context, url: String?, query: String?): String {
        val target = url?.trim().orEmpty()
        if (target.isNotEmpty()) {
            if (target.contains("://") && !target.startsWith("http://") && !target.startsWith("https://")) {
                return "Открываю в браузере только сайты (http и https)"
            }
            val full = if (target.contains("://")) target else "https://$target"
            return if (launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(full)))) {
                "Открываю $full"
            } else {
                "Не нашёл браузер"
            }
        }

        val search = query?.trim().orEmpty()
        if (search.isNotEmpty()) {
            val webSearch = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, search)
            if (launch(context, webSearch)) return "Ищу в интернете: «$search»"
            val google = Uri.parse("https://www.google.com/search?q=" + Uri.encode(search))
            return if (launch(context, Intent(Intent.ACTION_VIEW, google))) {
                "Ищу в интернете: «$search»"
            } else {
                "Не нашёл браузер"
            }
        }

        return if (launchCategory(context, Intent.CATEGORY_APP_BROWSER)) "Открываю браузер" else "Не нашёл браузер"
    }

    // ---------------------------------------------------------------- настройки

    fun openSettings(context: Context, section: String?): String {
        val key = section?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val (action, title) = when (key) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS to "Wi-Fi"
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            "display" -> Settings.ACTION_DISPLAY_SETTINGS to "экрана"
            "sound" -> Settings.ACTION_SOUND_SETTINGS to "звука"
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "батареи"
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS to "приложений"
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "геолокации"
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "режима полёта"
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "памяти"
            "date" -> Settings.ACTION_DATE_SETTINGS to "даты и времени"
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS to "специальных возможностей"
            "nfc" -> Settings.ACTION_NFC_SETTINGS to "NFC"
            else -> Settings.ACTION_SETTINGS to ""
        }
        if (!launch(context, Intent(action))) return "Не получилось открыть настройки"
        return if (title.isEmpty()) {
            "Открыл настройки"
        } else {
            "Открыл настройки $title. Переключить что-то сам я не могу, Android не разрешает это приложениям"
        }
    }

    // ---------------------------------------------------------------- WhatsApp

    /**
     * Открывает WhatsApp. С номером — сразу чат с ним (и с готовым текстом, если он есть),
     * отправляет сообщение сам пользователь.
     */
    fun openWhatsApp(context: Context, phone: String?, message: String?): String {
        var digits = phone?.filter { it.isDigit() }.orEmpty()
        // Казахстан и Россия: 8XXXXXXXXXX -> 7XXXXXXXXXX (для WhatsApp нужен международный формат)
        if (digits.length == 11 && digits.startsWith("8")) digits = "7" + digits.drop(1)
        val text = message?.trim().orEmpty()

        if (digits.isNotEmpty()) {
            val link = "https://wa.me/$digits" + if (text.isNotEmpty()) "?text=" + Uri.encode(text) else ""
            for (pkg in WHATSAPP_PACKAGES) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg)
                if (launch(context, intent)) {
                    return if (text.isNotEmpty()) {
                        "Открыл чат WhatsApp с +$digits, текст подставил. Нажми «Отправить»"
                    } else {
                        "Открыл чат WhatsApp с +$digits"
                    }
                }
            }
            return "WhatsApp не найден на телефоне"
        }

        if (text.isNotEmpty()) {
            for (pkg in WHATSAPP_PACKAGES) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    setPackage(pkg)
                }
                if (launch(context, intent)) return "Открыл WhatsApp: выбери, кому отправить текст"
            }
            return "WhatsApp не найден на телефоне"
        }

        for (pkg in WHATSAPP_PACKAGES) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null && launch(context, intent)) return "Открываю WhatsApp"
        }
        return "WhatsApp не найден на телефоне"
    }
}
