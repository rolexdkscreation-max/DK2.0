package com.dk.assistant.inspector

import com.dk.assistant.inspector.InspectorCore.Incident
import com.dk.assistant.inspector.InspectorCore.Severity
import org.json.JSONArray
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक): "बीटा में कौन-सा bug आया, स्टेबल में भेजूँ या नहीं" - शुद्ध नियम (Android के बिना, JVM test होते हैं)।
 *
 * - हिस्सा 1 (ठप्पा): हर Incident पर firstVer / firstChan / lastVer (InspectorCore.merge भरता है)।
 * - हिस्सा 2 (बीटा जाँच): इस version पर दिखी हर गड़बड़ी "नई / पुरानी / फिर से आई" में बँटती है।
 *   स्टेबल के लिए तैयार = 0 क्रैश + 0 नई गंभीर (High/Critical) + कम से कम [MIN_HOURS] घंटे चला।
 * Inspector की रिपोर्टें 3 दिन बाद साफ़ होती हैं, इसलिए version वाली गिनती अलग [Session] में रहती है।
 */
object BetaCheck {

    const val MIN_HOURS = 24L
    private const val HOUR_MS = 3_600_000L
    const val MAX_ITEMS = 40
    const val MAX_CHECKS = 8
    val CRASH_RULES = setOf("app_crash_file", "app_native_crash")

    enum class Channel(val hi: String) {
        BETA("बीटा"), STABLE("स्टेबल"), LOCAL("लोकल");
        companion object {
            fun of(s: String?): Channel = when (s?.trim()?.lowercase()) {
                "beta" -> BETA; "stable" -> STABLE; else -> LOCAL
            }
        }
    }

    enum class Kind(val hi: String) {
        NEW("नई - इसी version में पहली बार"), BACK("फिर से आई - हल/शांत होकर इस version में लौटी"),
        OLD("पुरानी - पहले के version में भी थी")
    }

    data class Item(
        val fp: String, val id: String, val title: String, val severity: Severity, val kind: Kind,
        val crash: Boolean, val hits: Int, val firstAt: Long, val lastAt: Long, val message: String = "",
    )

    data class Session(
        val version: String, val code: Long, val channel: Channel, val startedAt: Long,
        val items: List<Item> = emptyList(), val checked: Set<Int> = emptySet(),
    )

    data class Verdict(
        val ready: Boolean, val hours: Long, val crashes: Int, val newSerious: Int, val newMedium: Int,
        val back: Int, val old: Int, val reasons: List<String>, val headline: String,
    )

    /** "3.307/943" → "3.307" */
    fun name(appVersion: String): String = appVersion.substringBefore('/').ifBlank { "?" }

    fun isCrash(i: Incident) = i.ruleId in CRASH_RULES

    /**
     * इस version के हिसाब से गड़बड़ी किस तरह की है।
     * क्रैश की फ़ाइल पुराने version की हो सकती है (update से पहले वाला क्रैश) - संदेश में "(version X)" हो तो वही मानो।
     */
    fun kindOf(i: Incident, appVersion: String, reopened: Boolean): Kind {
        if (isCrash(i)) {
            val v = Regex("version\\s+([0-9][0-9.]*)").find(i.message)?.groupValues?.get(1)
            if (v != null && v != name(appVersion)) return Kind.OLD
        }
        return when {
            reopened -> Kind.BACK
            i.firstVer.isNotBlank() && i.firstVer == appVersion -> Kind.NEW
            else -> Kind.OLD
        }
    }

    /** नया version = नया session (पुरानी गिनती नहीं); वही version = वही session */
    fun ensure(s: Session?, version: String, code: Long, channel: Channel, startedAt: Long): Session =
        if (s == null || s.code != code) Session(version, code, channel, startedAt)
        else s.copy(channel = channel, startedAt = if (s.startedAt <= 0) startedAt else s.startedAt)

    /** Inspector.inspect की हर जाँच के बाद: इस बार छुई गई गड़बड़ियाँ जोड़ो */
    fun record(s: Session, touched: List<Incident>, reopenedFps: Set<String>, appVersion: String, now: Long): Session {
        if (touched.isEmpty()) return s
        val by = LinkedHashMap<String, Item>(); s.items.forEach { by[it.fp] = it }
        for (i in touched) {
            val k = kindOf(i, appVersion, i.fingerprint in reopenedFps)
            val o = by[i.fingerprint]
            by[i.fingerprint] = if (o == null) {
                Item(i.fingerprint, i.id, i.title, i.severity, k, isCrash(i), 1, now, now, InspectorCore.redact(i.message).take(200))
            } else {
                // BACK/NEW एक बार हुआ तो वही रहे (बाद की जाँच में "पुरानी" बनकर छिपे नहीं)
                val kind = if (o.kind != Kind.OLD) o.kind else k
                val sev = if (i.severity.rank > o.severity.rank) i.severity else o.severity
                o.copy(title = i.title, severity = sev, kind = kind, hits = o.hits + 1, lastAt = now,
                    message = InspectorCore.redact(i.message).take(200))
            }
        }
        return s.copy(items = by.values.sortedWith(compareByDescending<Item> { it.crash }
            .thenByDescending { it.severity.rank }.thenByDescending { it.lastAt }).take(MAX_ITEMS))
    }

    fun toggle(s: Session, index: Int, on: Boolean): Session =
        s.copy(checked = if (on) s.checked + index else s.checked - index)

    fun verdict(s: Session, now: Long): Verdict {
        val hours = if (s.startedAt <= 0) 0 else ((now - s.startedAt).coerceAtLeast(0) / HOUR_MS)
        val crashes = s.items.count { it.crash }
        val fresh = s.items.filter { !it.crash && it.kind != Kind.OLD }
        val serious = fresh.count { it.severity.rank >= Severity.HIGH.rank }
        val medium = fresh.count { it.severity == Severity.MEDIUM }
        val reasons = buildList {
            if (crashes > 0) add("$crashes क्रैश हुए")
            if (serious > 0) add("$serious नई गंभीर गड़बड़ी (High/Critical)")
            if (hours < MIN_HOURS) add("अभी ${hours} घंटे चला - कम से कम $MIN_HOURS घंटे चलाएँ")
        }
        val blocked = crashes > 0 || serious > 0
        val ready = reasons.isEmpty()
        val headline = when {
            ready -> "✅ स्टेबल के लिए तैयार"
            blocked -> "⛔ रोकें - स्टेबल में न भेजें"
            else -> "⏳ अभी जाँच चल रही है"
        }
        return Verdict(ready, hours, crashes, serious, medium, s.items.count { it.kind == Kind.BACK },
            s.items.count { it.kind == Kind.OLD }, reasons, headline)
    }

    private val DEFAULT_CHECKS = listOf(
        "DK लाइव से 2-3 बातें कीं (सुनना + जवाब ठीक)",
        "इस version में जो नया बदला, उसे एक बार खुद आज़माया",
    )

    /** release notes की "•" / "-" वाली लाइनें = क्या-क्या आज़माना है */
    fun checklist(notes: String?): List<String> {
        val xs = notes.orEmpty().lines().map { it.trim() }
            .filter { it.startsWith("•") || it.startsWith("- ") || it.startsWith("* ") }
            .map { it.trimStart('•', '-', '*', ' ').trim() }
            .filter { it.length >= 3 && !it.startsWith("छोटे सुधार") }
            .distinct().take(MAX_CHECKS)
        return xs.ifEmpty { DEFAULT_CHECKS }
    }

    fun summary(s: Session, v: Verdict, checks: List<String>): String = buildString {
        appendLine("DK ${s.version} (${s.channel.hi}) • ${v.hours} घंटे से चल रहा")
        appendLine("क्रैश: ${v.crashes} • नई गंभीर: ${v.newSerious} • नई मध्यम: ${v.newMedium}")
        appendLine("फिर से आई: ${v.back} • पुरानी (पहले से थीं): ${v.old}")
        appendLine("आपकी जाँच: ${s.checked.count { it in checks.indices }}/${checks.size}")
        appendLine()
        append(v.headline)
        if (v.reasons.isNotEmpty()) append("\n• " + v.reasons.joinToString("\n• "))
        if (v.ready && v.newMedium > 0) append("\n⚠ ${v.newMedium} नई मध्यम गड़बड़ी - एक बार देख लें")
    }

    fun reportTitle(s: Session, v: Verdict): String =
        "[बीटा ${s.version}] " + if (v.ready) "स्टेबल के लिए तैयार" else "रोकें: " + v.reasons.joinToString(", ").take(80)

    fun reportBody(s: Session, v: Verdict, checks: List<String>, device: String): String = buildString {
        appendLine("## बीटा जाँच रिपोर्ट"); appendLine()
        appendLine("- Version: DK ${s.version} (code ${s.code}, ${s.channel.hi})")
        appendLine("- चला: ${v.hours} घंटे • फ़ोन: $device")
        appendLine("- नतीजा: ${v.headline}")
        v.reasons.forEach { appendLine("  - $it") }
        appendLine(); appendLine("### गड़बड़ियाँ")
        if (s.items.isEmpty()) appendLine("कोई नहीं")
        s.items.forEach {
            appendLine("- ${if (it.crash) "क्रैश" else it.severity.name} • ${it.kind.hi} • ${it.id} ${it.title} (${it.hits} जाँच में)")
            if (it.message.isNotBlank()) appendLine("  `${it.message.replace('`', '\'')}`")
        }
        appendLine(); appendLine("### मालिक की जाँच")
        checks.forEachIndexed { n, t -> appendLine("- [${if (n in s.checked) "x" else " "}] $t") }
    }.let { InspectorCore.redact(it) }

    /** ⛔ असली रोक = क्रैश या नई गंभीर (सिर्फ़ "24 घंटे नहीं हुए" से रोक नहीं) */
    fun isBlocker(v: Verdict): Boolean = v.crashes > 0 || v.newSerious > 0

    /**
     * GitHub नया issue (label: beta; [blocker] हो तो beta-blocker भी)।
     * beta-blocker वाला खुला issue = main के हर PR पर beta-gate check लाल (.github/workflows/beta-gate.yml)।
     * label सिर्फ़ repo पर अधिकार वाले (मालिक) के भेजने पर लगते हैं; version label (v3.307) workflow लगाता है।
     */
    fun issueUrl(repo: String, title: String, body: String, maxBody: Int = 5000, blocker: Boolean = false): String {
        fun enc(x: String) = java.net.URLEncoder.encode(x, "UTF-8")
        val labels = if (blocker) "beta,beta-blocker" else "beta"
        return "https://github.com/$repo/issues/new?labels=${enc(labels)}&title=${enc(title)}&body=${enc(body.take(maxBody))}"
    }

    // ------------------------------------------------------------------ JSON

    fun toJson(s: Session): String = JSONObject().apply {
        put("v", s.version); put("code", s.code); put("ch", s.channel.name); put("start", s.startedAt)
        put("checked", JSONArray(s.checked.sorted()))
        put("items", JSONArray().apply {
            s.items.forEach { i ->
                put(JSONObject().apply {
                    put("fp", i.fp); put("id", i.id); put("t", i.title); put("sev", i.severity.name); put("k", i.kind.name)
                    put("crash", i.crash); put("hits", i.hits); put("first", i.firstAt); put("last", i.lastAt); put("msg", i.message)
                })
            }
        })
    }.toString()

    fun fromJson(raw: String?): Session? = try {
        if (raw.isNullOrBlank()) null else {
            val o = JSONObject(raw)
            val ia = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until ia.length()).map { n ->
                val j = ia.getJSONObject(n)
                Item(j.getString("fp"), j.optString("id"), j.optString("t"), Severity.valueOf(j.optString("sev", "LOW")),
                    Kind.valueOf(j.optString("k", "OLD")), j.optBoolean("crash"), j.optInt("hits"),
                    j.optLong("first"), j.optLong("last"), j.optString("msg"))
            }
            val ca = o.optJSONArray("checked") ?: JSONArray()
            Session(o.getString("v"), o.getLong("code"), Channel.valueOf(o.optString("ch", "LOCAL")), o.optLong("start"),
                items, (0 until ca.length()).map { ca.getInt(it) }.toSet())
        }
    } catch (e: Exception) { null }
}
