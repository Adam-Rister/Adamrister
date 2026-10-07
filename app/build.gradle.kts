plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.churchproductionpro.remote"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.churchproductionpro.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
}
