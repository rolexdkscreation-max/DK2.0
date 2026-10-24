package com.dk.assistant.forge

/**
 * DK AI ब्रीफ़ Part 14: बनते HTML से पता करना कि वेबसाइट किस पड़ाव पर है (6 पड़ाव).
 * Android import नहीं -> JVM test.
 */
enum class ForgeStage(val label: String, val labelHi: String, val start: Int) {
    STRUCTURE("Structure", "ढाँचा", 0),
    LAYOUT("Layout", "लेआउट", 12),
    STYLING("Styling", "स्टाइल", 28),
    CONTENT("Content", "सामग्री", 48),
    MOTION("Motion", "एनिमेशन", 72),
    FINISH("Finish", "आख़िरी छुअन", 90);

    val next: ForgeStage? get() = entries.getOrNull(ordinal + 1)
}

object ForgeStageDetector {
    private val SECTION = Regex("<(section|header|footer|main|nav|article)\\b", RegexOption.IGNORE_CASE)
    private val VISUAL = Regex("<(img|canvas|svg|video|picture)\\b|picsum\\.photos|pravatar\\.cc", RegexOption.IGNORE_CASE)
    private val SCRIPT = Regex("<script\\b", RegexOption.IGNORE_CASE)
    private val SEC_TAG = Regex("<section\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val ATTR_ID = Regex("\\b(?:id|aria-label|data-section)\\s*=\\s*\"([^\"]{1,40})\"", RegexOption.IGNORE_CASE)
    private val HEADING = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAGS = Regex("<[^>]+>")

    fun detect(partialHtml: String): ForgeStage {
        val low = partialHtml.lowercase()
        if (low.contains("</html>")) return ForgeStage.FINISH
        val b = low.indexOf("<body")
        if (b < 0) return ForgeStage.STRUCTURE                    // सिर्फ़ <head> = अभी शुरुआत
        val body = partialHtml.substring(b)
        val sections = SECTION.findAll(body).count()
        val visuals = VISUAL.findAll(body).count()
        val lastSection = SECTION.findAll(body).lastOrNull()?.range?.first ?: -1
        val scriptAfter = SCRIPT.findAll(body).any { it.range.first > lastSection }
        return when {
            sections >= 3 && scriptAfter -> ForgeStage.MOTION
            sections >= 3 && visuals >= 1 -> ForgeStage.CONTENT
            sections >= 1 -> ForgeStage.STYLING
            else -> ForgeStage.LAYOUT
        }
    }

    /** पड़ाव + लंबाई से 0..99 प्रतिशत (100 सिर्फ़ पूरा होने पर) */
    fun progress(stage: ForgeStage, chars: Int, expectedChars: Int = 30_000): Int {
        val end = stage.next?.start ?: 99
        val frac = (chars.toDouble() / expectedChars).coerceIn(0.0, 1.0)
        val overall = (frac * 99).toInt()
        return overall.coerceIn(stage.start, end - 1).coerceAtMost(99)
    }

    /** बन चुके हिस्सों के नाम (Hero, Menu, Contact ...) - HUD और "Laying out <Section>" के लिए */
    fun sectionNames(partialHtml: String): List<String> {
        val out = mutableListOf<String>()
        val secs = SEC_TAG.findAll(partialHtml).toList()
        for ((i, m) in secs.withIndex()) {
            val id = ATTR_ID.find(m.groupValues[1])?.groupValues?.get(1)
            val name = id?.let { pretty(it) } ?: run {
                val endIdx = secs.getOrNull(i + 1)?.range?.first ?: partialHtml.length
                val chunk = partialHtml.substring(m.range.last + 1, endIdx)
                HEADING.find(chunk)?.groupValues?.get(1)?.let { TAGS.replace(it, " ").replace(Regex("\\s+"), " ").trim().take(24) }
            } ?: "Section ${i + 1}"
            out += name.ifBlank { "Section ${i + 1}" }
        }
        return out
    }

    fun pretty(id: String): String = id.trim().replace(Regex("[-_]+"), " ")
        .split(' ').filter { it.isNotBlank() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        .take(24)
}
