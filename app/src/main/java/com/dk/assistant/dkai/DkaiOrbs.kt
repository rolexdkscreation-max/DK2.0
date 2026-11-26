package com.dk.assistant.dkai

import android.content.Context

/**
 * DKAI के orb - 13 नवंबर 2026 (मालिक): "Terminal वाले orb को बदलने के लिए 4-5 नए orb, या पुराना वाला".
 * pref "dkai_orb" (DKAI होम + mini orb दोनों में). "classic" = Settings वाला पुराना DK orb (उसकी shape "orb_style").
 */
object DkaiOrbs {
    const val PREF = "dkai_orb"
    const val FLUID = "fluid"
    const val AURORA = "aurora"
    const val SUNSET = "sunset"
    const val REACTOR = "reactor"
    const val SOLAR = "solar"
    const val CRYSTAL = "crystal"
    const val SWARM = "swarm"
    const val CLASSIC = "classic"

    /** key to नाम (मेनू में यही दिखता) */
    val ORBS: List<Pair<String, String>> = listOf(
        FLUID to "Fluid (नीला-बैंगनी)",
        AURORA to "Aurora (हरा-फ़िरोज़ी)",
        SUNSET to "Sunset (नारंगी-गुलाबी)",
        REACTOR to "Arc Reactor (घूमते छल्ले)",
        SOLAR to "Solar (सूरज की किरणें)",
        CRYSTAL to "Crystal (घूमता क्रिस्टल)",
        SWARM to "Particle Swarm (डॉट का गोला)",
        CLASSIC to "पुराना DK orb (Settings वाला)",
    )

    fun valid(k: String?): String = if (ORBS.any { it.first == k }) k!! else FLUID

    fun label(k: String?): String = ORBS.first { it.first == valid(k) }.second

    /** 3 रंग: शुरू, बीच, आख़िर */
    fun palette(k: String): IntArray = when (k) {
        AURORA -> intArrayOf(0xFF34D399.toInt(), 0xFF14B8A6.toInt(), 0xFF22D3EE.toInt())
        SUNSET -> intArrayOf(0xFFFB923C.toInt(), 0xFFF43F5E.toInt(), 0xFFEC4899.toInt())
        REACTOR -> intArrayOf(0xFF67E8F9.toInt(), 0xFF0EA5E9.toInt(), 0xFFE0F2FE.toInt())
        SOLAR -> intArrayOf(0xFFFACC15.toInt(), 0xFFF97316.toInt(), 0xFFFDE68A.toInt())
        CRYSTAL -> intArrayOf(0xFFA78BFA.toInt(), 0xFF6366F1.toInt(), 0xFF67E8F9.toInt())
        SWARM -> intArrayOf(0xFF22D3EE.toInt(), 0xFF8B5CF6.toInt(), 0xFFF472B6.toInt())
        else -> intArrayOf(0xFF00E5FF.toInt(), 0xFF3B82F6.toInt(), 0xFFA855F7.toInt())
    }

    /** रंग + alpha (0..255) */
    fun a(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

    /**
     * orb चुनने की सूची (DKAI orb पर टैप / ☰ मेनू). "पुराना DK orb" चुना = दूसरी सूची: उसकी shape (Settings वाली "orb_style", पुराने होम में भी वही).
     */
    fun pick(a: android.app.Activity, onDone: () -> Unit) {
        val cur = get(a)
        val names = ORBS.map { it.second }.toTypedArray()
        android.app.AlertDialog.Builder(a).setTitle("Orb बदलें")
            .setSingleChoiceItems(names, ORBS.indexOfFirst { it.first == cur }) { d, i ->
                d.dismiss()
                val k = ORBS[i].first
                set(a, k)
                if (k == CLASSIC) classic(a, onDone) else { onDone(); toast(a, "Orb: " + ORBS[i].second) }
            }.setNegativeButton("रद्द करें", null).show()
    }

    private fun classic(a: android.app.Activity, onDone: () -> Unit) {
        val st = com.dk.assistant.Shapes.STYLES
        val cur = com.dk.assistant.Prefs.orbStyle(a)
        android.app.AlertDialog.Builder(a).setTitle("पुराने DK orb का आकार")
            .setSingleChoiceItems(st.map { it.second }.toTypedArray(), st.indexOfFirst { it.first == cur }) { d, i ->
                d.dismiss()
                com.dk.assistant.Prefs.edit(a) { putString("orb_style", st[i].first) }
                onDone(); toast(a, "Orb: " + st[i].second)
            }.setNegativeButton("यही रहने दें") { _, _ -> onDone() }.show()
    }

    private fun toast(a: android.app.Activity, s: String) = android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun prefs(c: Context) = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
    fun get(c: Context): String = valid(try { prefs(c).getString(PREF, FLUID) } catch (_: Exception) { FLUID })
    fun set(c: Context, k: String) { prefs(c).edit().putString(PREF, valid(k)).apply() }
}
