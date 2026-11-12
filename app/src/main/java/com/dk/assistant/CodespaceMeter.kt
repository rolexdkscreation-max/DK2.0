package com.dk.assistant

import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक: "Codespaces के महीने के फ़्री घंटे (60 घंटे) - कोडिंग एजेंट में एक छोटी जगह दिखे कितना इस्तेमाल हुआ,
 * कितना बाकी - और फ़्री घंटे सिर्फ़ काम में लगें, फालतू में नहीं").
 *
 * GitHub Free (personal) = हर महीने 120 core-hours compute + 15 GB-month storage मुफ़्त. 2-core मशीन पर 120/2 = 60 घंटे.
 * (https://docs.github.com/en/billing/concepts/product-billing/github-codespaces)
 *
 * आँकड़े दो जगह से:
 *  1. GitHub की असली billing API (`/users/{login}/settings/billing/usage/summary?product=codespaces`) - token में "Plan: read" हो तो पक्का.
 *  2. नहीं तो DK का अपना हिसाब: DK ने codespace कब चालू किया, कब बंद - (सिर्फ़ DK के चलाए; GitHub वेबसाइट से खोला हुआ नहीं गिनता) - "अनुमान".
 * Pure Kotlin (JUnit: CodespaceMeterTest).
 */
object CodespaceMeter {
    /** GitHub Free: महीने के फ़्री core-hours */
    const val FREE_CORE_HOURS = 120.0
    const val FREE_STORAGE_GB = 15.0
    /** हमारी मशीन (सबसे छोटी) */
    const val CORES = 2
    /** इतना % से कम बचा = sandbox छोड़ो, सिर्फ़ GitHub Actions (बाकी घंटे ज़रूरी काम के लिए) */
    const val SAVE_BELOW_PCT = 10

    enum class Src { API, LOCAL }

    data class Usage(val coreHours: Double, val storageGbMonth: Double?, val quotaCoreHours: Double = FREE_CORE_HOURS, val src: Src, val at: Long = 0L) {
        val usedPct: Int get() = if (quotaCoreHours <= 0) 100 else ((coreHours / quotaCoreHours) * 100).toInt().coerceIn(0, 100)
        val leftPct: Int get() = 100 - usedPct
        /** 2-core मशीन के हिसाब से घंटे (मालिक "60 घंटे" जानते हैं) */
        val hoursUsed: Double get() = coreHours / CORES
        val hoursQuota: Double get() = quotaCoreHours / CORES
        val hoursLeft: Double get() = (hoursQuota - hoursUsed).coerceAtLeast(0.0)
    }

    /** "basicLinux32gb" / "Codespaces compute 2-core" / "compute_4_core" -> core */
    fun coresOf(sku: String): Int {
        val s = sku.lowercase()
        Regex("(\\d+)\\s*[-_ ]?\\s*core").find(s)?.let { return it.groupValues[1].toInt() }
        return when {
            s.contains("basiclinux") -> 2
            s.contains("standardlinux") -> 4
            s.contains("premiumlinux") -> 8
            s.contains("largepremiumlinux") -> 16
            else -> CORES
        }
    }

    /**
     * billing usage (summary या usage) का JSON -> Usage. Codespaces का कुछ न मिले = null (तब DK का अपना हिसाब).
     * items में: product, sku, unitType ("Hours"/"GigabyteHours"), grossQuantity.
     */
    fun parse(json: String, now: Long = 0L): Usage? {
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        val arr = o.optJSONArray("usageItems") ?: o.optJSONArray("usage_items") ?: return null
        var core = 0.0; var gbHours = 0.0; var any = false; var anyStorage = false
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val product = it.optString("product").lowercase()
            if (!product.contains("codespace")) continue
            val sku = it.optString("sku")
            val unit = it.optString("unitType", it.optString("unit_type")).lowercase()
            val q = it.optDouble("grossQuantity", it.optDouble("quantity", 0.0)).takeIf { d -> !d.isNaN() } ?: 0.0
            any = true
            when {
                unit.contains("gigabyte") || sku.lowercase().contains("storage") -> { gbHours += q; anyStorage = true }
                unit.contains("hour") || sku.lowercase().contains("compute") -> core += q * coresOf(sku)
            }
        }
        if (!any) return null
        return Usage(core, if (anyStorage) gbHours / HOURS_PER_MONTH else null, src = Src.API, at = now)
    }

    private const val HOURS_PER_MONTH = 730.0

    /** DK का अपना हिसाब: मिनट × core */
    fun local(coreMinutes: Long, now: Long = 0L): Usage = Usage(coreMinutes / 60.0, null, src = Src.LOCAL, at = now)

    /** एक चालू-बंद का हिसाब (core-minutes). बंद का संदेश छूट गया हो (बहुत लंबा) = ज़्यादा से ज़्यादा [MAX_SESSION_MIN] (idle timeout के बाद GitHub खुद बंद करता है) */
    fun session(startAt: Long, stopAt: Long, cores: Int): Long {
        if (startAt <= 0 || stopAt <= startAt) return 0
        val min = ((stopAt - startAt + 59_999) / 60_000L).coerceAtMost(MAX_SESSION_MIN)
        return min * cores.coerceAtLeast(1)
    }
    const val MAX_SESSION_MIN = 90L

    fun monthKey(year: Int, month: Int) = "%04d-%02d".format(year, month)

    private fun h(x: Double): String = when {
        x < 1.0 -> "${(x * 60).toInt()} मिनट"
        x < 10 -> "%.1f घंटे".format(java.util.Locale.US, x)
        else -> "${x.toInt()} घंटे"
    }

    /** कोडिंग एजेंट में छोटी पट्टी: "☁ Codespace: 12.5/60 घंटे • 21% इस्तेमाल • 79% बाकी" */
    fun line(u: Usage?): String {
        if (u == null) return "☁ Codespace: इस महीने का हिसाब अभी नहीं — छूकर ताज़ा करें"
        val used = if (u.hoursUsed < 1.0) "${(u.hoursUsed * 60).toInt()} मिनट / " else (if (u.hoursUsed < 10) "%.1f".format(java.util.Locale.US, u.hoursUsed) else "${u.hoursUsed.toInt()}") + "/"
        return "☁ Codespace: $used${u.hoursQuota.toInt()} घंटे • ${u.usedPct}% इस्तेमाल • ${u.leftPct}% बाकी" +
            if (u.src == Src.LOCAL) " (DK का अनुमान)" else ""
    }

    /** छूने पर पूरा ब्योरा */
    fun detail(u: Usage?, apiWhy: String = ""): String = buildString {
        if (u == null) { append("इस महीने का Codespaces हिसाब नहीं मिला।"); if (apiWhy.isNotBlank()) append("\nवजह: $apiWhy"); return@buildString }
        append("इस महीने (GitHub Free: 120 core-hours = 2-core मशीन पर 60 घंटे):\n")
        append("• इस्तेमाल: ${h(u.hoursUsed)} (${"%.1f".format(java.util.Locale.US, u.coreHours)} core-hours) — ${u.usedPct}%\n")
        append("• बाकी: ${h(u.hoursLeft)} — ${u.leftPct}%\n")
        u.storageGbMonth?.let { append("• Storage: ${"%.2f".format(java.util.Locale.US, it)} / ${FREE_STORAGE_GB.toInt()} GB-month\n") }
        append(if (u.src == Src.API) "• स्रोत: GitHub billing API (पक्का)\n"
            else "• स्रोत: DK का अपना हिसाब (सिर्फ़ DK के चलाए codespace; पक्का आँकड़ा चाहिए तो GitHub token में \"Plan: read\" इजाज़त दें)" +
                (if (apiWhy.isNotBlank()) " — API: $apiWhy" else "") + "\n")
        append("\nघंटे कैसे बचते हैं: codespace सिर्फ़ push के समय चालू होता है, जाँच पास होते ही और काम ख़त्म/रुकते ही बंद, " +
            "10 मिनट खाली = GitHub खुद बंद करता है, और ${SAVE_BELOW_PCT}% से कम बचे तो DK sandbox छोड़कर सिर्फ़ GitHub Actions से जाँच करता है।")
    }

    /** sandbox चलाएँ? (घंटे बहुत कम बचे = नहीं; हिसाब ही नहीं = हाँ) */
    fun allow(u: Usage?): Boolean = u == null || u.leftPct >= SAVE_BELOW_PCT
}
