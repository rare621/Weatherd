package app.weathered

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var waitingForPermission = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
        }
        web.setBackgroundColor(Color.parseColor("#0A1015"))
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "file") return false
                openExternal(uri)
                return true
            }
        }
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun openExternal(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
    }

    private fun send(js: String) = main.post { web.evaluateJavascript(js, null) }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun findLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        val fresh = best != null && System.currentTimeMillis() - best!!.time < 10 * 60 * 1000
        if (fresh) { reply(best!!); return }
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) { if (best != null) reply(best!!) else send("onNativeLocation(null,null,'off')"); return }
        var done = false
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { if (!done) { done = true; reply(l) } }
            @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (_: Exception) { send("onNativeLocation(null,null,'error')"); return }
        main.postDelayed({
            if (!done) {
                done = true
                lm.removeUpdates(listener)
                if (best != null) reply(best!!) else send("onNativeLocation(null,null,'timeout')")
            }
        }, 20000)
    }

    private fun reply(l: Location) = send("onNativeLocation(${l.latitude},${l.longitude},null)")

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != 7 || !waitingForPermission) return
        waitingForPermission = false
        if (hasLocationPermission()) findLocation() else send("onNativeLocation(null,null,'denied')")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        send("(window.onBackButton && window.onBackButton()) || Android.exit()")
    }

    inner class Bridge {
        @JavascriptInterface fun locate() = main.post {
            if (hasLocationPermission()) findLocation()
            else {
                waitingForPermission = true
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
            }
        }

        /** The app hands the current place, settings and forecast to the home screen widgets. */
        @JavascriptInterface fun saveWidget(lat: Double, lon: Double, name: String, units: String, rude: Boolean, gps: Boolean, json: String) {
            val app = applicationContext
            Thread { try { Wx.save(app, lat, lon, name, units, rude, gps, json) } catch (_: Exception) {} }.start()
        }

        @JavascriptInterface fun open(url: String) = main.post { openExternal(Uri.parse(url)) }

        @JavascriptInterface fun exit() = main.post { finish() }

        /** Matches the status and navigation bars to the page theme. */
        @JavascriptInterface fun setBars(color: String, darkTheme: Boolean) = main.post {
            val c = try { Color.parseColor(color) } catch (_: Exception) { return@post }
            window.statusBarColor = c
            window.navigationBarColor = c
            web.setBackgroundColor(c)
            var flags = window.decorView.systemUiVisibility
            flags = if (darkTheme) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) {
                flags = if (darkTheme) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }
}
