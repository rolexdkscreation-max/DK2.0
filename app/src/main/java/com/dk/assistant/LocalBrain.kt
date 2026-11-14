package com.dk.assistant

import android.content.Context
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

    /*
     * 14 नवंबर 2026 review: पहले यहाँ "dk_learned.json" था - वही नाम [LearnedWays] (दूसरे AI से सीखे तरीके) का भी!
     * दोनों एक-दूसरे की फ़ाइल मिटाते थे (चैट से एक जवाब सीखा = सारे सीखे तरीके गायब, और उल्टा). अब अलग फ़ाइल;
     * पुरानी फ़ाइल में बची सवाल-जवाब वाली बातें पहली बार अपने-आप यहाँ आ जाती हैं ([DkMind.oldEntries]).
     */
    private const val FILE = "dk_mind.json"
    private const val OLD_FILE = "dk_learned.json"

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

    // ---------------------------------------------------------------- roz seekhna = DK का अपना दिमाग ([DkMind])
    /*
     * 14 नवंबर 2026 (मालिक): पहले यहाँ सिर्फ़ 400 बातें, 30 दिन बाद बेकार, बैकअप में नहीं, और Live की बातचीत से कुछ नहीं सीखता था.
     * अब: 20,000 तक (सिर्फ़ इसी फ़ोन में), कभी अपने-आप बेकार नहीं (key न चले तब), बैकअप/रिस्टोर में साथ,
     * DK चैट + DK लाइव दोनों से रोज़ सीखता है. ऐप में कहीं दिखता नहीं - बस काम करता है.
     */
    @Volatile private var cache: List<DkMind.Entry>? = null
    @Volatile private var cacheOf = ""          // किस फ़ाइल का cache (नया install / test = नई फ़ाइल)

    @Synchronized fun all(c: Context): List<DkMind.Entry> {
        val f = File(c.filesDir, FILE)
        cache?.takeIf { cacheOf == f.path && (it.isEmpty() || f.exists()) }?.let { return it }   // फ़ाइल मिटी (ऐप का डेटा साफ़) = फिर से पढ़ो
        return (try {
            if (f.exists()) DkMind.fromJson(f.readText())
            else File(c.filesDir, OLD_FILE).takeIf { it.exists() }?.let { DkMind.oldEntries(it.readText()) }.orEmpty()
        } catch (_: Exception) { emptyList() })
            .also { cache = it; cacheOf = f.path }
    }

    @Synchronized private fun save(c: Context, list: List<DkMind.Entry>) {
        cache = list; cacheOf = File(c.filesDir, FILE).path
        try {
            val f = File(c.filesDir, FILE); val tmp = File(c.filesDir, "$FILE.tmp")
            tmp.writeText(DkMind.toJson(list)); if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {}
    }

    /** अच्छा जवाब मिला (AI से, बिना खोज / बिना फ़ोन के काम) -> सीख लो. अगली बार key न चले तब भी यही */
    fun learn(c: Context, question: String, answer: String) {
        val q = norm(question); val a = answer.trim()
        if (!DkMind.teachable(q, a)) return
        synchronized(this) { save(c, DkMind.add(all(c), q, a, System.currentTimeMillis())) }
    }

    /**
     * exact = API चलती है: सिर्फ़ बिल्कुल वही, ताज़ा, न बदलने वाला सवाल (तब API से पहले अपना दिमाग).
     * fuzzy = key / AI नहीं चली: मिलता-जुलता भी (अंक / "नहीं" अलग हों तो कभी नहीं). बदलने वाली बात = कभी नहीं.
     */
    fun recall(c: Context, question: String, exact: Boolean): String? {
        val q = norm(question)
        return DkMind.match(all(c), q, exact, System.currentTimeMillis())?.a
    }

    /** बैकअप के लिए (सिर्फ़ याददाश्त - कोई key नहीं) */
    fun exportJson(c: Context): String = DkMind.toJson(all(c))

    /** रिस्टोर: फ़ोन वाली + बैकअप वाली जोड़ो (कुछ नहीं मिटता). वापस = कुल कितनी बातें */
    fun importJson(c: Context, json: String): Int = synchronized(this) {
        val m = DkMind.merge(all(c), DkMind.fromJson(json)); save(c, m); m.size
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
