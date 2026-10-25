import java.util.Properties

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
    // v3.66: आवाज़-पहचान (ONNX Runtime) सिर्फ़ arm64 फ़ोनों के लिए - APK करीब +13 MB ही बढ़े।
    // 32-bit / x86 पर .so नहीं: वहाँ पहचान अपने-आप बंद रहती है और DK पहले की तरह सब सुनता है (कुछ टूटता नहीं)
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += setOf(
                "lib/x86/libonnxruntime.so", "lib/x86/libonnxruntime4j_jni.so",
                "lib/x86_64/libonnxruntime.so", "lib/x86_64/libonnxruntime4j_jni.so",
                "lib/armeabi-v7a/libonnxruntime.so", "lib/armeabi-v7a/libonnxruntime4j_jni.so",
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
}
