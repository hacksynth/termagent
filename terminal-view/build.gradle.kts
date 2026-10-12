// termagent :terminal-view
// Forked from https://github.com/termux/termux-app (terminal-view, GPL-3.0).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    // `api` so that consumers of :terminal-view also see com.termux.terminal.*
    api(project(":terminal-emulator"))
    testImplementation(libs.junit)
}
