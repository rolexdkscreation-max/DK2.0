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
    val ksPath = System.getenv("DK_KEYSTORE")
    signingConfigs {
        create("dk") {
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("DK_KS_PASS")
                keyAlias = System.getenv("DK_KEY_ALIAS") ?: "dk"
                keyPassword = System.getenv("DK_KS_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // DK AI Website Forge: 3D scene को app के assets से https पर चलाने के लिए (WebViewAssetLoader)
    implementation("androidx.webkit:webkit:1.11.0")
    // v3.40: DK लाइव का कैमरा (preview + हर सेकंड एक तस्वीर Gemini को)
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    testImplementation("junit:junit:4.13.2")
    // Android ka org.json unit test me khali (stub) hota hai - asli wala
    testImplementation("org.json:json:20240303")
}
