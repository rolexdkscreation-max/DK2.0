package com.dk.assistant.agent.android

import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.social.OpenSpec
import com.dk.assistant.agent.social.Platform
import com.dk.assistant.agent.social.WaDelete
import com.dk.assistant.swipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * नवंबर 2026: WhatsApp पर भेजा हुआ (गलती वाला) मैसेज सबके लिए डिलीट - सीधे स्क्रीन पर, AI एजेंट के बिना.
 * किस चैट में: बोला नाम > अभी खुली WhatsApp चैट > DK का अभी भेजा मैसेज (24 घंटे). कुछ न मिले = पूछो.
 * "Delete for everyone" न दिखे (बहुत पुराना) = रद्द करके सच बताओ; "मेरे लिए" तभी जब मालिक ने कहा.
 * मैसेज का text log नहीं होता.
 */
object WaDeleteRunner {
    private val lock = Mutex()

    suspend fun run(svc: DKAccessibilityService, request: String, contactArg: String = "", forMeArg: Boolean? = null, textArg: String = ""): String = lock.withLock {
        val forMe = forMeArg ?: WaDelete.forMeOnly(request)
        val d = AccessibilityScreenDriver(svc)
        val said = contactArg.trim().ifBlank { WaDelete.contactIn(request) }
        val recent = WaDelete.recent()
        var want = textArg.trim()
        var who = said
        val onWa = svc.currentPackage().startsWith(Platform.WHATSAPP.pkg)
        val openChat: Boolean
        var digits = ""
        when {
            said.isNotBlank() && recent != null && sameName(said, recent.name) -> { digits = recent.digits; who = recent.name; if (want.isEmpty()) want = recent.text; openChat = true }
            said.isNotBlank() -> when (val c = SocialAgent.contact(svc, said)) {
                is SocialAgent.Contact.Found -> { digits = c.digits; who = c.name; openChat = true }
                is SocialAgent.Contact.Ask -> return "NEEDS USER CHOICE (nothing was deleted): ${c.msg}. Ask the user"
            }
            onWa && d.capture()?.let { WaDelete.target(it) } != null -> openChat = false
            recent != null -> { digits = recent.digits; who = recent.name; if (want.isEmpty()) want = recent.text; openChat = true }
            else -> return "NEEDS USER CHOICE (nothing was deleted): किसकी चैट का मैसेज डिलीट करूँ? Ask the user for the contact name"
        }
        if (openChat) {
            if (!SocialAgent.open(svc, OpenSpec.Url("https://wa.me/$digits", Platform.WHATSAPP.pkg))) return "FAILED: WhatsApp नहीं खुला।"
        }
        // चैट आने तक
        var o: ScreenObservation? = null
        for (i in 0 until 16) {
            delay(if (i == 0 && openChat) 900 else 400)
            val c = d.capture() ?: continue
            if (c.pkg.startsWith(Platform.WHATSAPP.pkg) && WaDelete.target(c, want) != null) { o = c; break }
        }
        val obs = o ?: return "FAILED: ${if (who.isNotBlank()) "$who की " else ""}चैट में आपका भेजा कोई मैसेज स्क्रीन पर नहीं दिखा - कुछ डिलीट नहीं किया।"
        val bubble = WaDelete.target(obs, want)!!
        val gone = bubble.text
        // लंबा दबाओ = मैसेज चुनो
        svc.swipe(bubble.bounds.centerX.toFloat(), bubble.bounds.centerY.toFloat(), bubble.bounds.centerX.toFloat(), bubble.bounds.centerY.toFloat(), 850)
        var del: Pair<ScreenObservation, com.dk.assistant.agent.core.UiElement>? = null
        for (i in 0 until 6) {
            delay(300)
            val c = d.capture() ?: continue
            WaDelete.deleteButton(c)?.let { del = c to it }
            if (del != null) break
        }
        val found = del
        if (found == null) { d.back(); return "FAILED: मैसेज चुनने के बाद Delete का बटन नहीं मिला - कुछ डिलीट नहीं किया।" }
        val (o2, btn) = found
        d.click(o2, btn)
        // "Delete for everyone" / "Delete for me" वाली खिड़की
        for (i in 0 until 8) {
            delay(300)
            val c = d.capture() ?: continue
            val every = WaDelete.everyoneButton(c)
            val me = WaDelete.forMeButton(c)
            if (every == null && me == null) continue
            val pickBtn = if (forMe) me else every
            if (pickBtn == null) {
                WaDelete.cancelButton(c)?.let { d.click(c, it) } ?: d.back()
                return "NEEDS USER CHOICE (nothing was deleted): इस मैसेज पर \"सबके लिए डिलीट\" अब नहीं बचा (शायद बहुत पुराना है) - सिर्फ़ अपने फ़ोन से हटाऊँ? Ask the user"
            }
            d.click(c, pickBtn)
            for (j in 0 until 8) {
                delay(350)
                val after = d.capture() ?: continue
                if (WaDelete.deleted(after, gone) || (forMe && WaDelete.bubbles(after).none { it.text == gone })) {
                    DKLog.d("WhatsApp: भेजा मैसेज डिलीट (${if (forMe) "मेरे लिए" else "सबके लिए"})")
                    if (recent != null && (digits == recent.digits || recent.text == gone)) WaDelete.forget()     // दोबारा कहा = अगला पुराना नहीं मिटेगा
                    return WaDelete.done(who, !forMe)
                }
            }
            return "SUBMITTED_UNVERIFIED: डिलीट दबा दिया, पर स्क्रीन पर पक्का नहीं दिखा - WhatsApp में एक बार देख लीजिए।"
        }
        d.back()
        return "FAILED: Delete दबाने के बाद \"सबके लिए डिलीट\" वाली खिड़की नहीं आई - कुछ डिलीट नहीं किया।"
    }

    private fun sameName(a: String, b: String): Boolean {
        val x = a.trim().lowercase(); val y = b.trim().lowercase()
        return x.isNotEmpty() && y.isNotEmpty() && (y.contains(x) || x.contains(y) || com.dk.assistant.NameMatch.latin(x) == com.dk.assistant.NameMatch.latin(y))
    }
}
