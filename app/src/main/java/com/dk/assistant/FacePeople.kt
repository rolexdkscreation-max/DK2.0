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

    data class Person(val name: String, val print: FloatArray)

    data class Who(val name: String?, val boss: Boolean, val score: Float, val sure: Boolean) {
        /** Live / स्क्रीन पर बोलने लायक */
        fun say(userName: String): String = when {
            boss -> "यह आप हैं — ${NameRule.call(userName).ifBlank { "मालिक" }} (मिलान ${(score * 100).toInt()}%)"
            name != null && sure -> "यह $name हैं (मिलान ${(score * 100).toInt()}%)"
            name != null -> "शायद $name — पक्का नहीं (मिलान ${(score * 100).toInt()}%)"
            else -> "अनजान चेहरा — किसी सेव किए व्यक्ति से नहीं मिला"
        }
    }

    // ------------------------------------------------------------------ pure

    fun encode(list: List<Person>): String = JSONArray().apply {
        list.forEach { p -> put(JSONObject().put("n", p.name).put("v", JSONArray().apply { p.print.forEach { put(it.toDouble()) } })) }
    }.toString()

    fun decode(raw: String?): List<Person> = try {
        if (raw.isNullOrBlank()) emptyList() else {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("n").trim(); val v = o.optJSONArray("v") ?: return@mapNotNull null
                if (n.isBlank() || v.length() == 0) null else Person(n, FloatArray(v.length()) { v.optDouble(it).toFloat() })
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
        return if (best.first == BOSS) Who(null, true, best.second, sure) else Who(best.first, false, best.second, sure)
    }

    // ------------------------------------------------------------------ फ़ोन पर

    fun all(c: Context): List<Person> = decode(Prefs.str(c, KEY, ""))
    private fun store(c: Context, list: List<Person>) = Prefs.edit(c) { if (list.isEmpty()) remove(KEY) else putString(KEY, encode(list)) }
    fun add(c: Context, name: String, print: FloatArray): Boolean {
        val n = cleanName(name) ?: return false
        store(c, upsert(all(c), Person(n, print))); return true
    }
    fun remove(c: Context, name: String) = store(c, all(c).filterNot { it.name == name })
    fun clear(c: Context) = store(c, emptyList())

    fun threshold(c: Context): Float = if (Prefs.faceStrict(c)) FaceMath.STRICT else FaceMath.MATCH

    fun who(c: Context, e: FloatArray): Who =
        identify(e, if (FaceAI.enrolled(c)) Prefs.ownerFacePrint(c) else null, all(c), threshold(c))
}
