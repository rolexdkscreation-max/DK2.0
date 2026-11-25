package com.dk.assistant

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * पूरे ऐप की स्क्रीनों की भाषा: English (शुरुआती) / हिंदी / Hinglish / Custom (दुनिया की कोई भी). Settings → भाषा.
 * DK के जवाब की भाषा (Lang: Hinglish/हिंदी/English) इससे अलग है और वैसी ही रहती है.
 * हर Activity खुलते ही (DKApp) उसकी सारी TextView/Button/hint बदलती हैं, और बाद में बदले text भी (layout होते ही).
 * बातचीत/कोड/कहानी जैसे content (select होने वाले text, keep()) नहीं बदलते.
 */
object UiLang {
    const val KEY = "ui_lang"
    const val EN = "en"
    const val HI = "hi"
    /** 25 नवंबर 2026 (मालिक: "तीन आने चाहिए - हिंदी, English और Hinglish"): हिंदी रोमन अक्षरों में ([HinglishText]) */
    const val HINGLISH = "hien"

    @Volatile var code: String = EN
        private set
    @Volatile private var dict: UiText? = null
    @Volatile private var hinglish: HinglishText? = null

    /** हिंदी / English / Hinglish / Custom (दुनिया की कोई भी भाषा - ISO code, जैसे "fr", "ta", "mr") */
    fun norm(v: String?): String = when {
        v == HI || v == EN || v == HINGLISH -> v
        v != null && CUSTOM.matches(v) -> v
        else -> EN
    }
    private val CUSTOM = Regex("^[a-z]{2,3}(-[A-Za-z]{2,4})?$")

    /** हिंदी नहीं = स्क्रीनें बदलनी हैं (English / Hinglish / Custom) */
    fun en() = code != HI
    fun hi() = code == HI
    fun custom(c: String = code) = c != HI && c != EN && c != HINGLISH

    /** Settings में दिखने वाला नाम */
    fun label(c: String = code): String = when (c) {
        HI -> "हिंदी"; EN -> "English"; HINGLISH -> "Hinglish"
        else -> WorldLangs.find(c)?.let { if (it.native.isNotBlank()) it.native else it.english } ?: c
    }

    fun load(c: Context) {
        code = norm(c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getString(KEY, EN))
        UiMt.init(c)
        if (en() && dict == null) dict = try {
            UiText(UiText.parse(c.applicationContext.assets.open("ui_en.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() }))
        } catch (e: Exception) { DKLog.d("UI शब्दकोश नहीं खुला: ${e.message}"); null }
        if (code == HINGLISH && hinglish == null) hinglish = try {
            HinglishText(UiText.parse(c.applicationContext.assets.open("ui_en.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() }))
        } catch (e: Exception) { DKLog.d("Hinglish शब्द नहीं खुले: ${e.message}"); HinglishText() }
    }

    fun set(c: Context, v: String) {
        c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).edit().putString(KEY, norm(v)).apply()
        UiMt.clearPending()
        load(c)
    }

    /**
     * UI का text चुनी भाषा में:
     *  - English: शब्दकोश; उसमें न हो (हिंदी बची) तो फ़ोन वाला अनुवाद ([UiMt]) - तैयार होते ही स्क्रीन बदलती है
     *  - Hinglish: रोमन अक्षर ([HinglishText])
     *  - Custom: शब्दकोश वाली English से (या हिंदी से) उस भाषा में ([UiMt]); तैयार होने तक English
     */
    fun s(t: String): String {
        if (!en()) return t
        val d = dict
        return when (code) {
            HINGLISH -> (hinglish ?: HinglishText().also { hinglish = it }).tr(t)
            EN -> {
                val e = d?.tr(t) ?: t
                if (e.none { UiText.dev(it) }) e
                else UiMt.get(EN, t) ?: run { UiMt.want(EN, t, t, HI); e }
            }
            else -> {
                UiMt.get(code, t) ?: run {
                    val e = d?.tr(t) ?: t
                    if (e.none { UiText.dev(it) }) UiMt.want(code, t, e, EN) else UiMt.want(code, t, t, HI)
                    UiMt.get(EN, t) ?: e
                }
            }
        }
    }

    fun t(t: CharSequence?): CharSequence? {
        if (t == null || !en() || t.none { UiText.dev(it) }) return t
        if (t is Spanned && t.getSpans(0, t.length, Any::class.java).isNotEmpty()) return t
        return s(t.toString())
    }

    /** सामने वाली स्क्रीन (अनुवाद तैयार होते ही [refresh] उसी को नई लिखावट देता है) */
    @Volatile private var top: java.lang.ref.WeakReference<Activity>? = null

    /** [UiMt] का अनुवाद आया - सामने वाली स्क्रीन फिर से (main thread) */
    fun refresh() {
        val a = top?.get() ?: return
        if (a.isFinishing || a.isDestroyed) return
        val root = a.window?.decorView ?: return
        try { if (apply(root)) root.requestLayout() } catch (e: Exception) { Quiet.note(e) }
    }

    fun toast(c: Context, text: CharSequence, d: Int): Toast = Toast.makeText(c, t(text), d)

    /**
     * v3.41: वही text दोबारा लगाना = बेवजह पूरी screen का नया layout (+ अनुवाद की जाँच). बदला हो तभी लगाओ.
     * (दिखने वाला text अनुवादित होता है, इसलिए असली (हिंदी) text tag में याद रखते हैं)
     */
    fun setText(v: TextView, s: CharSequence) {
        val raw = s.toString()
        if (v.getTag(R.id.ui_raw) == raw) {
            val cur = v.text.toString()
            if (cur == raw || cur == t(raw)?.toString()) return      // किसी और ने बीच में बदला हो तो फिर से लगाओ
        }
        v.setTag(R.id.ui_raw, raw)
        v.text = s
    }

    /** यह view (और अंदर का सब) अनुवाद नहीं होगा - बातचीत/यादें/कहानी */
    fun keep(v: View) { v.setTag(R.id.ui_keep, true) }

    /** true = कुछ text बदला (तब यह layout पुराने हिंदी नाप का था) */
    fun apply(v: View): Boolean {
        if (!en() || v.getTag(R.id.ui_keep) == true) return false
        var changed = false
        if (v is TextView) {
            if (v is EditText) { v.hint?.let { h -> t(h).let { if (it !== h && it != h) { v.hint = it; changed = true } } } }
            else if (!v.isTextSelectable) {
                val cur = v.text
                // 25 नवंबर 2026: पहले बदला text (English) दिख रहा हो तो असली हिंदी से फिर - अनुवाद बाद में आए तब भी बदले
                val src: CharSequence? = (v.getTag(R.id.ui_src) as? String)?.takeIf { v.getTag(R.id.ui_out) == cur?.toString() } ?: cur
                val n = t(src)
                if (n != null && n.toString() != cur?.toString()) {
                    v.text = n; changed = true
                    v.setTag(R.id.ui_src, src?.toString()); v.setTag(R.id.ui_out, n.toString())
                }
                v.hint?.let { h -> t(h).let { if (it.toString() != h.toString()) { v.hint = it; changed = true } } }
            }
        }
        v.contentDescription?.let { d -> t(d).let { if (it.toString() != d.toString()) v.contentDescription = it } }
        if (v is ViewGroup) for (i in 0 until v.childCount) if (apply(v.getChildAt(i))) changed = true
        return changed
    }

    /** root पर अभी + हर नए layout पर (बाद में बदला text भी) */
    fun watch(root: View) {
        if (root.getTag(R.id.ui_stamp) != null) return
        root.setTag(R.id.ui_stamp, code)
        if (!en()) return
        apply(root)
        // v3.40 (UI हिलना): code हिंदी text लगाता है -> layout हिंदी के नाप (ऊँची लाइनें/अलग wrap) से -> यहाँ English.
        // पहले वही हिंदी-नाप वाला frame draw हो जाता था, अगले frame में English नाप = हर update (download %, DK बोले,
        // कोडिंग/कंप्यूटर का कदम) पर पूरा card ऊपर-नीचे झटका. अब text बदला तो वह frame draw नहीं (Android अगला
        // layout खुद करता है) - सिर्फ़ सही नाप वाला frame दिखता है.
        val skip = DrawSkip()
        root.viewTreeObserver.addOnGlobalLayoutListener { try { if (apply(root)) skip.changed() } catch (e: Exception) { Quiet.note(e) } }
        root.viewTreeObserver.addOnPreDrawListener { skip.draw() }
    }

    /** DKApp: हर screen पर. भाषा बदली हो तो screen नई बनती है (पुराने text वापस) */
    fun onResumed(a: Activity) {
        top = java.lang.ref.WeakReference(a)
        val root = a.window?.decorView ?: return
        val stamp = root.getTag(R.id.ui_stamp)
        if (stamp != null && stamp != code) { a.recreate(); return }
        watch(root)
    }
}

/** android.app.AlertDialog.Builder जैसा - title/message/बटन/लिस्ट UI भाषा में */
class UiDialog(c: Context, theme: Int = 0) : android.app.AlertDialog.Builder(c, theme) {
    override fun setTitle(title: CharSequence?): android.app.AlertDialog.Builder = super.setTitle(UiLang.t(title))
    override fun setMessage(message: CharSequence?): android.app.AlertDialog.Builder = super.setMessage(UiLang.t(message))
    override fun setPositiveButton(text: CharSequence?, l: DialogInterface.OnClickListener?): android.app.AlertDialog.Builder =
        super.setPositiveButton(UiLang.t(text), l)
    override fun setNegativeButton(text: CharSequence?, l: DialogInterface.OnClickListener?): android.app.AlertDialog.Builder =
        super.setNegativeButton(UiLang.t(text), l)
    override fun setNeutralButton(text: CharSequence?, l: DialogInterface.OnClickListener?): android.app.AlertDialog.Builder =
        super.setNeutralButton(UiLang.t(text), l)
    override fun setItems(items: Array<out CharSequence>?, l: DialogInterface.OnClickListener?): android.app.AlertDialog.Builder =
        super.setItems(items?.map { UiLang.t(it) ?: "" }?.toTypedArray(), l)
    override fun setSingleChoiceItems(items: Array<out CharSequence>?, checked: Int, l: DialogInterface.OnClickListener?): android.app.AlertDialog.Builder =
        super.setSingleChoiceItems(items?.map { UiLang.t(it) ?: "" }?.toTypedArray(), checked, l)
    override fun create(): android.app.AlertDialog {
        val d = super.create()
        d.window?.decorView?.let { v -> v.post { UiLang.watch(v); com.dk.assistant.ui.ClayPopup.style(v) } }
        return d
    }
}
