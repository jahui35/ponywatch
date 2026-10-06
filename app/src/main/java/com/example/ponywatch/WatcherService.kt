package com.example.ponywatch

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URLEncoder

class WatcherService : Service() {
    companion object {
        const val ACTION_STOP = "stop"
        const val CH_STATUS = "status"
        const val CH_ALERT = "alerts"
        val QUERIES = listOf("my little pony", "mlp", "pony")
        val MATCH = Regex("\\bmlp\\b|pony|ponies", RegexOption.IGNORE_CASE)
        // Collects every listing link (/p/...) on the results page with its visible text.
        const val JS = """(function(){var o=[],s={};document.querySelectorAll('a[href*="/p/"]').forEach(function(a){var h=a.href.split('?')[0];if(s[h])return;s[h]=1;o.push({u:h,t:(a.innerText||a.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim()});});return JSON.stringify(o);})()"""
    }

    private val h = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var prefs: SharedPreferences
    private var idx = 0
    private var delayMs = 60_000L

    private val loadRun = Runnable { load() }
    private val extractRun = Runnable { web?.evaluateJavascript(JS) { handle(it) } }
    private val timeoutRun = Runnable { status("Page timed out, retrying"); next() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        prefs = getSharedPreferences("pw", MODE_PRIVATE)
        if (web != null) return START_STICKY
        delayMs = prefs.getInt("secs", 60).coerceAtLeast(20) * 1000L
        createChannels()
        ServiceCompat.startForeground(
            this, 1, statusNotif("Starting..."),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        @Suppress("WakelockTimeout")
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ponywatch:w").apply { acquire() }
        setupWeb()
        h.post { load() }
        return START_STICKY
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val w = WebView(applicationContext)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = w.settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                h.removeCallbacks(extractRun)
                h.postDelayed(extractRun, 6000) // let the page render its listings
            }
        }
        web = w
    }

    private fun load() {
        val q = QUERIES[idx % QUERIES.size]
        val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        h.postDelayed(timeoutRun, 45_000)
        web?.loadUrl("https://www.carousell.sg/search/$enc?sort_by=3") // sort_by=3 = most recent
    }

    private fun next() {
        h.removeCallbacks(extractRun)
        h.removeCallbacks(timeoutRun)
        idx++
        h.postDelayed(loadRun, delayMs)
    }

    private fun handle(raw: String?) {
        val q = QUERIES[idx % QUERIES.size]
        try {
            val arr = JSONArray((JSONTokener(raw ?: "null").nextValue() as? String) ?: "[]")
            if (arr.length() == 0) {
                status("Read 0 listings for \"$q\" - open the app and tap Verify")
            } else {
                val seen = prefs.getStringSet("seen", emptySet())!!.toMutableSet()
                val seeded = prefs.getBoolean("seeded_$q", false) // first pass only records, no alerts
                var found = 0
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val u = o.getString("u")
                    val t = o.getString("t")
                    if (!seen.add(u)) continue
                    val slug = u.substringAfter("/p/").trimEnd('/').replace('-', ' ')
                    if (seeded && MATCH.containsMatchIn("$t $slug")) { alert(u, t.ifBlank { slug }); found++ }
                }
                prefs.edit().putStringSet("seen", seen).putBoolean("seeded_$q", true).apply()
                status("Checked \"$q\": ${arr.length()} listings, $found new matches")
            }
        } catch (e: Exception) {
            status("Error: ${e.message}")
        }
        next()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Watcher status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "New listings", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun statusNotif(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Pony Watch running")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun status(text: String) =
        getSystemService(NotificationManager::class.java).notify(1, statusNotif(text))

    private fun alert(url: String, title: String) {
        val pi = PendingIntent.getActivity(
            this, url.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New pony listing")
            .setContentText(title.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(url.hashCode(), n)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        web?.destroy(); web = null
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
