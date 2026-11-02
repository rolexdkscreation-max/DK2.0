import java.util.Properties
import java.security.MessageDigest
import java.net.URI
import java.net.HttpURLConnection

// ---------------------------------------------------------------------------------------------- Kokoro TTS (नवंबर 2026)
// फ़ोन में ही आवाज़ (बिना इंटरनेट): sherpa-onnx (Apache-2.0) + Kokoro-82M v1.0 multi-lang int8 (Apache-2.0).
// मालिक: "Piper पूरी तरह हटाओ, Kokoro डिफ़ॉल्ट; सिर्फ़ हिंदी + हिंग्लिश; सिर्फ़ 4 आवाज़ें (hf_alpha, hf_beta, hm_omega, hm_psi)".
// फ़ाइलें बड़ी हैं (repo में नहीं) - build के समय GitHub releases से एक बार आती हैं, sha256 जाँचकर `.dk-cache/` में
// (git-ignored; CI में cache). 2.x में espeak-ng हटा दिया गया (Kokoro की हिंदी उसी से) - इसलिए 1.13.8 पक्का.
// "static-link" वाली AAR: ONNX Runtime उसके अंदर ही जुड़ा है -> हमारे onnxruntime-android (आवाज़-पहचान) से टकराव नहीं.
val dkCache: File = rootProject.file(".dk-cache")
val sherpaVersion = "1.13.8"
val sherpaAar = File(dkCache, "sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
val sherpaAarSha = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
/** Kokoro model (sherpa-onnx tts-models) */
val kokoroName = "kokoro-int8-multi-lang-v1_0"
val kokoroSha = "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653"
/** voices.bin में सिर्फ़ ये 4 आवाज़ें भरी रहती हैं (बाकी 50 शून्य -> APK में ~2 MB). KokoroTtsEngine.VOICE_LIST जैसे ही (test मिलाता है) */
val kokoroSpeakers = linkedMapOf("hf_alpha" to 31, "hf_beta" to 32, "hm_omega" to 33, "hm_psi" to 34)
/** एक आवाज़ = 510 x 1 x 256 float32 */
val kokoroStyleBytes = 510 * 256 * 4
/** espeak-ng-data में सिर्फ़ हिंदी + English (हिंग्लिश के English शब्द) - बाकी ~15 MB के शब्दकोश नहीं */
val espeakDicts = setOf("hi_dict", "en_dict")
val kokoroAssets = File(dkCache, "kokoro-assets")

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

/** Kokoro -> .dk-cache/kokoro-assets/kokoro_model/ (model.onnx, tokens.txt, voices.bin, छोटा espeak-ng-data) */
fun prepareKokoro() {
    // पुरानी Piper फ़ाइलें (cache से) - हटाओ, ताकि APK / cache में कुछ न बचे
    dkCache.listFiles()?.filter { it.name.startsWith("vits-piper-") || it.name == "piper-assets" }?.forEach { it.deleteRecursively() }
    val root = File(kokoroAssets, "kokoro_model")
    val stamp = File(root, ".stamp")
    val want = "$sherpaVersion|$kokoroName=$kokoroSha|" + kokoroSpeakers.values.joinToString(",") + "|" + espeakDicts.sorted()
    if (stamp.exists() && stamp.readText() == want) return
    root.deleteRecursively(); root.mkdirs()
    val tar = File(dkCache, "$kokoroName.tar.bz2")
    dkFetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$kokoroName.tar.bz2", tar, kokoroSha)
    val x = File(dkCache, "x-kokoro"); x.deleteRecursively(); x.mkdirs()
    val p = ProcessBuilder("tar", "xjf", tar.absolutePath, "-C", x.absolutePath,
        "$kokoroName/model.int8.onnx", "$kokoroName/tokens.txt", "$kokoroName/voices.bin", "$kokoroName/espeak-ng-data", "$kokoroName/LICENSE")
        .redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (p.waitFor() != 0) throw GradleException("DK: ${tar.name} नहीं खुला: $out")
    val src = File(x, kokoroName)
    File(src, "model.int8.onnx").copyTo(File(root, "model.onnx"), true)
    File(src, "tokens.txt").copyTo(File(root, "tokens.txt"), true)
    File(src, "LICENSE").copyTo(File(root, "LICENSE"), true)
    // voices.bin: पूरी लंबाई (sherpa model की गिनती से मिलाता है), पर सिर्फ़ 4 हिंदी आवाज़ें भरी - बाकी शून्य (zip में लगभग 0 बाइट)
    val all = File(src, "voices.bin").readBytes()
    if (all.size % kokoroStyleBytes != 0) throw GradleException("DK: voices.bin का नाप अजीब (${all.size})")
    val keep = ByteArray(all.size)
    for (sid in kokoroSpeakers.values) System.arraycopy(all, sid * kokoroStyleBytes, keep, sid * kokoroStyleBytes, kokoroStyleBytes)
    File(root, "voices.bin").writeBytes(keep)
    File(src, "espeak-ng-data").copyRecursively(File(root, "espeak-ng-data"), true)
    File(root, "espeak-ng-data").listFiles()?.filter { it.isFile && it.name.endsWith("_dict") && it.name !in espeakDicts }?.forEach { it.delete() }
    x.deleteRecursively()
    stamp.writeText(want)
}

dkFetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/${sherpaAar.name}", sherpaAar, sherpaAarSha)
prepareKokoro()

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
    // Kokoro TTS (.dk-cache से, ऊपर देखें) APK के assets/kokoro_model/ में
    sourceSets.getByName("main").assets.srcDir(kokoroAssets)
    // model.onnx (~109 MB) बिना zip-दबाव: फ़ोन सीधे APK से पढ़ता है (खोलकर दोबारा RAM में नहीं)
    androidResources {
        noCompress += listOf("onnx")
    }
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
                // Kokoro TTS (sherpa-onnx) भी सिर्फ़ arm64 - बाकी फ़ोनों पर Kokoro अपने-आप "तैयार नहीं" और Gemini TTS / DK लाइव बोलते हैं
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
    // नवंबर 2026: Kokoro TTS (फ़ोन में आवाज़, बिना इंटरनेट) - sherpa-onnx की आधिकारिक AAR (Maven Central पर नहीं, ऊपर dkFetch)
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
