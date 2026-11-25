package com.dk.assistant

import org.json.JSONArray

/**
 * 25 नवंबर 2026: [UiMt] का internet वाला अनुवाद - pure हिस्से (JVM test): भाषा का code, जवाब पढ़ना, लाइनें बाँटना.
 * APK में कोई अनुवाद SDK नहीं (मालिक का नियम: भारी चीज़ internet से).
 */
object WebTr {
    /** URL में q का अंदाज़न नाप (देवनागरी = 9 अक्षर प्रति अक्षर) - इससे छोटे हिस्से */
    const val MAX_Q = 1_400

    /** Java के पुराने code -> Google वाले */
    fun code(c: String): String = when (c) {
        "zh" -> "zh-CN"; "he" -> "iw"; "jv" -> "jw"; "nb", "nn" -> "no"
        else -> c
    }

    private fun cost(s: String): Int = s.fold(3) { n, ch -> n + if (ch.code < 128) 3 else 9 }

    /** लाइनों को हिस्सों में (हर हिस्से के index), हर हिस्सा [MAX_Q] से छोटा (एक लंबी लाइन अकेली) */
    fun parts(texts: List<String>): List<List<Int>> {
        val out = ArrayList<List<Int>>(); var cur = ArrayList<Int>(); var n = 0
        texts.forEachIndexed { i, t ->
            val c = cost(t)
            if (cur.isNotEmpty() && n + c > MAX_Q) { out.add(cur); cur = ArrayList(); n = 0 }
            cur.add(i); n += c
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** [[["Hello","नमस्ते",...],["\nBye","\nअलविदा",...]],...] -> "Hello\nBye" */
    fun parse(body: String): String {
        val a = try { JSONArray(body).optJSONArray(0) } catch (_: Exception) { null } ?: return ""
        val sb = StringBuilder()
        for (i in 0 until a.length()) a.optJSONArray(i)?.optString(0)?.let { if (it != "null") sb.append(it) }
        return sb.toString()
    }

    /** जुड़ा अनुवाद -> उतनी ही लाइनें (गिनती न मिले = null) */
    fun split(joined: String, n: Int): List<String>? {
        val l = joined.split('\n').map { it.trim() }
        return if (l.size == n && l.none { it.isEmpty() }) l else null
    }
}
