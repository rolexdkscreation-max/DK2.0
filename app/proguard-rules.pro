# DK AI - R8 rules (used only when DK_MINIFY=1, see docs/RELEASE.md)

# Website Forge: JavaScript bridge methods are called by name from forge.js
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Services / receivers / activities are referenced from the manifest (AAPT keeps them),
# but keep their names stable for logs and Settings deep links.
-keep class com.dk.assistant.DKAccessibilityService
-keep class com.dk.assistant.DKNotifListener
-keep class com.dk.assistant.DKAssistActivity
-keep class com.dk.assistant.DKVoiceInteractionService
-keep class com.dk.assistant.DKVoiceInteractionSessionService

# org.json is part of Android; OkHttp and coroutines ship their own consumer rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Readable crash stack traces in the debug log
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# v3.66: ONNX Runtime (आवाज़-पहचान) - JNI से बुलाई जाने वाली classes
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# v3.68: आवाज़ इंजन नाम से (Class.forName + INSTANCE) ढूँढे जाते हैं - फ़ाइल हटाओ तो इंजन अपने-आप सूची से हट जाता है
-keep class com.dk.assistant.voice.engine.GeminiTtsEngine { *; }
-keep class com.dk.assistant.voice.engine.LiveNativeEngine { *; }


# नवंबर 2026: Supertonic 3 (नया, ऑफ़लाइन हिंदी आवाज़) - sherpa-onnx की native लाइब्रेरी (पहली बार डाउनलोड) इन classes / fields को JNI से नाम से पढ़ती है
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.dk.assistant.voice.engine.SupertonicTtsEngine { *; }
# native callback typed method invoke(float[]):Integer ढूँढता है - R8 इसे न हटाए/न बदले
-keepclassmembers class * implements kotlin.jvm.functions.Function1 { java.lang.Integer invoke(float[]); }
