# TermAgent

[![License: GPL-3.0-only](https://img.shields.io/badge/License-GPL--3.0--only-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/platform-Android%2010%2B%20(sideload)-3ddc84.svg)](#targetsdk--28-is-deliberate)
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange.svg)](CHANGELOG.md)

A native Android agent for your device's shell: a **forked Termux PTY backend** wrapped in a
**ChatGPT-desktop-style chat UI** built with **[Miuix](https://github.com/compose-miuix-ui/miuix)**,
plus an **agentic AI** that plans, runs real shell commands and streams their output back live.

```
┌──────────── TermAgent ────────────┐
│  Miuix + Compose chat shell       │  ← what you see
├───────────────────────────────────┤
│  AgentEngine (OpenAI-compatible)  │  ← plan → tool calls → observe → repeat
├───────────────────────────────────┤
│  ShellManager ──► TerminalSession │  ← forked from Termux
│        │              │           │
│        │         JNI (termux.c)   │  ← forkpty(3), SIGWINCH, waitpid
│        ▼              ▼           │
│   $PREFIX/bin/bash  (real PTY)    │  ← an actual Termux `$PREFIX`
└───────────────────────────────────┘
```

---

## What "fork Termux as the backend" means here

The app does **not** re-implement a terminal. Two modules are taken verbatim from
[`termux/termux-app`](https://github.com/termux/termux-app) and adapted to a modern Gradle
build:

| Module | Upstream contents | What it gives us |
| --- | --- | --- |
| `:terminal-emulator` | 18 Java classes + `jni/termux.c` | The VT100/xterm emulator (`TerminalEmulator`, `TerminalBuffer`, `TerminalRow`) and the PTY bridge (`TTY`, `JNI.createSubprocess` → `forkpty()`) |
| `:terminal-view` | 8 Java classes + drawables | `TerminalView`, the Canvas-based renderer, keyboard handling and text selection |

Both modules are **self-contained**: their only imports are `android.*`, `androidx.annotation`
and each other — verified, no `termux-shared` required. The only change to the build is that
the native bridge is compiled with **CMake** instead of `ndk-build`.

### Where the shell comes from

`$PREFIX` is the **official Termux bootstrap** (`bootstrap-aarch64.zip` from the
`termux-packages` releases — a real `$PREFIX` with `bash`, `coreutils`, `apt`, `git`,
`python`, …), downloaded and unpacked into the app's private storage by `BootstrapInstaller`:

```
/data/data/com.termagent/files/
├── usr/            ← $PREFIX        (usr/bin, usr/lib, usr/etc, usr/tmp)
├── usr-staging/    ← staging dir, renamed onto usr/ when complete
└── home/           ← $HOME
```

The unpacking logic is a port of upstream `TermuxInstaller`: extract everything into
`usr-staging` (so a failed install cannot corrupt `$PREFIX`), recreate the symlinks listed in
`SYMLINKS.txt` with `Os.symlink()`, `chmod 0700` the executables (`bin/`, `libexec`,
`lib/apt/methods`) because **zip archives carry no POSIX mode bits**, then `rename(2)` the
staging directory onto `usr/`.

> **Why this isn't Termux itself:** a distinct package name means a distinct private data
> directory, one Android permission prompt instead of Termux's `RUN_COMMAND_INTENT`, and no
> dependency on a separate app being installed.

### `targetSdk = 28` is deliberate

From Android 10 the OS enforces **W^X**: app-private storage (`/data/data/<pkg>/files`) is
mounted `noexec`, so `exec()`-ing `$PREFIX/bin/bash` fails. Termux pins `targetSdk 28` to
avoid this. **TermAgent does the same** — it is built for sideloading / F-Droid, not Google
Play. On Android 10+ the bootstrap binaries are extracted and `exec`'d the same way Termux
does it.

---

## Repository layout

```
app/                         # the UI + agent (Kotlin, Compose, Miuix)
├── agent/
│   ├── ChatApi.kt           # OpenAI wire model (requests, chunks, tool calls)
│   ├── LlmClient.kt         # SSE streaming client on okhttp-sse
│   ├── AgentTools.kt        # run_shell / read_file / write_file / list_dir / get_system_info
│   ├── AgentEngine.kt       # the agent loop, emits AgentEvent
│   └── Prompt.kt            # the system prompt
├── shell/
│   ├── TermuxEnvironment.kt # $PREFIX/$HOME/$TMPDIR layout + the child's `envp`
│   ├── BootstrapInstaller.kt# bootstrap extract / prefix repoint / apt hook / second stage
│   ├── ShellManager.kt      # the persistent PTY session + streamed command execution
│   └── TermuxSessionClient.kt # TerminalSessionClient → Kotlin lambdas
├── assets/
│   ├── bootstrap/           # the official Termux bootstrap, bundled at build time
│   ├── providers/           # the devx provider/model tables
│   └── termagent/repoint-deb.sh # apt hook: repoints downloaded .deb files at our package id
├── data/
│   ├── Models.kt            # Conversation / ChatEntry / TerminalBlock
│   ├── SessionRepository.kt # one JSON file per conversation
│   └── SettingsStore.kt     # provider, endpoint, key, model, theme (SharedPreferences)
├── provider/                # ported from termux-dev (devx)
│   ├── ProviderRegistry.kt  # 178 provider presets (assets/providers/providers.json)
│   ├── ModelCatalog.kt      # context-window + $/Mtok pricing tables (6541 models)
│   └── ModelDiscovery.kt    # GET {baseUrl}/models for the model picker
├── ui/
│   ├── ChatViewModel.kt     # orchestration + API-history reconstruction
│   ├── components/          # Markdown (parser + renderer), TerminalCard, ToolAction,
│   │                        # MessageBubble, Composer, SessionDrawer, EmbeddedTerminal
│   ├── screens/             # ChatScreen, SettingsScreen
│   └── theme/Theme.kt       # Miuix theme + the shell palette
└── MainActivity.kt          # single-activity host, drawer + surfaces

terminal-emulator/           # forked from termux-app (GPL-3.0)
terminal-view/               # forked from termux-app (GPL-3.0)
docs/                        # ARCHITECTURE.md, ui-preview.html
scripts/                     # build-on-termux.sh, README-sdk.md (SDK bootstrap)
LICENSE                      # GPL-3.0-only — this project is a Termux derivative work
NOTICE                       # third-party components, licences and what was changed
CONTRIBUTING.md              # build instructions + the constraints you must not "fix"
SECURITY.md                  # threat model + private vulnerability reporting
CODE_OF_CONDUCT.md           # Contributor Covenant 2.1
CHANGELOG.md                 # Keep a Changelog
```

---

## How a turn works

1. You type *"list the files in my home directory"*.
2. `ChatViewModel` stores the user message and calls `AgentEngine.run(history)`.
3. `LlmClient` streams the completion. Text deltas render immediately (bubble + blinking cursor).
4. The model returns a **tool call**: `run_shell {command: "ls -la ~"}`.
5. `ShellManager` writes `ls -la ~` into the **persistent PTY** and captures the output from the
   **raw byte stream** (not the rendered screen — see [How output is captured](#how-output-is-captured)).
   Every new slice is emitted as `AgentEvent.ToolOutput`.
6. The UI appends those slices to a **terminal card**, which auto-scrolls like a real terminal.
   Its header names the action — `→ Run: ls -la ~`, `→ Read /path/to/file` — rather than
   echoing the arguments, following the `devx` CLI's convention.
7. The exit status is captured, the result is fed back to the model as a `tool` message, and
   the loop repeats until the model answers with no tool calls.

### How output is captured

Commands are bracketed by **sentinel markers with a nonce**, printed by the shell itself, and the
**raw pty byte stream** is scanned for them — *before* those bytes reach the terminal emulator:

```text
pty ──► TerminalSession.MainThreadHandler ──┬──► RawOutputListener ──► CommandCapture ──► tool result
                                            │
                                            └──► TerminalEmulator ──► scrollback ──► TerminalView
```

Everything is driven by the byte read, so it is **not** a second path for the screen to travel:
`CommandCapture` is a plain scanner with no access to the emulator, and the emulator's
`terminal`/`scrollback` is never consulted for output.

That distinction is the whole point. An earlier version derived the output from the emulator's
scrollback (`getTranscriptTextWithFullLinesJoined()`) and diffed it positionally, which is
unsound: the screen is a **lossy projection** of the byte stream, not a buffer of it. Attaching a
terminal view, rotating the device or showing the IME all call `updateSize()`, reflowing every
line — so the same logical output yields a different number of characters, the diff desynchronises,
and an already-printed end marker can fall outside the recomputed region. The visible symptom was
a command that had *finished* (marker printed, sitting in the scrollback) blocking until its
timeout. The byte stream is append-only, so a marker is either present or not, and no output is
ever delivered twice.

The scanner itself ([`CommandCapture`](app/src/main/java/com/termagent/shell/CommandCapture.kt))
is deliberately free of Android types, so its rules — a marker split across two reads, a command
that prints marker-like text, output arriving one byte at a time, the shell's own echo of the
snippet, CRLF from the pty — are covered by plain JVM unit tests
(`app/src/test/java/com/termagent/shell/CommandCaptureTest.kt`).

The markers the shell prints around each command look like this:

```sh
__termagent_id=42
printf '%s\n' "__TERMAGENT_BEGIN_${__termagent_id}__"
ls -la ~
__termagent_rc=$?
printf '\n__TERMAGENT_END_%s__ %s\n' "${__termagent_id}" "${__termagent_rc}"
```

Two details make the parsing robust:

* the markers are produced by **shell expansion**, so the text the tty *echoes*
  (`__TERMAGENT_BEGIN_${__termagent_id}__`) never equals the expanded marker — markers can
  therefore only appear in genuine command output;
* the end marker is only accepted when followed by a bare integer (the exit status), which
  rules out an echo of the snippet.

Because the command runs in the **current** shell (not a subshell), `cd`, `export` and
`alias` persist between turns. A timed-out command is interrupted with Ctrl-C and the prompt
is resynchronised before the next call.

### Bounding the output

What the model receives is capped at **128 KB** (`OutputAccumulator`: the first 64 KB, the last
64 KB, and the middle replaced by `... [N characters omitted] ...`). A command's output is
unbounded in principle — `yes`, a build log, `base64` of a file — and it is handed to the model
verbatim as a tool message, so without a cap a single `cat` of a large file would push the next
request past the model's context window. Keeping the *tail* is deliberate: that is where a
compiler, a package manager or a test runner puts the verdict.

The `run_shell` reply says so explicitly when it happens (`note: output was too large …`),
because a model that assumes it saw everything will wrongly conclude a search found nothing; the
note points it at the workaround (redirect to a file, then `read_file` / `grep` / `tail`).

The terminal *card* is bounded separately (128 KB, tail-only) in `ChatViewModel`: it is a view
and it is persisted with the conversation, so it must not grow without bound either. The live
stream is never trimmed — only what is kept is.

### Long-running commands

A turn can spend minutes inside one `run_shell` (`apt install`, a build, a test suite), so for
the duration of a turn the app holds an `AgentForegroundService` — a foreground service plus a
`PARTIAL_WAKE_LOCK`. Without it, switching away lets Android's cached-app freezer suspend the
very timers command capture depends on (the command keeps running in the PTY, but nothing reads
it), and the process can be killed outright; the `ShellManager` KDoc records exactly that
failure appearing as a command that hangs for 55 minutes. The service is not a nuisance
notification bolted on afterwards — it *is* the mechanism: it declares to the platform that
user-visible work is in progress. It is `exported="false"`, `START_NOT_STICKY`, and stops in the
turn's `finally`, so it cannot outlive the turn it belongs to.

Pressing **Stop** cancels the turn *and* sends Ctrl-C to the PTY's foreground job. Cancelling the
coroutine alone would leave the child process running — and the next command's snippet would then
be typed straight into its `stdin` — so `ShellManager.exec` interrupts on cancellation and
resynchronises the prompt before the next call. Stop deliberately does not surface an error: it
is a user action, not a failure.

---

## Package management (`apt` / `pkg install`)

Installing packages under a **foreign package id** needs one more step than running the
bootstrap, because the official repository archives bake in the *upstream* prefix.

### The problem

Every `.deb` in the Termux repo records **absolute** paths:

```
/data/data/com.termux/files/usr/bin/figlet
```

Real Termux can unpack those verbatim — its `$PREFIX` *is* that path. Here `$PREFIX` is
`/data/data/com.tagent/files/usr`, so dpkg resolves the archive's paths against the real
root filesystem, which lands on the **other app's** private data directory, and the install
dies:

```
dpkg: error processing archive .../figlet_2.2.5-3_aarch64.deb (--unpack):
 unable to stat './data/data/com.termux/files' (which was about to be installed): Permission denied
```

`BootstrapInstaller.repointPrefix` rewrites the *bootstrap*, but it never sees the packages
downloaded later, so `pkg install` still failed.

### The fix

apt provides exactly the right hook: **`DPkg::Pre-Install-Pkgs`**, which is handed the list of
already-verified archives on stdin, before dpkg unpacks them. `BootstrapInstaller` installs a
small hook script and one apt configuration file:

```
$PREFIX/libexec/termagent/repoint-deb      ← the hook (0700, rendered from an asset)
$PREFIX/etc/apt/apt.conf.d/99-termagent-repoint
    DPkg::Pre-Install-Pkgs { "…/libexec/termagent/repoint-deb"; };
```

For each archive the hook:

1. unpacks it with `dpkg-deb -R`;
2. **renames** the `data/data/com.termux` directory to `data/data/com.tagent`;
3. rewrites every remaining reference in file *contents* — scripts, shebangs, `md5sums`
   paths, and prefix strings compiled into **ELF binaries** — with `sed -i`;
4. normalises the control members (`dpkg-deb -R` returns maintainer scripts as `0700`, and
   `dpkg-deb -b` refuses anything below `0555`) and repacks with `dpkg-deb -b`.

Step 3 is only safe because `com.termux` and `com.tagent` are **both 10 bytes**: the
substitution is length-preserving, so rewriting a binary's bytes cannot shift any offset. This
is enforced at build time by the `check` on `applicationId` in `app/build.gradle.kts`.

The hook is idempotent and re-asserted on every app launch (off the main thread), so a
`$PREFIX` installed by an older build picks it up without a reinstall.

### Kept in step with Termux

The bootstrap is extracted manually rather than installed with `dpkg`, so its packages never
had their `postinst` scripts run. Upstream Termux runs a **second stage**
(`termux-bootstrap-second-stage.sh`) right after extraction to do exactly that — registering
`update-alternatives` links and finalising `termux-exec`. `BootstrapInstaller` runs the same
script (best-effort: the shell is already usable, and the script's own lock file makes a
second run a no-op).

The session environment also mirrors the one real Termux exports, including
`LD_PRELOAD=$PREFIX/lib/libtermux-exec-ld-preload.so` and the `TERMUX__*` variables, because
`termux-exec`'s shim is what keeps `$PATH` lookups and `exec()` consistent once packages are
installed.

---

## Localization

The app **follows the Android system language**; nothing in the code pins a locale.

| Directory | Language |
| --- | --- |
| `res/values/` | English (default fallback) |
| `res/values-zh/` | 简体中文 (Simplified, `zh-Hans`) |
| `res/values-b+zh+Hant/` | 繁體中文 (Traditional) |

`values-zh` carries no script or region qualifier, so every Simplified locale (`zh-CN`, `zh-SG`,
plain `zh`, …) resolves to it. Traditional is declared with a **BCP 47 script subtag**
(`b+zh+Hant`) instead of `zh-rTW`, which makes one directory serve Taiwan, Hong Kong and Macau
rather than three near-identical copies; Hong Kong's `zh-HK` therefore also gets Traditional
rather than falling back to Simplified.

Two details make it behave the way the platform intends:

* **`locale` is not in `android:configChanges`.** When the system language changes, Android
  recreates the activity and the whole Compose tree is built against the new `Resources`, so
  nothing can keep rendering a string from the old locale. Every user-visible string is
  therefore resolved with `stringResource(...)` at composition time.
* **`res/xml/locales_config.xml` + `android:localeConfig`** advertise the supported locales,
  which is also what surfaces them in Android 13+'s *per-app language* picker — so a user can
  run the app in Chinese while the phone stays in English.

Strings are resolved in `ChatViewModel` only where they are *produced* rather than *displayed* —
bootstrap progress lines and model-discovery errors, which are transient status facts. Labels,
buttons and placeholders are all resolved in the composables.

Two things are deliberately **not** translated: the tool descriptions sent to the model (part of
the wire protocol), and `Conversation.title` placeholders — the persistence layer stores no
translated text, so a conversation saved in one language still reads correctly in another.

---

## Provider catalog (ported from devx)

The model-provider layer is lifted from **[termux-dev](https://github.com/apvcode/Termux-Dev)**
(`devx`), so TermAgent speaks to the same 178 endpoints without any hand-typed URLs:

| Piece | Source in devx | Where it lives here |
| --- | --- | --- |
| Provider presets (`ALL_PROVIDERS`) | `dist/cli/providers.js` | `assets/providers/providers.json` → `ProviderRegistry` |
| Context limits | `dist/core/models.js` + `model-limits.json` | `assets/providers/model-limits.json` → `ModelCatalog` |
| Pricing | `dist/core/pricing.js` + `model-pricing.json` | `assets/providers/model-pricing.json` → `ModelCatalog` |
| `/models` discovery | `cli/index.js · fetchModels()` | `ModelDiscovery` |
| Per-provider key/URL memory | `config.apiKeys` / `config.baseUrls` | `SettingsStore` (JSON maps in `SharedPreferences`) |

Settings → **Provider** opens a searchable picker over the whole catalog; choosing one
restores its remembered base URL and API key (falling back to the preset URL on a first
visit). **Fetch** queries `GET {baseUrl}/models` and opens a filterable model list, and the
context window of the selected model is shown from the bundled table. Each assistant turn
ends with a `in / out` token line and an estimated `$` cost (drawn from the pricing table, so
it is a best-effort estimate rather than a billed figure).

The three JSON assets (~700 KB) are stored uncompressed inside the APK and decoded lazily, so
they can be refreshed by dropping in newer files from devx without recompiling any code.

---

## Safety

`run_shell` is gated by a **Settings switch** ("Allow command execution"). With it off, the
tool is withheld from the model entirely, so it can only read and write files. The system
prompt additionally instructs the agent to ask before installing packages or running
destructive commands.

---

## Building

### On the device, in Termux — one command (verified)

```bash
pkg install openjdk-21 clang cmake aapt2 ndk-sysroot
./scripts/build-on-termux.sh
# → app/build/outputs/apk/debug/app-debug.apk
```

This has been verified end-to-end on an aarch64 Android device: **the APK builds and signs
natively, with no NDK and no x86_64 emulation.** Two obstacles make that possible:

* **No NDK is needed.** The Google NDK ships only x86_64 host binaries, so
  `externalNativeBuild` cannot run here. Termux instead provides a native aarch64 Android
  toolchain — `clang` targets `aarch64-linux-android`, and `ndk-sysroot` installs the Android
  headers and libraries into `$PREFIX` — so the script compiles the bridge itself with one
  invocation and packages it from `app/src/main/jniLibs/`:

  ```bash
  clang -shared -fPIC -O2 -D_GNU_SOURCE -Wall -Wextra -Werror \
    -o app/src/main/jniLibs/arm64-v8a/libtermux.so \
    terminal-emulator/src/main/cpp/termux.c
  ```

* **No `sdkmanager`.** Its `cmdline-tools` launcher is x86_64-only, so
  [`scripts/README-sdk.md`](scripts/README-sdk.md) fetches the SDK packages directly from
  Google's repository with SHA-1 verification, and `scripts/build-on-termux.sh` points Gradle
  at Termux's native `aapt2` (`-Pandroid.aapt2FromMavenOverride=…`).

`-Ptermagent.prebuiltJni=true` tells `:terminal-emulator` to skip CMake and take the prebuilt
`.so`; the resulting APK is therefore **single-ABI (`arm64-v8a`)**, i.e. perfect for the device
that built it. Gradle's TLS client is unreliable in Termux under concurrency, so the script
retries and lowers `--max-workers`.

### On a workstation (for a universal APK)

Same script semantics, but leave `prebuiltJni` unset so CMake compiles all three ABIs.

Prerequisites: **JDK 17+** and an Android SDK with **platform 37**, **build-tools 37.0.0**,
**NDK 27.0.12077973**, **CMake 3.22.1**.

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                # deploy to a connected device
```

> Platform 37 is not published under `platforms;android-37` yet — it currently ships as the
> `android-canary-*` SDK package. See [`scripts/README-sdk.md`](scripts/README-sdk.md).

### The 10-character package id (do not change it)

`applicationId` is pinned to **`com.tagent`** — exactly 10 characters — and `app/build.gradle.kts`
fails the build if that changes. This is not cosmetic:

The Termux bootstrap is compiled for `/data/data/com.termux/files/usr`, and that path is baked
into **all 505 ELF binaries** (`apt`, `dpkg`, toybox, …) as well as 113 script shebangs. Under a
foreign package name `apt`/`dpkg` cannot work at all: `dpkg` ignores `DPKG_CONF_DIR`, and apt
resolves its method drivers through a compiled-in path, so both fail with
*"Permission denied"* on `/data/data/com.termux/files/usr/etc/…`.

`BootstrapInstaller` fixes this by rewriting those strings **in place with a same-length byte
substitution**: `com.termux` (10) → `com.tagent` (10). Because the lengths match, every byte
offset in every file stays valid — nothing is shifted and no embedded offset is invalidated.
NUL-padding is deliberately *not* used, since it would terminate the string early and turn
`/…/usr/etc/dpkg` into `/…/usr`.

Consequences:

* There is **no `applicationIdSuffix`** either — a `.debug` suffix would make the id longer than
  10 and silently disable ELF repointing.
* `TermuxEnvironment.filesDir` uses the *short* `/data/data/<pkg>/files` spelling rather than
  `Context.filesDir` (which returns `/data/user/0/<pkg>/files`). They are the same directory, but
  the latter is two characters longer, which would again break the same-length substitution.

If the substitution cannot be applied (lengths differ), the install still succeeds: text files are
rewritten, ELF binaries are skipped, and a warning is logged — you keep a working shell but lose
package management.

The same-length repointing is applied **twice**: to the bootstrap at install time, and to every
`.deb` that `apt` downloads afterwards, through an apt hook — see
[Package management](#package-management-apt--pkg-install).

## Installing the APK

Debug builds are signed with the standard Android debug key. From Termux:

```bash
termux-open app/build/outputs/apk/debug/app-debug.apk
```

or copy the APK to shared storage and tap it.

### First launch

1. **Settings** → pick a **Provider** from the bundled catalog (or `Custom`), enter its API
   key, then tap **Fetch** to list its models — any OpenAI-compatible server works; local ones
   (`http://127.0.0.1:11434/v1`) need no key.
2. Back on the chat screen, tap **Install** — the bundled Termux bootstrap is unpacked into
   `files/usr`, repointed at this app's package id, and the apt repoint hook is installed.
3. Ask for something. The Terminal tab in the drawer shows the live PTY.
4. `pkg install <anything>` / `apt install <anything>` works out of the box — see
   [Package management](#package-management-apt--pkg-install).

The UI follows your phone's language (English, 简体中文, 繁體中文) with no setting to change —
see [Localization](#localization). On Android 13+ you can also override it for just this app
from *Settings → Apps → TermAgent → Language*.

---

## Tech stack

| Concern | Choice |
| --- | --- |
| UI | Jetpack Compose `1.12.0` + **Miuix `0.9.4`** (`top.yukonga.miuix.kmp`) |
| Terminal | forked `termux-app` `terminal-emulator` + `terminal-view` (GPL-3.0) |
| PTY | `forkpty(3)` via JNI (`termux.c`) + CMake |
| LLM transport | OkHttp 4 + `okhttp-sse` (streaming `text/event-stream`) |
| Serialisation | `kotlinx.serialization` (API wire model + on-disk conversations) |
| Persistence | one JSON file per conversation; `SharedPreferences` for settings |
| Localization | Android string resources; follows the system language, `localeConfig` for the Android 13+ per-app picker |
| Toolchain | AGP `9.4.1` (built-in Kotlin), Kotlin compiler plugins `2.4.21`, Gradle `9.8.1`, JDK ≥ 17, `compileSdk 37` |

The toolchain versions are **not** free choices: since AGP 9.0 Kotlin support is built into
AGP (applying `org.jetbrains.kotlin.android` is now a hard error), Compose 1.12 AARs declare
`minAndroidGradlePluginVersion=9.1.0`, and Miuix 0.9.4 declares `minCompileSdk=37`. These
values mirror the versions the Miuix 0.9.4 build itself uses.

---

## Contributing

Contributions are welcome — bug reports, fixes, translations and docs all count.

* Read **[`CONTRIBUTING.md`](CONTRIBUTING.md)** first: it covers the on-device and
  workstation builds, the architecture constraints that must not be "fixed"
  (`targetSdk = 28`, the 10-character package id, …), and the string-resource rule for UI text.
* Please follow the **[Code of Conduct](CODE_OF_CONDUCT.md)** (Contributor Covenant 2.1).
* Report crashes and wrong behaviour through the issue tracker; report security problems
  privately via **[`SECURITY.md`](SECURITY.md)**.

New here? The **provider catalog**, **localization** and **Markdown renderer** are the most
self-contained places to start — each has a narrow surface and all three ship JVM test suites.

Run the suite with:

```bash
bash scripts/build-on-termux.sh :app:testDebugUnitTest     # on device
./gradlew :app:testDebugUnitTest                           # on a workstation
```

It is 126 plain JVM tests, no emulator needed. The parts that look hardest to test on purpose
are the parts with the most coverage: the agent loop is driven through two interfaces
(`LlmStreamer`, `AgentToolRunner`) with scripted events and canned tool results, and the
entry-log → wire-conversation translation lives in `ApiHistory` as a pure function. If you
change a rule there, the tests are meant to tell you *which* one you broke — they were checked
by disabling each rule in turn and watching exactly the matching test fail.

## Status

Version **0.1.x** (alpha). The tree builds and runs end-to-end on an aarch64 Android device,
including `apt install`, but the persisted conversation format and the details of the
bootstrap/package layer may still change between minor versions. See
[`CHANGELOG.md`](CHANGELOG.md).

## Disclaimer

TermAgent executes **arbitrary shell commands generated by a language model** on your device.
There is no sandbox and no command allow-list; turning command execution off only removes the
tool from the model's context. Prompt injection from a file, a webpage or command output can
therefore run commands as your app.

Install it on a device you can afford to lose, use a scoped API key, and read
[`SECURITY.md`](SECURITY.md) before enabling anything.

This project is **not affiliated with, endorsed by, or sponsored by** the Termux project or
Google. It is a sideload/F-Droid build (`targetSdk = 28`) and is not published on Google Play.
"Termux" is a project of the Termux maintainers; "Android" is a trademark of Google LLC.

## Licence

TermAgent is licensed under the **GNU General Public License v3.0 only** — see
[`LICENSE`](LICENSE). SPDX identifier: `GPL-3.0-only`.

It is a **derivative work of [Termux](https://github.com/termux/termux-app)**: the
`terminal-emulator` and `terminal-view` modules are copied from that project, which is released
under GPLv3-only, so the combined work must be distributed under the same terms. Those two
modules cannot be relicensed, and their upstream `LICENSE.md` files are kept alongside them.

```
Copyright (C) 2026 the TermAgent contributors
Copyright (C) 2017-2026 the termux/termux-app contributors   (terminal-emulator, terminal-view)
Copyright (C) 2007-2016 Jack Palevich / jackpal project      (original Android Terminal Emulator)
Copyright (c) ApvCode                                        (termux-dev / devx provider & model tables)
```

This program is distributed in the hope that it will be useful, but **WITHOUT ANY WARRANTY**;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details.

A full inventory of third-party components, their licences and the exact changes made to each
is in [`NOTICE`](NOTICE).
