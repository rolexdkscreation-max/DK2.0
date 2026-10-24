package com.dk.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DK ka apna dimaag (bina internet, bina AI = ek pal me):
 *  - samay, tareekh, din, battery, naam, namaste, chhota hisaab
 *  - roz ki baatcheet se seekha hua: jo sawaal pehle poocha tha uska jawab yaad (dobara poochho to turant)
 */
object LocalBrain {

    private const val FILE = "dk_learned.json"
    private const val MAX = 400

    // ---------------------------------------------------------------- turant jawab
    fun instant(c: Context, raw: String): String? {
        val t = norm(raw)
        if (t.isBlank()) return null
        val words = t.split(' ').filter { it.isNotBlank() }
        val user = Prefs.userName(c)
        val now = Date()

        fun has(vararg w: String) = w.any { t.contains(it) }

        // samay
        if (has("टाइम क्या", "टाइम कितना", "कितने बजे", "क्या टाइम", "समय क्या", "समय कितना", "क्या समय", "time kya", "what time", "kitne baje")) {
            val h = SimpleDateFormat("h:mm", Locale.ENGLISH).format(now)
            val hr = SimpleDateFormat("H", Locale.ENGLISH).format(now).toInt()
            val part = when (hr) { in 4..11 -> "सुबह"; in 12..15 -> "दोपहर"; in 16..19 -> "शाम"; else -> "रात" }
            return "अभी $part के $h बजे हैं।"
        }
        // din / tareekh
        if (has("आज कौन सा दिन", "आज कौनसा दिन", "आज क्या दिन", "कौन सा वार", "आज क्या वार", "what day")) {
            return "आज ${dayHi(now)} है।"
        }
        if (has("आज की तारीख", "आज तारीख", "आज क्या तारीख", "कौन सी तारीख", "कितनी तारीख", "today date", "आज डेट", "date kya")) {
            return "आज ${SimpleDateFormat("d", Locale.ENGLISH).format(now)} ${monthHi(now)} ${SimpleDateFormat("yyyy", Locale.ENGLISH).format(now)}, ${dayHi(now)} है।"
        }
        // battery
        if (has("बैटरी कितनी", "बैटरी कितना", "कितनी बैटरी", "battery kitni", "बैटरी कितने")) {
            val bm = c.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val p = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (p in 0..100) return "बैटरी $p प्रतिशत है।"
        }
        // naam
        if (has("तुम्हारा नाम क्या", "तुम्हारा नाम कया", "आपका नाम क्या", "तेरा नाम क्या", "your name")) {
            return "मेरा नाम ${Prefs.assistantName(c)} है।"
        }
        if (user.isNotBlank() && has("मेरा नाम क्या", "मेरा नाम बताओ", "मैं कौन हूँ", "मैं कौन हूं")) return "आपका नाम $user है।"

        // sirf namaste / kaise ho
        if (words.size <= 3) {
            val greet = listOf("नमस्ते", "नमस्कार", "हेलो", "हैलो", "हाय", "hello", "hi", "राम राम", "प्रणाम", "सलाम")
            val how = listOf("कैसे हो", "कैसे हैं", "कैसा है", "क्या हाल", "how are you", "kaise ho")
            val n = if (user.isNotBlank()) " $user" else ""
            if (how.any { t.contains(it) }) return listOf("मैं बढ़िया हूँ$n, आप बताइए?", "एकदम ठीक हूँ$n, आप सुनाइए?").random()
            if (greet.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") }) return TalkStyle.greetReplies(n).random()   // v3.51: "क्या करूँ?" वाला दफ़्तरी सवाल नहीं
        }
        // chhota hisaab: "25 गुणा 4", "100 + 50"
        calc(raw.lowercase(Locale.ROOT).replace(Regex("[?।,!]"), " ").trim())?.let { return it }
        return null
    }

    private fun calc(t: String): String? {
        val s = t.replace("गुणा", "*").replace("गुना", "*").replace("x", "*").replace("×", "*").replace("प्लस", "+").replace("जोड़", "+")
            .replace("माइनस", "-").replace("घटा", "-").replace("भाग", "/").replace("डिवाइड", "/").replace("÷", "/")
        val m = Regex("(-?\\d+(?:\\.\\d+)?)\\s*([+*/-])\\s*(-?\\d+(?:\\.\\d+)?)").find(s) ?: return null
        // sirf hisaab wala sawaal (lambi baat nahi, "2-3 दिन" jaisa nahi)
        if (s.length - m.value.length > 22) return null
        val cue = listOf("कितना", "कितने", "कितनी", "क्या होगा", "क्या होता", "होता है", "बराबर", "=", "बताओ", "how much", "kitna")
        if (s.trim() != m.value.trim() && cue.none { s.contains(it) }) return null
        val a = m.groupValues[1].toDouble(); val b = m.groupValues[3].toDouble()
        val r = when (m.groupValues[2]) { "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> if (b == 0.0) return "शून्य से भाग नहीं हो सकता।" else a / b; else -> return null }
        val out = if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString() else String.format(Locale.ENGLISH, "%.2f", r)
        return "$out।"
    }

    private fun dayHi(d: Date) = when (SimpleDateFormat("u", Locale.ENGLISH).format(d)) {
        "1" -> "सोमवार"; "2" -> "मंगलवार"; "3" -> "बुधवार"; "4" -> "गुरुवार"; "5" -> "शुक्रवार"; "6" -> "शनिवार"; else -> "रविवार"
    }
    private fun monthHi(d: Date) = listOf("जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर")[
        SimpleDateFormat("M", Locale.ENGLISH).format(d).toInt() - 1]

    // ---------------------------------------------------------------- roz seekhna
    /** Woh sawaal jinka jawab badalta rehta hai ya pichhli baat par tika hai - inhe yaad mat karo */
    private fun learnable(q: String): Boolean {
        val w = q.split(' ').filter { it.isNotBlank() }
        if (w.size < 3) return false
        val ctx = listOf("ये", "यह", "वो", "वह", "इसका", "उसका", "इसके", "उसके", "इसे", "उसे", "और बताओ", "फिर", "पहले वाला", "ऊपर वाला")
        val time = listOf("आज", "अभी", "कल", "परसों", "इस हफ्ते", "मौसम", "भाव", "रेट", "न्यूज", "न्यूज़", "खबर", "स्कोर", "मैच", "टाइम", "समय", "बजे", "तारीख")
        return ctx.none { if (it.contains(' ')) q.contains(it) else w.contains(it) } && time.none { q.contains(it) }
    }

    @Synchronized private fun load(c: Context): JSONArray = try {
        val f = File(c.filesDir, FILE); if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (_: Exception) { JSONArray() }

    @Synchronized private fun save(c: Context, a: JSONArray) { try { File(c.filesDir, FILE).writeText(a.toString()) } catch (_: Exception) {} }

    /** AI ka achha jawab mila -> seekh lo (agli baar turant, internet na ho tab bhi) */
    fun learn(c: Context, question: String, answer: String) {
        val q = norm(question)
        if (!learnable(q) || answer.length < 4 || answer.length > 900) return
        val a = load(c)
        val out = JSONArray()
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (o.optString("q") != q) out.put(o) }
        out.put(JSONObject().put("q", q).put("a", answer).put("t", System.currentTimeMillis()))
        while (out.length() > MAX) out.remove(0)
        save(c, out)
    }

    /** exact = bilkul wahi sawaal (turant jawab). fuzzy = milta-julta (sirf jab AI na chale). */
    fun recall(c: Context, question: String, exact: Boolean): String? {
        val q = norm(question)
        if (!learnable(q)) return null
        val a = load(c)
        var best: String? = null; var bestScore = 0.0
        val qs = q.split(' ').filter { it.length > 1 }.toSet()
        for (i in a.length() - 1 downTo 0) {
            val o = a.optJSONObject(i) ?: continue
            val k = o.optString("q")
            // 30 din se purana jawab = dobara AI se pucho
            if (System.currentTimeMillis() - o.optLong("t") > 30L * 86_400_000L) continue
            if (k == q) return o.optString("a")
            if (!exact) {
                val ks = k.split(' ').filter { it.length > 1 }.toSet()
                val inter = qs.intersect(ks).size.toDouble(); val uni = (qs + ks).size.toDouble()
                val s = if (uni == 0.0) 0.0 else inter / uni
                if (s > bestScore) { bestScore = s; best = o.optString("a") }
            }
        }
        return if (!exact && bestScore >= 0.7) best else null
    }

    fun norm(s: String): String {
        var t = s.lowercase(Locale.ROOT)
            .replace(Regex("[\\p{Punct}।॥“”‘’…]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        for (w in listOf("डीके", "दीके", "डी के", "dk", "d k", "प्लीज़", "प्लीज", "please", "जरा", "ज़रा", "भाई")) {
            t = t.replace(Regex("(^|\\s)" + Regex.escape(w) + "(\\s|$)"), " ").trim()
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
