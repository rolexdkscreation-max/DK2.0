package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * "आगे करो" के लिए टीम की याद: काम कहाँ तक पहुँचा.
 * जो हिस्सा पूरा हो चुका (branch, प्लान के कदम, कोड, पास हुए tests) वह दोबारा नहीं चलता -
 * काम ठीक उसी जगह से आगे बढ़ता है जहाँ रुका था.
 *
 * phase:
 *  NONE   = कुछ शुरू नहीं हुआ
 *  BRANCH = backup + branch बन चुकी
 *  CODED  = कोड बदल चुका (प्लान के सारे कदम हो गए) - अब सिर्फ़ जाँच/सुधार बाकी
 *  PASSED = build + tests (और QA) पास - सिर्फ़ PR तैयार करना बाकी
 *
 * steps/stepsDone: जो प्लान अभी चल रहा था (पहला प्लान, या जाँच फ़ेल होने के बाद का सुधार) - कितने कदम हो चुके.
 * failLog: आखिरी फ़ेल जाँच का log. code तब से नहीं बदला, इसलिए "आगे करो" पर टेस्ट दोबारा नहीं - सीधे डिबगर.
 */
data class TeamProgress(
    val phase: Int = NONE,
    val rootCause: String = "",
    val changes: Map<String, String> = emptyMap(),
    val steps: List<Seven.Step> = emptyList(),
    val stepsDone: Int = 0,
    val failLog: String = "",
    val failUrl: String = "",
    val commit: String = "",
    val title: String = "",
    val backup: String = "",
    val qaOk: Boolean = false,
) {
    /** बीच में रुका प्लान: बाकी कदम */
    val leftSteps: Int get() = (steps.size - stepsDone).coerceAtLeast(0)

    /** यूज़र को एक लाइन: कहाँ तक पहुँचे थे */
    fun hindi(): String = when {
        leftSteps > 0 -> "प्लान के ${steps.size} में से $stepsDone कदम हो चुके थे — कदम ${stepsDone + 1} से आगे"
        phase >= PASSED -> "build और tests पहले ही पास हो चुके थे — सिर्फ़ PR तैयार करना बाकी"
        phase >= CODED && failLog.isNotBlank() -> "पिछली जाँच की गलती याद है — टेस्ट दोबारा नहीं, सीधे उसी गलती को ठीक कर रहा हूँ"
        phase >= CODED -> "कोड पहले ही बदल चुका था (${changes.size} फ़ाइल) — प्लान/कोडिंग दोबारा नहीं, सीधे जाँच"
        phase >= BRANCH -> "branch पहले ही बन चुकी थी — backup/branch दोबारा नहीं"
        else -> "शुरुआत से"
    }

    fun json(): String = JSONObject()
        .put("phase", phase).put("root", rootCause)
        .put("changes", JSONObject().apply { changes.forEach { (k, v) -> put(k, v) } })
        .put("steps", JSONArray().apply { steps.forEach { put(stepJson(it)) } })
        .put("done", stepsDone).put("failLog", failLog.take(MAX_LOG)).put("failUrl", failUrl)
        .put("commit", commit).put("title", title).put("backup", backup).put("qaOk", qaOk)
        .toString()

    companion object {
        const val NONE = 0
        const val BRANCH = 1
        const val CODED = 2
        const val PASSED = 3
        /** फ़ोन में रखा फ़ेल log इतना (डिबगर को इतना काफ़ी) */
        const val MAX_LOG = 20_000

        fun parse(s: String?): TeamProgress? = if (s.isNullOrBlank()) null else try {
            val o = JSONObject(s)
            val ch = LinkedHashMap<String, String>()
            o.optJSONObject("changes")?.let { c -> c.keys().forEach { k -> ch[k] = c.optString(k) } }
            val st = o.optJSONArray("steps")
            val steps = if (st == null) emptyList() else (0 until st.length()).mapNotNull { st.optJSONObject(it)?.let(::step) }
            TeamProgress(o.optInt("phase"), o.optString("root"), ch, steps, o.optInt("done").coerceIn(0, steps.size),
                o.optString("failLog"), o.optString("failUrl"), o.optString("commit"), o.optString("title"),
                o.optString("backup"), o.optBoolean("qaOk"))
        } catch (_: Exception) { null }

        private fun stepJson(s: Seven.Step) = JSONObject().put("file", s.file).put("action", s.action).put("change", s.change)
            .put("anchor", s.anchor).put("uses", JSONArray(s.uses)).put("why", s.why)

        private fun step(o: JSONObject): Seven.Step? {
            val f = o.optString("file"); if (f.isBlank()) return null
            val u = o.optJSONArray("uses")
            return Seven.Step(f, o.optString("action", "edit"), o.optString("change"), o.optString("anchor"),
                if (u == null) emptyList() else (0 until u.length()).map { u.optString(it) }, o.optString("why"))
        }
    }
}
