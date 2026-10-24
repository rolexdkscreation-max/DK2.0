package com.dk.assistant.agent.core

/**
 * DK AI Part 12: screen agent का pure हिस्सा (Android import नहीं -> JVM test).
 * असली फ़ोन वाला हिस्सा AccessibilityScreenDriver में; tests FakeScreenDriver से.
 */

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val area get() = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0)
}

data class UiElement(
    val id: Int,
    val text: String = "",
    val desc: String = "",
    val viewId: String = "",
    val cls: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val bounds: Box = Box(0, 0, 100, 100),
) {
    /** खोज के लिए: text + desc, छोटे अक्षर */
    val label: String get() = (text + " " + desc).trim().lowercase()
}

data class ScreenObservation(
    val observationId: Long,
    val pkg: String,
    val elements: List<UiElement>,
    val width: Int = 1080,
    val height: Int = 2400,
) {
    fun hasText(vararg words: String): Boolean = elements.any { e -> words.any { w -> e.label.contains(w.lowercase()) } }
    fun texts(): List<String> = elements.mapNotNull { it.text.trim().takeIf { t -> t.isNotEmpty() } }
}

enum class DriverResult { OK, FAILED, STALE, NOT_FOUND, UNSUPPORTED }

enum class ActionMethod { ACCESSIBILITY_CLICK, GESTURE_ON_ELEMENT_BOUNDS, SET_TEXT, GLOBAL_BACK, OPEN_APP }

/** ऐसी स्क्रीन जहाँ DK अपने-आप आगे नहीं जाता - यूज़र खुद करे */
enum class Blocker(val hi: String, val en: String) {
    LOGIN("लॉग-इन माँगा जा रहा है - कृपया खुद लॉग-इन कर लें", "The app is asking you to log in - please log in yourself"),
    CAPTCHA("CAPTCHA आया है - इसे आपको खुद भरना होगा", "A CAPTCHA appeared - please solve it yourself"),
    TWO_FACTOR("सुरक्षा कोड (2-step) माँगा गया है - कृपया खुद डालें", "A security / 2-step code is needed - please enter it yourself"),
    SECURITY_CHECK("ऐप ने सुरक्षा जाँच दिखाई है - कृपया खुद देख लें", "The app is showing a security check - please handle it yourself"),
    PERMISSION_PROMPT("Android इजाज़त माँग रहा है - कृपया खुद हाँ/ना चुनें", "Android is asking for a permission - please choose yourself"),
}

enum class FailureReason {
    TARGET_NOT_FOUND, ACTION_FAILED, NOT_VERIFIED, APP_NOT_OPEN, APP_NOT_INSTALLED, NO_SCREEN,
    CANCELLED, TIMEOUT, MEDIA_NOT_ALLOWED, CONTACT_NOT_FOUND, CONTACT_AMBIGUOUS, BAD_REQUEST,
}

sealed class ActionResult {
    data class Success(val method: ActionMethod, val obs: ScreenObservation?) : ActionResult()
    data class Failed(val reason: FailureReason, val detail: String = "") : ActionResult()
    data class Blocked(val blocker: Blocker) : ActionResult()
}

/** किस चीज़ पर काम करना है: text/desc/viewId के टुकड़ों से पहचान (कई भाषाएँ) */
data class TargetSpec(
    val labels: List<String> = emptyList(),
    val viewIds: List<String> = emptyList(),
    val exact: Boolean = false,
    val editable: Boolean = false,
    val clickableOnly: Boolean = false,
    /** स्क्रीन के इस हिस्से में (0..1 ऊपर से) - जैसे ऊपर-दाएँ "Next" */
    val topFraction: Float? = null,
    val bottomFraction: Float? = null,
    val name: String = labels.firstOrNull() ?: viewIds.firstOrNull() ?: "target",
)

object TargetResolver {
    /** सबसे अच्छा मेल; कोई नहीं = null. disabled/zero-size कभी नहीं */
    fun resolve(obs: ScreenObservation, spec: TargetSpec): UiElement? =
        obs.elements.asSequence()
            .filter { it.enabled && it.bounds.width > 0 && it.bounds.height > 0 }
            .filter { !spec.editable || it.editable }
            .filter { !spec.clickableOnly || it.clickable }
            .filter { e -> spec.topFraction?.let { e.bounds.centerY <= obs.height * it } ?: true }
            .filter { e -> spec.bottomFraction?.let { e.bounds.centerY >= obs.height * it } ?: true }
            .mapNotNull { e -> score(e, spec).takeIf { it > 0 }?.let { e to it } }
            .maxByOrNull { it.second }?.first

    fun score(e: UiElement, spec: TargetSpec): Int {
        var s = 0
        val vid = e.viewId.lowercase()
        if (spec.viewIds.any { v -> vid.isNotEmpty() && (vid == v.lowercase() || vid.endsWith("/" + v.lowercase())) }) s += 100
        val t = e.text.trim().lowercase(); val d = e.desc.trim().lowercase()
        for (l in spec.labels.map { it.lowercase() }) {
            when {
                t == l || d == l -> s += 80
                !spec.exact && (t.contains(l) || d.contains(l)) && l.length >= 3 -> s += 30
            }
        }
        if (s == 0) return 0
        if (e.clickable) s += 10
        if (spec.editable && e.editable) s += 20
        return s
    }

    /** element अब भी वही है? (नई observation में उसी जगह, वही label) */
    fun stillValid(old: UiElement, obs: ScreenObservation): Boolean =
        obs.elements.any { it.label == old.label && it.viewId == old.viewId && it.bounds == old.bounds && it.enabled }
}

object BlockerDetector {
    private val PERMISSION_PKGS = setOf("com.google.android.permissioncontroller", "com.android.permissioncontroller", "com.android.packageinstaller")
    private val CAPTCHA = listOf("captcha", "i'm not a robot", "i am not a robot", "मैं रोबोट नहीं")
    private val TWO_FA = listOf("security code", "confirmation code", "two-factor", "2-step", "enter the code", "authentication code", "सुरक्षा कोड", "पुष्टि कोड")
    private val SECURITY = listOf("suspicious", "verify it's you", "confirm it's you", "security check", "unusual activity", "account is locked", "पुष्टि करें कि यह आप हैं")
    private val LOGIN_WORDS = listOf("log in", "login", "sign in", "लॉग इन", "लॉग-इन")
    private val PASSWORD = listOf("password", "पासवर्ड")

    fun detect(obs: ScreenObservation): Blocker? {
        if (obs.pkg in PERMISSION_PKGS) return Blocker.PERMISSION_PROMPT
        if (obs.hasText(*CAPTCHA.toTypedArray())) return Blocker.CAPTCHA
        if (obs.hasText(*TWO_FA.toTypedArray())) return Blocker.TWO_FACTOR
        if (obs.hasText(*SECURITY.toTypedArray())) return Blocker.SECURITY_CHECK
        // लॉग-इन = "log in" + password वाला खाना (सिर्फ़ "log in" शब्द feed में भी आ सकता है)
        val pwField = obs.elements.any { it.editable && PASSWORD.any { p -> it.label.contains(p) } }
        if (pwField && obs.hasText(*LOGIN_WORDS.toTypedArray())) return Blocker.LOGIN
        return null
    }
}

/**
 * पक्का "हाँ" चाहिए (पोस्ट/भेजना वापस नहीं होता). मॉडल का confirm तभी माना जाए जब पूछने के बाद
 * यूज़र ने सच में हाँ कहा हो.
 */
object ConfirmGate {
    private val YES = listOf("हाँ", "हां", "हा", "जी", "ठीक है", "कर दो", "कर दीजिए", "भेज दो", "डाल दो", "पोस्ट कर", "शेयर कर",
        "haan", "han", "ha", "haa", "ji", "theek hai", "thik hai", "kar do", "bhej do", "daal do", "dal do", "post kar", "share kar",
        "yes", "yeah", "yep", "ok", "okay", "sure", "confirm", "go ahead", "do it", "send it", "post it")
    private val NO = listOf("नहीं", "नही", "मत", "रुको", "रुक", "रद्द", "nahi", "nahin", "mat", "ruko", "ruk", "cancel", "no", "don't", "dont", "stop", "wait")

    private fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{M}']+")).filter { it.isNotEmpty() }

    fun isYes(utterance: String): Boolean {
        val low = " " + utterance.lowercase().trim() + " "
        val w = words(utterance)
        val no = NO.any { n -> if (n.contains(' ')) low.contains(" $n ") else n in w }
        if (no) return false
        return YES.any { y -> if (y.contains(' ')) low.contains(" $y") else y in w }
    }

    /** confirm सिर्फ़ तब: पूछा गया था, जवाब पूछने के बाद आया, और जवाब "हाँ" है */
    fun accept(askedAt: Long, userAt: Long, userText: String): Boolean = askedAt > 0 && userAt > askedAt && isYes(userText)
}
