package com.dk.assistant.wake

import android.app.Application
import com.dk.assistant.plus.models.ModelCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** वेक वर्ड मॉडल: APK में नहीं, SHA-256 के साथ डाउनलोड सूची, manifest से नया संस्करण */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WakeModelsTest {
    private fun manifest(v: Int, drop: String? = null, badSha: Boolean = false): String {
        val arr = JSONArray()
        ModelCatalog.WAKE.filter { it.id != drop }.forEach { a ->
            arr.put(JSONObject().put("id", a.id).put("file", a.fileName.replace("_v1", "_v$v")).put("urls", JSONArray(a.urls))
                .put("sha256", if (badSha) "xyz" else a.sha256).put("bytes", a.bytes))
        }
        return JSONObject().put("version", v).put("files", arr).toString()
    }

    @Test fun catalog_hasFourWakeFiles_allChecked() {
        assertEquals(listOf("oww_mel", "oww_emb", "hey_dk", "hello_dk"), ModelCatalog.WAKE.map { it.id })
        assertTrue(ModelCatalog.problems(ModelCatalog.WAKE).isEmpty())
        assertTrue(ModelCatalog.WAKE.all { it.kind == ModelCatalog.Kind.WAKE && it.urls.all { u -> u.startsWith("https://") } })
        // सब मिलाकर छोटा (बैटरी / डाउनलोड हल्का)
        assertTrue(ModelCatalog.WAKE.sumOf { it.bytes } < 6_000_000L)
    }

    @Test fun manifest_parse() {
        val m = WakeModels.parse(manifest(2))
        assertNotNull(m); assertEquals(2, m!!.version); assertEquals(4, m.assets.size)
        assertNull("ज़रूरी मॉडल गायब", WakeModels.parse(manifest(2, drop = "hello_dk")))
        assertNull("SHA गलत", WakeModels.parse(manifest(2, badSha = true)))
        assertNull(WakeModels.parse("{टूटा"))
    }

    @Test fun nothingBundled() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
        val names = ModelCatalog.WAKE.map { it.fileName }.toSet() + setOf("hey_dk.onnx", "hello_dk.onnx", "melspectrogram.onnx", "embedding_model.onnx")
        val found = main.walkTopDown().filter { it.isFile && it.name in names }.toList()
        assertTrue("ऐप के अंदर वेक वर्ड मॉडल: $found", found.isEmpty())
    }

    @Test fun noKeyOrApiFieldInWakeFeature() {
        val dir = listOf(File("src/main/java/com/dk/assistant/wake"), File("app/src/main/java/com/dk/assistant/wake")).first { it.exists() }
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".kt") }) {
            val t = f.readText()
            assertFalse(f.name, t.contains("apiKey", ignoreCase = true) || t.contains("API key डालें") || t.contains("Picovoice", ignoreCase = true) || t.contains("Porcupine", ignoreCase = true))
        }
    }
}
