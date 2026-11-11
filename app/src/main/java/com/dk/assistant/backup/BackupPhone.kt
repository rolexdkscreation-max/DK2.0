package com.dk.assistant.backup

import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.LearnedWays
import com.dk.assistant.LocalMemory
import com.dk.assistant.Memory

/**
 * नवंबर 2026: फ़ोन वाला हिस्सा - याददाश्त इकट्ठा करना (बैकअप) और बैकअप को जोड़ना (रिस्टोर).
 * ईमेल + आख़िरी बैकअप का समय अलग छोटी फ़ाइल में (API keys वाली Prefs से अलग).
 */
object BackupPhone {
    private const val PREFS = "dk_backup"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun email(c: Context): String = sp(c).getString("email", "").orEmpty()
    fun setEmail(c: Context, e: String) = sp(c).edit().putString("email", MemoryBackup.cleanEmail(e)).apply()
    fun lastBackup(c: Context): Long = sp(c).getLong("last_backup", 0L)
    fun lastInfo(c: Context): String = sp(c).getString("last_info", "").orEmpty()
    fun markBackup(c: Context, at: Long, info: String) = sp(c).edit().putLong("last_backup", at).putString("last_info", info).apply()

    /** सिर्फ़ याददाश्त - और कुछ नहीं */
    fun snapshot(c: Context): MemoryBackup.Snapshot {
        val (facts, history, chat) = Memory.backupFiles(c)
        val (turns, points, rest) = LocalMemory.exportBlocking(c)
        return MemoryBackup.Snapshot(
            at = System.currentTimeMillis(), facts = facts, history = history, chat = chat,
            learned = LearnedWays.rawJson(c), turns = turns, points = points, days = rest.first, dropped = rest.second,
        )
    }

    /** बैकअप को फ़ोन की याद में जोड़ो - वापस: (कितनी बातें जुड़ीं, कितनी याद रखी बातें) */
    fun apply(c: Context, backup: MemoryBackup.Snapshot): Pair<Int, Int> {
        val m = MemoryBackup.merge(snapshot(c), backup)
        val (nt, np) = LocalMemory.importBlocking(c, m.newTurns, m.newPoints, m.days, m.dropped)
        Memory.restoreFiles(c, m.facts, m.history, m.chat)
        m.factsOverflow.forEach { LocalMemory.keepPoint(c, it) }
        LearnedWays.replaceAll(c, m.learned)
        // चैट स्क्रीन खाली हो (नया install) - बैकअप वाले संदेश तुरंत दिखें
        if (com.dk.assistant.DKState.messages.isEmpty()) try { com.dk.assistant.DKState.messages.addAll(Memory.loadChat(c)) } catch (_: Exception) {}
        DKLog.d("बैकअप रिस्टोर: $nt बातें, $np सार, ${m.facts.size} पक्की बातें")
        return nt to (np + m.facts.size)
    }
}
