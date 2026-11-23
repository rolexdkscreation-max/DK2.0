package com.dk.assistant.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * 23 नवंबर 2026 (मालिक: "हर पॉपअप साफ़ दिखे - ठोस पीछे, साफ़ लिखावट, पीछे हल्का काला पर्दा, हर थीम में"):
 * पूरे ऐप के हर डायलॉग / अलर्ट / बॉटम शीट / ड्रॉपडाउन का एक ही साझा रूप.
 *
 *  - [style] किसी भी पॉपअप विंडो की जड़ (decor view) लेता है: पीछे पर्दा ([DIM]), अलर्ट हो तो ठोस Clay पीछा
 *    ([ClayDialogDrawable], पारदर्शी नहीं), और हर लिखावट/बटन का रंग थीम से - कम दिखे तो ठीक.
 *  - [install] (DKApp) ऐप में खुलने वाली हर नई विंडो पर अपने-आप [style] चलाता है - कोई पॉपअप छूटे नहीं.
 *  - रंग सिर्फ़ [ClayTheme.t] से. Pure हिस्सा ([readable], [fixColor], [isPopupType]) JVM test में.
 */
object ClayPopup {
    /** पीछे की स्क्रीन पर काला पर्दा (0..1) */
    const val DIM = 0.6f
    /** लिखावट बनाम पीछे का कम-से-कम फ़र्क़ (WCAG AA) */
    const val MIN_TEXT = 4.5
    const val MIN_BUTTON = 3.0
    private const val TAG = 0x0C1A7D01

    /** pure: लिखावट [fg] पीछे [bg] पर पढ़ी जा सकती है? */
    fun readable(fg: Int, bg: Int, min: Double = MIN_TEXT): Boolean =
        ((fg ushr 24) and 0xFF) >= 0xB0 && NeonTheme.contrast(fg or (0xFF shl 24), bg or (0xFF shl 24)) >= min

    /**
     * pure: पॉपअप की लिखावट का रंग. बटन = accent (फ़र्क़ कम हो तो मुख्य लिखावट), बाकी: पढ़ा जाए तो वही, वरना थीम की लिखावट.
     */
    fun fixColor(cur: Int, button: Boolean, k: ClayTheme.Tokens): Int = when {
        button -> if (readable(k.accent, k.surface, MIN_BUTTON)) k.accent else k.text
        readable(cur, k.surface) -> cur
        else -> k.text
    }

    /** pure: कौन-सी विंडो पॉपअप है (dialog / panel / sub-panel) - activity की अपनी विंडो और toast नहीं */
    fun isPopupType(type: Int): Boolean =
        type == WindowManager.LayoutParams.TYPE_APPLICATION ||
            type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL ||
            type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL ||
            type == WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG

    private val ALERT_IDS = setOf("parentPanel", "topPanel", "contentPanel", "buttonPanel", "alertTitle")
    private val BUTTON_IDS = setOf("button1", "button2", "button3")

    private fun name(v: View): String = try { if (v.id == View.NO_ID) "" else v.resources.getResourceEntryName(v.id) } catch (e: Exception) { "" }

    private fun find(v: View, names: Set<String>): Boolean {
        if (name(v) in names) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (find(v.getChildAt(i), names)) return true
        return false
    }

    private fun hasList(v: View): Boolean {
        if (v is android.widget.AbsListView) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hasList(v.getChildAt(i))) return true
        return false
    }

    /** किसी पॉपअप विंडो की जड़ ([root] = decor view) को साझा रूप दो. बार-बार चलाना सुरक्षित. */
    fun style(root: View) {
        try {
            val lp = root.layoutParams as? WindowManager.LayoutParams
            val dialog = lp == null || lp.type == WindowManager.LayoutParams.TYPE_APPLICATION ||
                lp.type == WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            if (lp != null && dialog && (lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND == 0 || lp.dimAmount < DIM)) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                lp.dimAmount = DIM
                try { (root.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(root, lp) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
            }
            val alert = find(root, ALERT_IDS)
            // अलर्ट: पीछा ठोस Clay (थीम का surface - पारदर्शी नहीं)
            if (alert && root.getTag(TAG) == null) {
                root.background = ClayDialogDrawable()
                root.setTag(TAG, true)
                if (root is ViewGroup) root.viewTreeObserver.addOnGlobalLayoutListener { recolor(root, true) }
            }
            // ड्रॉपडाउन / पॉपअप मेन्यू (सूची वाला): ठोस Clay पीछा + साफ़ लिखावट
            val list = !alert && lp != null && !dialog && hasList(root)
            if (list && root.getTag(TAG) == null) {
                root.background = ClayDrawable(ClayDrawable.Style.RAISED, radiusPx = 16f * root.resources.displayMetrics.density)
                root.setTag(TAG, true)
            }
            if (alert || list) recolor(root, true)
        } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
    }

    /** पॉपअप के अंदर हर लिखावट / बटन / hint थीम के हिसाब से पढ़ने लायक */
    fun recolor(v: View, onSurface: Boolean) {
        val k = ClayTheme.t
        // अपना अलग ठोस पीछा (जैसे accent बटन) हो तो उसके अंदर नहीं छेड़ते
        val own = v.background
        val inside = onSurface && (v.getTag(TAG) != null || !solidOwn(own))
        if (v is TextView && inside) {
            val btn = v is Button || name(v) in BUTTON_IDS
            val want = fixColor(v.currentTextColor, btn, k)
            if (want != v.currentTextColor) v.setTextColor(want)
            if (v is EditText || v.hint != null) {
                val h = v.currentHintTextColor
                if (!readable(h, k.surface, MIN_BUTTON)) v.setHintTextColor(k.hint)
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) recolor(v.getChildAt(i), inside)
    }

    /** अपना अलग ठोस रंग वाला पीछा? (accent बटन, रंगीन डिब्बा) - तब अंदर की लिखावट उसी के हिसाब से है */
    private fun solidOwn(d: android.graphics.drawable.Drawable?): Boolean = when (d) {
        null -> false
        is ClayDialogDrawable -> false
        is ClayDrawable -> d.style == ClayDrawable.Style.ACCENT
        is android.graphics.drawable.ColorDrawable -> ((d.color ushr 24) and 0xFF) >= 0x80
        is android.graphics.drawable.GradientDrawable -> true
        else -> false
    }

    @Volatile private var installed = false

    /**
     * ऐप की हर नई विंडो पर [style] - Android की अपनी विंडो-सूची देखकर (कोई पॉपअप छूटे नहीं, चाहे कहीं भी बना हो).
     * न चल पाए (नया Android / रोक) तो चुपचाप छोड़ो - तब भी [com.dk.assistant.UiDialog] और XML थीम से वही रूप.
     */
    @Suppress("PrivateApi", "DiscouragedPrivateApi", "UNCHECKED_CAST")
    fun install() {
        if (installed) return
        try {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val g = cls.getMethod("getInstance").invoke(null)
            val f = cls.getDeclaredField("mViews").apply { isAccessible = true }
            val old = f.get(g) as ArrayList<View>
            val watch = object : ArrayList<View>(old) {
                override fun add(element: View): Boolean {
                    val r = super.add(element)
                    try {
                        val lp = element.layoutParams as? WindowManager.LayoutParams
                        if (lp == null || isPopupType(lp.type)) element.post { style(element) }
                    } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
                    return r
                }
            }
            f.set(g, watch)
            installed = true
        } catch (e: Throwable) { com.dk.assistant.Quiet.note(Exception(e)) }
    }
}
