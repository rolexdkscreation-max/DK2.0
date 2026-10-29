package com.dk.assistant

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/**
 * v4: WhatsApp / Instagram / Facebook / Messenger / Telegram ... की आने वाली कॉल.
 * ये Android की फ़ोन कॉल नहीं - सिर्फ़ एक notification (Answer / Decline बटन वाली). पहले DK का notification
 * हिस्सा "लगातार चलने वाले" notification छोड़ देता था, इसलिए DK को पता ही नहीं चलता था.
 * अब: कॉल वाला notification पहचानो -> DK पूछे "उठाऊँ या काट दूँ?" -> user की हाँ पर उसी notification का बटन.
 * कभी खुद से नहीं उठाता/काटता.
 */
object AppCall {
    enum class State { RINGING, ONGOING }

    data class Ring(
        val key: String, val pkg: String, val app: String, val who: String, val video: Boolean, val state: State,
        val answer: PendingIntent?, val decline: PendingIntent?, val hangup: PendingIntent?, val open: PendingIntent?,
        val since: Long,
    )

    @Volatile var current: Ring? = null
        private set
    /** बज रही कॉल ख़त्म (उठी / कटी / missed) - घंटी की आवाज़ वापस */
    @Volatile var onGone: (() -> Unit)? = null

    private const val RING_MAX_MS = 75_000L

    fun ringing(now: Long = System.currentTimeMillis()): Boolean =
        current?.let { it.state == State.RINGING && now - it.since < RING_MAX_MS } == true
    fun ongoing(): Boolean = current?.state == State.ONGOING
    fun active(): Boolean = ringing() || ongoing()
    fun who(): String = current?.who.orEmpty()

    // ------------------------------------------------------------ पहचान (शुद्ध - tests)

    /** फ़ोन की अपनी (SIM) कॉल - वह CallManager संभालता है, यहाँ नहीं (दो बार न पूछे) */
    fun isPhoneApp(pkg: String): Boolean {
        val p = pkg.lowercase()
        return listOf("dialer", "incallui", "telecom", "com.android.phone", "com.samsung.android.app.telephonyui", "contacts").any { p.contains(it) }
    }

    private fun words(t: String) = " " + t.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim() + " "
    private val ANSWER = listOf("answer", "accept", "pick up", "join", "उत्तर", "जवाब", "स्वीकार", "उठाएं", "उठाएँ", "रिसीव")
    private val DECLINE = listOf("decline", "reject", "dismiss", "ignore", "अस्वीकार", "मना", "काटें", "कट करें", "ठुकराएं")
    private val HANGUP = listOf("hang up", "end call", "end", "leave", "कॉल समाप्त", "समाप्त", "कॉल बंद", "काटें", "कट करें")

    /** बटन के नाम में से Answer / Decline / Hang up का नंबर (-1 = नहीं) */
    fun pickAnswer(titles: List<String>) = titles.indexOfFirst { t -> ANSWER.any { words(t).contains(" $it ") } }
    fun pickDecline(titles: List<String>) = titles.indexOfFirst { t -> DECLINE.any { words(t).contains(" $it ") } }
    fun pickHangup(titles: List<String>) = titles.indexOfFirst { t -> HANGUP.any { words(t).contains(" $it ") } }

    /** यह notification किसी ऐप की कॉल है? (category CALL / CallStyle / पूरी-स्क्रीन + Answer बटन) */
    fun isCall(pkg: String, category: String?, callStyle: Boolean, fullScreen: Boolean, titles: List<String>): Boolean {
        if (isPhoneApp(pkg)) return false
        if (category == Notification.CATEGORY_CALL || callStyle) return true
        return fullScreen && pickAnswer(titles) >= 0 && pickDecline(titles) >= 0
    }

    fun isVideo(vararg texts: String): Boolean = texts.any { t -> Regex("(?i)(video|वीडियो|विडियो)").containsMatchIn(t) }

    /** कौन कॉल कर रहा है: WhatsApp में title = नाम; Instagram/Messenger में कभी title = ऐप, text = "राहुल is calling you" */
    fun caller(title: String, text: String, app: String): String {
        val t = title.trim()
        if (t.isNotBlank() && !t.equals(app, true) && !Regex("(?i)^(incoming|ongoing|calling|missed)").containsMatchIn(t)) return t
        Regex("(?i)^(.+?)\\s+(?:is\\s+)?(?:video\\s+)?calling").find(text.trim())?.let { return it.groupValues[1].trim() }
        Regex("^(.+?)\\s+(?:आपको\\s+)?(?:वीडियो\\s+)?कॉल\\s+कर\\s+रह").find(text.trim())?.let { return it.groupValues[1].trim() }
        return ""
    }

    /** DK क्या पूछे */
    fun announce(app: String, who: String, video: Boolean): String {
        val kind = if (video) "वीडियो कॉल" else "कॉल"
        val a = app.ifBlank { "ऐप" }
        return if (who.isNotBlank()) "$a पर $who का $kind आ रहा है। उठाऊँ या काट दूँ?" else "$a पर किसी का $kind आ रहा है। उठाऊँ या काट दूँ?"
    }

    // ------------------------------------------------------------ notification से (DKNotifListener)

    /** true = नई बजती कॉल (DK को पूछना है) */
    fun posted(key: String, pkg: String, app: String, n: Notification, title: String, text: String): Boolean {
        val acts = n.actions?.toList().orEmpty()
        val titles = acts.map { it.title?.toString().orEmpty() }
        val ex = n.extras
        val callStyle = Build.VERSION.SDK_INT >= 31 && ex?.getString(Notification.EXTRA_TEMPLATE)?.endsWith("CallStyle") == true
        if (!isCall(pkg, n.category, callStyle, n.fullScreenIntent != null, titles)) return false
        @Suppress("DEPRECATION")
        fun extraPi(k: String): PendingIntent? = if (Build.VERSION.SDK_INT >= 31) try { ex?.getParcelable(k) as? PendingIntent } catch (_: Exception) { null } else null
        val ans = extraPi("android.answerIntent") ?: acts.getOrNull(pickAnswer(titles))?.actionIntent
        val dec = extraPi("android.declineIntent") ?: acts.getOrNull(pickDecline(titles))?.actionIntent
        val hang = extraPi("android.hangUpIntent") ?: acts.getOrNull(pickHangup(titles))?.actionIntent
        if (ans == null && dec == null && hang == null) return false    // बिना बटन (missed call वगैरह) - कुछ करने को नहीं
        val state = if (ans != null) State.RINGING else State.ONGOING
        val prev = current
        val r = Ring(key, pkg, app, caller(title, text, app).ifBlank { prev?.takeIf { it.key == key }?.who.orEmpty() },
            isVideo(title, text, *titles.toTypedArray()) || (prev?.key == key && prev.video), state, ans, dec, hang ?: dec,
            n.fullScreenIntent ?: n.contentIntent,
            if (prev?.key == key && prev.state == state) prev.since else System.currentTimeMillis())
        current = r
        return state == State.RINGING && !(prev?.key == key && prev.state == State.RINGING)
    }

    fun removed(key: String) {
        if (current?.key == key) { current = null; onGone?.invoke() }
    }

    // ------------------------------------------------------------ बटन दबाना

    private fun send(c: Context, pi: PendingIntent?): Boolean {
        if (pi == null) return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val o = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(c, 0, null, null, null, null, o.toBundle())
            } else pi.send()
            true
        } catch (e: Exception) { DKLog.d("AppCall बटन: ${e.message}"); false }
    }

    /** उठाओ: Answer बटन; न हो तो कॉल की स्क्रीन खोलो (फिर DK स्क्रीन पर Answer ढूँढता है) */
    fun answer(c: Context): Boolean {
        val r = current ?: return false
        if (r.state != State.RINGING) return false
        if (send(c, r.answer)) return true
        send(c, r.open)
        return false
    }

    /** काटो: बजती = Decline, चल रही = Hang up */
    fun end(c: Context): Boolean {
        val r = current ?: return false
        val ok = if (r.state == State.RINGING) send(c, r.decline) else send(c, r.hangup)
        if (ok && r.state == State.RINGING) removed(r.key)
        return ok
    }

    /** tests के लिए */
    internal fun reset() { current = null }
}
