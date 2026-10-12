// termagent :terminal-emulator
// Forked from https://github.com/termux/termux-app (terminal-emulator, GPL-3.0).
// Changes vs. upstream:
//   * JNI built with CMake instead of ndk-build
//   * modern Gradle configuration (namespace / compileSdk from version catalog)
plugins {
    alias(libs.plugins.android.library)
}

// ---------------------------------------------------------------------------
// Prebuilt-JNI mode.
//
// The Google NDK ships only x86_64 host binaries, so it cannot run on an aarch64
// Termux install. Termux does, however, provide a *native* aarch64 Android toolchain
// (`clang` targeting aarch64-linux-android + the `ndk-sysroot` package), which builds
// libtermux.so perfectly well:
//
//   clang -shared -fPIC -O2 -D_GNU_SOURCE -o libtermux.so termux.c
//
// So on-device builds pass -Ptermagent.prebuiltJni=true, which skips the NDK entirely and
// packages the already-compiled library from the :app module's jniLibs directory.
// Workstation builds (with a real NDK) leave it unset and compile from source.
// ---------------------------------------------------------------------------
val usePrebuiltJni: Boolean =
    (providers.gradleProperty("termagent.prebuiltJni").orNull ?: "false").toBoolean()

android {
    namespace = "com.termux.emulator"
    compileSdk = libs.versions.compileSdk.get().toInt()

    // Only required when we actually drive the NDK.
    if (!usePrebuiltJni) {
        ndkVersion = libs.versions.ndk.get()
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        if (!usePrebuiltJni) {
            externalNativeBuild {
                cmake {
                    cFlags += listOf(
                        "-std=c11",
                        "-Wall",
                        "-Wextra",
                        "-Werror",
                        "-Os",
                        "-fno-stack-protector",
                        "-D_GNU_SOURCE",
                    )
                }
            }
        }

        ndk {
            abiFilters += if (usePrebuiltJni) {
                // Only the ABI Termux's toolchain compiled for (the device's own).
                listOf("arm64-v8a")
            } else {
                // Termux itself ships these three; x86 is emulator-only and is skipped.
                listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    if (!usePrebuiltJni) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = libs.versions.cmake.get()
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Tests touch android.util.Log via Logger; return default values instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
