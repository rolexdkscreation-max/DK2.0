import java.util.Properties
import java.security.MessageDigest
import java.net.URI
import java.net.HttpURLConnection

// ---------------------------------------------------------------------------------------------- Piper TTS (नवंबर 2026)
// फ़ोन में ही आवाज़ (बिना इंटरनेट): sherpa-onnx (Apache-2.0) + Piper की आवाज़ें. फ़ाइलें बड़ी हैं (repo में नहीं) -
// build के समय GitHub releases से एक बार आती हैं, sha256 जाँचकर `.dk-cache/` में रहती हैं (git-ignored; CI में cache).
// 2.x में espeak-ng हटा दिया गया (Piper की हिंदी उसके बिना नहीं चलती) - इसलिए 1.13.8 पक्का.
// "static-link" वाली AAR: ONNX Runtime उसके अंदर ही जुड़ा है -> हमारे onnxruntime-android (आवाज़-पहचान) से टकराव नहीं.
val dkCache: File = rootProject.file(".dk-cache")
val sherpaVersion = "1.13.8"
val sherpaAar = File(dkCache, "sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
val sherpaAarSha = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
/** APK में जाने वाली Piper आवाज़ें (int8 = छोटी). नाम = PiperTtsEngine.VOICES के नाम (test दोनों मिलाता है) */
val piperVoices = linkedMapOf(
    "hi_IN-priyamvada-medium" to "2997c7bbc211e524af83dfdffe7712942f10bd76c079a526af2e09d1f36a92f2",
    "hi_IN-pratham-medium" to "20f568c56207c13b9a0d9478aec8b7d1449122e618aeebc7211f6abc942b58b7",
)
/** espeak-ng-data में सिर्फ़ यही भाषाएँ (en_dict: हिंदी जवाब के बीच के English शब्दों के लिए - English आवाज़ हटने के बाद भी) (बाकी ~15 MB के शब्दकोश नहीं - आवाज़ बिल्कुल वही रहती है) */
val espeakDicts = setOf("hi_dict", "en_dict")
val piperAssets = File(dkCache, "piper-assets")

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
    f.inputStream().use { inp -> val b = ByteArray(1 shl 16); while (true) { val n = inp.read(b); if (n < 0) break; md.update(b, 0, n) } }
    md.digest().joinToString("") { "%02x".format(it) }
}

/** एक बार डाउनलोड (3 कोशिश), sha256 मिले तभी रखो */
fun dkFetch(url: String, to: File, sha: String) {
    if (to.exists() && sha256(to) == sha) return
    to.parentFile.mkdirs()
    val tmp = File(to.path + ".part")
    var last: Exception? = null
    for (i in 1..3) {
        try {
            logger.lifecycle("DK: डाउनलोड $url")
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true; c.connectTimeout = 30_000; c.readTimeout = 60_000
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            val got = sha256(tmp)
            if (got != sha) throw GradleException("DK: $url का sha256 नहीं मिला ($got)")
            if (to.exists()) to.delete()
            if (!tmp.renameTo(to)) throw GradleException("DK: ${to.name} नहीं रखा जा सका")
            return
        } catch (e: Exception) { last = e; tmp.delete(); Thread.sleep(2_000L * i) }
    }
    throw GradleException("DK: $url नहीं आया (${last?.message}) - इंटरनेट देखें, फिर दोबारा build", last)
}

/** आवाज़ें -> .dk-cache/piper-assets/piper_models/<नाम>/ + एक साझा, छोटा espeak-ng-data */
fun preparePiper() {
    val stamp = File(piperAssets, "piper_models/.stamp")
    val want = "$sherpaVersion|" + piperVoices.entries.joinToString(",") { "${it.key}=${it.value}" } + "|" + espeakDicts.sorted()
    if (stamp.exists() && stamp.readText() == want) return
    val root = File(piperAssets, "piper_models"); root.deleteRecursively(); root.mkdirs()
    for ((name, sha) in piperVoices) {
        val tar = File(dkCache, "vits-piper-$name-int8.tar.bz2")
        dkFetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-$name-int8.tar.bz2", tar, sha)
        val x = File(dkCache, "x-$name"); x.deleteRecursively(); x.mkdirs()
        val p = ProcessBuilder("tar", "xjf", tar.absolutePath, "-C", x.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw GradleException("DK: ${tar.name} नहीं खुला: $out")
        val src = File(x, "vits-piper-$name-int8")
        val dst = File(root, name); dst.mkdirs()
        File(src, "$name.onnx").copyTo(File(dst, "model.onnx"), true)
        File(src, "tokens.txt").copyTo(File(dst, "tokens.txt"), true)
        File(src, "MODEL_CARD").takeIf { it.exists() }?.copyTo(File(dst, "MODEL_CARD"), true)
        val es = File(root, "espeak-ng-data")
        if (!es.exists()) File(src, "espeak-ng-data").copyRecursively(es, true)
        x.deleteRecursively()
    }
    File(root, "espeak-ng-data").listFiles()?.filter { it.isFile && it.name.endsWith("_dict") && it.name !in espeakDicts }?.forEach { it.delete() }
    stamp.writeText(want)
}

dkFetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/${sherpaAar.name}", sherpaAar, sherpaAarSha)
preparePiper()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dk.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dk.assistant"
        minSdk = 26
        targetSdk = 34
        // GitHub har build par VERSION_CODE badhata hai -> app ko pata chalta hai ki update naya hai
        val vc = (System.getenv("VERSION_CODE") ?: "3").toInt()
        versionCode = vc
        // Sirf version (3.1 / 3.1.1) - build number nahi. GitHub workflow update ke prakar se banata hai
        versionName = System.getenv("VERSION_NAME") ?: file("../version.txt").readText().trim()
    }

    // Hamesha ek hi key se sign -> purane app ke upar naya update install ho sake
    // 1) GitHub: DK_KEYSTORE / DK_KS_PASS env (पहले जैसा)  2) अपने कंप्यूटर पर: keystore.properties (git-ignored, docs/RELEASE.md)
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { input -> this.load(input) }
    }
    val ksPath = System.getenv("DK_KEYSTORE") ?: ksProps.getProperty("storeFile")
    val ksPass = System.getenv("DK_KS_PASS") ?: ksProps.getProperty("storePassword")
    val keyPass = System.getenv("DK_KS_PASS") ?: ksProps.getProperty("keyPassword") ?: ksPass
    signingConfigs {
        create("dk") {
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = System.getenv("DK_KEY_ALIAS") ?: ksProps.getProperty("keyAlias") ?: "dk"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            // DK AI Part 15: R8 (छोटा APK) सिर्फ़ DK_MINIFY=1 पर - फ़ोन पर जाँचे बिना default नहीं बदला
            val minify = System.getenv("DK_MINIFY") == "1"
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksPath != null && file(ksPath).exists())
                signingConfigs.getByName("dk") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = false
    }
    // Piper TTS की आवाज़ें (.dk-cache से, ऊपर देखें) APK के assets/piper_models/ में
    sourceSets.getByName("main").assets.srcDir(piperAssets)
    // व्यवहार वाले tests (Robolectric) को असली layout / theme / strings चाहिए
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    // v3.66: आवाज़-पहचान (ONNX Runtime) सिर्फ़ arm64 फ़ोनों के लिए - APK करीब +13 MB ही बढ़े।
    // 32-bit / x86 पर .so नहीं: वहाँ पहचान अपने-आप बंद रहती है और DK पहले की तरह सब सुनता है (कुछ टूटता नहीं)
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += setOf(
                "lib/x86/libonnxruntime.so", "lib/x86/libonnxruntime4j_jni.so",
                "lib/x86_64/libonnxruntime.so", "lib/x86_64/libonnxruntime4j_jni.so",
                "lib/armeabi-v7a/libonnxruntime.so", "lib/armeabi-v7a/libonnxruntime4j_jni.so",
                // Piper TTS (sherpa-onnx) भी सिर्फ़ arm64 - बाकी फ़ोनों पर Piper अपने-आप "तैयार नहीं" और Gemini TTS / DK लाइव बोलते हैं
                "lib/x86/libsherpa-onnx-jni.so", "lib/x86_64/libsherpa-onnx-jni.so", "lib/armeabi-v7a/libsherpa-onnx-jni.so",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // v3.66: "सिर्फ़ मेरी आवाज़" - फ़ोन पर ही आवाज़-पहचान (MIT). 1.22 इस मॉडल पर थोड़ा ग़लत नतीजा देता था, 1.31 = Python जैसा
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // नवंबर 2026: Piper TTS (फ़ोन में आवाज़, बिना इंटरनेट) - sherpa-onnx की आधिकारिक AAR (Maven Central पर नहीं, ऊपर dkFetch)
    implementation(files(sherpaAar))
    // DK AI Website Forge: 3D scene को app के assets से https पर चलाने के लिए (WebViewAssetLoader)
    implementation("androidx.webkit:webkit:1.11.0")
    // v3.40: DK लाइव का कैमरा (preview + हर सेकंड एक तस्वीर Gemini को)
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // v3.72: कनेक्टर हब - Google login (Drive/Gmail/Calendar...) बिना client secret, token Play services देता है
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    testImplementation("junit:junit:4.13.2")
    // Android ka org.json unit test me khali (stub) hota hai - asli wala
    testImplementation("org.json:json:20240303")
    // व्यवहार वाले tests (नवंबर 2026): असली Context / SharedPreferences फ़ोन के बिना - code की लाइन नहीं, असली नतीजा जाँचो
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}

// v3.89: test fail हो तो पूरा कारण log में (expected/actual + कौन-सी लाइन) - वरना सिर्फ़ "AssertionError at X.kt:68" आता था
// और कोडिंग एजेंट का डिबगर असली वजह देख ही नहीं पाता था. सिर्फ़ log का रूप बदलता है, कोई test या build नहीं.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
