package com.example.assistant

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Песочница для мини-приложений: страница работает с JavaScript, но без интернета, без доступа к файлам
 * и без мостов к приложению — она ничего не может сделать с телефоном. Кнопки: сохранить, поделиться, закрыть.
 */
class SandboxActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HTML = "html"
        const val EXTRA_FILE = "file"
        const val EXTRA_LIST = "list"
    }

    private var html = ""
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_LIST, false)) { showList(); return }
        html = intent.getStringExtra(EXTRA_HTML) ?: intent.getStringExtra(EXTRA_FILE)?.let { try { File(it).readText() } catch (e: Exception) { null } } ?: ""
        if (html.isBlank()) { finish(); return }
        title = MiniApps.title(html)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(VisualView.BG) }
        val bar = LinearLayout(this).apply { setPadding(16, 8, 16, 8) }
        fun button(label: String, action: () -> Unit) = TextView(this).apply {
            text = label; setTextColor(0xFFB9B0FF.toInt()); textSize = 15f; setPadding(20, 18, 20, 18); setOnClickListener { action() }
        }
        bar.addView(TextView(this).apply { text = title; setTextColor(VisualView.TEXT); textSize = 16f; setPadding(12, 18, 12, 18) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (intent.getStringExtra(EXTRA_FILE) == null) bar.addView(button("Сохранить") {
            val f = MiniApps.save(this, html); Toast.makeText(this, "Сохранено: ${f.name}", Toast.LENGTH_SHORT).show()
        })
        bar.addView(button("Поделиться") {
            startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/html")
                .putExtra(android.content.Intent.EXTRA_TEXT, html), "Поделиться"))
        })
        bar.addView(button("✕") { finish() })
        root.addView(bar)
        val w = WebView(this)
        web = w
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        w.webViewClient = object : WebViewClient() {
            // Никаких загрузок извне и переходов: только сама страница
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                if (request?.url?.scheme in setOf("http", "https", "file", "content", "ftp")) WebResourceResponse("text/plain", "utf-8", null) else null
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        }
        w.setBackgroundColor(VisualView.BG)
        w.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
        root.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun showList() {
        val files = MiniApps.list(this)
        title = "Мои мини-приложения"
        val list = ListView(this)
        list.setBackgroundColor(VisualView.BG)
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, files.map { it.nameWithoutExtension }) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(VisualView.TEXT) }
        }
        list.setOnItemClickListener { _, _, i, _ -> MiniApps.openFile(this, files[i]) }
        setContentView(list)
    }

    override fun onDestroy() {
        web?.destroy()
        super.onDestroy()
    }
}
