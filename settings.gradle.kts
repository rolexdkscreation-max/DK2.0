pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "DK"
include(":app")
// 14 नवंबर 2026: फ़ोन पर APK बनाने का साँचा (DKAI Terminal की `apk` कमांड)
include(":apkshell")
