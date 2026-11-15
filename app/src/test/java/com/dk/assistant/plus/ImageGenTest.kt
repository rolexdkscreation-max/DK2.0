package com.dk.assistant.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 15 नवंबर 2026: हर मुफ़्त इमेज रास्ते की अलग key; बिना key Pollinations नहीं (नवंबर 2026 से key ज़रूरी) */
class ImageGenTest {
    @Test fun order_chosenFirst_skipsKeyless_hordeAlwaysLast() {
        val none = ImageGen.order(ImageGen.AUTO) { false }
        assertEquals(listOf(ImageGen.Provider.HORDE), none)
        val hf = ImageGen.order("hf") { it == ImageGen.Provider.HUGGINGFACE }
        assertEquals(listOf(ImageGen.Provider.HUGGINGFACE, ImageGen.Provider.HORDE), hf)
        val all = ImageGen.order("pollinations") { true }
        assertEquals(ImageGen.Provider.POLLINATIONS, all.first())
        assertEquals(all.size, all.toSet().size)
        assertEquals(ImageGen.Provider.HORDE, ImageGen.order("horde") { true }.first())
    }

    @Test fun eachProvider_ownKeyPref_andSecret() {
        val prefs = ImageGen.Provider.values().map { it.keyPref }
        assertEquals(prefs.size, prefs.toSet().size)
        for (p in prefs) assertTrue(p, com.dk.assistant.SecretNames.isSecret(p))
        assertEquals(ImageGen.KEY, ImageGen.Provider.CUSTOM.keyPref)          // पुरानी key का नाम वही
        assertFalse(ImageGen.Provider.HORDE.keyNeeded)
    }

    @Test fun pollinations_newHost_withKey() {
        val u = ImageGen.pollinationsUrl("a red car", key = "sk_abc")
        assertTrue(u.startsWith("https://gen.pollinations.ai/image/a%20red%20car?"))
        assertTrue(u.contains("model=flux") && u.endsWith("&key=sk_abc"))
        assertFalse(ImageGen.pollinationsUrl("x").contains("image.pollinations.ai"))
    }

    @Test fun horde_bodyAndParsers() {
        val b = ImageGen.hordeBody("cat", 500, 700)
        assertEquals(448, b.getJSONObject("params").getInt("width"))
        assertFalse(b.getBoolean("nsfw"))
        assertEquals("abc", ImageGen.hordeId("{\"id\":\"abc\"}"))
        assertEquals(null, ImageGen.hordeId("oops"))
        assertTrue(ImageGen.hordeDone("{\"done\":true}"))
        assertEquals("https://x/y.webp", ImageGen.hordeImg("{\"generations\":[{\"img\":\"https://x/y.webp\"}]}"))
    }

    @Test fun hf_andOpenAi_andErrors() {
        assertEquals("https://router.huggingface.co/hf-inference/models/black-forest-labs/FLUX.1-schnell", ImageGen.hfUrl(""))
        assertEquals("QUJD" to null, ImageGen.openAiImage("{\"data\":[{\"b64_json\":\"QUJD\"}]}"))
        assertEquals(null to "https://i", ImageGen.openAiImage("{\"data\":[{\"url\":\"https://i\"}]}"))
        assertEquals("key गलत या नहीं डाली", ImageGen.why(401))
        assertEquals("png" to "image/png", ImageGen.kind(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0)))
        assertEquals("jpg", ImageGen.kind(byteArrayOf(-1, -40, -1, 0, 0)).first)
    }
}
