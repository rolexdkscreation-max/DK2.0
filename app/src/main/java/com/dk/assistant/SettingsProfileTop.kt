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
 *  - Settings खोलते ही सबसे ऊपर: पहले अक्षर वाला गोल + असली नाम + लाइसेंस वाला verified टिक
 *    (मालिक सुनहरा / प्रीमियम बैंगनी / बेसिक नीला). टैप = प्रोफ़ाइल पन्ना.
 *  - मालिक (दूसरी बार): पट्टी बाकी Settings पट्टियों जैसी ही (वही Clay कार्ड) - चारों तरफ़ पीला किनारा/ढलान नहीं;
 *    टिक वाला नाम सुनहरा; "Verified • Owner • मालिक" वाली लाइन बाहर नहीं, सिर्फ़ अंदर नाम वाले खाने के नीचे.
 *  - प्रोफ़ाइल पन्ने में सबसे ऊपर "असली नाम" का खाना - लिखते ही (लाइसेंस हो तो) उसी जगह टिक.
 * पहले यह सिर्फ़ DKAI चैट की Profile शीट में था ([com.dk.assistant.dkai.DkaiSheets.profile]), Settings में नहीं.
 */
internal fun SettingsActivity.profileTopCard() {
    val real = Prefs.realName(this)
    val tier = VerifiedBadge.of(this)
    val vt = tier?.takeIf { VerifiedBadge.show(real, it) }   // टिक वाला स्तर (नाम + लाइसेंस दोनों हों तभी)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val pd = (18 * dp).toInt(); setPadding(pd, pd, pd, pd)
        isClickable = true; isFocusable = true
        contentDescription = VerifiedBadge.headerName(real) + (if (vt != null) ", Verified" else "")
        background = com.dk.assistant.ui.Clay.card(this@profileTopCard)       // बाकी पट्टियों वाला ही कार्ड
        setOnClickListener {
            startActivity(android.content.Intent(this@profileTopCard, SettingsActivity::class.java).putExtra("section", "profile"))
        }
    }
    // नाम का पहला अक्षर गोल में (पहले जैसा)
    box.addView(TextView(this).apply {
        text = real.trim().take(1).uppercase().ifBlank { "+" }
        textSize = 22f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(tier?.color ?: com.dk.assistant.ui.ClayTheme.t.accent, 0xFF1E2A4A.toInt())).apply { shape = GradientDrawable.OVAL }
        setTag(PlainLook.SKIP, true); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        UiLang.keep(this)
    }, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })

    val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val nameTv = TextView(this).apply {
        text = VerifiedBadge.headerName(real); textSize = if (real.isBlank()) 17f else 20f
        setTypeface(typeface, Typeface.BOLD); setTextColor(com.dk.assistant.ui.ClayTheme.t.text); maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (real.isNotBlank()) UiLang.keep(this)        // असली नाम का अनुवाद नहीं
    }
    nameRow.addView(nameTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (vt != null) nameRow.addView(ImageView(this).apply {
        setImageDrawable(VerifiedTickDrawable(vt.color)); contentDescription = "Verified"
        setTag(PlainLook.SKIP, true)
    }, LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply { marginStart = (6 * dp).toInt() })
    txt.addView(nameRow)
    // नाम न लिखा हो तभी छोटी लाइन - नाम हो तो बाहर सिर्फ़ नाम + टिक
    if (real.isBlank()) txt.addView(TextView(this).apply {
        text = VerifiedBadge.ADD_HINT; textSize = 12.5f; setPadding(0, (3 * dp).toInt(), 0, 0)
        setTextColor(com.dk.assistant.ui.ClayTheme.t.gray)
    })
    box.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    box.addView(TextView(this).apply { text = "›"; textSize = 28f; setTextColor(com.dk.assistant.ui.ClayTheme.t.gray) })
    container.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() })
    // टिक वाला (मालिक/प्रीमियम/बेसिक) = नाम सुनहरे धातु जैसा (CONTROL CENTER वाला)
    if (vt != null) { nameTv.setTag(PlainLook.SKIP, true); FutureViews.goldShader(nameTv) }
}

/** प्रोफ़ाइल पन्ने में सबसे ऊपर: असली नाम का खाना + उसके आगे verified टिक (लिखते ही, अपने-आप सेव) */
internal fun SettingsActivity.realNameCard() {
    val tier = VerifiedBadge.of(this)
    card("असली नाम + verified टिक", "Settings में सबसे ऊपर यही नाम • लाइसेंस हो तो नाम के आगे टिक") { box ->
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
