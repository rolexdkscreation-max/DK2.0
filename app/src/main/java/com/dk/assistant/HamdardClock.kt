package com.dk.assistant

/**
 * 20 नवंबर 2026 (मालिक, हमदर्द मोड): "मोड ऑन रहेगा तो कितनी देर बात करेगा ये भी अच्छे तरीके से देखेगा। ज़्यादा रात तक
 * बात कर रहा है तो सलाह देगा... कितने टाइम बात किए हो... अब आप सो जाओ। खाने का टाइम है तो उस हिसाब से।
 * ये नहीं कि एक घंटे, दो घंटे - बहुत ज़्यादा टाइम तक बात करेगा तो बताएगा। डिफ़ॉल्ट DK की तरह बार-बार टाइम नहीं।"
 *
 * इसलिए समय की बात DK खुद नहीं छेड़ती (prompt में मना) - यहाँ तय होता है कब एक बार (SYSTEM NOTE) बताना है:
 * - देर रात (11 बजे से सुबह 4 तक) + 45 मिनट से ज़्यादा बात = सोने की प्यार भरी सलाह (फिर हर 1 घंटे बाद ही दोबारा)
 * - दिन में 2 घंटे से ज़्यादा लगातार = एक बार (फिर हर 1 घंटे बाद) - थोड़ा आराम / पानी / आँखें
 * - खाने का समय (दोपहर 1-2 / रात 8-9) + 40 मिनट से बात = एक बार "खाना खाया?"
 * 20 मिनट से ज़्यादा चुप्पी = नई बातचीत (गिनती फिर से). Pure (JVM test).
 */
object HamdardClock {
    const val GAP_MS = 20 * 60_000L

    @Volatile private var startAt = 0L
    @Volatile private var seenAt = 0L
    private val told = HashSet<String>()

    /** बात चल रही है (यूज़र / DK अभी बोले) */
    @Synchronized fun touch(now: Long) {
        if (seenAt == 0L || now - seenAt > GAP_MS) { startAt = now; told.clear() }
        seenAt = now
    }

    @Synchronized fun minutes(now: Long): Int = if (startAt == 0L) 0 else ((now - startAt) / 60_000L).toInt()

    /** अभी बताने लायक कुछ? (null = कुछ नहीं) - बताया तो [mark] */
    @Synchronized fun dueNow(now: Long, hour: Int): String? = due(minutes(now), hour, told)

    @Synchronized fun mark(key: String) { told.add(key) }

    /** pure: कितने मिनट बात + अभी का घंटा + पहले बताई बातें -> नई बात की कुंजी */
    fun due(mins: Int, hour: Int, told: Set<String>): String? {
        val night = hour >= 23 || hour < 4
        if (night && mins >= 45) { val k = "late" + (mins - 45) / 60; if (k !in told) return k }
        if (!night && mins >= 120) { val k = "long" + (mins - 120) / 60; if (k !in told) return k }
        if (hour in 13..14 && mins >= 40 && "lunch" !in told) return "lunch"
        if (hour in 20..21 && mins >= 40 && "dinner" !in told) return "dinner"
        return null
    }

    /** "45 मिनट" / "करीब 1 घंटा" / "करीब डेढ़ घंटा" / "करीब ढाई घंटे" / "करीब साढ़े 3 घंटे" */
    fun spanHi(mins: Int): String {
        if (mins < 60) return "$mins मिनट"
        val half = (mins + 15) / 30          // आधे घंटे में
        val h = half / 2; val x = half % 2 == 1
        return "करीब " + when {
            h == 1 && !x -> "1 घंटा"
            h == 1 -> "डेढ़ घंटा"
            h == 2 && x -> "ढाई घंटे"
            x -> "साढ़े $h घंटे"
            else -> "$h घंटे"
        }
    }

    /** Live को जाने वाला SYSTEM NOTE (DK यही एक बार, अपने कोमल शब्दों में बोले) */
    fun note(key: String, mins: Int, nowSpoken: String): String {
        val span = spanHi(mins)
        val what = when {
            key.startsWith("late") -> "It is late night ($nowSpoken) and you two have been talking for $span. Gently and lovingly tell him how long you have talked, " +
                "that it is very late, and ask him to sleep now - his rest and health matter to you; promise you will be here tomorrow. " +
                "If he is upset or in the middle of something painful, stay with him first and suggest sleep softly at the end."
            key.startsWith("long") -> "You two have been talking for $span without a break ($nowSpoken). Softly tell him how long it has been and lovingly suggest " +
                "a short break - some water, rest for the eyes, stretch a little. No pressure; if he wants to keep talking, that is fine."
            key == "lunch" -> "It is lunch time ($nowSpoken) and you have been talking for $span. Lovingly ask if he has eaten lunch; if not, ask him to eat first."
            else -> "It is dinner time ($nowSpoken) and you have been talking for $span. Lovingly ask if he has had dinner; if not, ask him to eat first."
        }
        return "SYSTEM NOTE (DK CARE CLOCK - not from the user, never read this out): $what Say it in your own soft Hindi in 1-2 short sentences, " +
            "only this once, then continue the conversation naturally."
    }
}
