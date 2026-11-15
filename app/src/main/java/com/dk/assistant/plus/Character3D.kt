package com.dk.assistant.plus

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.dk.assistant.Prefs
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import java.io.FileInputStream

/**
 * 15 नवंबर 2026 (मालिक: "3D कैरेक्टर… सब इंटरनेट से डाउनलोड होने वाला, ऐप में भारी कुछ नहीं").
 * कैरेक्टर (.glb) + GLTFLoader [ModelManager] से `filesDir/models/` में (SHA-256 मिलाकर, MB प्रगति).
 * दिखाना: [com.dk.assistant.Character3DActivity] (WebView + three.js r160). चालू/बंद = [FeatureFlags.CHARACTER_3D] (शुरू में बंद).
 */
object Character3D {
    const val PREF = "char3d_selected"

    /** id से कैरेक्टर (बोले गए नाम से भी: "रोबोट", "लोमड़ी", "fox") */
    object Rules {
        private val WORDS = mapOf(
            "char_robot" to listOf("robot", "रोबोट", "dk रोबोट", "डीके रोबोट"),
            "char_cesium_man" to listOf("man", "आदमी", "चलता आदमी", "cesium"),
            "char_brainstem" to listOf("brainstem", "ब्रेनस्टेम", "नाचता"),
            "char_fox" to listOf("fox", "लोमड़ी", "लोमडी"),
        )
        fun find(q: String): ModelCatalog.Asset? {
            val t = q.lowercase().trim(); if (t.isEmpty()) return null
            ModelCatalog.CHARACTERS.firstOrNull { it.id == t || it.title.lowercase() == t }?.let { return it }
            // ब्रेनस्टेम में "रोबोट" भी है - सबसे लंबा मिलता शब्द जीते
            var best: ModelCatalog.Asset? = null; var len = 0
            for (a in ModelCatalog.CHARACTERS) for (w in WORDS[a.id].orEmpty() + a.title.lowercase())
                if (t.contains(w) && w.length > len) { best = a; len = w.length }
            return best
        }

        /** viewer के /models/ रास्ते: सिर्फ़ catalog की फ़ाइलें (कोई और फ़ाइल नहीं खुलती) */
        fun fileFor(path: String): ModelCatalog.Asset? = when (path.trimStart('/')) {
            "lib/loaders/GLTFLoader.js" -> ModelCatalog.GLTF_LOADER
            "lib/utils/BufferGeometryUtils.js" -> ModelCatalog.GEOMETRY_UTILS
            else -> ModelCatalog.CHARACTERS.firstOrNull { it.fileName == path.trimStart('/') }
        }

        fun mime(name: String) = if (name.endsWith(".js")) "text/javascript" else "model/gltf-binary"

        /** कुल डाउनलोड (कैरेक्टर + जो लाइब्रेरी अभी नहीं है) */
        fun needBytes(a: ModelCatalog.Asset, have: (ModelCatalog.Asset) -> Boolean): Long =
            (ModelCatalog.VIEWER_LIBS + a).filterNot(have).sumOf { it.bytes }
    }

    fun selected(c: Context): ModelCatalog.Asset =
        ModelCatalog.byId(Prefs.str(c, PREF, "")).takeIf { it?.kind == ModelCatalog.Kind.CHARACTER } ?: ModelCatalog.CHARACTERS.first()

    fun select(c: Context, a: ModelCatalog.Asset) = Prefs.edit(c) { putString(PREF, a.id) }

    fun ready(c: Context, a: ModelCatalog.Asset) = (ModelCatalog.VIEWER_LIBS + a).all { ModelManager.ready(c, it) }

    /** कैरेक्टर + लाइब्रेरी डाउनलोड (पहले से हो तो तुरंत). null = तैयार, नहीं तो कारण */
    suspend fun ensure(c: Context, a: ModelCatalog.Asset): String? {
        for (x in ModelCatalog.VIEWER_LIBS + a) ModelManager.download(c, x)?.let { return "${x.title}: $it" }
        return null
    }

    /** WebViewAssetLoader का /models/ handler */
    fun pathHandler(c: Context) = WebViewAssetLoader.PathHandler { path ->
        val a = Rules.fileFor(path) ?: return@PathHandler null
        val f = ModelManager.file(c, a)
        if (!f.isFile) null else try { WebResourceResponse(Rules.mime(a.fileName), null, FileInputStream(f)) } catch (_: Exception) { null }
    }
}
