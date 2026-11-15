package com.dk.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 15 नवंबर 2026 (मालिक): "सेटिंग में चेहरा पहचानने वाला - फ़ेस रजिस्ट्रेशन - वह बनाना है, एग्ज़ैक्ट पहचानना चाहिए."
 *
 * मालिक (Boss) का चेहरा पहले से [FaceAI] में (owner_face_v2, encrypted). यहाँ बाकी जान-पहचान वाले लोग:
 * नाम + 128 अंकों की पहचान (SFace embedding) - तस्वीर कभी सेव नहीं होती. पूरी सूची एक ही राज़ वाली setting
 * ([KEY], [SecretNames] में) - AndroidKeyStore के ताले में.
 *
 * पहचान ([identify]): सबसे ज़्यादा मिलता चेहरा, और वह भी तभी जब मिलान [FaceMath.MATCH] (सख़्त = STRICT) से ऊपर हो
 * और दूसरे नंबर वाले से साफ़ आगे ([MARGIN]) - दो लोग लगभग बराबर मिलें तो "पक्का नहीं" (ग़लत नाम बोलने से अच्छा).
 * Pure हिस्से ([encode]/[decode]/[identify]) JVM test में.
 */
object FacePeople {
    const val KEY = "face_people_v1"
    const val MAX = 20
    const val BOSS = "__boss__"
    /** पहले और दूसरे नंबर के बीच कम से कम इतना फ़र्क - वरना "पक्का नहीं" */
    const val MARGIN = 0.06f

    /**
     * 15 नवंबर 2026 (मालिक: "फ़्रेंड है या फ़ैमिली मेंबर, और फ़ैमिली को क्या बोलते हैं"): [kind] = [FRIEND] / [FAMILY] / [OTHER]
     * (पुराने सेव में ख़ाली), [rel] = रिश्ता - जैसे "भाई", "मम्मी", "ऑफ़िस वाले". पुराना 2 अंकों वाला रूप वैसा ही चलता है.
     */
    data class Person(val name: String, val print: FloatArray, val kind: String = "", val rel: String = "") {
        /** "दोस्त" / "भाई" / "परिवार" ... (ख़ाली = कुछ नहीं बताया) */
        fun relation(): String = relationOf(kind, rel)
    }

    const val FRIEND = "friend"
    const val FAMILY = "family"
    const val OTHER = "other"
    /** परिवार में बोले जाने वाले रिश्ते (Settings में चुनने के लिए) */
    val FAMILY_RELS = listOf("मम्मी", "पापा", "भाई", "बहन", "पत्नी", "पति", "बेटा", "बेटी", "दादा", "दादी", "नाना", "नानी",
        "चाचा", "चाची", "मामा", "मामी", "बुआ", "मौसी", "भाभी", "जीजा", "भतीजा", "भतीजी", "चचेरा भाई", "चचेरी बहन")

    fun relationOf(kind: String, rel: String): String = rel.trim().ifBlank {
        when (kind) { FRIEND -> "दोस्त"; FAMILY -> "परिवार"; else -> "" }
    }

    /** "आपके दोस्त" / "आपके भाई" / "आपकी मम्मी" - Live में बोलने लायक */
    fun relationPhrase(kind: String, rel: String): String {
        val r = relationOf(kind, rel); if (r.isBlank()) return ""
        val her = setOf("मम्मी", "बहन", "पत्नी", "बेटी", "दादी", "नानी", "चाची", "मामी", "बुआ", "मौसी", "भाभी", "भतीजी", "चचेरी बहन", "दोस्त (लड़की)")
        return if (r in her) "आपकी $r" else if (kind == FRIEND || kind == FAMILY) "आपके $r" else r
    }

    data class Who(val name: String?, val boss: Boolean, val score: Float, val sure: Boolean, val kind: String = "", val rel: String = "") {
        fun relation(): String = relationOf(kind, rel)
        private fun tail() = relationPhrase(kind, rel).let { if (it.isBlank()) "" else " — $it" }
        /** Live / स्क्रीन पर बोलने लायक */
        fun say(userName: String): String = when {
            boss -> "यह आप हैं — ${NameRule.call(userName).ifBlank { "मालिक" }} (मिलान ${(score * 100).toInt()}%)"
            name != null && sure -> "यह $name हैं${tail()} (मिलान ${(score * 100).toInt()}%)"
            name != null -> "शायद $name${tail()} — पक्का नहीं (मिलान ${(score * 100).toInt()}%)"
            else -> "अनजान चेहरा — किसी सेव किए व्यक्ति से नहीं मिला"
        }
    }

    // ------------------------------------------------------------------ pure

    fun encode(list: List<Person>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().put("n", p.name).put("v", JSONArray().apply { p.print.forEach { put(it.toDouble()) } }).apply {
                if (p.kind.isNotBlank()) put("k", p.kind); if (p.rel.isNotBlank()) put("r", p.rel)
            })
        }
    }.toString()

    fun decode(raw: String?): List<Person> = try {
        if (raw.isNullOrBlank()) emptyList() else {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("n").trim(); val v = o.optJSONArray("v") ?: return@mapNotNull null
                if (n.isBlank() || v.length() == 0) null else Person(n, FloatArray(v.length()) { v.optDouble(it).toFloat() }, o.optString("k").trim(), o.optString("r").trim().take(30))
            }
        }
    } catch (_: Exception) { emptyList() }

    /** नाम साफ़ (ख़ाली / बहुत लंबा = null) */
    fun cleanName(n: String): String? = n.trim().replace(Regex("\\s+"), " ").take(30).takeIf { it.length >= 1 && it != BOSS }

    /** नया जोड़ो या उसी नाम वाले को बदलो (सबसे ज़्यादा [MAX]) */
    fun upsert(list: List<Person>, p: Person): List<Person> =
        (list.filterNot { it.name.equals(p.name, ignoreCase = true) } + p).takeLast(MAX)

    /**
     * कौन है: boss = मालिक की पहचान (null = सेट नहीं). threshold = पास होने का मिलान.
     * सबसे अच्छा मिलान threshold से नीचे = अनजान.
     */
    fun identify(e: FloatArray, boss: FloatArray?, people: List<Person>, threshold: Float): Who {
        val all = buildList {
            if (boss != null) add(BOSS to FaceMatch.cosineSimilarity(boss, e))
            people.forEach { add(it.name to FaceMatch.cosineSimilarity(it.print, e)) }
        }.sortedByDescending { it.second }
        val best = all.firstOrNull() ?: return Who(null, false, 0f, false)
        if (best.second < threshold) return Who(null, false, best.second, false)
        val second = all.getOrNull(1)?.second ?: -1f
        val sure = best.second - second >= MARGIN
        if (best.first == BOSS) return Who(null, true, best.second, sure)
        val p = people.firstOrNull { it.name == best.first }
        return Who(best.first, false, best.second, sure, p?.kind ?: "", p?.rel ?: "")
    }

    /**
     * 15 नवंबर 2026 (मालिक: "एक साथ दो-चार आदमी भी पहचाने"): एक फ़्रेम के कई चेहरे. हर चेहरे का [identify], फिर एक ही
     * व्यक्ति दो चेहरों पर न लगे - ज़्यादा मिलान वाला रखो, बाकी "अनजान" (दो लोगों को एक ही नाम बोलना ग़लत).
     */
    fun assign(prints: List<FloatArray?>, boss: FloatArray?, people: List<Person>, threshold: Float): List<Who> {
        val raw = prints.map { e -> if (e == null) Who(null, false, 0f, false) else identify(e, boss, people, threshold) }
        val key = raw.map { w -> if (w.boss) BOSS else w.name }
        return raw.mapIndexed { i, w ->
            val k = key[i] ?: return@mapIndexed w
            val bestIdx = raw.indices.filter { key[it] == k }.maxByOrNull { raw[it].score }
            if (bestIdx == i) w else Who(null, false, w.score, false)
        }
    }

    // ------------------------------------------------------------------ फ़ोन पर

    fun all(c: Context): List<Person> = decode(Prefs.str(c, KEY, ""))
    private fun store(c: Context, list: List<Person>) = Prefs.edit(c) { if (list.isEmpty()) remove(KEY) else putString(KEY, encode(list)) }
    fun add(c: Context, name: String, print: FloatArray, kind: String = "", rel: String = ""): Boolean {
        val n = cleanName(name) ?: return false
        store(c, upsert(all(c), Person(n, print, kind, rel.trim().take(30)))); return true
    }
    /** सिर्फ़ रिश्ता बदलो (चेहरा वही) */
    fun setRelation(c: Context, name: String, kind: String, rel: String) =
        store(c, all(c).map { if (it.name == name) it.copy(kind = kind, rel = rel.trim().take(30)) else it })
    fun remove(c: Context, name: String) = store(c, all(c).filterNot { it.name == name })
    fun clear(c: Context) = store(c, emptyList())

    fun threshold(c: Context): Float = if (Prefs.faceStrict(c)) FaceMath.STRICT else FaceMath.MATCH

    fun who(c: Context, e: FloatArray): Who =
        identify(e, if (FaceAI.enrolled(c)) Prefs.ownerFacePrint(c) else null, all(c), threshold(c))

    /** एक फ़्रेम के कई चेहरे ([assign]) */
    fun whoAll(c: Context, prints: List<FloatArray?>): List<Who> =
        assign(prints, if (FaceAI.enrolled(c)) Prefs.ownerFacePrint(c) else null, all(c), threshold(c))
}
