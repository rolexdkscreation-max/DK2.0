package com.dk.assistant

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dk.assistant.ui.VerifiedBadge
import com.dk.assistant.ui.VerifiedTickDrawable

/**
 * 25 नवंबर 2026 (मालिक: "प्रोफ़ाइल का नाम और उसके आगे का टिक सेटिंग के अंदर सबसे ऊपर टॉप में दिखना चाहिए"):
 *  - Settings खोलते ही सबसे ऊपर: असली नाम + लाइसेंस वाला verified टिक (मालिक सुनहरा / प्रीमियम बैंगनी / बेसिक नीला).
 *    टिक हो तो पट्टी प्रीमियम जैसी (गहरा ढलान + उसी रंग का किनारा). टैप = प्रोफ़ाइल पन्ना.
 *  - प्रोफ़ाइल पन्ने में सबसे ऊपर "असली नाम" का खाना - लिखते ही (लाइसेंस हो तो) उसी जगह टिक.
 * पहले यह सिर्फ़ DKAI चैट की Profile शीट में था ([com.dk.assistant.dkai.DkaiSheets.profile]), Settings में नहीं.
 */
internal fun SettingsActivity.profileTopCard() {
    val real = Prefs.realName(this)
    val tier = VerifiedBadge.of(this)
    val vt = tier?.takeIf { VerifiedBadge.show(real, it) }   // टिक वाला स्तर (नाम + लाइसेंस दोनों हों तभी)
    val verified = vt != null
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val pd = (18 * dp).toInt(); setPadding(pd, pd, pd, pd)
        isClickable = true; isFocusable = true
        contentDescription = VerifiedBadge.headerName(real) + ", " + VerifiedBadge.statusLine(real, tier)
        background = if (verified) premiumBg(vt?.color ?: 0) else com.dk.assistant.ui.Clay.card(this@profileTopCard)
        if (verified) setTag(PlainLook.SKIP, true)
        setOnClickListener {
            startActivity(android.content.Intent(this@profileTopCard, SettingsActivity::class.java).putExtra("section", "profile"))
        }
    }
    val textMain = if (verified) 0xFFFFFFFF.toInt() else com.dk.assistant.ui.ClayTheme.t.text
    val textSub = if (verified) 0xD9FFFFFF.toInt() else com.dk.assistant.ui.ClayTheme.t.gray
    // नाम का पहला अक्षर गोल में (टिक वाले का उसी रंग का ढलान)
    box.addView(TextView(this).apply {
        text = real.trim().take(1).uppercase().ifBlank { "+" }
        textSize = 24f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(tier?.color ?: com.dk.assistant.ui.ClayTheme.t.accent, 0xFF1E2A4A.toInt())).apply {
            shape = GradientDrawable.OVAL
            if (verified) setStroke((2 * dp).toInt(), 0xFFFFFFFF.toInt())
        }
        setTag(PlainLook.SKIP, true); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })

    val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val nameTv = TextView(this).apply {
        text = VerifiedBadge.headerName(real); textSize = if (real.isBlank()) 17f else 20f
        setTypeface(typeface, Typeface.BOLD); setTextColor(textMain); maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (verified) setTag(PlainLook.SKIP, true)
    }
    nameRow.addView(nameTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (vt != null) nameRow.addView(ImageView(this).apply {
        setImageDrawable(VerifiedTickDrawable(vt.color)); contentDescription = "Verified • ${vt.label}"
        setTag(PlainLook.SKIP, true)
    }, LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply { marginStart = (6 * dp).toInt() })
    txt.addView(nameRow)
    txt.addView(TextView(this).apply {
        text = VerifiedBadge.statusLine(real, tier); textSize = 12.5f; setPadding(0, (3 * dp).toInt(), 0, 0)
        setTextColor(textSub); if (verified) { setTag(PlainLook.SKIP, true); setTypeface(typeface, Typeface.BOLD) }
    })
    box.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    box.addView(TextView(this).apply { text = "›"; textSize = 28f; setTextColor(textSub); if (verified) setTag(PlainLook.SKIP, true) })
    container.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() })
    // मालिक = सुनहरा धातु जैसा नाम (CONTROL CENTER वाला)
    if (vt == VerifiedBadge.Tier.OWNER) FutureViews.goldShader(nameTv)
}

/** प्रीमियम पट्टी: गहरा नीला -> टिक के रंग का गहरा ढलान + उसी रंग का पतला चमकदार किनारा */
private fun SettingsActivity.premiumBg(color: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
    intArrayOf(0xFF141B33.toInt(), mix(color, 0xFF141B33.toInt(), 0.55f), mix(color, 0xFF000000.toInt(), 0.35f))).apply {
    cornerRadius = 22 * dp
    setStroke((1.5f * dp).toInt().coerceAtLeast(2), color)
}

private fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(x: Int, s: Int) = (x shr s) and 0xFF
    fun m(s: Int) = (ch(a, s) * (1 - t) + ch(b, s) * t).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
}

/** प्रोफ़ाइल पन्ने में सबसे ऊपर: असली नाम का खाना + उसके आगे verified टिक (लिखते ही, अपने-आप सेव) */
internal fun SettingsActivity.realNameCard() {
    val tier = VerifiedBadge.of(this)
    card("असली नाम + verified टिक", "Settings में सबसे ऊपर यही नाम • लाइसेंस हो तो नाम के आगे टिक", startOpen = true) { box ->
        val ed = field(box, "असली नाम (Real name)", Prefs.realName(this),
            googleName(this).ifBlank { "जैसे: धीरज कुमार सिंह" })
        // खाना और टिक एक ही लाइन में
        box.removeView(ed)
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(ed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tick = ImageView(this).apply {
            tier?.let { setImageDrawable(VerifiedTickDrawable(it.color)); contentDescription = "Verified • ${it.label}" }
            setTag(PlainLook.SKIP, true)
            visibility = if (VerifiedBadge.show(Prefs.realName(this@realNameCard), tier)) View.VISIBLE else View.GONE
        }
        r.addView(tick, LinearLayout.LayoutParams((30 * dp).toInt(), (30 * dp).toInt()).apply { marginStart = (10 * dp).toInt() })
        box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val status = TextView(this).apply {
            textSize = 12.5f; setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0)
            setTextColor(tier?.color ?: com.dk.assistant.ui.ClayTheme.t.gray)
            text = VerifiedBadge.statusLine(Prefs.realName(this@realNameCard), tier)
        }
        box.addView(status)
        ed.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) { /* सिर्फ़ afterTextChanged चाहिए */ }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) { /* सिर्फ़ afterTextChanged चाहिए */ }
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString().orEmpty().trim().take(60)
                Prefs.edit(this@realNameCard) { putString("real_name", v) }
                tick.visibility = if (VerifiedBadge.show(v, tier)) View.VISIBLE else View.GONE
                status.text = VerifiedBadge.statusLine(v, tier)
            }
        })
        hint(box, "टिक का रंग: मालिक = सुनहरा • प्रीमियम = बैंगनी • बेसिक = नीला • मुफ़्त = टिक नहीं। " +
            "लाइसेंस \"लाइसेंस की\" में डालें। DK आपको किस नाम से बुलाए, वह नीचे \"आपका नाम\" में (उसमें टिक नहीं)।")
    }
}

/** Google से लॉगिन हो तो उसका नाम (सिर्फ़ खाली खाने में सुझाव के तौर पर) */
private fun googleName(c: android.content.Context): String =
    try { com.dk.assistant.backend.BackendSession.displayName(c) } catch (_: Exception) { "" }
