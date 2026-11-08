package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "जवाब लिखा आता है पर आवाज़ नहीं आती / बीच में कट जाती"): Live की आवाज़ बजाने के नियम.
 *
 * जड़ (GeminiLivePlayer की जाँच में मिली):
 *  1. AudioTrack.write() गलती लौटाए (जैसे ERROR_DEAD_OBJECT = Bluetooth / कॉल / रास्ता बदलते ही track मर गया) -> टुकड़ा चुपचाप
 *     छूट जाता था, और उसके बाद हर टुकड़ा भी (track वही मरा हुआ) = text आता रहा, आवाज़ गायब. अब: track दोबारा बनाओ, वही टुकड़ा फिर लिखो.
 *  2. बजाने वाले thread में कोई भी exception (JARVIS परत / गति-पिच / लिप-सिंक) = thread खत्म, कतार भरती रही, आगे कुछ नहीं बजा.
 *     अब: गलती लिखो, अगला टुकड़ा. thread मरा मिले तो नया.
 *  3. आवाज़ का रास्ता बदलते (Bluetooth / कॉल) पूरा player दोबारा शुरू होता था, जो कतार भी खाली कर देता था = चलते जवाब का बचा हिस्सा गायब.
 *     अब: सिर्फ़ track बदलता है, कतार वही रहती है.
 *  4. track बना ही नहीं (STATE_UNINITIALIZED) तब भी play() = IllegalStateException. अब: पहले जाँच, न बने तो मीडिया वाले तरीके से दोबारा.
 *
 * यहाँ सिर्फ़ फ़ैसले (Android नहीं) - JVM test.
 */
object LiveAudioGuard {
    const val TAG = "DK_AUDIO_DEBUG"

    // android.media.AudioTrack के गलती वाले कोड (यहाँ अंक, ताकि test में Android न चाहिए)
    const val ERROR = -1
    const val ERROR_BAD_VALUE = -2
    const val ERROR_INVALID_OPERATION = -3
    const val ERROR_DEAD_OBJECT = -6

    /** AudioTrack.STATE_INITIALIZED */
    const val STATE_INITIALIZED = 1

    /** एक टुकड़े पर ज़्यादा से ज़्यादा इतनी बार track दोबारा बनाकर लिखो (फिर छोड़ो - अटको नहीं) */
    const val MAX_REBUILDS_PER_CHUNK = 1
    /** लगातार इतनी बार 0 लिखा गया (track रुका पड़ा) = दोबारा बनाओ */
    const val MAX_ZERO_WRITES = 3

    enum class Write { OK, RETRY, REBUILD, SKIP }

    /**
     * write() के नतीजे पर क्या करें.
     * @param n write() ने क्या लौटाया
     * @param rebuilds इस टुकड़े पर कितनी बार track बन चुका
     * @param zeros लगातार कितनी बार 0
     */
    fun onWrite(n: Int, rebuilds: Int, zeros: Int): Write = when {
        n > 0 -> Write.OK
        n == 0 -> if (zeros + 1 >= MAX_ZERO_WRITES) (if (rebuilds < MAX_REBUILDS_PER_CHUNK) Write.REBUILD else Write.SKIP) else Write.RETRY
        n == ERROR_BAD_VALUE -> Write.SKIP                         // टुकड़ा ही ख़राब - track ठीक है
        rebuilds < MAX_REBUILDS_PER_CHUNK -> Write.REBUILD         // DEAD_OBJECT / INVALID_OPERATION / ERROR / exception
        else -> Write.SKIP
    }

    /** play() सिर्फ़ तब जब track सच में बना */
    fun canPlay(state: Int): Boolean = state == STATE_INITIALIZED

    /** गलती का नाम (log में) */
    fun errName(n: Int): String = when (n) {
        ERROR -> "ERROR"
        ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        0 -> "0 bytes"
        else -> "code $n"
    }

    /**
     * server के modelTurn.parts का एक हिस्सा: आवाज़ है? नहीं तो क्यों (log के लिए). null = आवाज़ है / सामान्य text / thought.
     * @param keys उस part के JSON keys
     */
    fun missingAudio(keys: Set<String>, thought: Boolean, hasText: Boolean, mime: String?, dataLen: Int): String? = when {
        thought || hasText -> null
        "inlineData" !in keys && "inline_data" !in keys -> "part में inlineData नहीं (keys: ${keys.sorted().joinToString(",").ifBlank { "-" }})"
        mime.isNullOrBlank() -> "inlineData में mimeType नहीं"
        !mime.startsWith("audio/pcm") -> "आवाज़ नहीं, $mime"
        dataLen == 0 -> "inlineData.data खाली"
        else -> null
    }

    /** "audio/pcm;rate=24000" -> 24000 (न हो = [fallback]) */
    fun rateOf(mime: String?, fallback: Int): Int =
        Regex("rate=(\\d+)").find(mime.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 8_000..48_000 } ?: fallback
}
