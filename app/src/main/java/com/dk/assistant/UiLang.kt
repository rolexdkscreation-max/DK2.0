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
 * पूरे ऐप की स्क्रीनों की भाषा: English (शुरुआती) / हिंदी. Settings → भाषा.
 * DK के जवाब की भाषा (Lang: Hinglish/हिंदी/English) इससे अलग है और वैसी ही रहती है.
 * हर Activity खुलते ही (DKApp) उसकी सारी TextView/Button/hint बदलती हैं, और बाद में बदले text भी (layout होते ही).
 * बातचीत/कोड/कहानी जैसे content (select होने वाले text, keep()) नहीं बदलते.
 */
object UiLang {
    const val KEY = "ui_lang"
    const val EN = "en"
    const val HI = "hi"

    @Volatile var code: String = EN
        private set
    @Volatile private var dict: UiText? = null

    fun norm(v: String?) = if (v == HI) HI else EN
    fun en() = code == EN

    fun load(c: Context) {
        code = norm(c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getString(KEY, EN))
        if (en() && dict == null) dict = try {
            UiText(UiText.parse(c.applicationContext.assets.open("ui_en.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() }))
        } catch (e: Exception) { DKLog.d("UI शब्दकोश नहीं खुला: ${e.message}"); null }
    }

    fun set(c: Context, v: String) {
        c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).edit().putString(KEY, norm(v)).apply()
        load(c)
    }

    /** UI का text (English चुनी हो तो English) */
    fun s(t: String): String = if (!en()) t else dict?.tr(t) ?: t

    fun t(t: CharSequence?): CharSequence? {
        if (t == null || !en() || t.none { UiText.dev(it) }) return t
        if (t is Spanned && t.getSpans(0, t.length, Any::class.java).isNotEmpty()) return t
        return s(t.toString())
    }

    fun toast(c: Context, text: CharSequence, d: Int): Toast = Toast.makeText(c, t(text), d)

    /** यह view (और अंदर का सब) अनुवाद नहीं होगा - बातचीत/यादें/कहानी */
    fun keep(v: View) { v.setTag(R.id.ui_keep, true) }

    fun apply(v: View) {
        if (!en() || v.getTag(R.id.ui_keep) == true) return
        if (v is TextView) {
            if (v is EditText) { v.hint?.let { h -> t(h).let { if (it !== h && it != h) v.hint = it } } }
            else if (!v.isTextSelectable) {
                val cur = v.text
                val n = t(cur)
                if (n !== cur && n.toString() != cur.toString()) v.text = n
                v.hint?.let { h -> t(h).let { if (it.toString() != h.toString()) v.hint = it } }
            }
        }
        v.contentDescription?.let { d -> t(d).let { if (it.toString() != d.toString()) v.contentDescription = it } }
        if (v is ViewGroup) for (i in 0 until v.childCount) apply(v.getChildAt(i))
    }

    /** root पर अभी + हर नए layout पर (बाद में बदला text भी) */
    fun watch(root: View) {
        if (root.getTag(R.id.ui_stamp) != null) return
        root.setTag(R.id.ui_stamp, code)
        if (!en()) return
        apply(root)
        root.viewTreeObserver.addOnGlobalLayoutListener { try { apply(root) } catch (_: Exception) {} }
    }

    /** DKApp: हर screen पर. भाषा बदली हो तो screen नई बनती है (पुराने text वापस) */
    fun onResumed(a: Activity) {
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
        d.window?.decorView?.let { v -> v.post { UiLang.watch(v) } }
        return d
    }
}
