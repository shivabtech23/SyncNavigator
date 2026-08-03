// Root build file. Plugin versions are declared here once and applied in :app.
//
// AGP must be compatible with your Android Studio version. If Studio reports
// "Android Gradle plugin requires Java X" or refuses to sync, bump/lower the
// AGP version here first — that is the single most common setup failure and
// it has nothing to do with the project's own code.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
