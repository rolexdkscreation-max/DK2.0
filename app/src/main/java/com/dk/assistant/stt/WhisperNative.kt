package com.dk.assistant.stt

import com.dk.assistant.DKLog

/**
 * 18 नवंबर 2026 (मालिक: "सुनना अलग, सोचना-बोलना अलग"): whisper.cpp v1.9.5 का JNI पुल (`libdk_whisper.so`, सिर्फ़ arm64).
 *
 * लाइब्रेरी armv8.2 + dotprod + fp16 के लिए बनी है (q5_1 मॉडल पर तेज़). पुराने CPU पर उसे लोड करते ही ऐप गिर सकता है
 * (SIGILL, जो पकड़ा नहीं जा सकता) - इसलिए [load] पहले `/proc/cpuinfo` देखता है ([cpuOk], pure - JUnit).
 * न चले = [problem] में साफ़ हिंदी कारण, ऐप Android की बोली-पहचान पर चलता रहता है.
 */
object WhisperNative {
    @Volatile private var tried = false
    @Volatile var loaded = false
        private set
    @Volatile var problem: String = ""
        private set

    /** CPU में जो चाहिए: asimddp (dotprod) + fphp/asimdhp (fp16). Pure. */
    fun cpuOk(cpuinfo: String, abis: List<String>): Boolean {
        if (abis.none { it == "arm64-v8a" }) return false
        val feats = cpuinfo.lineSequence().filter { it.trimStart().startsWith("Features", ignoreCase = true) }
            .flatMap { it.substringAfter(':').trim().split(Regex("\\s+")).asSequence() }.toSet()
        if (feats.isEmpty()) return false
        return "asimddp" in feats && ("asimdhp" in feats || "fphp" in feats)
    }

    @Synchronized
    fun load(): Boolean {
        if (tried) return loaded
        tried = true
        val abis = try { android.os.Build.SUPPORTED_ABIS.toList() } catch (_: Throwable) { emptyList() }
        val info = try { java.io.File("/proc/cpuinfo").readText() } catch (_: Throwable) { "" }
        if (!cpuOk(info, abis)) {
            problem = if (abis.none { it == "arm64-v8a" }) "यह फ़ोन 64-bit ARM नहीं है - Whisper नहीं चलेगा"
                else "इस फ़ोन का प्रोसेसर Whisper के लिए पुराना है (dotprod/fp16 नहीं)"
            DKLog.d("Whisper: $problem")
            return false
        }
        loaded = try { System.loadLibrary("dk_whisper"); true } catch (e: Throwable) {
            problem = "Whisper लाइब्रेरी नहीं खुली (${e.javaClass.simpleName})"; DKLog.d("Whisper: $problem ${e.message}"); false
        }
        if (loaded) try { DKLog.d("Whisper: लाइब्रेरी तैयार • ${systemInfo().take(160)}") } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) }
        return loaded
    }

    @JvmStatic external fun initContext(path: String): Long
    @JvmStatic external fun freeContext(ptr: Long)
    @JvmStatic external fun setAbort(on: Boolean)
    @JvmStatic external fun systemInfo(): String
    @JvmStatic external fun transcribe(ptr: Long, samples: FloatArray, lang: String, threads: Int, prompt: String, audioCtx: Int): ByteArray?
}
