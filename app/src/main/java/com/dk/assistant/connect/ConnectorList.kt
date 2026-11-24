package com.dk.assistant.connect

import com.dk.assistant.R

/**
 * 24 नवंबर 2026 (मालिक: "जो भी चीज़ कनेक्ट होता है, वो कनेक्टर के अंदर कनेक्ट होने का ऑप्शन होना चाहिए" + फ़ोटो जैसी सूची):
 * कनेक्टर पन्ने के ऊपर की साफ़ सूची - हर पंक्ति: गोल-कोने डिब्बे में ऐप का आइकन, मोटा नाम, धूसर विवरण, दाईं ओर "+" (जोड़ो)
 * या जुड़ा हो तो "−" (हटाओ). ऊपर खोज ("कनेक्टर खोजें") + चिप (सब / जुड़े हुए). Pure हिस्सा - JVM test ([ConnectorListTest]).
 */
object ConnectorList {
    /** [section] = नीचे वाला पूरा हिस्सा (सेटअप / token) जो पंक्ति दबाने पर खुले */
    data class Row(val id: String, val title: String, val sub: String, val icon: Int, val kind: Kind, val section: String)

    enum class Filter { ALL, CONNECTED }

    private val ICONS = mapOf(
        "gmail" to R.drawable.ic_cn_gmail, "drive" to R.drawable.ic_cn_drive, "calendar" to R.drawable.ic_cn_calendar,
        "contacts" to R.drawable.ic_cn_contacts, "tasks" to R.drawable.ic_cn_tasks, "sheets_docs" to R.drawable.ic_cn_sheets,
        "youtube" to R.drawable.ic_cn_youtube, "github" to R.drawable.ic_cn_github, "fb_page" to R.drawable.ic_cn_facebook,
        "instagram" to R.drawable.ic_cn_instagram, "mcp" to R.drawable.ic_cn_mcp,
    )

    fun icon(id: String): Int = ICONS[id] ?: R.drawable.ic_cn_mcp

    /** सूची का क्रम: Google (YouTube समेत) -> GitHub -> Facebook Page -> Instagram -> MCP */
    fun rows(): List<Row> {
        val g = Catalog.GOOGLE.map { Row(it.id, it.title, it.sub, icon(it.id), Kind.GOOGLE, it.id) }
        return g + listOf(
            Row(Catalog.GITHUB.id, Catalog.GITHUB.title, Catalog.GITHUB.sub, icon("github"), Kind.GITHUB, Catalog.GITHUB.id),
            Row(Catalog.FB_PAGE.id, Catalog.FB_PAGE.title, Catalog.FB_PAGE.sub, icon("fb_page"), Kind.META, "meta"),
            Row(Catalog.INSTAGRAM.id, Catalog.INSTAGRAM.title, Catalog.INSTAGRAM.sub, icon("instagram"), Kind.META, "meta"),
            Row("mcp", "MCP server", "Notion, Slack, Jira… किसी का भी MCP server URL जोड़ें", icon("mcp"), Kind.MCP, "mcp"),
        )
    }

    /** खोज: नाम / विवरण / id में (छोटे-बड़े अक्षर एक), खाली = सब; [Filter.CONNECTED] = सिर्फ़ जुड़े */
    fun filter(all: List<Row>, q: String, f: Filter, isOn: (String) -> Boolean): List<Row> {
        val t = q.trim().lowercase()
        return all.filter { r ->
            (t.isEmpty() || r.title.lowercase().contains(t) || r.sub.lowercase().contains(t) || r.id.contains(t) || alias(r.id).any { it.contains(t) }) &&
                (f == Filter.ALL || isOn(r.id))
        }
    }

    /** हिंदी / बोलचाल नाम से भी मिले */
    private fun alias(id: String): List<String> = when (id) {
        "gmail" -> listOf("मेल", "ईमेल", "email", "जीमेल")
        "drive" -> listOf("ड्राइव", "फ़ाइल", "file")
        "calendar" -> listOf("कैलेंडर", "इवेंट")
        "youtube" -> listOf("यूट्यूब", "यूट्यूब", "yt", "वीडियो")
        "fb_page" -> listOf("फेसबुक", "facebook", "fb", "meta")
        "instagram" -> listOf("इंस्टाग्राम", "insta", "meta")
        "github" -> listOf("गिटहब", "code", "कोड")
        else -> emptyList()
    }

    /** दाईं ओर का बटन: जुड़ा = हटाओ (−), नहीं = जोड़ो (+) */
    fun actionIcon(on: Boolean) = if (on) R.drawable.ic_cn_remove else R.drawable.ic_cn_plus
}
