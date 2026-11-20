package com.dk.assistant

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 20 नवंबर 2026: फ़ोन वाली याद (SQLite v2 + FTS4) - पुरानी बातें बदलते समय न मिटें, खोज हिंदी/English में,
 * ऐप बंद-खोलने पर बातचीत मौजूद, 1000 संदेशों में खोज 50 ms से कम.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HistoryDbTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun fresh() {
        LocalMemory.resetForTest()
        for (n in listOf(LocalMemory.DB_NAME, LocalMemory.BACKUP_NAME)) c.deleteDatabase(n)
    }

    private fun all(q: String, now: Long = System.currentTimeMillis()) =
        LocalMemory.searchRowsBlocking(c, HistoryOnDemand.request(q, null, null, 5, now))

    @Test fun upgradeFromV1KeepsEverythingBacksUpAndFillsFts() {
        val f = c.getDatabasePath(LocalMemory.DB_NAME); f.parentFile?.mkdirs()
        val now = System.currentTimeMillis()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { d ->
            d.execSQL("CREATE TABLE turns(id INTEGER PRIMARY KEY AUTOINCREMENT, who TEXT NOT NULL, text TEXT NOT NULL, at INTEGER NOT NULL, src TEXT NOT NULL DEFAULT '', day TEXT NOT NULL)")
            d.execSQL("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, text TEXT NOT NULL, at INTEGER NOT NULL)")
            d.execSQL("CREATE TABLE days(day TEXT PRIMARY KEY, done INTEGER NOT NULL DEFAULT 1)")
            d.execSQL("CREATE TABLE dropped(norm TEXT PRIMARY KEY)")
            for (i in 1..50) d.execSQL("INSERT INTO turns(who, text, at, src, day) VALUES (?, ?, ?, 'voice', '2026-11-01')",
                arrayOf<Any?>(if (i % 2 == 1) "u" else "d", if (i == 7) "मेरी Bike का service कब है?" else "पुरानी बात $i", now - (60 - i) * 60_000L))
            d.version = 1
        }
        LocalMemory.resetForTest()
        assertEquals(50, LocalMemory.countForTest(c, "turns"))                   // कुछ नहीं मिटा
        assertEquals(50, LocalMemory.countForTest(c, "turns_backup_v1"))         // DB के अंदर प्रति
        assertTrue(c.getDatabasePath(LocalMemory.BACKUP_NAME).exists())          // फ़ाइल का बैकअप
        if (LocalMemory.ftsOn(c)) assertEquals(50, LocalMemory.countForTest(c, "turns_fts"))   // पुरानी सब बातें FTS में
        val hit = all("bike के बारे में मैंने क्या पूछा था", now)
        assertEquals("मेरी Bike का service कब है?", hit.first().text)            // बड़े-छोटे अक्षर एक
    }

    @Test fun searchHindiEnglishOrAndRecent() {
        val now = System.currentTimeMillis()
        LocalMemory.insertForTest(c, "u", "मुझे नया मोबाइल लेना है", now - 3 * 60_000L)
        LocalMemory.insertForTest(c, "d", "Samsung का फ़ोन अच्छा रहेगा।", now - 2 * 60_000L)
        LocalMemory.insertForTest(c, "u", "आज खाने में क्या बनाऊँ", now - 60_000L)
        val h = all("मोबाइल samsung के बारे में क्या बात हुई थी", now)
        assertEquals(2, h.size)                                                   // हर शब्द अलग (OR)
        assertTrue(h.any { it.text.startsWith("Samsung") } && h.any { it.text.contains("मोबाइल") })
        val last = all("पिछली बार क्या पूछा था", now)                            // बिना शब्द = सबसे हाल की
        assertEquals("आज खाने में क्या बनाऊँ", last.first().text)
        val json = JSONObject(runBlocking { LocalMemory.searchHistory(c, "samsung", null, null, 5) })
        assertEquals(1, json.getInt("count"))
        assertEquals("DK", json.getJSONArray("results").getJSONObject(0).getString("who"))
    }

    // ---- टेस्ट 4: ऐप बंद-खोलने पर बातचीत मौजूद
    @Test fun survivesReopen() {
        val now = System.currentTimeMillis()
        LocalMemory.insertForTest(c, "u", "मेरा नाम राहुल है", now)
        LocalMemory.resetForTest()                                               // ऐप बंद
        assertEquals(1, LocalMemory.countForTest(c, "turns"))                    // फिर खुला
        assertEquals("मेरा नाम राहुल है", all("राहुल", now + 1000).first().text)
    }

    // ---- टेस्ट 5: 1000 संदेशों में खोज 50 ms से कम
    @Test fun thousandMessagesUnder50ms() {
        val now = System.currentTimeMillis()
        for (i in 0 until 1000) LocalMemory.insertForTest(c, if (i % 2 == 0) "u" else "d",
            if (i == 124) "मेरी दवाई का समय रात 9 बजे है" else "आम बात नंबर $i कुछ शब्द hello world", now - (1000 - i) * 30_000L)
        all("दवाई", now)                                                         // पहली बार (warm-up)
        val req = HistoryOnDemand.request("दवाई के बारे में मैंने क्या बताया था", null, null, 5, now)
        val t0 = System.nanoTime()
        val h = LocalMemory.searchRowsBlocking(c, req)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("मेरी दवाई का समय रात 9 बजे है", h.first().text)
        assertTrue("खोज $ms ms", ms < 50)
    }

    @Test fun deleteRemovesFromSearchToo() {
        val now = System.currentTimeMillis()
        val id = LocalMemory.insertForTest(c, "u", "गुप्त बात pizza", now)
        assertEquals(1, all("pizza", now).size)
        runBlocking { LocalMemory.deleteTurn(c, id) }
        assertTrue(all("pizza", now).isEmpty())
        assertEquals(0, LocalMemory.countForTest(c, "turns"))
    }
}
