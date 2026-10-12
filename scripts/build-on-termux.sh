#!/data/data/com.termux/files/usr/bin/bash
#
# Build TermAgent inside Termux, on an aarch64 Android device, with no NDK and no
# x86_64 tooling.
#
# Why this script exists
# ----------------------
# Building an Android app on aarch64 Termux normally fails for two reasons:
#
#   1. the Google NDK ships only x86_64 *host* binaries, so `externalNativeBuild` cannot
#      run here. We sidestep it completely: Termux provides a native aarch64 Android
#      toolchain (the `clang` package targets aarch64-linux-android, and `ndk-sysroot`
#      installs the Android headers/libs straight into $PREFIX). One command builds the
#      JNI bridge, and the result is packaged from app/src/main/jniLibs/:
#
#         clang -shared -fPIC -O2 -D_GNU_SOURCE -o libtermux.so termux.c
#
#   2. the SDK's aapt2 is an x86_64 binary. Termux ships its own native aapt2, wired in
#      through `-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2`.
#
# Gradle's TLS client is flaky on this platform under concurrency, so the build is retried
# (Gradle caches whatever it already downloaded, making each retry cheaper).
#
# Usage:  ./scripts/build-on-termux.sh [extra gradle args...]

set -uo pipefail

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-21-openjdk}"
export JAVA_HOME

# Keep the SDK outside $HOME if you can: $HOME is mounted noexec on some setups, which
# breaks AGP's build tools. Default to $HOME/android-sdk for convenience.
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_HOME

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR" || exit 1

say() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- preflight
[ -d "$JAVA_HOME" ] || die "JDK not found at $JAVA_HOME — run: pkg install openjdk-21"
[ -d "$ANDROID_HOME/platforms" ] || die "No Android SDK at $ANDROID_HOME (see scripts/README-sdk.md)"
command -v clang >/dev/null || die "clang missing — run: pkg install clang"
command -v "$PREFIX/bin/aapt2" >/dev/null 2>&1 || [ -x "$PREFIX/bin/aapt2" ] ||
    die "aapt2 missing — run: pkg install aapt2"

say "JAVA_HOME     = $JAVA_HOME"
say "ANDROID_HOME  = $ANDROID_HOME"
java -version 2>&1 | head -1 | sed 's/^/    /'

# ---------------------------------------------------------------- 1. JNI bridge
# Rebuild libtermux.so from the forked Termux source, natively for this device's ABI.
ABI="arm64-v8a"
JNI_SRC="$PROJECT_DIR/terminal-emulator/src/main/cpp/termux.c"
JNI_OUT_DIR="$PROJECT_DIR/app/src/main/jniLibs/$ABI"
JNI_OUT="$JNI_OUT_DIR/libtermux.so"

if [ -f "$JNI_SRC" ]; then
    say "Building libtermux.so for $ABI with Termux's native clang"
    mkdir -p "$JNI_OUT_DIR"
    clang -shared -fPIC -O2 -D_GNU_SOURCE \
        -Wall -Wextra -Werror \
        -o "$JNI_OUT" "$JNI_SRC" \
        || die "JNI compilation failed"
    say "  -> $JNI_OUT ($(stat -c%s "$JNI_OUT") bytes)"
else
    say "termux.c not found, keeping existing $JNI_OUT"
fi

# ---------------------------------------------------------------- 2. Termux bootstrap
#
# The bootstrap is fetched HERE, at build time, and bundled into the APK's assets. The app
# therefore installs its shell entirely offline — no CDN dependency on first launch — and the
# archive's integrity is guaranteed by the APK signature, so no separate checksum is needed.
#
# Set TERMAGENT_SKIP_BOOTSTRAP=1 to skip (useful when iterating on UI only); the app then falls
# back to downloading at runtime.
ABI="arm64-v8a"
BOOTSTRAP_ASSET_DIR="$PROJECT_DIR/app/src/main/assets/bootstrap"
BOOTSTRAP_ASSET="$BOOTSTRAP_ASSET_DIR/bootstrap-$ABI.zip"
BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip"

if [ "${TERMAGENT_SKIP_BOOTSTRAP:-0}" = "1" ]; then
    say "Skipping bootstrap bundling (TERMAGENT_SKIP_BOOTSTRAP=1)"
elif [ -f "$BOOTSTRAP_ASSET" ]; then
    say "Bundled bootstrap already present: $(stat -c%s "$BOOTSTRAP_ASSET") bytes"
else
    say "Fetching Termux bootstrap for $ABI (bundled into the APK)"
    mkdir -p "$BOOTSTRAP_ASSET_DIR"
    if curl -fSL --retry 3 --retry-delay 2 -o "$BOOTSTRAP_ASSET.part" "$BOOTSTRAP_URL"; then
        mv "$BOOTSTRAP_ASSET.part" "$BOOTSTRAP_ASSET"
        say "  -> $(stat -c%s "$BOOTSTRAP_ASSET") bytes"
        say "  sha256: $(sha256sum "$BOOTSTRAP_ASSET" | cut -d' ' -f1)"
    else
        rm -f "$BOOTSTRAP_ASSET.part"
        say "WARNING: bootstrap download failed — the APK will fall back to downloading at runtime"
    fi
fi

# ---------------------------------------------------------------- 3. Gradle
GRADLE_ARGS=(
    ":app:assembleDebug"
    "-Ptermagent.prebuiltJni=true"
    "-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2"
    # Gradle's TLS handshake is unreliable in Termux when many connections open at once.
    "--max-workers=2"
    "-Dorg.gradle.parallel=false"
    "-Dorg.gradle.internal.http.connectionTimeout=120000"
    "-Dorg.gradle.internal.http.socketTimeout=120000"
    "-Dorg.gradle.daemon=false"
)
[ "$#" -gt 0 ] && GRADLE_ARGS+=("$@")

say "Gradle: ${GRADLE_ARGS[*]}"

ATTEMPTS=4
for attempt in $(seq 1 "$ATTEMPTS"); do
    say "Build attempt $attempt/$ATTEMPTS"
    if ./gradlew "${GRADLE_ARGS[@]}" "$@"; then
        APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
        say "BUILD SUCCEEDED"
        [ -f "$APK" ] && say "APK: $APK ($(stat -c%s "$APK") bytes)"
        exit 0
    fi
    # Retrying only helps for network/TLS flakiness; a compile error will fail again fast.
    say "attempt $attempt failed — retrying (Gradle caches completed downloads)"
    sleep 3
done

die "build failed after $ATTEMPTS attempts"
