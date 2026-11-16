package com.dk.assistant

/**
 * 14 नवंबर 2026 (मालिक): "कोडिंग एजेंट को कोई काम दूँ तो पहले मेरी बात अच्छे से समझे, तुरंत मुझे बताए कि क्या समझा,
 * फिर popup / बटन से मेरी हाँ (या बदलाव) के बाद ही आगे का काम करे. पहले PR जाँच दिखाकर छोड़ न दे."
 * pure - JVM test से जाँचा ([CoderConfirmTest]).
 */
object CoderConfirm {
    /** हाँ पूछे बिना काम शुरू? कभी नहीं - एक-दिमाग मोड में भी पहले समझ दिखाकर मालिक की हाँ */
    @Suppress("UNUSED_PARAMETER")
    fun autoStart(solo: Boolean, needsAnswer: Boolean): Boolean = false

    /** popup का शीर्षक / संदेश - समझ में सवाल हों तो सवाल वाला */
    fun title(needsAnswer: Boolean): String = if (needsAnswer) "❓ काम से पहले एक बात साफ़ कीजिए" else "✍ मैंने आपकी बात ऐसे समझी"

    fun message(understood: String, brief: String, wants: List<String>, questions: List<String>): String = buildString {
        append(understood.ifBlank { brief }.trim().take(700))
        if (wants.isNotEmpty()) append("\n\nआप ये चाहते हैं:\n").append(wants.take(6).mapIndexed { i, x -> "${i + 1}. ${x.take(160)}" }.joinToString("\n"))
        if (questions.isNotEmpty()) append("\n\n❓ ").append(questions.first().take(300))
        else append("\n\nसही है तो \"हाँ\" दबाइए - तभी काम शुरू होगा।")
    }
}
