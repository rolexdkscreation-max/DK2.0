package com.dk.assistant.plus

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** मालिक: कोई license key / paywall / Hunter TTS नहीं - पूरे main code में */
class NoPaywallTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun code() = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }

    @Test fun noHunterTts() {
        val bad = code().filter { it.readText().contains("Hunter TTS", ignoreCase = true) }.toList()
        assertTrue("$bad", bad.isEmpty())
    }

    @Test fun noLicensePackage() {
        assertTrue(!File(main, "java/com/dk/assistant/license").exists())
        val bad = code().filter { it.readText().contains("AccessKeyManager") }.toList()
        assertTrue("$bad", bad.isEmpty())
    }
}
