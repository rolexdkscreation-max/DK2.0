package com.dk.assistant.backup

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): "ईमेल डालो → DK की याददाश्त उसके Google Drive में बैकअप; ऐप दोबारा डालकर वही ईमेल → रिस्टोर.
 * सिर्फ़ याददाश्त, और कुछ नहीं. सेटिंग्स में बैकअप → अंदर बैकअप और रिस्टोर का बटन."
 */
class MemoryBackupTest {
    private fun src(p: String) = listOf(File(p), File("app/$p")).first { it.exists() }.readText()

    private val t1 = MemoryBackup.T("u", "मेरी बहन की शादी 12 दिसंबर को है", 1_000L, "voice", "2026-01-01")
    private val t2 = MemoryBackup.T("d", "ठीक है, याद रखूँगी", 1_500L, "voice", "2026-01-01")
    private val t3 = MemoryBackup.T("u", "कल गोवा जाना है", 9_000L, "text", "2026-11-10")

    private fun snap(
        turns: List<MemoryBackup.T> = listOf(t1, t2), facts: List<String> = listOf("मेरी बाइक MH12 AB 1234"),
        chat: String = """[{"r":"u","t":"नमस्ते","w":1}]""", learned: String = """[{"t":"पहले लक्ष्य साफ़ लिखो","f":"Claude","p":"","a":1}]""",
    ) = MemoryBackup.Snapshot(
        // org.json का क्रम (HashMap) - तुलना के लिए पहले से उसी रूप में
        at = 5_000L, facts = facts, history = JSONArray("""[{"r":"user","t":"hi"}]""").toString(), chat = JSONArray(chat).toString(), learned = JSONArray(learned).toString(),
        turns = turns, points = listOf(MemoryBackup.P("2026-01-01", "बहन की शादी 12 दिसंबर", 1_000L)),
        days = listOf("2026-01-01"), dropped = listOf("पुरानी बात"),
    )

    @Test fun email_check() {
        assertTrue(MemoryBackup.validEmail("Ram.Kumar@Gmail.com "))
        assertEquals("ram.kumar@gmail.com", MemoryBackup.cleanEmail(" Ram.Kumar@Gmail.com "))
        for (bad in listOf("", "ram", "ram@", "@gmail.com", "ram@gmail", "ram kumar@gmail.com")) assertFalse(bad, MemoryBackup.validEmail(bad))
    }

    @Test fun packUnpack_roundTrip_sameMemory() {
        val s = snap()
        val back = MemoryBackup.unpack(MemoryBackup.pack(s))
        assertEquals(s, back)
        assertEquals(2, back!!.talkCount)
        assertEquals(3, back.keptCount)          // 1 fact + 1 point + 1 सीखा तरीका
        // gzip: बड़ी याद छोटी फ़ाइल
        val big = snap(turns = (1..2000).map { MemoryBackup.T("u", "बात नंबर $it - आज का मौसम अच्छा है", it.toLong(), "voice", "2026-01-01") })
        assertTrue(MemoryBackup.pack(big).size < MemoryBackup.toJson(big).toByteArray().size / 4)
    }

    @Test fun onlyMemory_isInBackup_nothingElse() {
        val o = JSONObject(MemoryBackup.toJson(snap()))
        assertEquals(setOf("kind", "v", "at", "facts", "history", "chat", "learned", "turns", "points", "days", "dropped"), o.keys().asSequence().toSet())
        // फ़ोन वाला हिस्सा भी सिर्फ़ याद पढ़ता है - keys / Prefs / फ़ोटो नहीं
        val phone = src("src/main/java/com/dk/assistant/backup/BackupPhone.kt")
        for (no in listOf("Prefs.", "Key", "license", "wallpaper", "photo")) assertFalse(no, phone.substringAfter("fun snapshot").substringBefore("fun apply").contains(no))
        // सिर्फ़ DK का छिपा Drive फ़ोल्डर - user की पूरी Drive नहीं
        assertEquals("https://www.googleapis.com/auth/drive.appdata", MemoryBackup.SCOPE)
    }

    @Test fun wrongFile_isNotRestored() {
        assertNull(MemoryBackup.fromJson("""{"kind":"something_else","turns":[]}"""))
        assertNull(MemoryBackup.fromJson("not json"))
        assertNull(MemoryBackup.unpack("hello".toByteArray()))
    }

    @Test fun restore_addsOnly_neverRemoves() {
        val phone = snap(turns = listOf(t2, t3), facts = listOf("मेरा नाम दीपक"), chat = """[{"r":"u","t":"नया फ़ोन","w":2}]""", learned = "[]")
        assertTrue(phone.chat.contains("नया फ़ोन"))
        val backup = snap()
        val m = MemoryBackup.merge(phone, backup)
        assertEquals(listOf(t1), m.newTurns)                       // t2 पहले से - दोबारा नहीं
        assertEquals(listOf("मेरी बाइक MH12 AB 1234", "मेरा नाम दीपक"), m.facts)
        assertEquals(phone.chat, m.chat)                           // फ़ोन की चैट खाली नहीं - वही रहती है
        assertTrue(m.learned.contains("पहले लक्ष्य साफ़ लिखो"))
        assertTrue(m.newPoints.isEmpty())                          // वही सार फ़ोन में पहले से
        // नया install (फ़ोन खाली) = बैकअप की सारी चीज़ें
        val fresh = MemoryBackup.merge(MemoryBackup.Snapshot(at = 0, chat = "[]", history = "[]"), backup)
        assertEquals(backup.turns, fresh.newTurns)
        assertEquals(backup.chat, fresh.chat)
        assertEquals(backup.history, fresh.history)
        assertEquals(backup.points, fresh.newPoints)
    }

    @Test fun restore_tooManyFacts_goToPermanentNotes() {
        val a = (1..50).map { "बात a$it" }; val b = (1..30).map { "बात b$it" }
        val m = MemoryBackup.merge(snap(facts = b), snap(facts = a))
        assertEquals(MemoryBackup.MAX_FACTS, m.facts.size)
        assertEquals(20, m.factsOverflow.size)
        assertEquals((a + b).toSet(), (m.facts + m.factsOverflow).toSet())   // एक भी नहीं खोई
    }

    private class FakeStore : MemoryBackup.Store {
        var file: Pair<String, ByteArray>? = null
        val uploads = mutableListOf<String?>()
        override fun find(name: String) = file?.let { MemoryBackup.Remote(it.first, "2026-11-11T10:00:00Z", it.second.size.toLong()) }
        override fun upload(existingId: String?, name: String, bytes: ByteArray): String {
            uploads += existingId; val id = existingId ?: "f1"; file = id to bytes; return id
        }
        override fun download(id: String) = file!!.second
    }

    @Test fun backup_thenRestore_onNewPhone() {
        val drive = FakeStore()
        val r1 = MemoryBackup.backup(drive, snap())
        assertTrue(r1.message, r1.ok && r1.message.startsWith("बैकअप हो गया"))
        assertEquals(2, r1.talks)
        val r2 = MemoryBackup.backup(drive, snap(turns = listOf(t1, t2, t3)))
        assertTrue(r2.ok)
        assertEquals(listOf(null, "f1"), drive.uploads)              // दूसरी बार वही फ़ाइल बदली - नई नहीं
        // ऐप दोबारा डाला: वही ईमेल -> रिस्टोर
        val (got, err) = MemoryBackup.fetch(drive)
        assertEquals("", err)
        assertEquals(listOf(t1, t2, t3), got!!.turns)
    }

    @Test fun emptyPhone_doesNotWipeDriveBackup_withoutAsking() {
        val drive = FakeStore()
        MemoryBackup.backup(drive, snap(turns = (1..6000).map { MemoryBackup.T("u", "पुरानी बात $it ${(it * 7919L) % 100003} ${(it * 31337L) % 9973}", it * 1013L, "v", "d$it") }))
        val empty = MemoryBackup.Snapshot(at = 1)
        val r = MemoryBackup.backup(drive, empty)
        assertFalse(r.ok); assertTrue(r.needConfirm); assertTrue(r.message.contains("रिस्टोर"))
        assertEquals(1, drive.uploads.size)                        // कुछ नहीं बदला
        // बहुत छोटी याद भी पहले पूछे
        val small = MemoryBackup.backup(drive, snap(turns = listOf(t1)))
        assertTrue(small.needConfirm)
        // user ने "फिर भी बैकअप" दबाया
        assertTrue(MemoryBackup.backup(drive, empty, force = true).ok)
        assertEquals(2, drive.uploads.size)
    }

    @Test fun restore_noBackup_or_broken_clearMessage() {
        val drive = FakeStore()
        assertTrue(MemoryBackup.fetch(drive).second.contains("कोई बैकअप नहीं मिला"))
        drive.file = "x" to "टूटा".toByteArray()
        assertTrue(MemoryBackup.fetch(drive).second.contains("ख़राब"))
    }

    @Test fun pickLatest_rightFile() {
        val j = """{"files":[{"id":"a","name":"dk_memory_backup.json.gz","modifiedTime":"2026-01-01T00:00:00Z","size":"10"},
            {"id":"b","name":"dk_memory_backup.json.gz","modifiedTime":"2026-11-01T00:00:00Z","size":"20"},
            {"id":"c","name":"other.txt","modifiedTime":"2026-12-01T00:00:00Z"}]}"""
        assertEquals(MemoryBackup.Remote("b", "2026-11-01T00:00:00Z", 20), MemoryBackup.pickLatest(j))
        assertNull(MemoryBackup.pickLatest("""{"files":[]}"""))
        assertNull(MemoryBackup.pickLatest("oops"))
    }

    /** असली Drive वाला code (OkHttp) - नेट के बिना, नकली जवाबों से: सही URL, token, छिपा फ़ोल्डर, resumable */
    @Test fun driveAppData_usesHiddenFolder_andResumableUpload() {
        val seen = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { ch ->
            val r = ch.request(); seen += r
            bodies += r.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            val url = r.url.toString()
            val (code, body, loc) = when {
                r.method == "GET" && url.contains("alt=media") -> Triple(200, "DATA", null)
                r.method == "GET" -> Triple(200, """{"files":[]}""", null)
                url.contains("uploadType=resumable") -> Triple(200, "", "https://upload.example/session1")
                r.method == "PUT" -> Triple(200, """{"id":"new1"}""", null)
                else -> Triple(404, "", null)
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .apply { if (loc != null) header("Location", loc) }.body(body.toResponseBody()).build()
        }).build()
        val d = DriveAppData("TOKEN1", client)
        assertNull(d.find(MemoryBackup.FILE_NAME))
        assertTrue(seen[0].url.toString().contains("spaces=appDataFolder"))
        assertEquals("Bearer TOKEN1", seen[0].header("Authorization"))
        assertEquals("new1", d.upload(null, MemoryBackup.FILE_NAME, byteArrayOf(1, 2, 3)))
        assertEquals("POST", seen[1].method)
        assertEquals("application/gzip", seen[1].header("X-Upload-Content-Type"))
        val meta = JSONObject(bodies[1])
        assertEquals(MemoryBackup.FILE_NAME, meta.getString("name"))
        assertEquals("appDataFolder", (meta.get("parents") as JSONArray).getString(0))
        assertEquals("PUT", seen[2].method); assertEquals("https://upload.example/session1", seen[2].url.toString())
        // पुरानी फ़ाइल हो तो उसी को बदलो (PATCH), नई नहीं
        d.upload("old9", MemoryBackup.FILE_NAME, byteArrayOf(1))
        assertEquals("PATCH", seen[3].method); assertTrue(seen[3].url.encodedPath.endsWith("/files/old9"))
        assertEquals("DATA", String(d.download("old9")))
        assertTrue(DriveAppData.explain(DriveAppData.HttpError(403, "storageQuotaExceeded")).contains("जगह"))
        assertTrue(DriveAppData.explain(java.net.UnknownHostException("x")).contains("इंटरनेट"))
    }

    @Test fun settings_hasBackupScreen_withBothButtons() {
        val set = src("src/main/java/com/dk/assistant/SettingsActivity.kt")
        assertTrue(set.contains("Triple(\"backup\", \"बैकअप\""))
        assertTrue(set.contains("backup.BackupActivity::class.java"))
        assertTrue(src("src/main/AndroidManifest.xml").contains(".backup.BackupActivity"))
        val a = src("src/main/java/com/dk/assistant/backup/BackupActivity.kt")
        for (s in listOf("\"बैकअप करो\"", "\"रिस्टोर करो\"", "\"बैकअप हो रहा है…\"", "\"रिस्टोर हो रहा है…\"", ".setAccount(Account(email, \"com.google\"))", "MemoryBackup.SCOPE"))
            assertTrue(s, a.contains(s))
        assertEquals(com.dk.assistant.SettingsSearch.ACT_BACKUP, com.dk.assistant.SettingsSearch.search("बैकअप").first().section)
        assertTrue(com.dk.assistant.SettingsSearch.search("restore").any { it.section == com.dk.assistant.SettingsSearch.ACT_BACKUP })
        // Android का अपना backup अब भी बंद - API keys कभी फ़ोन backup से बाहर नहीं
        assertTrue(src("src/main/AndroidManifest.xml").contains("android:allowBackup=\"false\""))
        assertNotNull(Regex("<exclude domain=\"sharedpref\" />").find(src("src/main/res/xml/data_extraction_rules.xml")))
    }
}
