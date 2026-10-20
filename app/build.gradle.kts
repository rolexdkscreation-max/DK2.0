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
        versionName = "2.5.$vc"
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
}
