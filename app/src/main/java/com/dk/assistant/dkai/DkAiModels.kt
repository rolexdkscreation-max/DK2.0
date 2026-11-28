package com.dk.assistant.dkai

import android.content.Context
import com.dk.assistant.AiRouter
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs

/**
 * 28 नवंबर 2026 (मालिक): "एक key से सब" (ऑल-राउंडर) वाली key लगी हो = चिप की सूची में सिर्फ़ उसी एक कंपनी के मॉडल आते थे.
 * अब DK सर्वर (backend) के सारे चलने लायक मॉडल भी - नाम "DK AI ..." ([DkAiNames]), सबसे ताकतवर ऊपर.
 * चुना = सर्वर पर वही मॉडल पहले; न चले = चुपचाप बाकी (अपनी keys → One AI → सर्वर की बाकी कड़ी). असली id अंदर ही.
 */
object DkAiModels {
    /** चिप में सेव कंपनी की id (= [AiRouter.PROVIDER_ID]) */
    const val ID = AiRouter.PROVIDER_ID
    const val TITLE = "DK AI • DK सर्वर के मॉडल"
    private const val PREF = "dk_server_models"
    private const val PREF_AT = "dk_server_models_at"
    private const val CACHE_MS = 30 * 60_000L

    /** सूची दिखे? ऑल-राउंडर key + सर्वर इस user के लिए चालू */
    fun show(c: Context): Boolean = Prefs.oneKeyOn(c) && AiRouter.backendReady() && AiRouter.serverAiOk()

    fun cached(c: Context): List<Pair<String, String>> =
        Prefs.raw(c, PREF).split('\n').mapNotNull { DkAiNames.decode(it) }

    /** पीछे से ताज़ा सूची (IO thread). true = कुछ बदला. गड़बड़ = पुरानी सूची, कोई गलती नहीं दिखती */
    fun refresh(c: Context, force: Boolean = false): Boolean {
        if (!show(c)) return false
        val at = Prefs.str(c, PREF_AT, "0").toLongOrNull() ?: 0L
        if (!force && cached(c).isNotEmpty() && System.currentTimeMillis() - at < CACHE_MS) return false
        val fresh = try { com.dk.assistant.backend.BackendApi.serverModels(c.applicationContext) } catch (e: Exception) {
            DKLog.d("DK AI मॉडल सूची: ${e.message?.take(60)}"); return false
        }
        val old = cached(c)
        Prefs.edit(c) {
            putString(PREF, fresh.joinToString("\n") { DkAiNames.encode(it.first, it.second) })
            putString(PREF_AT, System.currentTimeMillis().toString())
        }
        return fresh != old
    }

    /** चिप का group (सबसे ताकतवर ऊपर). दिखाना नहीं / सूची खाली = null */
    fun group(c: Context): ModelCatalog.Group? {
        if (!show(c)) return null
        return groupOf(cached(c))
    }

    /** Pure: (कड़ी, मॉडल) की सूची -> group */
    fun groupOf(list: List<Pair<String, String>>): ModelCatalog.Group? {
        val rows = DkAiNames.rank(list)
        if (rows.isEmpty()) return null
        return ModelCatalog.Group(ID, TITLE, rows.map { r ->
            ModelCatalog.Entry(ID, DkAiNames.encode(r.provider, r.model), r.label, r.tier, false, false)
        })
    }

    /** चिप में अभी "DK AI ..." चुना है = (कड़ी, मॉडल). नहीं = null */
    fun picked(c: Context): Pair<String, String>? =
        SelectedModel.get(c)?.takeIf { it.provider == ID }?.let { DkAiNames.decode(it.model) }

    /** चिप पर दिखने वाला नाम */
    fun labelOf(c: Context, id: String): String {
        val (p, m) = DkAiNames.decode(id) ?: return DkAiNames.BRAND
        return DkAiNames.rank(cached(c)).firstOrNull { it.provider == p && it.model == m }?.label ?: DkAiNames.label(p, m)
    }
}
