package com.dk.assistant.agent.core

/**
 * नवंबर 2026 (मालिक): "DK किसी भी ऐप में (ब्राउज़र या कोई भी ऐप) फ़ॉर्म भर दे - जो-जो भरना है वह मुझसे पूछे और भरता जाए;
 * एक बार उस ऐप में गया तो वहीं रुककर करे (बार-बार बैक नहीं), और उस ऐप में जो लिखा है वह पूरा उसे दिखे."
 *
 * यह pure हिस्सा (Android import नहीं, JUnit): स्क्रीन से फ़ॉर्म के खाने (field) और उनके नाम पहचानना, पूरी स्क्रीन का लिखा
 * Live को बताने लायक बनाना, मालिक के बोले नाम / नंबर से सही खाना चुनना. स्क्रीन पर काम [com.dk.assistant.agent.android.FormFillAgent].
 */
object FormFill {
    enum class Kind(val hi: String) { TEXT("लिखने का खाना"), CHECK("टिक / स्विच"), CHOICE("चुनने वाली सूची") }

    /** फ़ॉर्म का एक खाना. key = नाम+किस्म (स्क्रॉल के बाद भी वही खाना पहचानने के लिए) */
    data class Field(val label: String, val value: String, val kind: Kind, val box: Box, val key: String, val secret: Boolean = false)

    private fun clsHas(e: UiElement, vararg w: String) = w.any { e.cls.contains(it, ignoreCase = true) }

    fun isCheck(e: UiElement) = clsHas(e, "CheckBox", "Switch", "RadioButton", "ToggleButton", "CheckedTextView")
    fun isChoice(e: UiElement) = clsHas(e, "Spinner") || Regex("(spinner|dropdown|drop_down)", RegexOption.IGNORE_CASE).containsMatchIn(e.viewId)

    private val SECRET = Regex("(password|passcode|पासवर्ड|\\bpin\\b|पिन|otp|ओटीपी|cvv)", RegexOption.IGNORE_CASE)

    fun norm(s: String) = s.lowercase().replace(Regex("[*:：•]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** खाने के पास (ऊपर या बाएँ) लिखा नाम */
    fun labelFor(o: ScreenObservation, e: UiElement): String {
        if (e.desc.isNotBlank() && norm(e.desc) != norm(e.text)) return e.desc.trim()
        var best: UiElement? = null; var bestD = Int.MAX_VALUE
        for (c in o.elements) {
            if (c === e || c.editable || c.text.isBlank() || isCheck(c) || c.text.length > 80) continue
            val hOverlap = minOf(c.bounds.right, e.bounds.right) - maxOf(c.bounds.left, e.bounds.left) > 0
            val vOverlap = minOf(c.bounds.bottom, e.bounds.bottom) - maxOf(c.bounds.top, e.bounds.top) > 0
            val d = when {
                c.bounds.bottom <= e.bounds.top + 12 && hOverlap && e.bounds.top - c.bounds.bottom <= 170 -> e.bounds.top - c.bounds.bottom
                vOverlap && c.bounds.right <= e.bounds.left + 12 && e.bounds.left - c.bounds.right <= 400 -> e.bounds.left - c.bounds.right + 5
                else -> continue
            }
            if (d < bestD) { bestD = d; best = c }
        }
        return best?.text?.trim().orEmpty()
    }

    /** स्क्रीन के सारे खाने, ऊपर से नीचे */
    fun fields(o: ScreenObservation): List<Field> {
        val out = ArrayList<Field>()
        for (e in o.elements.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))) {
            if (!e.enabled) continue
            val kind = when { e.editable -> Kind.TEXT; isCheck(e) -> Kind.CHECK; isChoice(e) -> Kind.CHOICE; else -> continue }
            var label = labelFor(o, e)
            var value = e.text.trim()
            if (kind == Kind.CHECK) { label = (e.text.ifBlank { e.desc }).trim().ifBlank { label }; value = "" }
            if (kind == Kind.TEXT && label.isBlank()) { label = value; value = "" }      // अपना ही लिखा = hint (खाली खाना)
            if (kind == Kind.CHOICE && label.isBlank()) { label = (e.text.ifBlank { e.desc }).trim(); value = "" }
            if (kind == Kind.TEXT && norm(value) == norm(label)) value = ""
            if (label.isBlank()) label = kind.hi
            val secret = SECRET.containsMatchIn(label) || e.cls.contains("Password", true)
            out += Field(label.take(80), value.take(120), kind, e.bounds, norm(label) + "|" + kind.name, secret)
        }
        // एक ही नाम के कई खाने: key अलग (#2, #3)
        val seen = HashMap<String, Int>()
        return out.map { f -> val n = (seen[f.key] ?: 0) + 1; seen[f.key] = n; if (n == 1) f else f.copy(key = f.key + "#$n") }
    }

    /** स्क्रॉल करते हुए मिले खाने जोड़ो - क्रम वही, दोहराव नहीं (नई value से पुरानी बदलो) */
    fun merge(old: List<Field>, new: List<Field>): List<Field> {
        val out = LinkedHashMap<String, Field>()
        old.forEach { out[it.key] = it }
        new.forEach { out[it.key] = it }
        return out.values.toList()
    }

    /** स्क्रीन का सारा लिखा (दोहराव नहीं) */
    fun mergeTexts(old: List<String>, new: List<String>): List<String> = LinkedHashSet(old).apply { addAll(new.map { it.trim() }.filter { it.isNotEmpty() }) }.toList()

    fun signature(o: ScreenObservation): String = o.elements.joinToString("|") { it.text + "@" + it.bounds.top }.hashCode().toString()

    private val ORD = listOf(
        listOf("पहला", "पहले", "first", "1st", "pehla"), listOf("दूसरा", "दूसरे", "second", "2nd", "dusra"),
        listOf("तीसरा", "तीसरे", "third", "3rd", "teesra"), listOf("चौथा", "चौथे", "fourth", "4th", "chautha"),
        listOf("पांचवां", "पाँचवाँ", "पाँचवा", "fifth", "5th"), listOf("छठा", "sixth", "6th"),
    )

    /** मालिक ने कौन-सा खाना कहा: नंबर ("3", "तीसरा") या नाम ("नाम", "mobile number") */
    fun match(fields: List<Field>, spoken: String): Field? {
        val s = norm(spoken)
        if (s.isEmpty() || fields.isEmpty()) return null
        Regex("^#?(\\d{1,2})$").find(s)?.let { m -> return fields.getOrNull(m.groupValues[1].toInt() - 1) }
        ORD.forEachIndexed { i, w -> if (w.any { s == it || s.startsWith("$it ") }) return fields.getOrNull(i) }
        fields.firstOrNull { norm(it.label) == s }?.let { return it }
        fields.firstOrNull { norm(it.label).contains(s) }?.let { return it }
        fields.firstOrNull { s.contains(norm(it.label)) && norm(it.label).length >= 3 }?.let { return it }
        val words = s.split(' ').filter { it.length >= 2 }
        return fields.map { f -> f to words.count { norm(f.label).contains(it) } }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    private val SUBMIT = Regex("^(submit|सबमिट|जमा करें|जमा करो|send|भेजें|next|आगे|continue|save|सेव|सेव करें|register|sign up|signup|apply|done|ok|proceed|confirm|pay|book|login|log in|sign in)$", RegexOption.IGNORE_CASE)

    fun isSubmit(e: UiElement): Boolean = !e.editable && (e.clickable || e.cls.contains("Button")) &&
        SUBMIT.containsMatchIn(norm(e.text.ifBlank { e.desc }))

    fun submitButton(o: ScreenObservation): UiElement? = o.elements.filter { isSubmit(it) }.maxByOrNull { it.bounds.top }

    /** हाँ / ना जैसा जवाब (टिक वाले खाने के लिए) */
    fun yes(v: String): Boolean? {
        val w = norm(v).split(' ').firstOrNull().orEmpty()
        return when (w) {
            in setOf("हाँ", "हां", "हा", "haan", "han", "ha", "yes", "y", "on", "चालू", "true", "टिक", "tick", "check", "ok") -> true
            in setOf("नहीं", "नही", "ना", "nahi", "nahin", "no", "n", "off", "बंद", "false", "untick", "uncheck") -> false
            else -> null
        }
    }

    fun line(i: Int, f: Field): String = "${i + 1}. ${f.label} (${f.kind.hi})" +
        (if (f.secret) " [निजी - मालिक खुद भरे]" else if (f.value.isNotBlank()) " = \"${f.value}\"" else " = खाली")

    /** Live के लिए: पूरी स्क्रीन का लिखा + खानों की सूची + आगे क्या करना */
    fun describe(app: String, fields: List<Field>, texts: List<String>, maxChars: Int = 2_400): String {
        val all = texts.joinToString(" | ").let { if (it.length > maxChars) it.take(maxChars) + "…" else it }
        val list = if (fields.isEmpty()) "(इस स्क्रीन पर भरने वाला कोई खाना नहीं मिला)" else fields.mapIndexed { i, f -> line(i, f) }.joinToString("\n")
        return "FORM READ in $app (DK stays in this same app - no back, no other app). Everything written on the screen (top to bottom, whole page):\n$all\n\n" +
            "Fields to fill:\n$list\n\n" +
            if (fields.isEmpty()) "Tell the user no form field is visible here and ask him to open the form page."
            else "Now tell the user in short Hindi what this form is and ask him ONLY for the first empty field (one question at a time). " +
                "After each answer call form_fill action=fill field=<number or name> value=<his exact words>, then ask for the next field. " +
                "Never invent or guess any value. Fields marked निजी (password/OTP/PIN) - ask the user to type them himself. " +
                "When all are filled, read back the values and ask 'सबमिट कर दूँ?'; only after a clear yes call action=submit confirm=true."
    }
}
