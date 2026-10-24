package com.dk.assistant

/**
 * v3.53: फ़ोन से निकली आवाज़ (वीडियो / गाना / रील) को DK "यूज़र की बात" न समझे.
 *
 * पहले की वजह: DK लाइव में DK चुप हो तो "यूज़र बोल रहा है" सिर्फ़ आवाज़ की तेज़ी (TurnDetector) से तय होता था.
 * वीडियो की आवाज़ भी तेज़ और बोलने जैसी उतार-चढ़ाव वाली होती है -> दरवाज़ा खुलता, वही आवाज़ सीधे Gemini को जाती
 * और DK वीडियो की बातों का जवाब देने लगता (ऊपर से उसी पर वीडियो धीमा भी हो जाता था).
 * पुराने wake-word रास्ते में यह नियम पहले से था ("गाना/वीडियो चल रहा हो तो सिर्फ़ नाम के साथ बोली बात") - लाइव में नहीं था.
 *
 * अब: किसी दूसरे ऐप की आवाज़ बज रही हो (और DK से बात चालू न हो) तो आवाज़ पहले सिर्फ़ लिखने वाले (caption) को जाती है.
 * उसमें DK का नाम ("डीके / DK …") आया = यूज़र है -> रुकी हुई आवाज़ Gemini को, बात आगे सामान्य.
 * नाम नहीं आया = फ़ोन की अपनी आवाज़ -> छोड़ दो (न जवाब, न वीडियो धीमा, न स्क्रीन पर लिखा).
 * (जैसे पुराने रास्ते में: वीडियो/गाना चलते समय हर बात DK के नाम से. "खुली खिड़की" जानबूझकर नहीं - DK ने खुद गाना चलाया
 * तो उसी खिड़की में गाने की आवाज़ "बात" बनकर फिर जवाब -> फिर खिड़की, ऐसा चक्र बन जाता.)
 * गाना/वीडियो बंद या रुका = पहले जैसा, बिना नाम सब सुनता है. DK बोल रहा हो तब का टोकना (barge) पहले जैसा.
 */
object PhoneAudioGuard {
    /** बात खत्म होने के बाद caption के शब्द इतनी देर तक आ सकते हैं, फिर छोड़ दो */
    const val LATE_TEXT_MS = 1_500L
    /** DK की अपनी आवाज़ थमने के बाद इतनी देर तक "गाना बज रहा" की जाँच भरोसे लायक नहीं */
    const val SELF_TAIL_MS = 1_500L
    /** रुकी आवाज़ ज़्यादा से ज़्यादा (40ms x 250 = 10s) */
    const val MAX_HOLD_CHUNKS = 250

    /** गार्ड चालू? [othersMedia] = फ़ोन पर media आवाज़ बज रही है, [selfQuietMs] = DK की अपनी आवाज़ थमे कितनी देर हुई */
    fun active(othersMedia: Boolean, selfQuietMs: Long): Boolean = othersMedia && selfQuietMs > SELF_TAIL_MS

    /** लिखे शब्दों में DK का नाम है? (छोटे-बड़े अक्षर, "डी के", "D.K." सब) */
    fun hasName(text: String, wakeWords: List<String>): Boolean {
        val t = norm(text)
        if (t.isBlank()) return false
        return wakeWords.map { norm(it) }.filter { it.isNotBlank() }.any { w ->
            // अंग्रेज़ी का छोटा नाम ("dk") किसी शब्द के अंदर न मिले (जैसे "pdk") - अलग शब्द हो
            if (w.all { it in 'a'..'z' || it == ' ' }) Regex("(^|[^a-z])" + Regex.escape(w) + "($|[^a-z])").containsMatchIn(t)
            else t.contains(w)
        }
    }

    /** रुकी बात के शब्द आने का समय निकल गया? */
    fun expired(endAt: Long, now: Long): Boolean = endAt > 0 && now - endAt > LATE_TEXT_MS

    private fun norm(s: String) = s.lowercase().replace('.', ' ').replace(Regex("\\s+"), " ").trim()
}
