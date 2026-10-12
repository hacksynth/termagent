# Contributing to TermAgent

Thanks for wanting to help. TermAgent is an Android app that puts an LLM agent in
front of a real Termux `$PREFIX`, so most contributions fall into one of three
buckets: the **UI/agent** (Kotlin, `:app`), the **forked terminal modules** (Java,
`:terminal-emulator` / `:terminal-view`) and the **build/packaging glue**
(`scripts/`, `BootstrapInstaller`).

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

---

## Before you start

* **Read [`NOTICE`](NOTICE) first.** Two Gradle modules are forked from
  [`termux/termux-app`](https://github.com/termux/termux-app) and are **GPL-3.0-only**.
  The whole repository is therefore GPL-3.0-only, and the forked modules cannot be
  relicensed. By submitting a patch you agree to license it under GPL-3.0-only.
* **Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)** — it explains why
  `targetSdk = 28` is deliberate, why `applicationId` must be exactly 10 characters,
  and how commands are captured from the PTY. Several of those constraints look like
  bugs until you know the reason.

---

## Architecture constraints you must not "fix"

| Constraint | Why it exists |
| --- | --- |
| `targetSdk = 28` | From Android 10, app-private storage is mounted `noexec`, which breaks `exec()` of the bootstrap binaries. Same reason Termux pins 28. |
| `applicationId` is exactly 10 chars (`com.tagent`) | `BootstrapInstaller` repoints `com.termux` → `com.tagent` with a **length-preserving** in-place byte substitution inside ELF binaries and shebangs. Enforced by a `check` in `app/build.gradle.kts`. |
| No `applicationIdSuffix` on debug | A suffix would make the id longer than 10 bytes and break the above. |
| The bootstrap archive is **not** committed | It is fetched at build time and is ~32 MB. See `scripts/build-on-termux.sh`. |
| `:app`'s native lib may be prebuilt | The Google NDK ships x86_64-only host binaries and cannot run on aarch64 Termux; `-Ptermagent.prebuiltJni=true` packages a library compiled by Termux's own clang. |

---

## Getting set up

### Option A — build natively on the device (Termux, aarch64)

This is the fastest loop if you are already in Termux, and it is the path CI-less
contributors should use. No NDK, no `sdkmanager`, no emulator.

```bash
pkg install openjdk-21 clang cmake aapt2 ndk-sysroot
./scripts/build-on-termux.sh                 # → app/build/outputs/apk/debug/app-debug.apk
```

To run just the fast checks while iterating (these do not need the NDK either):

```bash
bash scripts/build-on-termux.sh :app:compileDebugKotlin
bash scripts/build-on-termux.sh :terminal-emulator:test :app:testDebugUnitTest
```

> The script retries Gradle a few times on purpose: Gradle's TLS client is flaky
> under concurrency on this platform. A compile error will still fail fast.

### Option B — build on a workstation

A normal Android Studio / SDK setup works, and is the only way to produce a
multi-ABI APK, because it compiles `libtermux.so` from source with a real NDK:

```bash
cp local.properties.example local.properties   # point sdk.dir at your SDK
./gradlew :app:assembleDebug
```

Required SDK components are listed in
[`local.properties.example`](local.properties.example); the on-device SDK
bootstrap procedure is in [`scripts/README-sdk.md`](scripts/README-sdk.md).

---

## Making a change

1. **Open an issue first for anything non-trivial** (new tool, new screen, build
   changes). Small fixes and docs can go straight to a PR.
2. Keep commits **focused**; one logical change per commit.
3. **Match the surrounding style.** The codebase is commented heavily and
   deliberately — explanations of *why* a non-obvious thing is done are welcome,
   and are a large part of the value of this tree.
4. **Do not hardcode user-visible strings.** They belong in `res/values/strings.xml`
   with translations in `res/values-zh/` (Simplified) and `res/values-b+zh+Hant/`
   (Traditional). See the *Localization* section of the README.
5. **Do not put `/*` inside a Kotlin block comment.** Kotlin block comments *nest*,
   so a stray `/*` in a KDoc comment silently swallows the rest of the file.
6. **Never commit** `local.properties`, `*.log`, `build/`, `.tmp_*`, or the
   bootstrap zip — `.gitignore` already covers these.

### Commit messages

Conventional-commits-ish, imperative mood, scoped by area:

```
app: fix assistant text dropped on text-only turns
terminal-emulator: build the JNI bridge with CMake
bootstrap: repoint .deb archives via DPkg::Pre-Install-Pkgs
docs: explain why targetSdk is pinned to 28
```

### Tests

* Pure-logic code (parsers, the provider catalogue, the agent loop) should have JVM
  unit tests next to it. `app/src/test/.../MarkdownParserTest.kt` is a good model —
  it tests the parser only, because the renderer needs `androidx.compose.ui`.
* Run them with `bash scripts/build-on-termux.sh :app:testDebugUnitTest`
  (or `./gradlew :app:testDebugUnitTest` on a workstation).
* Terminal-emulator behaviour is covered by upstream's own test suite in
  `terminal-emulator/src/test/`; keep it green.

### Pull requests

Please include:

* **what** changed and **why**;
* **how you verified it** — build command, on-device test, screenshots for UI;
* for anything touching the PTY, bootstrap or apt hooking, the exact device/Android
  version you tested on.

A PR that changes build configuration should also confirm the on-device build still
succeeds (`scripts/build-on-termux.sh`).

---

## Reporting bugs

Use the issue tracker and include:

* app version (`Settings → About`) / commit hash;
* device, Android version and ABI;
* whether the failure is in the bootstrap (first launch), a package install, the
  terminal, or the agent turn;
* `adb logcat` or `su -c 'logcat -d -b crash'` output if the app crashed;
* for agent issues, the provider/model and whether `run_shell` was enabled.

**Security issues do not go in the issue tracker** — see [SECURITY.md](SECURITY.md).

---

## Licensing of contributions

TermAgent is GPL-3.0-only. There is no CLA and no DCO sign-off requirement: by
opening a pull request you confirm you wrote the code (or have the right to submit
it) and you license it to the project under GPL-3.0-only.

If your patch vendors or adapts third-party code, add it to [`NOTICE`](NOTICE) with
its license and what you changed. Do not paste code under a license that is
incompatible with GPL-3.0.
