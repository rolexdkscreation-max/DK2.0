package com.dk.assistant.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.DkMind
import com.dk.assistant.LocalBrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 14 नवंबर 2026: DK के दिमाग ने जो सीखा - बैकअप में जाए, नया install + रिस्टोर पर वापस आए */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DkMindBackupTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun learnedTalk_survivesBackupAndReinstall() {
        LocalBrain.learn(c, "डीके गुरुत्वाकर्षण क्या होता है", "धरती की खींचने वाली ताकत।")
        LocalBrain.learn(c, "आज मौसम कैसा है", "धूप है")          // बदलने वाली - नहीं सीखी
        assertEquals("धरती की खींचने वाली ताकत।", LocalBrain.recall(c, "गुरुत्वाकर्षण क्या होता है", exact = true))
        assertNull(LocalBrain.recall(c, "आज मौसम कैसा है", exact = false))
        val bytes = MemoryBackup.pack(BackupPhone.snapshot(c))
        // नया install: दिमाग की फ़ाइल गायब
        File(c.filesDir, "dk_mind.json").delete()
        LocalBrain.importJson(c, "[]")
        assertNull(LocalBrain.recall(c, "गुरुत्वाकर्षण क्या होता है", exact = false))
        BackupPhone.apply(c, MemoryBackup.unpack(bytes)!!)
        assertEquals("धरती की खींचने वाली ताकत।", LocalBrain.recall(c, "गुरुत्वाकर्षण क्या होता है", exact = false))
        // पुराना बैकअप (brain नहीं) भी खुलता है
        assertEquals("[]", MemoryBackup.fromJson("""{"kind":"dk_memory_backup","v":1,"at":1}""")!!.brain)
        assertTrue(DkMind.fromJson(MemoryBackup.unpack(bytes)!!.brain).isNotEmpty())
    }

    /** Review में मिली पुरानी गड़बड़: दिमाग और "दूसरे AI से सीखे तरीके" एक ही फ़ाइल में थे - एक लिखे तो दूसरा मिटे */
    @Test fun learnedWaysAndBrain_neverEraseEachOther() {
        com.dk.assistant.LearnedWays.add(c, listOf("पहले लक्ष्य साफ़ लिखो"), "Claude", "")
        LocalBrain.learn(c, "नींद क्यों आती है", "शरीर को आराम चाहिए होता है।")
        com.dk.assistant.LearnedWays.add(c, listOf("काम छोटे टुकड़ों में बाँटो"), "ChatGPT", "")
        assertEquals(2, com.dk.assistant.LearnedWays.all(c).size)
        assertEquals("शरीर को आराम चाहिए होता है।", LocalBrain.recall(c, "नींद क्यों आती है", exact = false))
        // पुरानी साझा फ़ाइल: सवाल-जवाब दिमाग में आएँ, तरीकों में न गिने जाएँ
        val old = """[{"q":"चाय कैसे बनती है","a":"पानी, पत्ती, दूध","t":5},{"t":"पुराना तरीका","f":"Claude","p":"","a":1}]"""
        assertEquals(listOf("चाय कैसे बनती है"), DkMind.oldEntries(old).map { it.q })
        assertEquals(listOf("पुराना तरीका"), com.dk.assistant.LearnedWays.fromJson(old).map { it.text })
    }
}
