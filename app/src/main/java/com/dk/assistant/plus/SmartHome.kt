package com.dk.assistant.plus

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 15 नवंबर 2026 (मालिक, Side Drawer → DK Home): घर के उपकरण (लाइट, पंखा, AC, फ्रिज, पानी की टंकी का पंप) लोकल
 * webhook / relay (ESP8266 / Tasmota / Home Assistant / Sonoff ...) से चालू-बंद + बिजली बिल का अनुमान.
 * - हर उपकरण: नाम, वॉट, ON URL, OFF URL (GET या POST), रोज़ के अनुमानित घंटे
 * - DK से चालू/बंद करने पर असली चलने का समय जुड़ता है -> असली खपत (kWh) + महीने का अनुमान
 * - टैरिफ़ (₹/यूनिट) Settings में. कोई cloud / paid service नहीं - सिर्फ़ आपका अपना लोकल नेटवर्क.
 * Pure हिस्सा ([Bill]) JVM test में ([SmartHomeTest]).
 */
object SmartHome {
    const val PREF = "plus_home_items"
    const val TARIFF = "plus_home_tariff"
    const val DEFAULT_TARIFF = 8.0

    data class Item(
        val id: String, val name: String, val watts: Double, val onUrl: String, val offUrl: String,
        val post: Boolean = false, val hoursPerDay: Double = 0.0,
        val isOn: Boolean = false, val onSince: Long = 0L, val runMs: Long = 0L, val since: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("watts", watts).put("on_url", onUrl).put("off_url", offUrl)
            .put("post", post).put("hpd", hoursPerDay).put("is_on", isOn).put("on_since", onSince).put("run_ms", runMs).put("since", since)
        companion object {
            fun of(o: JSONObject) = Item(o.optString("id"), o.optString("name"), o.optDouble("watts", 0.0), o.optString("on_url"), o.optString("off_url"),
                o.optBoolean("post"), o.optDouble("hpd", 0.0), o.optBoolean("is_on"), o.optLong("on_since"), o.optLong("run_ms"), o.optLong("since"))
        }
    }

    object Bill {
        fun kwh(watts: Double, hours: Double): Double = (watts.coerceAtLeast(0.0) * hours.coerceAtLeast(0.0)) / 1000.0
        fun cost(kwh: Double, tariff: Double): Double = kwh * tariff.coerceAtLeast(0.0)

        /** अभी तक का असली चलना (चालू है तो अब तक का भी) */
        fun runHours(i: Item, now: Long): Double = (i.runMs + if (i.isOn && i.onSince > 0) (now - i.onSince).coerceAtLeast(0) else 0L) / 3_600_000.0

        /** महीने का अनुमान (30 दिन): कम से कम 1 दिन का असली रिकॉर्ड हो तो उसका रोज़ का औसत, वरना रोज़ के अनुमानित घंटे */
        fun monthlyKwh(i: Item, now: Long): Double {
            val days = if (i.since > 0) (now - i.since) / 86_400_000.0 else 0.0
            val perDayHours = if (days >= 1.0) runHours(i, now) / days else i.hoursPerDay
            return kwh(i.watts, perDayHours * 30.0)
        }

        fun monthlyCost(items: List<Item>, tariff: Double, now: Long): Double = items.sumOf { cost(monthlyKwh(it, now), tariff) }

        /** चालू/बंद होने पर समय का हिसाब */
        fun toggled(i: Item, on: Boolean, now: Long): Item = when {
            on && !i.isOn -> i.copy(isOn = true, onSince = now, since = if (i.since == 0L) now else i.since)
            !on && i.isOn -> i.copy(isOn = false, runMs = i.runMs + (now - i.onSince).coerceAtLeast(0), onSince = 0L)
            else -> i
        }

        /** बोलकर: "पंखा" -> नाम से उपकरण (पूरा नाम या शुरुआत) */
        fun find(items: List<Item>, said: String): Item? {
            val s = said.trim().lowercase()
            return items.firstOrNull { it.name.lowercase() == s } ?: items.firstOrNull { s.contains(it.name.lowercase()) }
        }
    }

    fun load(c: Context): List<Item> = try {
        val a = JSONArray(FeatureFlags.str(c, PREF, "[]")); (0 until a.length()).map { Item.of(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    fun save(c: Context, list: List<Item>) { val a = JSONArray(); list.forEach { a.put(it.toJson()) }; FeatureFlags.putStr(c, PREF, a.toString()) }

    fun tariff(c: Context): Double = FeatureFlags.str(c, TARIFF, DEFAULT_TARIFF.toString()).toDoubleOrNull() ?: DEFAULT_TARIFF

    /** webhook भेजो. null = हो गया, वरना वजह */
    suspend fun switch(c: Context, id: String, on: Boolean): String? = withContext(Dispatchers.IO) {
        if (!FeatureFlags.on(c, FeatureFlags.SMART_HOME)) return@withContext "DK Home बंद है। सेटिंग्स → DK AI Plus में चालू करें।"
        val list = load(c); val i = list.firstOrNull { it.id == id } ?: return@withContext "उपकरण नहीं मिला"
        val url = (if (on) i.onUrl else i.offUrl).trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return@withContext "${i.name} का ${if (on) "ON" else "OFF"} URL नहीं डाला"
        try {
            val body = JSONObject().put("state", if (on) "on" else "off").toString()
            if (url.startsWith("http://")) {
                // घर का relay (192.168.x.x) - सिर्फ़ लोकल नेटवर्क ([LanHttp])
                val code = LanHttp.send(url, i.post, body)
                if (code !in 200..399) return@withContext "${i.name}: HTTP $code"
            } else {
                val http = com.dk.assistant.NetFix.builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
                val rb = Request.Builder().url(url)
                if (i.post) rb.post(body.toRequestBody("application/json".toMediaType()))
                http.newCall(rb.build()).execute().use { r -> if (!r.isSuccessful) return@withContext "${i.name}: HTTP ${r.code}" }
            }
        } catch (e: Exception) { return@withContext "${i.name} तक नहीं पहुँचा (${e.message})" }
        save(c, list.map { if (it.id == id) Bill.toggled(it, on, System.currentTimeMillis()) else it })
        null
    }
}
