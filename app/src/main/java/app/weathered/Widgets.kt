package app.weathered

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.os.SystemClock
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Home screen widgets. Data is fetched in °F / mph and converted for display, same as the app. */
object Wx {
    private const val PREFS = "widget"
    private const val STALE_MS = 20 * 60 * 1000L

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called from the app: remember the place and settings, optionally hand over fresh data. */
    fun save(c: Context, lat: Double, lon: Double, name: String, units: String, rude: Boolean, gps: Boolean, json: String?) {
        val e = prefs(c).edit()
            .putString("lat", lat.toString()).putString("lon", lon.toString())
            .putString("name", name).putString("units", units).putBoolean("rude", rude).putBoolean("gps", gps)
        if (!json.isNullOrEmpty()) e.putString("json", json).putLong("t", System.currentTimeMillis())
        e.apply()
        schedule(c)
        renderAll(c)
    }

    /** Android's own widget timer is lazy, so also ask for a refresh roughly every 30 minutes. */
    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, 1, Intent(c, RefreshReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HALF_HOUR, AlarmManager.INTERVAL_HALF_HOUR, pi)
    }

    fun refresh(c: Context, force: Boolean = false) {
        val p = prefs(c)
        val age = System.currentTimeMillis() - p.getLong("t", 0)
        if (force || age > STALE_MS || p.getString("json", null) == null) fetch(c)
        renderAll(c)
    }

    @SuppressLint("MissingPermission")
    private fun position(c: Context): Pair<Double, Double>? {
        val p = prefs(c)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return null
        if (p.getBoolean("gps", false) &&
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
                if (best != null && System.currentTimeMillis() - best.time < 3 * 3600 * 1000L) return best.latitude to best.longitude
            } catch (_: Exception) {}
        }
        return lat to lon
    }

    private fun fetch(c: Context) {
        val (lat, lon) = position(c) ?: return
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,is_day,weather_code,wind_gusts_10m" +
            "&hourly=temperature_2m,apparent_temperature,precipitation_probability,precipitation,weather_code,wind_gusts_10m,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&forecast_days=7&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch&timezone=auto"
        try {
            val con = URL(url).openConnection() as HttpURLConnection
            con.connectTimeout = 15000; con.readTimeout = 15000
            val body = con.inputStream.bufferedReader().use { it.readText() }
            if (con.responseCode == 200) prefs(c).edit().putString("json", body).putLong("t", System.currentTimeMillis()).apply()
        } catch (_: Exception) {}
    }

    // ---------- rendering ----------
    private val PROVIDERS = listOf(
        SmallWidget::class.java to R.layout.widget_small,
        MediumWidget::class.java to R.layout.widget_medium,
        LargeWidget::class.java to R.layout.widget_large,
    )

    fun renderAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        for ((cls, layout) in PROVIDERS) {
            val ids = m.getAppWidgetIds(ComponentName(c, cls))
            if (ids.isEmpty()) continue
            val rv = try { build(c, layout) } catch (_: Exception) { continue }
            for (id in ids) m.updateAppWidget(id, rv)
        }
    }

    private fun kind(code: Int) = when (code) {
        0, 1 -> "clear"; 2 -> "pcloud"; 3 -> "cloud"; 45, 48 -> "fog"
        in 51..55 -> "drizzle"; 56, 57, 66, 67 -> "frz"; in 61..65, in 80..82 -> "rain"
        in 71..77, 85, 86 -> "snow"; 95 -> "thunder"; 96, 99 -> "hail"; else -> "cloud"
    }

    private val LABEL = mapOf(0 to "Clear", 1 to "Mostly clear", 2 to "Partly cloudy", 3 to "Overcast", 45 to "Fog", 48 to "Freezing fog",
        51 to "Light drizzle", 53 to "Drizzle", 55 to "Heavy drizzle", 56 to "Freezing drizzle", 57 to "Freezing drizzle",
        61 to "Light rain", 63 to "Rain", 65 to "Heavy rain", 66 to "Freezing rain", 67 to "Freezing rain",
        71 to "Light snow", 73 to "Snow", 75 to "Heavy snow", 77 to "Snow grains", 80 to "Light showers", 81 to "Showers",
        82 to "Violent showers", 85 to "Snow showers", 86 to "Heavy snow showers", 95 to "Thunderstorm", 96 to "Thunderstorm, hail", 99 to "Severe storm, hail")

    private fun icon(code: Int, day: Boolean) = when (kind(code)) {
        "clear" -> if (day) R.drawable.wi_clear_d else R.drawable.wi_clear_n
        "pcloud" -> if (day) R.drawable.wi_pcloud_d else R.drawable.wi_pcloud_n
        "fog" -> R.drawable.wi_fog
        "drizzle", "rain" -> R.drawable.wi_rain
        "frz" -> R.drawable.wi_frz
        "snow" -> R.drawable.wi_snow
        "thunder" -> R.drawable.wi_thunder
        "hail" -> R.drawable.wi_hail
        else -> R.drawable.wi_cloud
    }

    private fun background(code: Int, tempF: Double, day: Boolean): Int {
        val k = kind(code)
        val s = when (k) {
            "drizzle", "rain" -> if (tempF <= 33) "mix" else "rain"
            "frz" -> "mix"; "thunder" -> "storm"; else -> k
        }
        val name = "wbg_${s}_${if (day) "d" else "n"}"
        return when (name) {
            "wbg_clear_d" -> R.drawable.wbg_clear_d; "wbg_clear_n" -> R.drawable.wbg_clear_n
            "wbg_pcloud_d" -> R.drawable.wbg_pcloud_d; "wbg_pcloud_n" -> R.drawable.wbg_pcloud_n
            "wbg_cloud_d" -> R.drawable.wbg_cloud_d; "wbg_cloud_n" -> R.drawable.wbg_cloud_n
            "wbg_rain_d" -> R.drawable.wbg_rain_d; "wbg_rain_n" -> R.drawable.wbg_rain_n
            "wbg_mix_d" -> R.drawable.wbg_mix_d; "wbg_mix_n" -> R.drawable.wbg_mix_n
            "wbg_snow_d" -> R.drawable.wbg_snow_d; "wbg_snow_n" -> R.drawable.wbg_snow_n
            "wbg_hail_d" -> R.drawable.wbg_hail_d; "wbg_hail_n" -> R.drawable.wbg_hail_n
            "wbg_storm_d" -> R.drawable.wbg_storm_d; "wbg_storm_n" -> R.drawable.wbg_storm_n
            "wbg_fog_d" -> R.drawable.wbg_fog_d; else -> R.drawable.wbg_fog_n
        }
    }

    private fun hash(s: String): Long { var h = 2166136261L; for (ch in s) { h = h xor ch.code.toLong(); h = (h * 16777619L) and 0xffffffffL }; return h }

    /** Same idea as the app: judge the 8 AM to 8 PM work day, not just this hour. */
    private fun quip(j: JSONObject, rude: Boolean): String {
        val H = j.getJSONObject("hourly"); val cur = j.getJSONObject("current")
        val time = H.getJSONArray("time"); val day = Instant.now().atOffset(ZoneOffset.ofTotalSeconds(j.optInt("utc_offset_seconds", 0))).toLocalDate().toString()
        val feel = H.getJSONArray("apparent_temperature"); val gust = H.getJSONArray("wind_gusts_10m")
        val code = H.getJSONArray("weather_code"); val pp = H.getJSONArray("precipitation_probability"); val pr = H.getJSONArray("precipitation")
        var fMax = -1e9; var fMin = 1e9; var gMax = 0.0; var thunder = false; var snow = false; var rain = false; var fog = false
        for (i in 0 until time.length()) {
            val t = time.getString(i); if (!t.startsWith(day)) continue
            val h = t.substring(11, 13).toInt(); if (h < 8 || h > 20) continue
            fMax = maxOf(fMax, feel.optDouble(i, 60.0)); fMin = minOf(fMin, feel.optDouble(i, 60.0)); gMax = maxOf(gMax, gust.optDouble(i, 0.0))
            when (kind(code.optInt(i))) {
                "thunder", "hail" -> thunder = true
                "snow", "frz" -> snow = true
                "rain", "drizzle" -> rain = true
                "fog" -> fog = true
                else -> if (pp.optInt(i) >= 60 && pr.optDouble(i, 0.0) >= 0.02) rain = true
            }
        }
        if (fMax < -1e8) { fMax = cur.optDouble("apparent_temperature", 60.0); fMin = fMax; gMax = cur.optDouble("wind_gusts_10m", 0.0) }
        val k0 = kind(cur.optInt("weather_code")); if (k0 == "thunder" || k0 == "hail") thunder = true
        val key = when {
            thunder -> "thunder"; fMax >= 100 -> "hot"; snow -> "snow"; rain -> "rain"; gMax >= 30 -> "wind"; fog -> "fog"
            fMin <= 20 -> "cold"; fMax >= 85 -> "warm"; fMax <= 40 -> "cool"; else -> "nice"
        }
        val list = Quips.Q[key] ?: Quips.Q.getValue("nice")
        // changes every 3 hours so the widget doesn't say the same thing all day
        val slot = Instant.now().atOffset(ZoneOffset.ofTotalSeconds(j.optInt("utc_offset_seconds", 0))).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH")).let { it.substring(0, 11) + (it.substring(11, 13).toInt() / 3) }
        val p = list[(hash(j.optDouble("latitude").toString().take(5) + slot + key) % list.size).toInt()]
        return if (rude) p.first else p.second
    }

    private fun hr12(iso: String): String { val h = iso.substring(11, 13).toInt(); return "${if (h % 12 == 0) 12 else h % 12} ${if (h >= 12) "PM" else "AM"}" }

    private fun build(c: Context, layout: Int): RemoteViews {
        val rv = RemoteViews(c.packageName, layout)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        rv.setOnClickPendingIntent(R.id.root, open)
        val p = prefs(c)
        val us = p.getString("units", "us") != "metric"
        val rude = p.getBoolean("rude", true)
        fun t(f: Double) = "${(if (us) f else (f - 32) * 5 / 9).roundToInt()}°"
        val raw = p.getString("json", null)
        if (raw == null) {
            rv.setTextViewText(R.id.place, p.getString("name", null) ?: "Weathered")
            rv.setTextViewText(R.id.temp, "--°")
            rv.setTextViewText(R.id.cond, "No data yet")
            rv.setTextViewText(R.id.quip, if (rude) "Open the damn app and set a location, genius." else "Open the app to set a location.")
            return rv
        }
        val j = JSONObject(raw)
        val cur = j.getJSONObject("current"); val H = j.getJSONObject("hourly"); val D = j.getJSONObject("daily")
        val time = H.getJSONArray("time")
        // which hour is it right now at that place (the saved "current" can be old)
        val off = j.optInt("utc_offset_seconds", 0)
        val nowLocal = Instant.now().atOffset(ZoneOffset.ofTotalSeconds(off)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        var i0 = -1; for (i in 0 until time.length()) if (time.getString(i) <= nowLocal) i0 = i
        if (i0 < 0) i0 = 0
        val age = System.currentTimeMillis() - p.getLong("t", 0)
        val useHourly = age > 45 * 60 * 1000L || cur.optString("time").take(13) != nowLocal.take(13)
        val code = if (useHourly) H.getJSONArray("weather_code").optInt(i0) else cur.optInt("weather_code")
        val day = (if (useHourly) H.getJSONArray("is_day").optInt(i0, 1) else cur.optInt("is_day", 1)) == 1
        val tempF = if (useHourly) H.getJSONArray("temperature_2m").optDouble(i0) else cur.optDouble("temperature_2m")
        val feelsF = if (useHourly) H.getJSONArray("apparent_temperature").optDouble(i0) else cur.optDouble("apparent_temperature")
        val upd = Instant.ofEpochMilli(p.getLong("t", 0)).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))
        rv.setInt(R.id.root, "setBackgroundResource", background(code, tempF, day))
        rv.setImageViewResource(R.id.icon, icon(code, day))
        rv.setTextViewText(R.id.temp, t(tempF))
        rv.setTextViewText(R.id.cond, LABEL[code] ?: "")
        rv.setTextViewText(R.id.place, "${p.getString("name", "") ?: ""} · $upd")
        val hi = D.getJSONArray("temperature_2m_max"); val lo = D.getJSONArray("temperature_2m_min")
        val dts = D.getJSONArray("time"); var td = 0; for (k in 0 until dts.length()) if (dts.getString(k) < nowLocal.take(10)) td = k + 1
        rv.setTextViewText(R.id.hilo, "H ${t(hi.optDouble(td))}  L ${t(lo.optDouble(td))}")
        rv.setTextViewText(R.id.quip, quip(j, rude))
        if (layout == R.layout.widget_small) return rv

        rv.setTextViewText(R.id.feels, "Feels ${t(feelsF)}")
        val hT = intArrayOf(R.id.h0t, R.id.h1t, R.id.h2t, R.id.h3t, R.id.h4t)
        val hI = intArrayOf(R.id.h0i, R.id.h1i, R.id.h2i, R.id.h3i, R.id.h4i)
        val hV = intArrayOf(R.id.h0v, R.id.h1v, R.id.h2v, R.id.h3v, R.id.h4v)
        val hP = intArrayOf(R.id.h0p, R.id.h1p, R.id.h2p, R.id.h3p, R.id.h4p)
        val hc = H.getJSONArray("weather_code"); val ht = H.getJSONArray("temperature_2m"); val hd = H.getJSONArray("is_day"); val hp = H.getJSONArray("precipitation_probability")
        for (k in 0 until 5) {
            val i = minOf(i0 + k, time.length() - 1)
            rv.setTextViewText(hT[k], if (k == 0) "Now" else hr12(time.getString(i)))
            rv.setImageViewResource(hI[k], icon(hc.optInt(i), hd.optInt(i, 1) == 1))
            rv.setTextViewText(hV[k], t(ht.optDouble(i)))
            val pc = hp.optInt(i); rv.setTextViewText(hP[k], if (pc >= 10) "$pc%" else " ")
        }
        if (layout != R.layout.widget_large) return rv

        val dN = intArrayOf(R.id.d0n, R.id.d1n, R.id.d2n, R.id.d3n, R.id.d4n)
        val dI = intArrayOf(R.id.d0i, R.id.d1i, R.id.d2i, R.id.d3i, R.id.d4i)
        val dP = intArrayOf(R.id.d0p, R.id.d1p, R.id.d2p, R.id.d3p, R.id.d4p)
        val dL = intArrayOf(R.id.d0l, R.id.d1l, R.id.d2l, R.id.d3l, R.id.d4l)
        val dH = intArrayOf(R.id.d0h, R.id.d1h, R.id.d2h, R.id.d3h, R.id.d4h)
        val dt = D.getJSONArray("time"); val dc = D.getJSONArray("weather_code"); val dp = D.getJSONArray("precipitation_probability_max")
        val today = nowLocal.take(10); var d0 = 0
        for (k in 0 until dt.length()) if (dt.getString(k) < today) d0 = k + 1
        for (kk in 0 until 5) {
            val k = d0 + kk
            if (k >= dt.length()) { rv.setTextViewText(dN[kk], " "); continue }
            val date = LocalDate.parse(dt.getString(k))
            rv.setTextViewText(dN[kk], if (kk == 0) "Today" else date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() })
            rv.setImageViewResource(dI[kk], icon(dc.optInt(k), true))
            val pc = dp.optInt(k); rv.setTextViewText(dP[kk], if (pc >= 10) "$pc%" else " ")
            rv.setTextViewText(dL[kk], t(lo.optDouble(k)))
            rv.setTextViewText(dH[kk], t(hi.optDouble(k)))
        }
        return rv
    }
}

open class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try { Wx.schedule(c); Wx.refresh(c) } catch (_: Exception) {} finally { pending.finish() }
        }.start()
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, options: Bundle) {
        Wx.renderAll(c)
    }
}

class SmallWidget : BaseWidget()
class MediumWidget : BaseWidget()
class LargeWidget : BaseWidget()

/** Fired by the 30-minute alarm. */
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        Thread { try { Wx.refresh(c, force = true) } catch (_: Exception) {} finally { pending.finish() } }.start()
    }
}
