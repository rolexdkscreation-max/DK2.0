package com.dk.assistant.plus

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * 15 नवंबर 2026 (मालिक की सूची): WhatsApp ग्रुप रिपोर्ट.
 * बार-बार भेजी जाने वाली रिपोर्ट का टेम्पलेट: नाम + WhatsApp ग्रुप का नाम + रूप, जिसमें {खाने} हों - जैसे
 *   "आज की बिक्री {तारीख}\nकुल: {कुल} रुपये\nनकद: {नकद}"
 * बोलकर (या DK लाइव कैमरे से पढ़कर) खाने भरो -> यूज़र को सुनाओ -> "हाँ" पर ग्रुप में भेजो.
 * [Rules] = pure logic (JVM test). भेजना = WhatsApp share -> ग्रुप चुनो -> Send (Accessibility, [A11yRetry]).
 */
object GroupReport {
    const val KEY = "wa_report_templates_v1"
    const val MAX = 15

    data class Template(val name: String, val group: String, val format: String)

    object Rules {
        private val FIELD = Regex("\\{([^{}\\n]{1,30})\\}")

        /** रूप में कौन-कौन से खाने हैं (क्रम से, एक बार) */
        fun fields(format: String): List<String> = FIELD.findAll(format).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

        private fun norm(s: String) = s.lowercase().replace("़", "").replace(Regex("\\s+"), " ").trim()

        /** खाने भरो. @return (भरा पाठ, जो खाने खाली रहे). {तारीख}/{date} अपने-आप आज की तारीख */
        fun fill(format: String, values: Map<String, String>, today: String): Pair<String, List<String>> {
            val v = values.mapKeys { norm(it.key) }
            val missing = ArrayList<String>()
            val out = FIELD.replace(format) { m ->
                val k = m.groupValues[1].trim()
                v[norm(k)]?.takeIf { it.isNotBlank() }?.trim()
                    ?: (if (norm(k) in setOf("तारीख", "date", "दिनांक", "आज")) today else null)
                    ?: run { missing.add(k); m.value }
            }
            return out to missing.distinct()
        }

        /** "कुल: 500, नकद = 200" या JSON {"कुल":"500"} -> map */
        fun parseValues(raw: String): Map<String, String> {
            val t = raw.trim(); if (t.isEmpty()) return emptyMap()
            if (t.startsWith("{")) return try {
                val o = JSONObject(t); o.keys().asSequence().associateWith { o.optString(it) }
            } catch (_: Exception) { emptyMap() }
            return t.split(Regex("[,;\\n]")).mapNotNull { p ->
                val i = p.indexOfFirst { it == ':' || it == '=' }
                if (i <= 0) null else p.substring(0, i).trim() to p.substring(i + 1).trim()
            }.filter { it.first.isNotEmpty() }.toMap()
        }

        fun encode(list: List<Template>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("n", it.name).put("g", it.group).put("f", it.format)) }
        }.toString()

        fun decode(raw: String?): List<Template> = try {
            val a = JSONArray(raw ?: "")
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Template(o.optString("n").trim(), o.optString("g").trim(), o.optString("f")).takeIf { it.name.isNotEmpty() && it.group.isNotEmpty() && it.format.isNotBlank() }
            }
        } catch (_: Exception) { emptyList() }

        /** उसी नाम का टेम्पलेट बदलो, नहीं तो जोड़ो (ज़्यादा से ज़्यादा [MAX]) */
        fun upsert(list: List<Template>, t: Template): List<Template> =
            (list.filterNot { norm(it.name) == norm(t.name) } + t).takeLast(MAX)

        /** बोले गए नाम से टेम्पलेट (पूरा नाम, फिर हिस्सा) */
        fun find(list: List<Template>, q: String): Template? {
            val n = norm(q); if (n.isEmpty()) return list.singleOrNull()
            return list.firstOrNull { norm(it.name) == n } ?: list.firstOrNull { norm(it.name).contains(n) || n.contains(norm(it.name)) }
                ?: list.firstOrNull { norm(it.group).contains(n) }
        }

        fun catalog(list: List<Template>): String = if (list.isEmpty()) "NO_TEMPLATES: the user has not saved any group report yet - tell him to add one in Settings → ऑटो रिप्लाई → WhatsApp ग्रुप रिपोर्ट."
        else "SAVED GROUP REPORTS:\n" + list.joinToString("\n") { "- '${it.name}' → group '${it.group}', fields: ${fields(it.format).joinToString().ifEmpty { "(none)" }}" }
    }

    // ------------------------------------------------------------------ फ़ोन पर
    fun all(c: Context) = Rules.decode(Prefs.str(c, KEY, ""))
    fun save(c: Context, t: Template) = Prefs.edit(c) { putString(KEY, Rules.encode(Rules.upsert(all(c), t))) }
    fun remove(c: Context, name: String) = Prefs.edit(c) { putString(KEY, Rules.encode(all(c).filterNot { it.name == name })) }
    fun today(): String = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US).format(java.util.Date())

    private fun waPkg(c: Context): String? = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
        try { c.packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
    }

    /** WhatsApp में ग्रुप चुनकर पाठ भेजो. @return null = भेज दिया, नहीं तो कारण */
    suspend fun send(svc: DKAccessibilityService, group: String, text: String): String? {
        val pkg = waPkg(svc) ?: return "WhatsApp इस फ़ोन में नहीं है"
        try {
            svc.startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (e: Exception) { return "WhatsApp नहीं खुला (${e.message})" }
        delay(1_800)
        // 1) सूची में ग्रुप दिखे तो वहीं से; नहीं तो खोज में नाम लिखकर
        if (!A11yRetry.click(svc, group).ok) {
            A11yRetry.click(svc, "Search", viewId = "$pkg:id/menuitem_search")
            delay(700)
            if (!typeInto(svc, group)) return "WhatsApp की खोज में ग्रुप का नाम नहीं लिख पाई"
            delay(1_200)
            if (!A11yRetry.click(svc, group).ok) return "WhatsApp में '$group' ग्रुप नहीं मिला - नाम ठीक वैसा लिखें जैसा WhatsApp में है"
        }
        delay(600)
        // 2) Send (कई बार WhatsApp पहले चैट खोलकर पाठ बॉक्स में रखता है - तब फिर से Send)
        if (!A11yRetry.click(svc, "Send", viewId = "$pkg:id/send").ok) return "Send बटन नहीं मिला"
        delay(1_500)
        val entry = svc.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("$pkg:id/entry")?.firstOrNull()
        if (entry != null && !entry.text.isNullOrBlank()) {
            if (!A11yRetry.click(svc, "Send", viewId = "$pkg:id/send").ok) return "पाठ चैट में है पर Send नहीं दब पाया"
        }
        DKLog.d("ग्रुप रिपोर्ट भेजी: $group")
        return null
    }

    private fun typeInto(svc: DKAccessibilityService, t: String): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>(); svc.rootInActiveWindow?.let { q.add(it) }; var seen = 0
        while (q.isNotEmpty() && seen < 600) {
            val n = q.removeFirst(); seen++
            if (n.isEditable) {
                val b = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
                return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return false
    }
}
