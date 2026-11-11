package com.dk.assistant.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.LearnedWays
import com.dk.assistant.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** असली फ़ोन जैसा (SQLite + फ़ाइलें): बैकअप लिया → नया install (खाली) → रिस्टोर = सब वापस, दोबारा रिस्टोर = कुछ दोहराया नहीं */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupPhoneTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun restore_bringsBackEverything_once() {
        val backup = MemoryBackup.Snapshot(
            at = 1L, facts = listOf("मेरी बाइक MH12 AB 1234"),
            chat = """[{"r":"u","t":"पुरानी चैट","w":5}]""",
            learned = """[{"t":"पहले लक्ष्य साफ़ लिखो","f":"Claude","p":"","a":1}]""",
            turns = listOf(
                MemoryBackup.T("u", "बहन की शादी 12 दिसंबर को है", 1_000L, "voice", "2026-01-01"),
                MemoryBackup.T("d", "ठीक है, याद रखूँगी", 1_200L, "voice", "2026-01-01"),
            ),
            points = listOf(MemoryBackup.P("2026-01-01", "बहन की शादी 12 दिसंबर", 1_000L)),
            days = listOf("2026-01-01"),
        )
        val before = BackupPhone.snapshot(c).talkCount
        val (talks, _) = BackupPhone.apply(c, backup)
        assertEquals(2, talks)
        val now = BackupPhone.snapshot(c)
        assertEquals(before + 2, now.talkCount)
        assertTrue(now.turns.any { it.text == "बहन की शादी 12 दिसंबर को है" && it.at == 1_000L })
        assertTrue(now.points.any { it.text == "बहन की शादी 12 दिसंबर" })
        assertTrue("मेरी बाइक MH12 AB 1234" in Memory.facts(c))
        assertTrue(LearnedWays.all(c).any { it.text == "पहले लक्ष्य साफ़ लिखो" })
        assertTrue(Memory.loadChat(c).any { it.text == "पुरानी चैट" })
        // दोबारा रिस्टोर: कुछ भी दोहराया नहीं
        assertEquals(0, BackupPhone.apply(c, backup).first)
        assertEquals(now.talkCount, BackupPhone.snapshot(c).talkCount)
        assertEquals(1, Memory.facts(c).count { it == "मेरी बाइक MH12 AB 1234" })
        // पूरा चक्कर: फ़ोन की याद -> पैक -> खोलो = वही
        val again = MemoryBackup.unpack(MemoryBackup.pack(BackupPhone.snapshot(c)))!!
        assertEquals(now.turns, again.turns)
    }

    @Test fun email_isRemembered() {
        BackupPhone.setEmail(c, "  Ram@Gmail.com ")
        assertEquals("ram@gmail.com", BackupPhone.email(c))
    }
}
