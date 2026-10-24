package com.dk.assistant

/**
 * DK AI ब्रांड - नाम, टैगलाइन और मालिक/एडमिन एक ही जगह (DK AI ब्रीफ़ Part 02).
 * मालिक का नाम हर जगह (About, Profile, licensing संदेश, "तुम्हें किसने बनाया?") इसी से आता है.
 * Android import नहीं -> JVM test.
 */
object AssistantBrand {
    const val NAME = "DK"
    const val FULL_NAME = "DK AI"
    const val TAGLINE = "Your Intelligent AI Companion"
    const val OWNER_NAME = "धीरज कुमार सिंह"

    /** "तुम्हें किसने बनाया?" का तय जवाब (पहले जैसा ही - बस नाम अब OWNER_NAME से) */
    const val CREATOR_ANSWER = "मुझे $OWNER_NAME जी ने बनाया है। मैं धीरज सर के लिए काम करता हूँ, वही मेरे बॉस हैं।"

    /** About स्क्रीन के लिए */
    const val CREATED_BY = "Created by $OWNER_NAME"

    /** licensing (Part 13) में एडमिन वाला संदेश */
    fun blockedByAdmin(): String = "blocked by admin $OWNER_NAME"
}
