package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dk.assistant.agent.master.ActionLog
import com.dk.assistant.agent.master.DkMaster
import com.dk.assistant.agent.master.ShowRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 25 नवंबर 2026 (मालिक: "वो अंदर ही अंदर कर रहा है... अगर मैं पूछा दिखाओ तो वो डायरेक्ट वो विंडो या वो पेज खोल के मुझे दिखाना चाहिए").
 * DK जहाँ-जहाँ काम करता है (DK कंप्यूटर का छिपा ब्राउज़र, फ़ोन का ब्राउज़र, कोई ऐप) उसकी आख़िरी जगह याद रखता है,
 * और "पेज दिखाओ" पर वही सामने खोलता है. पढ़ने / दिखाने भर का काम - कुछ दबाता या भेजता नहीं, इसलिए हमेशा चालू.
 */
object ShowPage {
    @Volatile private var spots: List<ShowRules.Spot> = emptyList()

    private fun note(s: ShowRules.Spot) { spots = ShowRules.push(spots, s) }

    fun dkWeb(url: String, title: String, pay: Boolean = false) {
        if (url.startsWith("http")) note(ShowRules.Spot(ShowRules.Kind.DK_WEB, url, title, System.currentTimeMillis(), pay))
    }

    fun web(url: String, title: String) {
        if (url.startsWith("http")) note(ShowRules.Spot(ShowRules.Kind.WEB, url, title, System.currentTimeMillis()))
    }

    /** DK ने इस ऐप में काम किया (DK खुद / launcher / system UI नहीं) */
    fun app(c: Context, pkg: String, title: String = "", pay: Boolean = false) {
        if (pkg.isBlank() || pkg == c.packageName || pkg == "com.android.systemui" || pkg == home(c)) return
        note(ShowRules.Spot(ShowRules.Kind.APP, pkg, title, System.currentTimeMillis(), pay))
    }

    /** अभी स्क्रीन पर जो ऐप है (phone agent का काम खत्म / भुगतान पर रुका) */
    fun appNow(svc: DKAccessibilityService, pay: Boolean = false) = app(svc, svc.currentPackage(), pay = pay)

    private fun home(c: Context): String = try {
        c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName.orEmpty()
    } catch (_: Exception) { "" }

    /** कुछ याद है? (computer_task / phone_task में "दिखाओ" पकड़ने से पहले) */
    fun has(): Boolean = ShowRules.pick(spots, System.currentTimeMillis()) != null

    /** "पेज दिखाओ" - Gemini के लिए सच्चा नतीजा */
    suspend fun show(c: Context): String = withContext(Dispatchers.Main) {
        val s = ShowRules.pick(spots, System.currentTimeMillis()) ?: return@withContext ShowRules.NOTHING_NOTE
        val ok = try {
            when (s.kind) {
                ShowRules.Kind.DK_WEB -> { c.startActivity(Intent(c, BrowserShowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
                ShowRules.Kind.WEB -> { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
                // ऐप का अपना task सामने = वही स्क्रीन जहाँ DK रुका था (नया नहीं खुलता)
                ShowRules.Kind.APP -> c.packageManager.getLaunchIntentForPackage(s.target)
                    ?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } ?: false
            }
        } catch (e: Exception) { Quiet.note(e); false }
        DKLog.d("पेज दिखाओ: ${s.kind} ${s.target.take(80)} -> ${if (ok) "खुला" else "नहीं खुला"}")
        if (DkMaster.on(c)) ActionLog.add(c, "दिखाया", if (ok) s.title.ifBlank { s.target }.take(80) else "नहीं खुला")
        if (ok) ShowRules.shownNote(s) else ShowRules.FAIL_NOTE
    }
}
