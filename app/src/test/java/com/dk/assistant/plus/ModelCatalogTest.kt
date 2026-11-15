package com.dk.assistant.plus

import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** भारी मॉडल ऐप में नहीं - सिर्फ़ डाउनलोड सूची (SHA-256 के साथ) */
class ModelCatalogTest {
    @Test fun catalog_valid() {
        assertEquals(emptyList<String>(), ModelCatalog.problems())
        assertTrue(ModelCatalog.ALL.all { a -> a.urls.all { it.startsWith("https://") } && a.sha256.length == 64 })
    }

    @Test fun noHeavyModels_bundledInApk() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
        val heavy = Regex(""".*\.(onnx|tflite|bin|glb|gltf)$""", RegexOption.IGNORE_CASE)
        val allowed = setOf("silero_vad.onnx") // पहले से (2.3 MB) - Phase 1 से पुराना
        val found = listOf(File(main, "assets"), File(main, "res/raw")).filter { it.exists() }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && heavy.matches(it.name) && it.name !in allowed }.toList() }
        assertTrue("ऐप में भारी फ़ाइल: $found", found.isEmpty())
    }

    @Test fun resumeHelpers() {
        assertNull(ModelManager.rangeHeader(0))
        assertEquals("bytes=500-", ModelManager.rangeHeader(500))
        assertTrue(ModelManager.resumeAccepted(500, 206))
        assertFalse(ModelManager.resumeAccepted(500, 200))
        assertFalse(ModelManager.resumeAccepted(0, 206))
        assertEquals(1000L, ModelManager.backoffMs(1))
        assertEquals(16000L, ModelManager.backoffMs(99))
    }

    @Test fun sha256_ofFile() {
        val f = File.createTempFile("dk_sha", ".txt").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelManager.sha256(f))
    }
}
