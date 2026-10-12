# Assembling an Android SDK for aarch64 Termux

`sdkmanager` cannot be used on this platform: the current `cmdline-tools` ship an **x86_64**
launcher (`/lib64/ld-linux-x86-64.so.2`) and no aarch64 build is published. Everything below
fetches the SDK packages straight from Google's repository instead, verifying each archive's
SHA-1 against the repository manifest.

Target layout:

```
~/android-sdk/
├── platforms/android-37/       (compileSdk 37 — required by Miuix 0.9.4 + Compose 1.12)
├── build-tools/37.0.0/
└── licenses/android-sdk-license
```

## 1. Platform 37

API 37 is not published as `platforms;android-37` yet — it ships under the canary codename.
Look it up in the repository manifest (`repository2-3.xml`):

```bash
curl -sSL -o sdkrepo.xml https://dl.google.com/android/repository/repository2-3.xml
grep -o 'platform-canary-[0-9]*_r[0-9]*\.zip' sdkrepo.xml | tail -1
```

Then download, verify and unpack:

```bash
cd ~
curl -sSL -o platform-37.zip \
  https://dl.google.com/android/repository/platform-canary-20261007_r17.zip
sha1sum platform-37.zip     # must match <checksum> in the manifest

mkdir -p android-sdk/platforms && cd android-sdk/platforms
unzip -q ~/platform-37.zip
mv android-canary-20261007 android-37
```

### Why `source.properties` must be rewritten

The canary platform declares itself as a *preview*:

```properties
AndroidVersion.ApiLevel=37.2
AndroidVersion.CodeName=CANARY
AndroidVersion.PreviewSdkInt=20261007
```

AGP then looks for the target hash `android-CANARY` (not `android-37`) and fails with
`Failed to find target with hash string 'android-37'`. Rewrite
`android-sdk/platforms/android-37/source.properties` as:

```properties
Pkg.Desc=Android SDK Platform 37
Pkg.UserSrc=false
Platform.Version=37
Platform.CodeName=
Pkg.Revision=17
AndroidVersion.ApiLevel=37
AndroidVersion.ExtensionLevel=24
AndroidVersion.IsBaseSdk=true
```

(Only the metadata changes — `android.jar` already contains the API 37 classes.)

## 2. Build-tools 37.0.0

```bash
cd ~
curl -sSL -o build-tools-37.zip \
  https://dl.google.com/android/repository/build-tools_r37_linux.zip
sha1sum build-tools-37.zip  # 70954e99f4c3d9d46ee70fa32624672fe7cd6ebe

mkdir -p android-sdk/build-tools && cd android-sdk/build-tools
unzip -q ~/build-tools-37.zip
mv android-37.0 37.0.0
```

## 3. Licences

```bash
mkdir -p ~/android-sdk/licenses
printf '24333f8a63b6825ea9c5514f83c2829b004d1fee\n84831b9409646a918e30573bab4c9c91346d8abd\nd56f5187479451eabf01fb78af6dfcb131a6481e\n' \
  > ~/android-sdk/licenses/android-sdk-license
```

## 4. Point Gradle at it

```bash
echo "sdk.dir=$HOME/android-sdk" > ~/termagent/local.properties
```

## Host tools: using Termux's aapt2

The SDK's `aapt2` is x86_64 and cannot run here. Termux ships a native aarch64 one
(`pkg install aapt2`). `scripts/build-on-termux.sh` passes:

```
-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2
```

Equivalently, put this in `~/.gradle/gradle.properties`:

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

## The JNI library needs no NDK at all

The Google NDK is x86_64-only, so `externalNativeBuild` cannot run on this device. Termux
provides an equivalent native toolchain instead:

| Termux package | Replaces |
| --- | --- |
| `clang` (`21.x`, targeting `aarch64-linux-android`) | NDK's clang |
| `ndk-sysroot` | NDK sysroot (installs Android headers/libs into `$PREFIX`) |
| `ndk-multilib`, `ndk-multilib-native-static`, `ndk-multilib-native-stubs` | extra ABIs / static stubs |

`scripts/build-on-termux.sh` therefore compiles the bridge itself:

```bash
clang -shared -fPIC -O2 -D_GNU_SOURCE -Wall -Wextra -Werror \
  -o app/src/main/jniLibs/arm64-v8a/libtermux.so \
  terminal-emulator/src/main/cpp/termux.c
```

and the build then runs with `-Ptermagent.prebuiltJni=true`, which skips
`externalNativeBuild` and packages the prebuilt `.so` from `app/src/main/jniLibs/`.

**ABI caveat:** the library targets the device's own ABI (`arm64-v8a`), so an on-device build
is a *single-ABI* APK. For a universal APK, build on a workstation with a real NDK and leave
`-Ptermagent.prebuiltJni` unset — `:terminal-emulator` will then compile all three ABIs via
CMake.

## Required Termux packages

```bash
pkg install openjdk-21 clang cmake aapt2 ndk-sysroot
```
