package com.dk.assistant

/** नवंबर 2026: गलतियों का छोटा इतिहास (शुद्ध नियम - JUnit). नई सबसे ऊपर, आख़िरी [KEEP] तक */
object CrashLog {
    const val KEEP = 5
    const val SEP = "\n=====\n"

    fun push(old: String, entry: String, keep: Int = KEEP): String {
        val prev = old.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
        return (listOf(entry.trim()) + prev).take(keep.coerceAtLeast(1)).joinToString(SEP)
    }

    fun entries(all: String): List<String> = all.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
}
