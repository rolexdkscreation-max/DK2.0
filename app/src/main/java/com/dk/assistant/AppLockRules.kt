package com.dk.assistant

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 21 नवंबर 2026 (मालिक): "DK में खुद का ऐप लॉक - DK खोलते समय और किसी भी ऐप पर लॉक. पैटर्न, PIN, फ़िंगरप्रिंट / फ़ेस".
 * Android import नहीं -> JVM test ([AppLockRulesTest]). यहाँ सिर्फ़ नियम:
 *  - DK का सीक्रेट PIN / पैटर्न: कभी सीधे सेव नहीं - PBKDF2 (salt के साथ) का hash ही, मिलान constant-time.
 *  - गलत कोशिशें: [FREE_TRIES] के बाद रुकना पड़े ([waitMs]) - अंदाज़े से खोलना मुश्किल.
 *  - कौन सा ऐप सामने है और कब फिर से ताला ([Session]).
 * यही सीक्रेट PIN टच मोड की चेतावनी स्क्रीन पर सायरन बंद करने में भी चलता है ([Guard.pinDisarm]).
 */
object AppLockRules {
    const val PIN_MIN = 4
    const val PIN_MAX = 12
    const val PATTERN_MIN = 4
    const val ITER = 12_000
    /** इतनी गलत कोशिशें मुफ़्त, फिर रुकना */
    const val FREE_TRIES = 5
    const val MAX_WAIT_MS = 15 * 60_000L

    /** ताला खोलने के तरीके (पहला वाला ताला खुलते ही अपने-आप सामने) */
    enum class Method(val id: String, val hi: String) {
        PIN("pin", "DK सीक्रेट PIN"),
        PATTERN("pattern", "DK पैटर्न"),
        PHONE("phone", "फ़ोन का फ़िंगरप्रिंट / फ़ेस / स्क्रीन लॉक"),
        FACE("face", "DK फ़ेस स्कैन (होलोग्राम)");

        companion object { fun of(id: String?) = values().firstOrNull { it.id == id } ?: PIN }
    }

    /** पैड कितने अंक पर ख़ुद जाँचे (0 = OK से). सेव लंबाई बेतुकी / PIN नहीं = 0 */
    fun autoLen(saved: Int, hasPin: Boolean): Int = if (hasPin && saved in PIN_MIN..PIN_MAX) saved else 0

    fun pinOk(p: String) = p.length in PIN_MIN..PIN_MAX && p.all { it in '0'..'9' }

    /** बहुत आसान PIN (1111, 1234, 4321) - मना */
    fun weakPin(p: String): Boolean {
        if (p.isEmpty()) return true
        if (p.all { it == p[0] }) return true
        val up = p.zipWithNext().all { (a, b) -> b - a == 1 }
        val down = p.zipWithNext().all { (a, b) -> a - b == 1 }
        return up || down
    }

    /** पैटर्न: 3x3 के बिंदु 0..8, कम से कम [PATTERN_MIN], कोई दोबारा नहीं */
    fun patternOk(dots: List<Int>) = dots.size >= PATTERN_MIN && dots.distinct().size == dots.size && dots.all { it in 0..8 }

    fun patternKey(dots: List<Int>) = "P:" + dots.joinToString("-")

    private fun pbkdf2(secret: String, salt: ByteArray, iter: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(secret.toCharArray(), salt, iter, 256)).encoded

    /** सेव करने लायक: "v1:iter:salt:hash" (असली PIN कहीं नहीं) */
    fun seal(secret: String, salt: ByteArray, iter: Int = ITER): String {
        val e = Base64.getEncoder()
        return "v1:$iter:${e.encodeToString(salt)}:${e.encodeToString(pbkdf2(secret, salt, iter))}"
    }

    fun check(secret: String, stored: String?): Boolean {
        if (stored.isNullOrBlank() || secret.isEmpty()) return false
        val p = stored.split(':')
        if (p.size != 4 || p[0] != "v1") return false
        return try {
            val d = Base64.getDecoder()
            val iter = p[1].toInt().coerceIn(1_000, 200_000)
            MessageDigest.isEqual(pbkdf2(secret, d.decode(p[2]), iter), d.decode(p[3]))
        } catch (_: Exception) { false }
    }

    /** इतनी गलत कोशिशों के बाद कितना रुकना (0 = अभी कोशिश करो) */
    fun waitMs(fails: Int): Long {
        if (fails < FREE_TRIES) return 0L
        val step = (fails - FREE_TRIES).coerceAtMost(5)
        return (30_000L shl step).coerceAtMost(MAX_WAIT_MS)
    }

    fun waitLabel(ms: Long): String {
        val s = ((ms + 999) / 1000).coerceAtLeast(1)
        return if (s < 60) "$s सेकंड" else "${(s + 59) / 60} मिनट"
    }

    fun dots(n: Int, total: Int = n) = "●".repeat(n) + "○".repeat((total - n).coerceAtLeast(0))

    // ------------------------------------------------------------------ कौन सा ऐप सामने
    /** फिर से ताला कब: 0 = ऐप छोड़ते ही, या इतने ms बाद, [ON_SCREEN_OFF] = सिर्फ़ स्क्रीन बंद होने पर */
    const val ON_SCREEN_OFF = Long.MAX_VALUE
    val RELOCK = listOf(0L to "ऐप छोड़ते ही", 10_000L to "10 सेकंड बाद", 60_000L to "1 मिनट बाद", ON_SCREEN_OFF to "सिर्फ़ स्क्रीन बंद होने पर")
    const val RELOCK_DEFAULT = 10_000L

    /** DK के वे पन्ने जिन पर "DK खोलते समय लॉक" लगता है (मदद वाले छोटे पन्ने - कॉल, अलार्म, स्कैन - नहीं) */
    val DK_SCREENS = setOf(
        "MainActivity", "ChatActivity", "SettingsActivity", "MemoryActivity", "ComputerActivity", "CodingActivity",
        "StoryWriterActivity", "TeacherActivity", "studio.StudioActivity", "forge.ForgeActivity",
        "template.TemplateSettingsActivity", "auto.AutoSettingsActivity", "backup.BackupActivity", "connect.ConnectorsActivity",
        "onboard.OnboardingActivity", "WatchmanActivity", "FaceWhoActivity",
    )

    /** ये सामने आएँ तो "ऐप बदला" नहीं माना जाता (ऊपर का पैनल, कीबोर्ड, शेयर / परमिशन वाले खाने) */
    private val PASS = setOf(
        "android", "com.android.systemui", "com.android.intentresolver", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller",
    )

    /**
     * accessibility event से सामने वाला ऐप.
     * @return null = गिनती में नहीं (सिस्टम / कीबोर्ड / DK का अपना ताला या मदद वाला पन्ना); own = DK खुद (लॉक लायक पन्ना)
     */
    fun front(pkg: String?, cls: String?, own: String, ns: String = "com.dk.assistant"): String? {
        if (pkg.isNullOrBlank()) return null
        if (pkg == own) {
            val c = cls ?: return null
            return if (DK_SCREENS.any { c == "$ns.$it" || c == "$own.$it" }) own else null
        }
        if (pkg in PASS || pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.endsWith(".ime")) return null
        return pkg
    }

    /**
     * किस ऐप का ताला अभी खुला है. एक ऐप खोला -> दूसरे पर गए -> [graceMs] के बाद लौटे = फिर ताला.
     * स्क्रीन बंद = सबका ताला फिर से.
     */
    class Session(@Volatile var graceMs: Long = RELOCK_DEFAULT) {
        private val open = HashMap<String, Long>()       // pkg -> कब छोड़ा (IN_FRONT = अभी सामने)
        @Volatile var front: String? = null
            private set

        /** @param locked यह ऐप ताले वाली सूची में है. @return true = अभी ताला दिखाओ */
        @Synchronized fun onFront(pkg: String, locked: Boolean, now: Long): Boolean {
            val prev = front
            if (prev != null && prev != pkg && open[prev] == IN_FRONT) open[prev] = now
            front = pkg
            if (!locked) return false
            val left = open[pkg] ?: return true
            if (left == IN_FRONT || graceMs == ON_SCREEN_OFF || now - left <= graceMs) { open[pkg] = IN_FRONT; return false }
            open.remove(pkg)
            return true
        }

        @Synchronized fun unlocked(pkg: String) { open[pkg] = IN_FRONT; front = pkg }
        @Synchronized fun isOpen(pkg: String) = open.containsKey(pkg)
        @Synchronized fun screenOff() { open.clear(); front = null }
    }

    private const val IN_FRONT = -1L
}
