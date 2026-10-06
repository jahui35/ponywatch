package com.example.ponywatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("pw", MODE_PRIVATE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        fun button(label: String, action: () -> Unit) {
            col.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        col.addView(TextView(this).apply {
            text = "Pony Watch\nWatches Carousell SG (newest first) for \"my little pony\", \"mlp\" and \"pony\"."
            textSize = 18f
        })
        val secs = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Seconds between checks (min 20)"
            setText(prefs.getInt("secs", 60).toString())
        }
        col.addView(secs)

        button("1. Verify Carousell (do once)") {
            startActivity(Intent(this, VerifyActivity::class.java))
        }
        button("2. Allow running in background") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        button("3. Start watching") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            val s = secs.text.toString().toIntOrNull() ?: 60
            prefs.edit().putInt("secs", s.coerceAtLeast(20)).apply()
            startForegroundService(Intent(this, WatcherService::class.java))
        }
        button("Stop") {
            startService(Intent(this, WatcherService::class.java).setAction(WatcherService.ACTION_STOP))
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }
}

/** Opens Carousell once so you can pass any "verify you are human" check. Cookies are shared with the watcher. */
class VerifyActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = w.settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = WebViewClient()
        setContentView(w)
        w.loadUrl("https://www.carousell.sg/search/my%20little%20pony?sort_by=3")
    }
}
