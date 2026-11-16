package com.dk.assistant.agent

import com.dk.assistant.agent.social.ClipImage
import com.dk.assistant.agent.social.ClipTokenizer
import com.dk.assistant.agent.social.GalleryRank
import com.dk.assistant.agent.social.Int4Table
import com.dk.assistant.plus.models.ModelCatalog
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 16 नवंबर 2026: सोशल पोस्ट स्पेशलिस्ट - MobileCLIP2 गैलरी पहचान का pure हिस्सा */
class SocialClipTest {
    private fun res(n: String) = listOf(File("src/test/resources/clip/$n"), File("app/src/test/resources/clip/$n")).first { it.exists() }

    @Test fun catalog_socialModels_pinned_under110MB() {
        assertEquals(emptyList<String>(), ModelCatalog.problems())
        val s = ModelCatalog.SOCIAL_CLIP
        assertEquals(4, s.size)
        assertTrue(s.all { it.kind == ModelCatalog.Kind.SOCIAL && it in ModelCatalog.ALL })
        val total = s.sumOf { it.bytes }
        assertTrue("कुल $total", total in 90_000_000L..110_000_000L)          // मालिक: "100 MB तक", GB नहीं
        assertEquals(45_555_784L, ModelCatalog.CLIP_VISION.bytes)
        assertEquals("a232ca0f7af15f7fa9741cd246a7997d6985781812c69864dde9ae16e7197b88", ModelCatalog.CLIP_WORDS.sha256)
        assertEquals(ClipTokenizer.VOCAB.toLong() * Int4Table.ROW, ModelCatalog.CLIP_WORDS.bytes)   // 49408 पंक्ति x 288
    }

    /** open_clip के असली tokenizer से बने ids (golden.json, 38 वाक्य - हिंदी नहीं पर चीनी / कोरियन / emoji / लंबा) */
    @Test fun tokenizer_matchesOpenClipGolden() {
        val t = res("bpe_simple_vocab_16e6.txt.gz").inputStream().use { ClipTokenizer.load(it) }
        val g = JSONArray(res("golden_ids.json").readText())
        assertTrue(g.length() >= 38)
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            val want = IntArray(77).also { a -> val ids = o.getJSONArray("ids"); for (k in 0 until ids.length()) a[k] = ids.getInt(k) }
            assertArrayEquals(o.getString("text"), want, t.encode(o.getString("text")))
        }
        val gym = t.encode("gym")
        assertEquals(ClipTokenizer.SOT, gym[0]); assertEquals(5222, gym[1]); assertEquals(ClipTokenizer.EOT, gym[2]); assertEquals(0, gym[3])
        assertEquals(2, Int4Table.eotIndex(gym))
        val long = t.encode("word ".repeat(200))
        assertEquals(ClipTokenizer.EOT, long[76]); assertEquals(76, Int4Table.eotIndex(long))
    }

    /** "gym" (id 5222) की असली पंक्ति - numpy से निकाले मान */
    @Test fun int4Row_matchesNumpy() {
        val hex = "d75c8867998caf756c8acabce68a5b66994b767ae6848a84179959da17a8d78a78995c4e776b88a7c797b7e9b775d1b37b53897a62a49ea98657fad76cc4547d4aa91b3836ab98b8abaf898a32ac6979989798db9a7576a6f77abb587767d8aa8ae863c52d7cf78595b9af8398a5b66699b36847a7257cb735a79ceb95a88a21a6818792acb9bca88982ba568aab885767756b68a9175e36a54757989966a75b9e66582849ccd688cf8f5a97967796bb5456cf357879246d49b487b89569b678f4c874a4183ca9aa749caeba83c49346b9721d7347a266bd5524689818b7c9cda97a978c95b62d7388a99572d12b619c585899b8469fa85e748477893687a699001e451db61dce1ceb1c111e6c1d531cf91db61eea1d761caa1c181c631c5a1c"
        val row = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        assertEquals(Int4Table.ROW, row.size)
        val out = FloatArray(512)
        Int4Table.unpack(row, 0, out, 0)
        val want = floatArrayOf(-0.005859375f, 0.029296875f, 0.0234375f, -0.017578125f, 0f, 0f)
        for (i in want.indices) assertEquals(want[i], out[i], 1e-7f)
        assertEquals(0.00424957275390625f, out[511], 1e-7f)
        assertEquals(0.02385711669921875, out.sumOf { it.toDouble() }, 1e-5)
    }

    @Test fun half_floats() {
        assertEquals(1f, Int4Table.half(0x3c00), 0f)
        assertEquals(-2f, Int4Table.half(0xc000), 0f)
        assertEquals(0.000061035156f, Int4Table.half(0x0400), 1e-9f)
        assertEquals(0f, Int4Table.half(0), 0f)
    }

    @Test fun image_resizeCropPlan_andChw_noNormalisation() {
        assertArrayEquals(intArrayOf(455, 256, 99, 0), ClipImage.plan(1920, 1080))
        assertArrayEquals(intArrayOf(256, 341, 0, 42), ClipImage.plan(300, 400))
        val px = IntArray(256 * 256) { 0xFFFF8000.toInt() }
        val x = ClipImage.chw(px)
        assertEquals(3 * 256 * 256, x.size)
        assertEquals(1f, x[0], 0f); assertEquals(128 / 255f, x[256 * 256], 1e-6f); assertEquals(0f, x[2 * 256 * 256], 0f)   // 0..1 - mean/std नहीं
    }

    @Test fun rank_picksBest_tieGoesToNewer_andRefusesWeak() {
        val q = GalleryRank.normalize(floatArrayOf(1f, 0f, 0f))
        val beach = GalleryRank.Item("u1", "image/jpeg", 10)
        val beach2 = GalleryRank.Item("u2", "image/jpeg", 20)
        val cat = GalleryRank.Item("u3", "image/jpeg", 30)
        val items = listOf(beach to floatArrayOf(0.3f, 0.95f, 0f), beach2 to floatArrayOf(0.299f, 0.95f, 0f), cat to floatArrayOf(0.05f, 0.99f, 0f))
        val r = GalleryRank.rank(q, items)
        assertEquals("u2", r[0].item.uri)                               // लगभग बराबर -> नई वाली
        assertEquals("u3", r.last().item.uri)
        assertEquals("u2", GalleryRank.best(q, items)?.item?.uri)
        assertNull(GalleryRank.best(q, listOf(cat to floatArrayOf(0.05f, 0.99f, 0f))))   // पक्की नहीं = कोई झूठा चुनाव नहीं
        assertNull(GalleryRank.best(q, emptyList()))
        assertEquals("a photo of a red car", GalleryRank.prompt("a photo of a red car"))
        assertEquals("a photo of beach sunset", GalleryRank.prompt(" beach sunset "))
    }

    @Test fun cache_roundTrip_andBadBytes() {
        val m = linkedMapOf(GalleryRank.key("content://media/1", 5) to floatArrayOf(0.1f, -0.2f), "x|1" to FloatArray(512) { it / 512f })
        val back = GalleryRank.decodeCache(GalleryRank.encodeCache(m))
        assertEquals(m.keys, back.keys)
        assertArrayEquals(m["x|1"], back["x|1"], 0f)
        assertTrue(GalleryRank.decodeCache(byteArrayOf(1, 2, 3)).isEmpty())
    }

    @Test fun galleryClip_android_readOnly_andHonest() {
        val s = listOf(File("src/main/java/com/dk/assistant/agent/android/GalleryClip.kt"), File("app/src/main/java/com/dk/assistant/agent/android/GalleryClip.kt")).first { it.exists() }.readText()
        assertTrue(!s.contains(".delete(") && !s.contains("contentResolver.delete"))   // गैलरी की फ़ाइल कभी नहीं मिटती
        assertTrue(s.contains("media_not_found"))                                         // न मिले तो साफ़ बताओ
        assertTrue(s.contains("\"x\" to tx") && s.contains("\"eot\" to te"))
        val man = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(man.contains("android.permission.READ_MEDIA_VIDEO"))
    }
}
