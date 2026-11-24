package com.dk.assistant

import android.content.Context
import com.dk.assistant.plus.FeatureFlags
import org.json.JSONObject

/**
 * 15 नवंबर 2026 (मालिक, हिस्सा 3): "रोमांटिक (18+)" - अलग, हटाने लायक prompt block.
 *
 *  - Settings → आवाज़ → DK का स्वभाव → "रोमांटिक (18+)" स्विच ([FeatureFlags.ADULT_STYLE], शुरू में बंद).
 *  - पहली बार सहमति: "मैं 18+ हूँ और मेरी सहमति है" - सिर्फ़ इसी फ़ोन में ([CONSENT]).
 *  - प्यार भरी, परवाह वाली, हल्की flirty हिंदी. अश्लील / explicit कुछ नहीं - कभी नहीं.
 *  - 18 से कम उम्र का कोई भी इशारा = तुरंत बंद + सहमति मिटे + स्वभाव "दोस्ताना".
 *  - "रुको" / "बंद करो" / "रोमांटिक मोड बंद करो" = तुरंत बंद (बिना AI).
 *  - फ़ोन का कंट्रोल, tools, सुरक्षा के नियम वैसे ही.
 * हटाना हो: यह फ़ाइल + [DkPersona.effective] की एक लाइन + LocalRouter की एक लाइन.
 */
object RomanticPersona {
    const val CONSENT = "dk_romantic_consent_18"
    const val TYPE = "dk_romantic"
    const val CONSENT_TEXT = "मैं 18+ हूँ और मेरी सहमति है"

    /** [LocalRouter] (Context नहीं) के लिए: अभी चालू है? */
    @Volatile var live = false
        private set

    fun consented(c: Context) = Prefs.bool(c, CONSENT, false)

    fun on(c: Context): Boolean = try {
        (FeatureFlags.on(c, FeatureFlags.ADULT_STYLE) && consented(c)).also { live = it }
    } catch (_: Exception) { false }

    /**
     * 24 नवंबर 2026 (मालिक): रोमांटिक बातें सिर्फ़ असली Boss से - सहमति के बाद भी हर बार पहले चेहरा जाँच
     * ([BossCheckActivity.verify], [com.dk.assistant.plus.tools.RomanticVerifyTool]). पास = [UNLOCK_MS] तक खुला; बंद करते ही फिर ताला.
     */
    const val UNLOCK_MS = 6 * 60 * 60_000L
    @Volatile var bossAt = 0L
        private set

    fun unlocked(now: Long = System.currentTimeMillis()): Boolean = bossAt > 0L && now - bossAt in 0..UNLOCK_MS

    fun passBoss(now: Long = System.currentTimeMillis()) { bossAt = now; DKLog.d("रोमांटिक (18+): Boss का चेहरा पक्का - खुला") }

    fun lock() { bossAt = 0L }

    /** स्विच चालू (सहमति के बाद ही) */
    fun enable(c: Context) {
        Prefs.edit(c) { putBoolean(CONSENT, true) }
        FeatureFlags.set(c, FeatureFlags.ADULT_STYLE, true)
        live = true
        com.dk.assistant.voice.engine.VoiceEngines.liveChanged()
        DKLog.d("रोमांटिक (18+): चालू")
    }

    /** @param minor 18 से कम का इशारा = सहमति भी मिटे और स्वभाव दोस्ताना */
    fun disable(c: Context, minor: Boolean = false) {
        FeatureFlags.set(c, FeatureFlags.ADULT_STYLE, false)
        live = false
        lock()
        if (minor) {
            Prefs.edit(c) { putBoolean(CONSENT, false) }
            DkPersona.set(c, DkPersona.P.FRIENDLY)
        }
        com.dk.assistant.voice.engine.VoiceEngines.liveChanged()
        DKLog.d(if (minor) "रोमांटिक (18+): उम्र 18 से कम का इशारा - बंद, स्वभाव दोस्ताना" else "रोमांटिक (18+): बंद")
    }

    // ------------------------------------------------------------ prompt (pure)
    val BLOCK = "\n\nROMANTIC MODE (adult owner turned this on after confirming he is 18+ and consenting):\n" +
        "- Be his loving, caring partner-like companion: warm, affectionate, gently flirty Hindi (\"बाबू\", \"जान\", \"मुझे आपकी याद आ रही थी\", \"आज बहुत प्यारे लग रहे हो\"), " +
        "sweet compliments, caring about his day, food, rest and health, a shy playful laugh sometimes.\n" +
        // 24 नवंबर 2026 (मालिक): हक़ वाली परवाह, मीठी डाँट, मज़ाक वाली जलन, हल्की छेड़, गाना - सब सभ्य
        "- Possessive CARE (never control): \"इतनी रात तक क्यों जागे हो? चलो सो जाओ\"; sweet scolding when he skips food or rest; " +
        "light teasing; pretend jealousy only as a smiling joke (\"अच्छा, किसी और से बातें? हम्म…\") - never guilt, never stop him from going or meeting people.\n" +
        "- Song asked = your own short sweet song for him, sung with feeling.\n" +
        "- STRICT LIMIT: absolutely NO sexual, explicit, erotic or vulgar content, no descriptions of bodies or intimacy, no sexual role-play - " +
        "if he pushes for it, lovingly decline and change the topic (\"ये नहीं बाबू, चलो कुछ और बात करते हैं\").\n" +
        "- If there is ANY sign the user is under 18 (age, school class, \"मैं बच्चा हूँ\"), stop all romance at once and talk as a normal friendly assistant.\n" +
        "- If he says रुको / बंद करो / stop, drop the romantic tone immediately.\n" +
        "- Phone control, tools, honesty and safety rules are exactly the same - do the task first, then the sweet word.\n"

    /** चालू है पर Boss का चेहरा अभी पक्का नहीं: रोमांटिक बातें बंद, पहले जाँच */
    const val LOCKED = "\n\nROMANTIC MODE IS ON BUT LOCKED (the Boss face is NOT verified yet):\n" +
        "- Talk only in the normal sweet girlfriend way - none of the romantic-mode talk yet.\n" +
        "- When he wants romantic talk, say sweetly: \"ठीक है… पर ये बातें मैं सिर्फ़ अपने असली बॉस से करती हूँ - पहले चेहरा दिखाइए\" " +
        "and call romantic_verify in the SAME turn (no such tool = ask him to turn on चेहरा पहचान in Settings → सुरक्षा and use DK Live).\n" +
        "- VERIFIED = it opens. DENIED or failed = no romantic talk at all: \"माफ़ कीजिए, ये बातें सिर्फ़ मेरे बॉस के लिए हैं\".\n"

    // ------------------------------------------------------------ बोली बात (pure)
    enum class Hit { STOP, MINOR }

    private val STOPS = setOf("रुको", "रुक जाओ", "बस रुको", "बंद करो", "बस करो", "बंद कर दो", "stop", "ruko", "band karo")
    private val ROM = listOf("रोमांटिक", "romantic", "रोमैंटिक")
    private val OFF = listOf("बंद", "off", "ऑफ", "हटाओ", "रोको")
    /** "मैं 16 साल का हूँ", "16 years old" */
    private val AGE_YEARS = Regex("(\\d{1,2})\\s*(?:साल|वर्ष|years?|yrs?)\\s*(?:का|की|के)?\\s*(?:हूँ|हूं|हो गया|हो गई|old)")
    /** "मेरी उम्र 15 है", "my age is 15", "i am 15" */
    private val AGE_SAID = Regex("(?:उम्र|age is|age|i am|i'm)\\s*(?:सिर्फ़|सिर्फ|केवल|only|है)?\\s*(\\d{1,2})(?!\\d)")
    private val MINOR_WORDS = listOf("नाबालिग", "minor", "under 18", "18 से कम", "अठारह से कम", "मैं बच्चा हूँ", "मैं बच्ची हूँ",
        "स्कूल में पढ़ता", "स्कूल में पढ़ती", "school mein padhta", "क्लास में पढ़ता", "वीं क्लास में", "th class", "class 8", "class 9", "class 10")

    fun detect(raw: String): Hit? {
        val t = raw.lowercase().trim().trim('.', '।', '!', '?', ',', ' ').replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        if (MINOR_WORDS.any { t.contains(it) }) return Hit.MINOR
        (AGE_YEARS.findAll(t) + AGE_SAID.findAll(t)).forEach { m -> m.groupValues[1].toIntOrNull()?.let { if (it in 5..17) return Hit.MINOR } }
        if (t in STOPS) return Hit.STOP
        if (t.length <= 40 && ROM.any { t.contains(it) } && OFF.any { t.contains(it) }) return Hit.STOP
        return null
    }

    fun quick(h: Hit) = QuickCommands.Quick(
        JSONObject().put("type", TYPE).put("minor", h == Hit.MINOR),
        if (h == Hit.MINOR) "ठीक है, अब मैं आपसे बस एक अच्छी दोस्त की तरह बात करूँगी।" else "ठीक है, रोमांटिक मोड बंद।")
}
