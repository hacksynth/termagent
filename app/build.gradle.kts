// termagent :app
// Native Android front-end (Miuix + Compose) over a forked Termux PTY backend.
//
// NOTE: there is deliberately no "org.jetbrains.kotlin.android" plugin here. Since AGP 9.0
// Kotlin support is built in, and applying that plugin is now a hard error. Kotlin
// *compiler* plugins (compose, kotlinx-serialization) are still applied normally.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// See :terminal-emulator — when set, libtermux.so comes prebuilt from
// app/src/main/jniLibs/<abi>/ (built natively by Termux's clang) and no NDK is needed.
val usePrebuiltJni: Boolean =
    (providers.gradleProperty("termagent.prebuiltJni").orNull ?: "false").toBoolean()

android {
    namespace = "com.termagent"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // AGP defaults to its own preferred build-tools revision, which may not be installed on a
    // device where the SDK was assembled by hand. Pin it to the revision we verify against.
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        // The applicationId must be EXACTLY 10 characters. Rationale:
        //
        // The bundled Termux bootstrap is compiled for `/data/data/com.termux/files/usr`, and that
        // path is baked into 505 ELF binaries (apt, dpkg, toybox, …) as well as the shebangs of
        // 113 scripts. Under a different package name apt/dpkg cannot work — `dpkg` ignores
        // DPKG_CONF_DIR and apt locates its method drivers through a compiled-in path.
        //
        // BootstrapInstaller therefore repoints those strings with a strictly *same-length*
        // byte substitution: `com.termux` -> `com.tagent` is 10 -> 10, which keeps every byte
        // offset (and every other embedded pointer/offset) intact. That only holds if the
        // package id is 10 characters, so it is enforced here instead of failing at runtime.
        applicationId = "com.tagent"
        check(applicationId!!.length == 10) {
            "applicationId must be exactly 10 characters (e.g. \"com.tagent\") so that " +
                "com.termux can be repointed in-place inside the bundled Termux binaries; " +
                "got \"$applicationId\" (${applicationId!!.length})."
        }
        minSdk = libs.versions.minSdk.get().toInt()

        // IMPORTANT: Termux deliberately targets SDK 28.
        // From targetSdk 29 (Android 10) the OS enforces W^X / noexec on app-private
        // storage, which breaks exec() of the bootstrap binaries (bash, coreutils, ...).
        // This app is intended for sideloading / F-Droid, not Google Play.
        targetSdk = 28

        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables { useSupportLibrary = true }

        ndk {
            abiFilters += if (usePrebuiltJni) {
                // Only the prebuilt library we actually have.
                listOf("arm64-v8a")
            } else {
                listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    buildTypes {
        debug {
            // NOTE: deliberately no applicationIdSuffix. A suffix would make the package id
            // longer than 10 characters and break the same-length prefix repointing above
            // (the `check` in defaultConfig would fail the build).
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            /**
             * Let unmocked `android.*` calls return their defaults instead of throwing.
             *
             * The classes under test are deliberately plain Kotlin, but the agent loop still calls
             * `android.util.Log` for diagnostics. Without this, every JVM test that drives the loop
             * would fail with "Method d in android.util.Log not mocked" — forcing either Robolectric
             * (heavy, slow) or removing the logging (worse). Only the `android.jar` stubs are
             * affected; nothing here touches a real Android runtime.
             */
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The Termux bootstrap in assets/bootstrap/ is already a zip. Storing it as-is means
        // the APK does not pay for compressing ~32 MB again, keeps the archive byte-identical,
        // and lets BootstrapInstaller use AssetFileDescriptor.length for progress reporting.
        noCompress += "zip"
    }

    lint {
        abortOnError = false
    }
}

// AGP 9's built-in Kotlin derives jvmTarget from the Java `compileOptions` above, so there is
// no Kotlin block here. Deliberately no `jvmToolchain(17)` either: that would force Gradle to
// locate (or download) a JDK 17, whereas any JDK >= 17 works fine — Termux ships JDK 21.

dependencies {
    // ---- forked Termux backend (PTY + VT emulation + terminal widget) ----
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    // ---- AndroidX core ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.profileinstaller)

    // ---- Compose (androidx, pinned to the version Miuix resolves to) ----
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // ---- Miuix UI (MIUI-flavoured Compose components) ----
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.squircle)

    // ---- Agent / networking ----
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // ---- Tests ----
    testImplementation(libs.junit)
}
