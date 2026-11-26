plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.minimal.scrcpyclient"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.minimal.scrcpyclient"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // scrcpy-server has no extension; make sure it's stored untouched
    androidResources { noCompress += "" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    // Pure-Java ADB client (VERIFY latest version on Maven Central / GitHub releases)
    implementation("io.github.muntashirakon:libadb-android:3.1.1")
    // Conscrypt provides TLS 1.3 needed by libadb-android for Android 11+ TLS ADB
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
