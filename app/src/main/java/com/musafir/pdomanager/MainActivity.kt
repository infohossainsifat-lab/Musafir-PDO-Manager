package com.musafir.pdomanager

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("pdo_queue", Context.MODE_PRIVATE) }
    private var queue = mutableListOf<String>()
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = prefs.getString("queue", "")!!.lines().filter { it.isNotBlank() }.toMutableList()
        index = prefs.getInt("index", 0).coerceIn(0, queue.size)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 8)
        }
        page.addView(TextView(this).apply {
            text = "Musafir PDO Manager • পরীক্ষামূলক সংস্করণ"
            textSize = 18f
        })

        input = EditText(this).apply {
            hint = "প্রতি লাইনে একটি Passport Number পেস্ট করুন"
            minLines = 3
            maxLines = 5
            setText(queue.joinToString("\n"))
            textSize = 14f
        }
        page.addView(input, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(Button(this).apply {
            text = "তালিকা সংরক্ষণ / নতুন ব্যাচ"
            setOnClickListener { saveQueue() }
        })

        status = TextView(this).apply { textSize = 14f; setPadding(2, 6, 2, 6) }
        page.addView(status)

        val row1 = LinearLayout(this)
        row1.addView(Button(this).apply {
            text = "PDO ওয়েবসাইট খুলুন"
            setOnClickListener { if (web.url == null) web.loadUrl("https://www.oep.gov.bd/pdo-enrollment-card") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(Button(this).apply {
            text = "বর্তমান নম্বর বসান"
            setOnClickListener { fillCurrentPassport() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(row1)

        val row2 = LinearLayout(this)
        row2.addView(Button(this).apply {
            text = "আগের"
            setOnClickListener {
                if (index > 0) { index--; persist(); updateStatus(); fillCurrentPassport() }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(Button(this).apply {
            text = "পরের নম্বর"
            setOnClickListener {
                if (index < queue.size) { index++; persist(); updateStatus(); fillCurrentPassport() }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(Button(this).apply {
            text = "কার্ড Print / PDF"
            setOnClickListener { printCurrentPage() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(row2)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                // OEP may open the enrollment card in a new tab/window after View is tapped.
                // Route that navigation back into the visible WebView in this app.
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val popup = WebView(this@MainActivity).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                web.loadUrl(url)
                                return true
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                if (url.isNullOrBlank()) return false
                                web.loadUrl(url)
                                return true
                            }
                        }
                    }
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        page.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        updateStatus()
        web.loadUrl("https://www.oep.gov.bd/pdo-enrollment-card")
    }

    private fun saveQueue() {
        val cleaned = input.text.toString().lines().map { it.trim() }
            .filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) {
            Toast.makeText(this, "আগে Passport Number পেস্ট করুন", Toast.LENGTH_SHORT).show()
            return
        }
        queue = cleaned.toMutableList()
        index = 0
        persist()
        input.setText(queue.joinToString("\n"))
        updateStatus()
        Toast.makeText(this, "${queue.size}টি নম্বর সংরক্ষিত হয়েছে", Toast.LENGTH_SHORT).show()
    }

    private fun fillCurrentPassport() {
        if (index !in queue.indices) {
            Toast.makeText(this, "বর্তমান ব্যাচে আর নম্বর নেই", Toast.LENGTH_SHORT).show()
            return
        }
        val passport = queue[index]
        // Only fills Passport/NID. CAPTCHA is never read, solved, or submitted by this app.
        val js = """
            (function() {
              const fields = Array.from(document.querySelectorAll('input'));
              const target = fields.find(e => {
                const hint = ((e.placeholder || '') + ' ' + (e.getAttribute('aria-label') || '')).toLowerCase();
                return hint.includes('passport') || hint.includes('nid');
              }) || fields.find(e => (e.type || '').toLowerCase() === 'text' && !e.value);
              if (!target) return 'NOT_FOUND';
              target.focus();
              const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
              setter.call(target, '__PASSPORT__');
              target.dispatchEvent(new Event('input', {bubbles:true}));
              target.dispatchEvent(new Event('change', {bubbles:true}));
              return 'FILLED';
            })();
        """.trimIndent().replace("__PASSPORT__", JSONObject.quote(passport))
        web.evaluateJavascript(js) { result ->
            val msg = if (result?.contains("FILLED") == true) "Passport বসানো হয়েছে"
                else "Passport ঘর পাওয়া যায়নি—ওয়েবপেজ লোড হয়েছে কি না দেখুন"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun printCurrentPage() {
        val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print("PDO_Enrollment_Card", web.createPrintDocumentAdapter("PDO_Enrollment_Card"),
            PrintAttributes.Builder().build())
    }

    private fun persist() {
        prefs.edit().putString("queue", queue.joinToString("\n")).putInt("index", index).apply()
    }

    private fun updateStatus() {
        status.text = if (queue.isEmpty()) "কোনো ব্যাচ লোড করা হয়নি"
        else "ব্যাচ: ${queue.size}টি • বর্তমান: ${if (index < queue.size) index + 1 else "সম্পন্ন"} / ${queue.size}"
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

}
