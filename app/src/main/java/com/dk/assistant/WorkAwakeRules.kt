package com.dk.assistant

/** [WorkAwake] का हिसाब - Android import नहीं -> JVM test */
object WorkAwakeRules {
    /** फ़ोन पर चलने वाले TaskStatus काम (DK कंप्यूटर / कोडिंग टीम server पर चलते हैं - उनके लिए स्क्रीन नहीं जगानी) */
    val REMOTE_KEYS = setOf(TaskStatus.PC, TaskStatus.CODER)

    /** स्क्रीन जगी रखें + पट्टी दिखाएँ? */
    /** सिर्फ़ "WORKING" हालत (कोई असली job नहीं) इससे ज़्यादा = शायद अटकी हालत: स्क्रीन हमेशा जगी न रहे */
    const val MODE_ONLY_MAX_MS = 3 * 60_000L

    /** AgentRunner छोटे सवाल-जवाब में भी busy होता है - इतनी देर से कम + WORKING नहीं = सिर्फ़ बात (पट्टी नहीं, झपक नहीं) */
    const val TALK_GRACE_MS = 4_000L

    fun working(agentBusy: Boolean, mode: Mode, phoneTasks: Int, modeOnlyMs: Long = 0L, busyMs: Long = Long.MAX_VALUE): Boolean =
        phoneTasks > 0 || (agentBusy && (mode == Mode.WORKING || busyMs >= TALK_GRACE_MS)) ||
            (!agentBusy && mode == Mode.WORKING && modeOnlyMs < MODE_ONLY_MAX_MS)

    /** पट्टी का text: पहले चलते काम का असली कदम, फिर स्क्रीन वाला hint, वरना सामान्य */
    fun label(step: String, hint: String): String {
        val s = step.trim().ifBlank { hint.trim() }.replace(Regex("\\s+"), " ")
        return if (s.isBlank()) "DK काम कर रहा है…" else "DK: " + s.take(90)
    }

    /** DK का अपना ऐप सामने = उसकी अपनी screen पहले से दिखाती है (और ScreenAwake स्क्रीन जगाए रखता है) */
    fun showBanner(working: Boolean, fgPkg: String, ownPkg: String): Boolean = working && fgPkg != ownPkg
}
