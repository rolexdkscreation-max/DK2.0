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
            append("- The user's name is \"$u\". Address and acknowledge him/her as \"${call(u)}\" (e.g. \"${call(u)}, बताइए\", \"हाँ ${call(u)}\", ")
            append("\"ठीक है ${call(u)}, कर रहा हूँ\", \"हो गया ${call(u)}\"). ");
            if (!isTitle(u)) append("Never say \"बॉस\" or \"boss\" to the user; ")
            append("wherever an example in these instructions says बॉस / सर, use \"${call(u)}\" instead. Not in every sentence - only where a name fits naturally.\n")
            append("- GREETING (owner's rule, 15 Nov 2026): when the user greets you (\"हेलो DK\", \"हाय\", \"नमस्ते\", \"good morning\"), greet back with the saved name: ")
            append("\"${greet(u)}\" / \"${call(u)}, बताइए\". Never say \"जी जी\".\n")
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
        return ADDRESS.replace(text, c).replace("जी जी ", "जी ")
    }

    /** 15 नवंबर 2026: अभी के स्वभाव का पहला संबोधन ("बाबू" / "सर" / "बॉस"); "" = पुराना "जी <नाम>". DKApp जोड़ता है, JVM test में "" */
    @Volatile var personaCall: () -> String = { "" }
}
