// 14 नवंबर 2026 (मालिक): "Terminal से ऐप बनाओ और इंस्टॉल करने लायक बनाओ".
// यह छोटा-सा WebView ऐप सिर्फ़ एक साँचा (template) है - DK इसे अपने assets में रखता है और फ़ोन पर ही
// इसमें आपकी HTML/JS फ़ाइलें डालकर, नया package नाम + ऐप का नाम लिखकर, sign करके असली APK बनाता है (dkai/DkaiApk.kt).
// कोई library नहीं (सिर्फ़ Android framework) - APK बहुत छोटा रहे.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.dk.apkshell"
    compileSdk = 34

    defaultConfig {
        // फ़ोन पर यही नाम बदला जाता है (DkaiApk.PLACEHOLDER_PKG) - इसे बदलें तो वहाँ भी बदलें
        applicationId = "com.dk.apkshell.placeholder"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // sign फ़ोन पर होता है (हर फ़ोन की अपनी key) - यहाँ unsigned
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
