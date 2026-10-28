package com.dk.assistant

/**
 * काम शुरू करने से पहले रिपो की जाँच (Android-free - JUnit से जाँचा जाता है).
 *
 * मैनेजर + प्रॉम्प्ट इंजीनियर की समझ user को दिखाने से पहले: GitHub पर कौन-से PR खुले हैं और
 * उनमें से कोई उसी जगह (फ़ाइल) को तो नहीं छू रहा जहाँ यह काम होगा. सिर्फ़ पढ़ना - कुछ नहीं बदलता.
 * नतीजा समझ के साथ user को दिखता है, ताकि "हाँ" देने से पहले उसे रिपो की हालत पता हो.
 */
object RepoPreCheck {

    /** समझ के "area" ("path - why") से सिर्फ़ फ़ाइल path */
    fun areaPaths(area: List<String>): List<String> = area.mapNotNull { a ->
        val p = a.substringBefore(" - ").substringBefore(" — ").substringBefore(':').trim().trim('`', '"', '\'')
        p.takeIf { it.isNotBlank() && !it.contains(' ') && (it.contains('/') || it.contains('.')) }
    }.distinct()

    /** PR की बदली फ़ाइलों में से वे जो इस काम की जगह से मिलती हैं (पूरा path या फ़ाइल-नाम) */
    fun overlap(areaPaths: List<String>, prFiles: List<String>): List<String> = prFiles.filter { f ->
        areaPaths.any { a -> f == a || f.endsWith("/$a") || a.endsWith("/$f") || f.substringAfterLast('/') == a.substringAfterLast('/') }
    }.distinct()

    /**
     * user को दिखने वाला छोटा हिंदी नोट.
     * @param touching PR नंबर -> उसकी वे फ़ाइलें जो इसी काम की जगह हैं
     * @param ownPr टीम का अपना (इसी सत्र का) खुला PR - उसमें काम जुड़ता है, यह टकराव नहीं
     */
    fun text(prs: List<RepoDoctor.Pr>, touching: Map<Int, List<String>>, ownPr: Int? = null, base: String = "main"): String = buildString {
        append("🔎 रिपो जाँच (काम शुरू करने से पहले):\n")
        if (prs.isEmpty()) { append("• कोई PR खुला नहीं है — काम $base से नई branch में होगा।"); return@buildString }
        append("• ${prs.size} PR खुले हैं:\n")
        prs.take(6).forEach { p ->
            append("  #${p.number} ${p.title.take(70)}")
            if (p.number == ownPr) append(" (टीम का अपना — यह काम इसी में जुड़ेगा)")
            touching[p.number]?.takeIf { it.isNotEmpty() && p.number != ownPr }?.let { append(" ⚠ यही फ़ाइलें छूता है: ").append(it.take(3).joinToString { f -> f.substringAfterLast('/') }) }
            append("\n")
        }
        if (prs.size > 6) append("  … और ${prs.size - 6}\n")
        val clash = touching.filter { (n, f) -> n != ownPr && f.isNotEmpty() }.keys
        if (clash.isEmpty()) append("• किसी खुले PR से टकराव नहीं दिखा।")
        else append("• ⚠ PR " + clash.joinToString { "#$it" } + " इसी जगह को बदलता है — पहले उसे merge/बंद करना ठीक रहेगा, वरना टकराव हो सकता है। \"हाँ\" कहें तो फिर भी अलग नई PR में काम होगा।")
    }.trimEnd()
}
