package com.dk.assistant.agent.master

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.dk.assistant.DKLog
import com.dk.assistant.Quiet
import com.dk.assistant.UiLang
import com.dk.assistant.plus.ConfirmGate
import com.dk.assistant.plus.FeatureFlags

/**
 * 24 नवंबर 2026 (मालिक: Universal AI Action Agent, चरण 1 - "पहले केंद्रीय दिमाग और एक्सेसिबिलिटी वाला एजेंट").
 * फ़ोन वाला हिस्सा. [FeatureFlags.MASTER_AGENT] बंद = कुछ नहीं बदलता (हर फ़ंक्शन तुरंत खाली लौटता है).
 * चालू = AI एजेंट वाले (लंबे) काम से पहले: इरादा + प्लान स्क्रीन पर, संवेदनशील जानकारी भेजने पर आवाज़ में "हाँ",
 * एजेंट को सख़्त नियम ([MasterRules.agentNote]), और हर काम + नतीजा [ActionLog] में.
 * असली काम पहले से बने एजेंट ही करते हैं (Accessibility वाला AgentRunner / टूल एजेंट) - यहाँ कोई नया "करने वाला" नहीं.
 */
object DkMaster {
    data class Pre(val plan: MasterRules.Plan, val note: String)

    fun on(c: Context) = FeatureFlags.on(c, FeatureFlags.MASTER_AGENT)

    /** अभी चल रहा बुकिंग वाला काम (फ़ोन एजेंट \"Book / Confirm\" पर ब्योरे के साथ पूछता है). null = बुकिंग नहीं */
    @Volatile var trip: BookRules.Trip? = null

    /**
     * एजेंट चलने से पहले. null = मास्टर बंद (पुराना रास्ता). [Pre.note] खाली नहीं = एजेंट के note में जोड़ो.
     * [block] भरा हो = काम मत चलाओ, यही लाइन Gemini को लौटाओ (आवाज़ में पुष्टि माँगनी है / मना हुआ).
     */
    fun before(c: Context, req: String): Pair<Pre?, String?> {
        if (!on(c)) return null to null
        val p = MasterRules.plan(req)
        ActionLog.add(c, "काम", req)
        ActionLog.add(c, "प्लान", MasterRules.planText(p))
        DKLog.d("मास्टर: ${p.specialist.id}, जोखिम ${p.risks.joinToString { it.name }}")
        if (MasterRules.confirmUpfront(req)) {
            ConfirmGate.check(c, "निजी जानकारी भेजूँ?", req, "master_secret_share")?.let { line ->
                ActionLog.add(c, "रुका", "निजी जानकारी भेजने से पहले पुष्टि")
                return null to line
            }
            ActionLog.add(c, "पुष्टि", "मालिक ने हाँ कहा")
        }
        // 25 नवंबर 2026 (बुकिंग एजेंट): ज़रूरी ब्योरा (कहाँ से / तक / कब / कितने लोग) न हो = एजेंट नहीं, पहले मालिक से पूछो - अपने से कुछ नहीं भरते
        var note = MasterRules.agentNote(p)
        trip = null
        if (p.specialist == MasterRules.Specialist.BOOKING && BookRules.isBooking(req)) {
            val t = BookRules.trip(req)
            val miss = BookRules.missing(t)
            ActionLog.add(c, "बुकिंग", BookRules.summary(t))
            if (miss.isNotEmpty()) {
                ActionLog.add(c, "पूछा", miss.joinToString(", "))
                return null to BookRules.askNote(t, miss)
            }
            val pm = c.packageManager
            val apps = BookRules.appsFor(t.kind) { pkg -> try { pm.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false } }
            note += "\n" + BookRules.agentNote(t, apps)
            trip = t
        }
        show(c, MasterRules.planText(p))
        return Pre(p, note) to null
    }

    /** एजेंट का नतीजा लॉग में (मास्टर चालू हो तो) */
    fun after(c: Context, result: String) {
        if (!on(c)) return
        ActionLog.add(c, "नतीजा", result)
    }

    /** "रुको" - लॉग में */
    fun stopped(c: Context, req: String) {
        if (on(c)) ActionLog.add(c, "रोका", req)
    }

    /** "DK क्या करने वाला है" - स्क्रीन पर */
    private fun show(c: Context, t: String) {
        Handler(Looper.getMainLooper()).post {
            try { UiLang.toast(c.applicationContext, t, Toast.LENGTH_LONG).show() } catch (e: Exception) { Quiet.note(e) }
        }
    }
}
