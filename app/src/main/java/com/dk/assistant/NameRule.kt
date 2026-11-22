package com.dk.assistant

/**
 * 14 नवंबर 2026 (मालिक): "प्रोफ़ाइल में जो यूज़र का नाम डाला है, DK उसके आगे 'जी' लगाकर बुलाए - जी धीरज / जी मम्मी /
 * जी पापा / जी सर. और असिस्टेंट का जो नाम डाला है, उसी नाम से अपना परिचय दे." (बनाने वाले = धीरज कुमार सिंह, वैसे ही.)
 *
 * - [block]: हर system prompt में (Live + चैट) - AI के अपने बोले जवाब इसी से
 * - [swap]: DK की पहले से लिखी लाइनें ("ठीक है बॉस", "बॉस, कॉल आ रहा है…") - दिखने/बोलने से पहले "जी <नाम>"
 * नाम खाली = पहले जैसा (कुछ नहीं बदलता). Android import नहीं -> JVM test.
 */
object NameRule {
    /** "जी धीरज" (नाम खाली = "") */
    fun call(userName: String): String = userName.trim().removePrefix("जी ").trim().takeIf { it.isNotBlank() }?.let { "जी $it" } ?: ""

    /** 15 नवंबर 2026 (मालिक): नाम ख़ुद एक संबोधन हो (बॉस / सर / मैडम) - तब "बॉस मत कहो" वाली बात नहीं */
    val TITLES = setOf("बॉस", "सर", "मैडम", "boss", "sir", "madam", "मालिक", "हुकुम")
    fun isTitle(userName: String): Boolean = userName.trim().removePrefix("जी ").trim().lowercase() in TITLES

    /** अभिवादन: "हेलो DK" पर "हेलो <नाम>" (नाम खाली = "हेलो") */
    fun greet(userName: String): String = userName.trim().removePrefix("जी ").trim().takeIf { it.isNotBlank() }?.let { "हेलो $it" } ?: "हेलो"

    fun block(userName: String, assistantName: String): String = buildString {
        val u = userName.trim()
        val a = assistantName.trim().ifBlank { AssistantBrand.NAME }
        append("\nNAMES (owner's rule - overrides every other way of addressing the user written anywhere in these instructions):\n")
        if (u.isNotBlank()) {
            val b = bare(u)
            // 22 नवंबर 2026 (मालिक: "'हेलो जी boss' नहीं, 'हेलो बॉस' अच्छा; 'मैं यह कर रहा हूँ जी बॉस' खराब, 'जी बॉस, मैं यह कर रहा हूँ' अच्छा"):
            // "जी <नाम>" सिर्फ़ बुलाने (power बटन / Hey DK) के जवाब में - वह ऐप खुद बोलता है. बाकी जवाबों में नाम बिना "जी", वाक्य की शुरुआत में.
            append("- The user's name is \"$u\". The short reply \"${call(u)}\" is ONLY for the moment he calls you (power button / Hey DK) - the app says it itself. ")
            append("In your answers call him \"$b\" (without \"जी\"), not in every reply, and ONLY at the START of a sentence: ")
            append("\"हेलो $b, …\", \"हाँ $b, बताइए\", \"$b, मैं अभी कर रहा हूँ\", \"$b, हो गया\". ")
            if (!isTitle(u)) append("Never say \"बॉस\" or \"boss\" to the user; ")
            append("wherever an example in these instructions says बॉस / सर, use \"$b\" instead, at the start of the sentence.\n")
            append("- GREETING (owner's rule): when the user greets you (\"हेलो DK\", \"हाय\", \"नमस्ते\", \"good morning\"), greet back with the saved name: ")
            append("\"${greet(u)}\" / \"$b, बताइए\". Never \"हेलो जी $b\", never \"जी जी\".\n")
        }
        if (a != AssistantBrand.NAME) {
            append("- YOUR name is \"$a\". When you introduce yourself or anyone asks your name, say \"मैं $a हूँ\". ")
            append("Your creator and developer is still ${AssistantBrand.OWNER_NAME} (identity rule stays the same).\n")
        } else append("- Your name is \"$a\". When you introduce yourself, say \"मैं $a हूँ\".\n")
    }

    /** "बॉस" जिसके बाद ये न हों (बात किसी और "बॉस" की हो रही = नहीं बदलना): "मेरे बॉस", "बॉस ने", "बॉस का/की/के/को/से" */
    private val ADDRESS = Regex("(?<!मेरे )(?<!आपके )(?<!उनके )(?<!तुम्हारे )बॉस(?!\\s*(ने|का|की|के|को|से|हैं|है|थे|था)(?![\\p{L}\\p{M}]))")

    /** पहले से लिखी लाइन में यूज़र को "बॉस" कहा = "जी <नाम>" ("हाँ बॉस" -> "हाँ जी धीरज"). नाम खाली = वैसी ही */
    fun swap(text: String, userName: String, persona: String = personaCall()): String {
        // 15 नवंबर 2026 (मालिक): "गर्लफ्रेंड मोड में बाबू/जान, बाकी में बॉस/सर" - चुने स्वभाव का संबोधन सबसे पहले
        val p = persona.trim()
        if (p.isNotBlank()) return if (!text.contains("बॉस") || p == "बॉस") text else ADDRESS.replace(text, p)
        val c = call(userName)
        if (c.isBlank() || !text.contains("बॉस")) return text
        // 22 नवंबर 2026 (मालिक): "जी <नाम>" सिर्फ़ वाक्य की शुरुआत / "हाँ / ठीक है / जी" के बाद; "हो गया बॉस" = "हो गया <नाम>" ("हो गया जी <नाम>" नहीं)
        val b = bare(userName)
        return ADDRESS.replace(text) { m -> if (openerBefore(text, m.range.first)) c else b }.replace("जी जी ", "जी ")
    }

    /** नाम बिना "जी" ("जी धीरज" -> "धीरज") */
    fun bare(userName: String): String = userName.trim().removePrefix("जी ").trim()

    /** इनके ठीक बाद "जी <नाम>" अच्छा लगता है ("हाँ जी सर", "ठीक है जी सर") */
    private val OPENERS = Regex("(?i)(^|\\s)(हाँ|हां|जी|ठीक है|अच्छा|ओके|ok|okay)[,!]?$")
    private fun openerBefore(text: String, at: Int): Boolean {
        val before = text.substring(0, at).trimEnd().trimEnd(',').trimEnd()
        return before.isEmpty() || before.last() in "।.!?\n" || OPENERS.containsMatchIn(before)
    }

    /**
     * 22 नवंबर 2026 (मालिक: "बात के बीच / आख़िर में 'जी बॉस' अच्छा नहीं लगता, शुरुआत में ठीक; 'हेलो जी boss' नहीं, 'हेलो बॉस'"):
     * AI के लिखे जवाब में संबोधन की जगह ठीक करो - prompt ([placement]) के ऊपर पक्की जाँच.
     *  - "हेलो / हाय / नमस्ते जी <नाम>" -> "हेलो <नाम>"
     *  - वाक्य के आख़िर में "जी <नाम>" -> पूरे जवाब ([stream] = false) के पहले वाक्य में शुरुआत में ("जी <नाम>, …"),
     *    बाकी वाक्यों / बहते जवाब ([stream] = true, आधा बोला जा चुका) में हटाओ
     *  - "हाँ जी <नाम>" / "ठीक है जी <नाम>" / वाक्य की शुरुआत = वैसा ही
     * [names] = जिन शब्दों से यूज़र को बुलाते हैं (नाम, बॉस / सर, स्वभाव वाले). खाली = कुछ नहीं बदलता.
     */
    fun tidy(text: String, names: Collection<String>, stream: Boolean = false): String {
        val ns = names.map { bare(it) }.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
        if (ns.isEmpty() || !text.contains("जी")) return text
        val alt = ns.joinToString("|") { Regex.escape(it) }
        val greet = Regex("(?i)(हेलो|हैलो|हाय|नमस्ते|hello|hi)\\s+जी\\s+($alt)(?![\\p{L}\\p{M}])")
        var t = greet.replace(text) { m -> m.groupValues[1] + " " + m.groupValues[2] }
        // वाक्य के आख़िर का ", जी <नाम>" (पीछे कुछ असली बात हो)
        val tail = Regex("(?i)([^।.!?\\n]*?[\\p{L}\\p{M}\\p{N}][^।.!?\\n]*?)[,\\s]+जी\\s+($alt)(?![\\p{L}\\p{M}])\\s*([।.!?…]+|$)")
        var first = true
        t = tail.replace(t) { m ->
            val body = m.groupValues[1]; val who = m.groupValues[2]; val end = m.groupValues[3]
            val lead = body.takeWhile { it.isWhitespace() }; val core = body.trim()
            val isFirst = first && t.substring(0, m.range.first).isBlank()
            first = false
            when {
                OPENERS.containsMatchIn(core) && core.split(Regex("\\s+")).size <= 2 -> m.value      // "हाँ जी सर" / "ठीक है जी सर"
                !stream && isFirst -> "${lead}जी $who, $core$end"
                else -> "$lead$core$end"
            }
        }
        return t
    }

    /** [tidy] के लिए: यूज़र को किन शब्दों से बुलाते हैं (सहेजा नाम, अभी का स्वभाव वाला शब्द, बॉस / सर…) */
    fun names(userName: String, persona: String = try { personaCall() } catch (_: Exception) { "" }): List<String> =
        listOf(userName, persona) + TITLES

    /** prompt के बिल्कुल आख़िर में (स्वभाव / JARVIS / काम वाले उदाहरणों के बाद भी यही चले) */
    fun placement(userName: String): String {
        val b = bare(userName).ifBlank { "बॉस" }
        return "\n\nHOW TO ADDRESS THE USER (owner's newest rule - final, overrides every example above): put his name / बॉस / सर ONLY at the START of a sentence " +
            "(\"$b, मैं अभी कर रहा हूँ\", \"जी $b, हो गया\", \"हेलो $b\"). NEVER in the middle or at the END of a sentence - wrong: \"मैं यह कर रहा हूँ जी $b\", " +
            "\"हो गया जी $b\", \"और कुछ करना है $b?\" (say \"$b, और कुछ करना है?\"). After हेलो / हाय / नमस्ते never add \"जी\" (\"हेलो $b\", not \"हेलो जी $b\"). " +
            "\"जी $b\" alone is only the reply when he calls you. Use the name at most once per reply, only where it sounds natural."
    }

    /** 15 नवंबर 2026: अभी के स्वभाव का पहला संबोधन ("बाबू" / "सर" / "बॉस"); "" = पुराना "जी <नाम>". DKApp जोड़ता है, JVM test में "" */
    @Volatile var personaCall: () -> String = { "" }
}
