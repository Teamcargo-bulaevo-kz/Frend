package com.example.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Быстрые действия с экраном — общие для плиток шторки и кнопок в чате. */
object ScreenQuick {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Служба специальных возможностей нужна всем экранным функциям. */
    private fun ready(context: Context): Boolean {
        if (AssistantAccessibilityService.instance != null) return true
        Toast.makeText(context, "Включи службу специальных возможностей «Assistant» (или «Выдать все разрешения через Shizuku»)", Toast.LENGTH_LONG).show()
        try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        return false
    }

    /** Перевести то, что под шторкой или под чатом: даём экрану закрыться и переводим. */
    fun translate(context: Context, delayMs: Long = 700L) {
        val app = context.applicationContext
        if (!ready(app)) return
        handler.postDelayed({
            ScreenOverlay.showCard("Перевод", "Перевожу…", 8_000L)
            scope.launch {
                val r = ScreenAssist.translate(app)
                if (r.startsWith("Перевёл")) ScreenOverlay.removeCard() else ScreenOverlay.showCard("Перевод", r)
            }
        }, delayMs)
    }

    /** Спросить про экран голосом. */
    fun ask(context: Context, delayMs: Long = 700L) {
        val app = context.applicationContext
        if (!ready(app)) return
        handler.postDelayed({ ScreenAssist.askByVoice(app) }, delayMs)
    }

    /** Кнопки «?» и «Aあ» у края экрана: вкл/выкл. Возвращает новое состояние. */
    fun toggleLive(context: Context): Boolean {
        val on = !ScreenAssist.live(context)
        val msg = ScreenAssist.setLive(context, on)
        Toast.makeText(context, msg.substringBefore(':').take(60), Toast.LENGTH_SHORT).show()
        return on
    }
}

/** Общее для плиток: закрыть шторку (через службу — без открытия экрана) и выполнить действие. */
abstract class QuickActionTile : TileService() {
    abstract val title: String
    open fun active(): Boolean = false
    abstract fun act()

    override fun onStartListening() {
        qsTile?.apply {
            label = title
            state = if (active()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    protected fun collapseAndRun(action: () -> Unit) {
        val service = AssistantAccessibilityService.instance
        if (service != null) {
            service.globalAction("dismiss_shade") // шторка закрывается, под ней — нужное приложение
            action()
        } else {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 31, intent, PendingIntent.FLAG_IMMUTABLE))
            else startActivityAndCollapse(intent)
        }
    }

    override fun onClick() = act()
}

/** Плитка «Перевести экран». */
class TranslateTile : QuickActionTile() {
    override val title = "Перевести экран"
    override fun act() = collapseAndRun { ScreenQuick.translate(this, 900L) }
}

/** Плитка «Что на экране»: спросить голосом. */
class AskScreenTile : QuickActionTile() {
    override val title = "Что на экране"
    override fun act() = collapseAndRun { ScreenQuick.ask(this, 900L) }
}

/** Плитка «Живой экран»: кнопки «?» и «Aあ» у края экрана вкл/выкл. */
class LiveScreenTile : QuickActionTile() {
    override val title = "Живой экран"
    override fun active() = ScreenAssist.live(this)
    override fun act() {
        val on = ScreenQuick.toggleLive(this)
        qsTile?.apply { state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }
    }
}
