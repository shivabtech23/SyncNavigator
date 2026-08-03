plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0+ moved the Compose compiler into a Kotlin plugin.
    // Its version must match the Kotlin plugin version exactly.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shiv.syncnavigator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shiv.syncnavigator"

        // PHASE1_SETUP.md: Notification.getChannelId() is called
        // unconditionally in NotificationLoggerService and requires API 26.
        // Do not lower this.
        minSdk = 26

        targetSdk = 35
        versionCode = 1
        versionName = "0.1-phase1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Left off deliberately for Phase 1. Minification would obfuscate
            // class names, and ViewTreeInspector records view.javaClass.name
            // as capture data — obfuscating it would corrupt the corpus.
            isMinifyEnabled = false
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
        compose = true
    }
}

dependencies {
    // FileProvider (CaptureDebugScreen.exportAll)
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // StateFlow / MutableStateFlow in NotificationCapture.kt (CaptureStore).
    // Declared explicitly rather than relied on transitively — CaptureStore is
    // the Phase 2 integration point and should not depend on another
    // library's transitive graph.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")      // asImageBitmap()
    implementation("androidx.compose.foundation:foundation") // LazyColumn/LazyRow/Image
    implementation("androidx.compose.material3:material3")   // HorizontalDivider needs m3 >= 1.2
    implementation("com.smartdevicelink:sdl_android:5.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-splashscreen:1.0.1")
}
