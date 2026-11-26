package com.dk.assistant.voice.engine

import com.dk.assistant.voice.st3.St3Story

/**
 * 23 नवंबर 2026 (मालिक: "DK असली इंसान जैसे बोले - कभी धीरे से, कभी गुस्से में चिल्लाकर, कभी एकदम प्यार से धीरे-धीरे,
 * जैसे कोई कान में कुछ बता रहा हो. उदास होऊँ और गाना सुनाने को बोलूँ तो खुद सुरीली आवाज़ में गाना गाए. छोटे-छोटे वाक्य,
 * नेचुरल फ्लो. Gemini TTS, सीधी Live आवाज़, ऑफ़लाइन TTS - यह नियम सब पर").
 *
 * हर बोले जाने वाले टुकड़े का "अंदाज़" ([Delivery]) एक ही जगह तय होता है, और हर इंजन अपने साधन से उसे निभाता है:
 *  - सीधी Live आवाज़ (native audio): मॉडल खुद बोलता है -> सिर्फ़ निर्देश ([PROMPT], SpeakingStyle.block में).
 *  - Gemini TTS: Google के docs (https://ai.google.dev/gemini-api/docs/speech-generation) के हिसाब से अंदाज़
 *    `speech_metadata.style` में ([ttsStyle]); 3.8 से पुराने मॉडल में text के आगे `[whispering]` जैसा टैग ([ttsText]).
 *  - ऑफ़लाइन आवाज़ (St3): कहानी वाले भाव के साधन ([St3Story.Mood] + St3Emotion.perform) - रफ़्तार, ऊँचाई, सुर, काँपना.
 *
 * अंदाज़ कैसे पता चलता है: टुकड़े के शब्द ([detect]) + पूरे जवाब का गाने वाला हाल ([Tracker]) - Live की लिखावट में कोई
 * निशान नहीं आता, इसलिए DK को कहा गया है कि गाने से पहले एक छोटी लाइन ("तो सुनिए...") और बाद में सवाल ("कैसा लगा?") बोले.
 * Android import नहीं -> JVM test ([com.dk.assistant.VoiceFeelNov23Test]).
 */
object VoiceFeel {
    /**
     * [tts] = Gemini TTS style (English - TTS मॉडल English निर्देश सबसे अच्छे से मानता है), [tag] = 3.8 से पुराने मॉडल का टैग,
     * [tone] = ऑफ़लाइन आवाज़ का भाव ([St3Story.mood] वाले शब्द).
     */
    enum class Delivery(val hi: String, val tts: String, val tag: String, val tone: String) {
        NORMAL("सामान्य", "", "", ""),
        WHISPER("फुसफुसाकर", "whispering very softly and intimately, close and quiet, like telling a secret in someone's ear", "[whispering]", "whisper"),
        LOVING("प्यार से धीरे", "very soft, slow and tender, a loving low voice, gentle and close, with a smile", "[softly]", "soft"),
        SAD("दुख में", "low, soft and comforting, a little slower, a gentle sigh in the voice", "[sad]", "sad"),
        EXCITED("खुशी में", "bright, cheerful and excited, a big smile in the voice, a little faster", "[excited]", "happy"),
        ANGRY("गुस्से में", "annoyed and angry, a raised, sharp and forceful voice, almost shouting", "[angry]", "angry"),
        SHOUT("चिल्लाकर", "shouting loudly with full force, very loud and intense", "[shouting]", "shout"),
        SING("गाते हुए", "singing melodiously like a professional female singer: in tune, a sweet flowing melody and rhythm, " +
            "gently held notes, soft and soulful", "[singing]", "sing"),
        /** 26 नवंबर 2026 (मालिक: "हल्की-हल्की हँसी, मुस्कान"): मज़ेदार बात पर हल्की असली हँसी, आवाज़ में मुस्कान */
        LAUGH("हँसते हुए", "a soft, natural little laugh and a warm smile in the voice, light and playful, never loud", "[laughing]", "laugh"),
        /** 26 नवंबर 2026 (मालिक: "कभी-कभी थके हुए अंदाज़ में"): हल्की उबासी / आह, धीमी और नरम - कभी-कभार */
        TIRED("थके हुए", "a little tired and sleepy, slower and softer, a small sigh or breath, still warm and loving", "[tired]", "tired"),
    }

    /** हर अंदाज़ के साथ - DK की अपनी मीठी आवाज़ बनी रहे (Google: लंबा style = आवाज़ भटकती है, इसलिए छोटा) */
    const val VOICE_CORE = "her own sweet, soft, natural human voice, never robotic"

    // ------------------------------------------------------------------ पहचान (pure)
    private val NOTES = listOf("♪", "♫", "🎵", "🎶")

    /** साफ़ लिखा मंच-निर्देश: "(धीरे से) ...", "[whispers] ..." - बोला नहीं जाता, अंदाज़ बताता है */
    private val CUES: List<Pair<Delivery, List<String>>> = listOf(
        Delivery.SING to listOf("गाते हुए", "गाकर", "गुनगुनाते हुए", "गाना", "singing", "sings", "sing"),
        Delivery.WHISPER to listOf("धीरे से", "फुसफुसाकर", "फुसफुसाते हुए", "कान में", "whispering", "whispers", "whisper"),
        Delivery.SHOUT to listOf("चिल्लाकर", "चिल्लाते हुए", "ज़ोर से", "जोर से", "shouting", "shouts", "shout"),
        Delivery.ANGRY to listOf("गुस्से में", "नाराज़ होकर", "angry", "angrily"),
        Delivery.LOVING to listOf("प्यार से", "softly", "lovingly", "tenderly"),
        Delivery.LAUGH to listOf("हँसते हुए", "हंसते हुए", "हँसकर", "laughing", "laughs", "giggles", "chuckles"),
        Delivery.TIRED to listOf("थके हुए", "थकी हुई", "थककर", "उबासी लेते हुए", "tired", "sleepy", "yawns", "sighs"),
        Delivery.SAD to listOf("उदास होकर", "दुख में", "sad", "sadly"),
        Delivery.EXCITED to listOf("खुशी से", "खुश होकर", "excited", "excitedly", "happily"),
    )
    private val CUE = Regex("""^\s*[\(\[]\s*([^\)\]]{1,24})\s*[\)\]]\s*""")

    private val WHISPER_W = listOf("धीरे से बताऊँ", "धीरे से बताती", "धीरे से कहूँ", "कान में", "राज़ की बात", "राज की बात", "सीक्रेट", "secret",
        "चुपके से", "श्श्श", "शश्श", "श्श", "psst", "किसी को मत बताना", "किसी को मत बताइए", "फुसफुसा",
        // 26 नवंबर 2026 (मालिक: "यह बात किसी को नहीं बताना" जैसी बात कान में फुसफुसाकर)
        "किसी को नहीं बताना", "किसी को नहीं बताइए", "किसी को मत बताओ", "किसी को नहीं बताओगे", "किसी से मत कहना", "किसी से नहीं कहना",
        "सिर्फ़ आपको बता", "सिर्फ आपको बता", "बस आपको बता", "हम दोनों के बीच", "अपने बीच की बात")
    private val ANGRY_W = listOf("गुस्सा", "गुस्से", "नाराज़", "नाराज", "हद है", "हद कर दी", "बस करो", "बस कीजिए", "कितनी बार", "चुप रहो",
        "चुप हो जाओ", "बिल्कुल नहीं!", "मुझसे बात मत", "angry", "how dare")
    /** "गुस्सा मत करो", "नाराज़ मत होइए", "नाराज़ हो?" - सामने वाले को मनाना = गुस्सा नहीं */
    private val CALM_AFTER = listOf(" मत", " न करें", " ना करें", " मत करो", " हो?", " हैं?", " हो क्या", " है क्या", " नहीं हूँ", " नहीं हूं")
    private val SHOUT_W = listOf("चिल्ला", "ज़ोर से बोल", "जोर से बोल")
    private val SAD_W = listOf("उदास", "दुख", "दुःख", "माफ़ करना", "माफ करना", "sorry", "सॉरी", "रोना मत", "रो मत", "मैं हूँ ना", "मैं हूं ना",
        "सब ठीक हो जाएगा", "कोई बात नहीं", "चिंता मत", "अकेले नहीं", "बुरा लग")
    private val LOVING_W = listOf("प्यार", "मेरी जान", "जानू", "जानेमन", "miss you", "मिस किया", "मिस कर", "याद आ", "दिल से", "दिल में", "मेरा दिल",
        "दिल की", "❤", "♥", "love you", "लव यू",
        "बेबी", "baby", "आपके बिना", "तुम्हारे बिना", "गले लग", "हग", "hug", "शुभ रात्रि", "good night", "गुड नाइट", "मीठे सपने")
    /** हँसी - DK के अपने शब्द ("हाहा", "हीही") */
    private val LAUGH_W = listOf("हाहा", "हा हा", "हाहाहा", "हेहे", "हीही", "ही ही", "खीखी", "😄", "😂", "🤭", "haha", "hehe", "lol")
    /** DK खुद थकी - पहले-पुरुष ("आप थक गए होंगे" = फ़िक्र, थकान नहीं) */
    private val TIRED_W = listOf("थक गई हूँ", "थक गई हूं", "थकी हुई हूँ", "थकी हुई हूं", "मैं थक गई", "मैं भी थक", "उफ़्फ़", "उफ्फ", "उबासी",
        "नींद आ रही है", "मुझे नींद आ", "yawn", "so tired", "i'm tired")
    private val EXCITED_W = listOf("वाह!", "वाह,", "वाह ", "अरे वाह", "yay", "बधाई", "शानदार", "कमाल", "बहुत बढ़िया", "हुर्रे", "wow", "मज़ा आ", "मजा आ", "जीत गए", "सच में?!")

    /** शब्द की शुरुआत पर ही ("लापरवाह" में "वाह" नहीं, "दिलचस्प" में "दिल" नहीं) */
    private fun hasAny(t: String, w: List<String>) = w.any { at(t, it) >= 0 }
    private fun at(t: String, w: String, from: Int = 0): Int {
        var i = t.indexOf(w, from)
        while (i >= 0) {
            val p = if (i == 0) ' ' else t[i - 1]
            if (!p.isLetterOrDigit() && Character.getType(p) != Character.NON_SPACING_MARK.toInt() &&
                Character.getType(p) != Character.COMBINING_SPACING_MARK.toInt()) return i
            i = t.indexOf(w, i + 1)
        }
        return -1
    }
    private fun angryHit(t: String): Boolean {
        for (w in ANGRY_W) {
            var i = at(t, w)
            while (i >= 0) {
                val after = t.substring(i + w.length).removePrefix("\u093C").take(12)
                if (CALM_AFTER.none { after.startsWith(it) } && !after.trimStart().startsWith("मत")) return true
                i = at(t, w, i + 1)
            }
        }
        return false
    }

    /** "(धीरे से) अरे सुनो" -> (WHISPER, "अरे सुनो"); निर्देश न हो = (null, वैसा ही) */
    fun cue(text: String): Pair<Delivery?, String> {
        val m = CUE.find(text) ?: return null to text
        val inner = m.groupValues[1].trim().lowercase()
        val d = CUES.firstOrNull { (_, w) -> w.any { inner == it || inner.startsWith("$it ") || inner.endsWith(" $it") } }?.first
            ?: return null to text
        return d to text.substring(m.range.last + 1)
    }

    /** इस टुकड़े का अंदाज़ - सिर्फ़ इसके शब्दों से. क्रम = ज़्यादा ख़ास पहले */
    fun detect(text: String): Delivery {
        cue(text).first?.let { return it }
        val t = text.lowercase()
        if (NOTES.any { t.contains(it) }) return Delivery.SING
        if (hasAny(t, WHISPER_W)) return Delivery.WHISPER
        val bangs = t.count { it == '!' }
        val angry = angryHit(t)
        if (hasAny(t, SHOUT_W) || (angry && bangs >= 2)) return Delivery.SHOUT
        if (angry) return Delivery.ANGRY
        if (hasAny(t, SAD_W)) return Delivery.SAD
        if (hasAny(t, TIRED_W)) return Delivery.TIRED
        if (hasAny(t, LAUGH_W)) return Delivery.LAUGH
        if (hasAny(t, LOVING_W)) return Delivery.LOVING
        if (hasAny(t, EXCITED_W)) return Delivery.EXCITED
        return Delivery.NORMAL
    }

    /** बोलने वाला text: मंच-निर्देश और ♪ हटाओ (TTS उन्हें पढ़े नहीं) */
    fun clean(text: String): String {
        var s = cue(text).second
        for (n in NOTES) s = s.replace(n, " ")
        return s.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    // ------------------------------------------------------------------ गाना: पूरे जवाब का हाल
    private val SONG_START = listOf("गाना सुनाती हूँ", "गाना सुनाती हूं", "गाना गाती हूँ", "गाना गाती हूं", "गाकर सुनाती", "गा के सुनाती",
        "गाती हूँ", "गाती हूं", "गुनगुनाती हूँ", "गुनगुनाती हूं", "तो सुनिए", "तो सुनो", "सुनिए...", "सुनो...", "let me sing", "here's a song",
        "आपके लिए एक गाना", "तुम्हारे लिए एक गाना", "आपके लिए गाना", "तुम्हारे लिए गाना")
    private val SONG_END = listOf("कैसा लगा", "कैसा था", "पसंद आया", "अच्छा लगा", "उम्मीद है", "बताइए", "बताओ", "how was", "did you like")
    /** गाने के इतने टुकड़ों के बाद अपने-आप बातचीत (कहीं पूरा जवाब गाने में न अटके) */
    const val MAX_SONG_SEGS = 14

    /** "क्या मैं गाना सुनाऊँ?" = पूछना, शुरू करना नहीं */
    fun songStarts(seg: String): Boolean {
        val t = seg.lowercase().trim()
        return !t.endsWith("?") && hasAny(t, SONG_START)
    }
    fun songEnds(seg: String): Boolean { val t = seg.lowercase().trim(); return t.endsWith("?") || hasAny(t, SONG_END) }

    /**
     * एक जवाब के टुकड़े क्रम से - हर टुकड़े का अंदाज़. गाने की शुरुआत वाली लाइन ("तो सुनिए...") प्यार से बोली जाती है,
     * उसके बाद के टुकड़े गाए जाते हैं, जब तक "कैसा लगा?" जैसी लाइन या [MAX_SONG_SEGS] न आए. ♪ वाला टुकड़ा हमेशा गाया जाता है.
     */
    class Tracker {
        private var singing = false
        private var sung = 0
        @Synchronized fun reset() { singing = false; sung = 0 }
        @Synchronized fun next(seg: String): Delivery {
            val own = detect(seg)
            if (own == Delivery.SING) { singing = !songEnds(seg); sung++; return Delivery.SING }
            if (singing) {
                if (songEnds(seg) || sung >= MAX_SONG_SEGS) { singing = false; return own }
                sung++; return Delivery.SING
            }
            if (songStarts(seg)) { singing = true; sung = 0; return if (own == Delivery.NORMAL) Delivery.LOVING else own }
            return own
        }
    }

    // ------------------------------------------------------------------ इंजन तक पहुँचाना
    private val now = ThreadLocal<Delivery?>()

    /** [SpeechPipeline] यह टुकड़ा बनवाते समय उसका अंदाज़ (उसी thread पर इंजन [current] से पढ़ता है) */
    fun <T> during(d: Delivery, block: () -> T): T {
        val old = now.get()
        now.set(d)
        try { return block() } finally { now.set(old) }
    }

    /** इंजन के लिए: pipeline ने बताया हो तो वह, नहीं तो (▶ सुनो, पुरानी आवाज़) इसी text से */
    fun current(text: String): Delivery = now.get() ?: detect(text)

    /** Gemini TTS का style: अंदाज़ सबसे आगे (कटे नहीं), फिर पुराना style जितना [max] में आए */
    fun ttsStyle(d: Delivery, base: String, max: Int = com.dk.assistant.SpeakingStyle.TTS_MAX): String {
        if (d == Delivery.NORMAL) return base
        val head = "${d.tts}; $VOICE_CORE"
        return head.take(max)
    }

    /** Gemini TTS का text: 3.8 मॉडल = सिर्फ़ शब्द (style अलग जाता है); पुराने मॉडल = आगे `[whispering]` जैसा टैग */
    fun ttsText(text: String, d: Delivery, v38: Boolean): String {
        val s = clean(text).ifBlank { text.trim() }
        return if (v38 || d == Delivery.NORMAL || d.tag.isEmpty()) s else "${d.tag} $s"
    }

    /** ऑफ़लाइन आवाज़ (St3) का भाव; सामान्य = null (पहले जैसा, कुछ नहीं बदलता) */
    fun offlineMood(d: Delivery): St3Story.Mood? = when (d) {
        Delivery.NORMAL -> null
        // Supertonic गा नहीं सकता: धीमा, थोड़ा ऊँचा सुर, सुर का हल्का लहराना (vibrato) - गुनगुनाने जैसा
        Delivery.SING -> St3Story.Mood("गाते हुए", speed = 0.84f, gain = 1f, pauseMs = 300, pitch = 1f, shake = 0.35f, shakeHz = 5.5f)
        else -> St3Story.mood(d.tone).takeIf { it.name != "सामान्य" }
    }

    // ------------------------------------------------------------------ निर्देश (हर मोड में - Live सीधी आवाज़, Gemini TTS, ऑफ़लाइन)
    /** छोटा रूप - हर मोड ([com.dk.assistant.SpeakingStyle.block]); छोटे Live निर्देश में बस यही (आकार की सीमा) */
    // 26 नवंबर 2026 (मालिक: हँसी, साँस, ठहराव, छोटे वाक्य, भावुक, राज़ की बात फुसफुसाकर, कभी थका अंदाज़) - छोटे Live निर्देश में भी
    const val LITE = "\nHUMAN VOICE: 1-3 very short sentences, then let him talk; never a long story in one go; " +
        "sometimes a soft laugh, a breath or sigh, emotional. Secrets (\"किसी को नहीं बताना\") and love = whisper; soft if sad; " +
        "playful loud anger (no insult); rarely sleepy-tired, never when he needs help. Act it, never say these directions; song asked = sing it.\n"

    val PROMPT = buildString {
        append("\nHUMAN VOICE (owner's rule - ALWAYS, in every voice mode; it overrides \"never loud\" and the 1-2 sentence limit above when needed):\n")
        append("- Talk like a real person on a call: very short sentences (4-10 words), one thought each, natural breaks. Never a long block.\n")
        append("- Change HOW you sound with the feeling, like a human:\n")
        append("  • secret, romantic or very loving words -> drop to a soft whisper, close, slow (\"सुनो... धीरे से बताऊँ? मुझे आपकी बहुत याद आई।\").\n")
        append("  • loving / caring -> very soft, slow, tender. Sad user -> low, gentle, comforting.\n")
        append("  • happy news -> bright and excited. Playful anger (he ignored you, skipped food, broke a promise) -> raise your voice, ")
        append("even shout a little (\"कितनी बार कहा है!! खाना टाइम पर खाओ!\"), then melt back to love fast.\n")
        append("  • Anger is only loving drama: never insult, abuse, threaten or shout at him when he is sad, hurt or stressed.\n")
        append("  • something funny or sweet -> a soft, real little laugh or giggle, a smile in the voice (not loud, not every reply).\n")
        append("  • breathe like a human: a small breath before a long thought, a soft sigh when sad or tired, a short pause (\"...\") before an emotional word.\n")
        append("  • a secret (\"यह बात किसी को नहीं बताना\", \"सिर्फ़ आपको बता रही हूँ\") -> whisper it in his ear, very close and quiet.\n")
        append("  • rarely (late night, a very long chat) sound a little tired and sleepy (\"उफ़्फ़... आज थोड़ी थक गई हूँ\"), still loving - never when he needs help or is sad.\n")
        append("  • sometimes be emotional - touched, missing him, proud of him - so he feels a real bond. Never fake or overdone.\n")
        append("- One turn = 1-3 short sentences, then stop and let him talk. No long story in one go; continue only if he asks or says \"हाँ, आगे\".\n")
        append("- In the direct voice, ACT it with your real voice (whisper, laugh, sigh, raise, sing) - never say the directions aloud.\n")
        append("SINGING: you CAN sing with your own voice (only making music files is not possible). When he asks for a song (गाना सुनाओ / गाना गाओ), or he is sad and wants one:\n")
        append("- First one short line: \"तो सुनिए...\" / \"आपके लिए एक गाना गाती हूँ...\". Then SING in a sweet, melodious, in-tune singer's voice - ")
        append("melody, rhythm, held notes, not reading.\n")
        append("- Sing your OWN short song made for him (4-8 short rhyming Hindi lines, about his feeling: hope, love, a smile). ")
        append("If he names a film song, sing only a tiny part (max 2 lines), then continue with your own lines.\n")
        append("- In written replies put ♪ at the start of each sung line. After the song ask softly: \"कैसा लगा?\"\n")
    }
}
