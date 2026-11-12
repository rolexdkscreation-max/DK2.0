package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026: sherpa-onnx 1.13.8 की native लाइब्रेरी JNI से इन classes के field नाम से पढ़ती है - नाम बदले तो Supertonic नहीं खुलेगा.
 * और class लोड होते ही System.loadLibrary नहीं (लाइब्रेरी APK में नहीं, डाउनलोड से आती है).
 */
class SherpaApiShapeTest {
    private fun fields(n: String) = Class.forName("com.k2fsa.sherpa.onnx.$n").declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }

    @Test fun fieldNamesMatchNative() {
        assertEquals(listOf("model", "lexicon", "tokens", "dataDir", "dictDir", "noiseScale", "noiseScaleW", "lengthScale"), fields("OfflineTtsVitsModelConfig"))
        assertEquals(listOf("vits", "matcha", "kokoro", "zipvoice", "kitten", "pocket", "supertonic", "numThreads", "debug", "provider"), fields("OfflineTtsModelConfig"))
        assertEquals(listOf("model", "ruleFsts", "ruleFars", "maxNumSentences", "silenceScale"), fields("OfflineTtsConfig"))
    }

    @Test fun classLoadsWithoutNativeLib() {
        val c = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts", true, javaClass.classLoader)   // loadLibrary होता तो यहीं UnsatisfiedLinkError
        val natives = c.declaredMethods.filter { java.lang.reflect.Modifier.isNative(it.modifiers) }.map { it.name }.toSet()
        assertTrue(natives.containsAll(setOf("newFromFile", "delete", "getSampleRate", "generateWithCallbackImpl")))
    }
}
