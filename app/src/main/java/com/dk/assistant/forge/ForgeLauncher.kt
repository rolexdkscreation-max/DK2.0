package com.dk.assistant.forge

import android.content.Context
import android.content.Intent
import com.dk.assistant.DKLog

/**
 * DK AI: Website Forge शुरू करने के तीन रास्ते एक जगह - DK लाइव का build_website tool,
 * लिखी/बोली बात ("ek cafe ki website banao", AgentRunner) और होम का "वेबसाइट" chip.
 */
object ForgeLauncher {
    /** Forge शुरू करो + स्क्रीन खोलो. return = यूज़र को बोलने लायक संदेश + शुरू हुआ या नहीं */
    fun launch(c: Context, prompt: String): Pair<Boolean, String> {
        val r = ForgeController.start(c, prompt)
        val ok = r is ForgeController.StartResult.Started
        if (ok || r is ForgeController.StartResult.Busy) open(c)
        DKLog.d("Forge: " + if (ok) "शुरू" else r.message)
        return ok to r.message
    }

    fun open(c: Context) {
        try {
            c.startActivity(Intent(c, ForgeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ForgeController.EXTRA_OPEN_FORGE, true))
        } catch (e: Exception) { DKLog.d("Forge: स्क्रीन नहीं खुली - ${e.javaClass.simpleName}") }
    }

    /** DK लाइव tool का जवाब (मॉडल के लिए) */
    fun fromVoice(c: Context, prompt: String): String {
        val (ok, msg) = launch(c, prompt)
        return if (ok) "forge_started. The Website Forge screen is open and the website is being built live. Tell the user briefly: $msg"
        else "forge_not_started: $msg Tell the user exactly this."
    }
}
