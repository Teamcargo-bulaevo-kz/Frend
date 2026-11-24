package com.example.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * Всё, что ассистент рисует поверх других приложений: перевод надписей на их местах, карточка с ответом
 * и плавающая кнопка «Живого экрана». Окна типа TYPE_ACCESSIBILITY_OVERLAY от службы специальных возможностей:
 * отдельное разрешение «поверх окон» не нужно. Вызывать с главного потока.
 */
object ScreenOverlay {

    private const val PREFS = "assistant_prefs"
    private const val KEY_BUBBLE = "screen_bubble"

    private val handler = Handler(Looper.getMainLooper())
    private var translation: View? = null
    private var translationPackage = ""
    private var card: View? = null
    private var bubble: View? = null
    private val hideCard = Runnable { removeCard() }

    private fun wm(context: Context) = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun dp(context: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun params(w: Int, h: Int, touchable: Boolean = true) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    private fun service(): AssistantAccessibilityService? = AssistantAccessibilityService.instance

    // ---------------------------------------------------------------- перевод на местах

    /** Переводы поверх исходных надписей. Касание любого места убирает перевод. */
    fun showTranslation(items: List<Pair<String, Rect>>, packageName: String) {
        val s = service() ?: return
        removeTranslation()
        val root = FrameLayout(s)
        root.setBackgroundColor(Color.argb(40, 0, 0, 0))
        // Координаты окна считаются от верха экрана: учитываем, что само окно рисуется под строкой состояния
        val loc = IntArray(2)
        for ((text, r) in items) {
            val tv = TextView(s).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, (r.height() * 0.42f).coerceIn(dp(s, 10).toFloat(), dp(s, 18).toFloat()))
                background = rounded(Color.argb(225, 20, 24, 40), dp(s, 4).toFloat())
                setPadding(dp(s, 3), 0, dp(s, 3), 0)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 4
            }
            val lp = FrameLayout.LayoutParams(maxOf(r.width(), dp(s, 40)), FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = r.left
            lp.topMargin = r.top
            root.addView(tv, lp)
        }
        root.addView(TextView(s).apply {
            text = "Перевод · коснись, чтобы закрыть"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = rounded(Color.argb(230, 108, 92, 255), dp(s, 12).toFloat())
            setPadding(dp(s, 10), dp(s, 4), dp(s, 10), dp(s, 4))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(s, 72) })
        root.setOnClickListener { removeTranslation() }
        val p = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        try {
            wm(s).addView(root, p)
            translation = root
            translationPackage = packageName
            // Если окно всё-таки сдвинуто (вырез экрана), поправим отступы
            root.post {
                root.getLocationOnScreen(loc)
                if (loc[1] != 0 || loc[0] != 0) for (i in 0 until root.childCount - 1) {
                    val lp = root.getChildAt(i).layoutParams as FrameLayout.LayoutParams
                    lp.leftMargin -= loc[0]; lp.topMargin -= loc[1]
                    root.getChildAt(i).layoutParams = lp
                }
            }
        } catch (_: Exception) {}
    }

    fun removeTranslation() {
        translation?.let { v -> service()?.let { try { wm(it).removeView(v) } catch (_: Exception) {} } }
        translation = null
    }

    /** Пользователь ушёл в другое приложение — перевод устарел. */
    fun onWindowChanged(pkg: String) {
        if (translation == null || pkg.isBlank()) return
        if (pkg != translationPackage && pkg != "com.android.systemui" && pkg != service()?.packageName) removeTranslation()
    }

    // ---------------------------------------------------------------- карточка с ответом

    /** Карточка внизу экрана: ответ про экран или перевод текста с картинки. Закрывается касанием или сама. */
    fun showCard(title: String, text: String, autoHideMs: Long = 45_000L) {
        val s = service() ?: return
        removeCard()
        val box = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(240, 22, 22, 34), dp(s, 18).toFloat())
            setPadding(dp(s, 16), dp(s, 12), dp(s, 16), dp(s, 12))
            elevation = dp(s, 8).toFloat()
        }
        box.addView(TextView(s).apply {
            this.text = "$title   ✕"
            setTextColor(Color.rgb(170, 160, 255)); textSize = 13f
        })
        val scroll = ScrollView(s)
        scroll.addView(TextView(s).apply {
            this.text = text
            setTextColor(Color.WHITE); textSize = 15f
            setTextIsSelectable(false)
            setPadding(0, dp(s, 6), 0, 0)
        })
        box.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.setOnClickListener { removeCard() }
        val dm = s.resources.displayMetrics
        val p = params(dm.widthPixels - dp(s, 24), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(s, 90)
        }
        scroll.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
        box.post {
            val max = (dm.heightPixels * 0.45).toInt()
            if (scroll.height > max) { scroll.layoutParams.height = max; scroll.requestLayout() }
        }
        try { wm(s).addView(box, p); card = box } catch (_: Exception) {}
        handler.removeCallbacks(hideCard)
        if (autoHideMs > 0) handler.postDelayed(hideCard, autoHideMs)
    }

    fun removeCard() {
        handler.removeCallbacks(hideCard)
        card?.let { v -> service()?.let { try { wm(it).removeView(v) } catch (_: Exception) {} } }
        card = null
    }

    // ---------------------------------------------------------------- плавающая кнопка «Живого экрана»

    fun bubbleEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BUBBLE, false)

    fun setBubble(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BUBBLE, on).apply()
        if (on) showBubble() else removeBubble()
    }

    fun restoreBubble(context: Context) { if (bubbleEnabled(context)) showBubble() }

    /** Две кнопки у края экрана: «?» — спросить про экран голосом, «Aあ» — перевести экран. Перетаскиваются. */
    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        val s = service() ?: return
        if (bubble != null) return
        val column = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(200, 30, 30, 48), dp(s, 22).toFloat())
            setPadding(dp(s, 4), dp(s, 4), dp(s, 4), dp(s, 4))
        }
        fun button(label: String, onTap: () -> Unit) = TextView(s).apply {
            text = label
            setTextColor(Color.WHITE); textSize = 15f
            gravity = Gravity.CENTER
            background = rounded(Color.argb(235, 108, 92, 255), dp(s, 19).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(s, 38), dp(s, 38)).apply { setMargins(0, dp(s, 2), 0, dp(s, 2)) }
            tag = onTap
        }
        val ask = button("?") { ScreenAssist.askByVoice(s) }
        val translate = button("Aあ") { ScreenAssist.translateFromBubble(s) }
        column.addView(ask)
        column.addView(translate)
        val dm = s.resources.displayMetrics
        val p = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = dm.widthPixels - dp(s, 56); y = (dm.heightPixels * 0.45).toInt()
        }
        // Перетаскивание за любую кнопку; короткое касание — действие кнопки
        val drag = object : View.OnTouchListener {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (abs(dx) > 12 || abs(dy) > 12) moved = true
                        if (moved) { p.x = (startX + dx).toInt(); p.y = (startY + dy).toInt(); try { wm(s).updateViewLayout(column, p) } catch (_: Exception) {} }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) {
                        @Suppress("UNCHECKED_CAST") (v.tag as? () -> Unit)?.invoke()
                    }
                }
                return true
            }
        }
        ask.setOnTouchListener(drag)
        translate.setOnTouchListener(drag)
        try { wm(s).addView(column, p); bubble = column } catch (_: Exception) {}
    }

    fun removeBubble() {
        bubble?.let { v -> service()?.let { try { wm(it).removeView(v) } catch (_: Exception) {} } }
        bubble = null
    }

    // ---------------------------------------------------------------- для снимка экрана

    private var hiddenViews: List<View> = emptyList()

    /** Прячет наши окна, чтобы они не попали на снимок. true — было что прятать. */
    fun hideTemporarily(): Boolean {
        val views = listOfNotNull(translation, card, bubble).filter { it.visibility == View.VISIBLE }
        views.forEach { it.visibility = View.INVISIBLE }
        hiddenViews = views
        return views.isNotEmpty()
    }

    fun restore() {
        hiddenViews.forEach { it.visibility = View.VISIBLE }
        hiddenViews = emptyList()
    }

    fun removeAll() {
        removeTranslation(); removeCard(); removeBubble()
    }
}
