# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **Note on the version number.** TermAgent is a pre-1.0, sideload-only application.
> `1.0.0` is reserved for the point at which the bootstrap/package-management layer
> and the agent loop are considered stable on a representative range of devices.
> Until then, minor versions may contain breaking changes to the persisted
> conversation format.

## [Unreleased]

### Added

* **Test coverage for the agent loop and the wire-history rules** — the two places
  where a mistake is invisible until a *later* turn fails. `AgentEngine` now depends
  on two interfaces (`LlmStreamer`, `AgentToolRunner`) instead of the concrete
  `LlmClient` / `AgentTools`, so the loop is driven by scripted event streams and
  canned tool results, with no network, pty or `Context`. The entry-log →
  wire-conversation translation moved out of `ChatViewModel` into `ApiHistory`, a
  pure function. `AgentEngineTest` (19) and `ApiHistoryTest` (16) bring the suite to
  **126** tests. The suite was validated by mutation, not just by passing: each rule
  was disabled in turn and exactly the matching test failed.
  `AgentEngine` also takes a `tooManyToolCalls: (Int) -> String` rather than an
  Android `Context` — it needs one translated string, and the `Context` was what made
  the loop uninstantiable off-device.

* **A foreground service for the duration of a turn.** A turn can spend minutes
  inside one `run_shell` (`apt install`, a build, a test suite), and once the app
  was backgrounded Android's cached-app freezer could suspend the very timers the
  command capture depends on — the command kept running in the pty while nothing
  read it — or the process could be killed outright. `AgentForegroundService` is a
  foreground service plus a `PARTIAL_WAKE_LOCK`, started for the turn and stopped
  in its `finally`; it is `exported="false"` and `START_NOT_STICKY`, so it cannot
  outlive the work it exists for. (The `WAKE_LOCK` and `FOREGROUND_SERVICE`
  permissions were already declared but unused.)
* `OutputAccumulator` (with `OutputAccumulatorTest`) bounding a command's captured
  output, and `ToolResultTest` guarding the "carry metadata, never sniff it"
  contract.
* Repository open-sourced under **GPL-3.0-only**, with a [`NOTICE`](NOTICE)
  recording the Termux / jackpal / termux-dev (`devx`) attribution and their
  licenses.
* [`CONTRIBUTING.md`](CONTRIBUTING.md), [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)
  (Contributor Covenant 2.1) and [`SECURITY.md`](SECURITY.md) describing the threat
  model.

### Fixed

* **Command output was unbounded.** Everything a command printed was accumulated in
  memory and handed to the model verbatim as a tool message, so a single `cat` of a
  large file — or `yes`, or a build log — could exhaust memory *and* blow the
  model's context window on the next request. Capture is now capped at 128 KB: the
  first 64 KB and the last 64 KB, with the middle replaced by
  `... [N characters omitted] ...`. Both ends are kept on purpose (the head has the
  `exit_code:` line, the tail has a compiler's verdict). The reply also states the
  truncation in prose and points at the workaround, because a model that assumes it
  saw everything will wrongly conclude a search found nothing. `ShellResult.truncated`
  carries the fact out of band, so a command that *printed* that sentence cannot be
  mistaken for a truncated capture.
* **Pressing Stop left the command running.** Cancelling the coroutine does not kill
  the child process, so the pty kept executing; the next command's snippet would then
  be typed straight into its `stdin`, and its output would be scanned as the next
  command's (the marker scan is positional). `ShellManager.exec` now sends Ctrl-C on
  cancellation and flags the shell for prompt recovery.
* **Pressing Stop showed a red "Job was cancelled" error.** `CancellationException`
  fell into the generic `Throwable` handler. Stopping a turn is a user action, not a
  failure, and is now handled separately.
* **A `read_file` of a file mentioning `exit_code: 124` showed an "exit 124" badge.**
  The UI re-derived the exit status by regexing the tool reply text, but that text is
  arbitrary content for every tool except `run_shell`. The exit status is now carried
  on `ToolResult` / `AgentEvent.ToolFinished` (`ToolResult.exitCode`), and the
  text-sniffing `EXIT_CODE_REGEX` is gone.
* **`persist()` saved the wrong conversation.** It ignored its argument and always
  wrote `_state.value.current`, so a save queued while switching, deleting or clearing
  a conversation could silently write the wrong one — losing the edit that prompted it.
* **A terminal card's output was unbounded** in memory and in the persisted
  conversation JSON. The card (a live view, not the tool result) keeps a separate
  128 KB tail window (`ChatViewModel.MAX_TERMINAL_OUTPUT_CHARS`).

* **A finished command could hang until its timeout.** Command output was derived
  from the terminal emulator's rendered scrollback and diffed positionally. The
  screen is a *lossy projection* of the pty byte stream: attaching the terminal
  tab, rotating the device or showing the IME calls `updateSize()`, which reflows
  every line and changes the character count, so the diff desynchronised and an
  end marker that had already been printed could go unrecognised. Output is now
  captured from the **raw byte stream** (`CommandCapture`), scanned before the
  bytes reach the emulator, which is append-only and therefore cannot mis-detect a
  marker. Reported symptom: a completed `pkg install` blocking for its whole budget.
* **A non-zero exit — or a timeout — showed as "Succeeded".** The tool result was
  classified by `text.startsWith("Error")`, but `run_shell` replies with
  `exit_code: 124, …`, so a killed command rendered a green badge. Failure is now
  carried explicitly on the result (`ToolResult.isError`).
* **The `run_shell` timeout ceiling was not what the schema advertised.** The tool
  clamped to 3600 s while the shell enforced 600 s; both values are now derived from
  one constant, and a caller-supplied timeout is clamped in the shell as well.
* **Multi-byte output split across a `read(2)` was mangled** into replacement
  characters (`Utf8StreamDecoder` carries the partial sequence over).
* **A turn's progress was only written to disk when it ended**, so a conversation
  in the middle of a slow tool looked empty and a turn interrupted by the process
  being killed was lost entirely. It is now persisted as it streams (throttled to
  once a second, written straight through at the end of the turn).
* **A conversation reloaded after a killed turn could render a permanently blinking
  cursor**, because `ChatEntry.isStreaming` was persisted but never cleared on load.

### Security

* **`android:allowBackup` is now `false`,** with an empty
  `res/xml/data_extraction_rules.xml` covering both `<cloud-backup>` and
  `<device-transfer>`. Provider API keys live unencrypted in `SharedPreferences`, and
  the app's private storage holds the unpacked `$PREFIX` (the user's shell history and
  every file the agent wrote) — none of which should leave the sandbox in a backup.
  Clearing the app's data or reinstalling is the supported way to reset; there is no
  restore path, and nothing worth restoring that the APK does not already contain.
  See [`SECURITY.md`](SECURITY.md).

### Changed

* **Terminal cards now name the action instead of dumping arguments.** The header
  reads `→ Run: ls -la ~`, `→ Read /path/to/file`, `→ List ~`, `→ Look at shot.png`
  — the same shape `devx` prints before it runs a tool
  (`dist/core/loop.js:getActionDescription()`). Previously a non-shell tool fell back
  to a truncated `{"path":"…"}` blob, which told the user nothing. The verb table
  accepts `devx`'s aliases (`bash`, `ls`, `patch`, `view_file`, …), so it keeps
  working if a provider or MCP server uses different names. The copy button now
  copies what the label refers to (the command, the path) rather than the raw JSON,
  and is hidden for tools that have no such payload. Labels are resolved in the UI
  from the stored arguments, never persisted, so a conversation re-renders in
  whichever language the app is currently running in.

## [0.1.0] — unreleased

Initial version. Not yet tagged or published; this entry describes the tree as it
stands at the first public commit.

### Added

**Terminal backend (forked from [`termux/termux-app`](https://github.com/termux/termux-app), GPL-3.0)**

* The `:terminal-emulator` and `:terminal-view` modules, vendored verbatim apart from
  a modern Gradle setup and a **CMake**-based build of the JNI bridge
  (`terminal-emulator/src/main/cpp/termux.c` → `libtermux.so`, `forkpty(3)`).
* Prebuilt-JNI mode (`-Ptermagent.prebuiltJni=true`) so the native bridge can be
  compiled by Termux's own aarch64 `clang` on the device, where the x86_64-only
  Google NDK cannot run.

**Bootstrap**

* `BootstrapInstaller` unpacks the **official Termux bootstrap** into app-private
  storage, in the upstream two-phase way: extract into `usr-staging`, recreate
  `SYMLINKS.txt` with `Os.symlink()`, `chmod 0700` the executables, then `rename(2)`
  onto `usr/`.
* Same-length in-place prefix repointing (`com.termux` → `com.tagent`) across the
  bootstrap's ELF binaries and script shebangs; `applicationId` length is enforced at
  build time so the substitution stays byte-exact.
* The upstream `termux-bootstrap-second-stage.sh` is executed after extraction, so
  `update-alternatives` links and `termux-exec` are finalised.
* The session environment mirrors real Termux: `LD_PRELOAD=libtermux-exec-ld-preload.so`,
  `TERMUX__PREFIX`, `TERMUX__ROOTFS_DIR`, `TERMUX__HOME`, `TERMUX__USER_ID`,
  `ANDROID__BUILD_VERSION_SDK`, `TMPDIR` inside the prefix.
* The bootstrap archive is downloaded at build time by
  [`scripts/build-on-termux.sh`](scripts/build-on-termux.sh) and bundled into the APK,
  so the first launch installs the shell **offline**.

**Package management (`apt` / `pkg install`)**

* A `DPkg::Pre-Install-Pkgs` apt hook
  (`$PREFIX/libexec/termagent/repoint-deb` + `apt.conf.d/99-termagent-repoint`) that
  rewrites downloaded `.deb` archives — and their compiled-in prefix strings — at the
  app's own package id, making `apt install` / `pkg install` work under a foreign
  package id for the first time. Re-asserted on every launch for older prefixes.

**Agent**

* `LlmClient`, an OpenAI-compatible **SSE streaming** client on okhttp-sse.
* `AgentEngine`, the plan → tool call → observe loop, streaming
  `AgentEvent.{TextDelta, ReasoningDelta, ToolCall, ToolOutput, Usage, …}`.
* Tools: `run_shell`, `read_file`, `write_file`, `list_dir`, `get_system_info`,
  gated by a *Settings → Allow command execution* switch that withholds `run_shell`
  from the model entirely when off.
* Robust PTY command capture using nonce'd shell-expanded sentinel markers plus the
  exit status, so echoed commands cannot be mistaken for output; `cd`/`export`/`alias`
  persist across turns in the shared session.
* Token accounting and best-effort cost estimation per turn, from the bundled pricing
  table.
* Vision: image attachments are downscaled and sent as multimodal content.

**Provider catalog (ported from [`apvcode/Termux-Dev`](https://github.com/apvcode/Termux-Dev), MIT)**

* 178 provider presets, a 6541-entry context-window table and a matching
  `$/Mtok` pricing table, decoded lazily from JSON assets.
* `GET {baseUrl}/models` discovery with a searchable model picker, remembered
  per-provider base URLs and API keys, and per-model context-limit display.

**UI**

* A Miuix/Compose single-activity app: chat transcript, streaming bubbles with a
  blinking cursor, per-turn usage footer, auto-scrolling terminal cards, session
  drawer, settings, and an embedded `TerminalView` surface.
* A full Markdown renderer (headings, nested quotes/lists/task lists, tables, rules,
  fenced code with copy, inline emphasis/strike/links/clickable URLs) with a JVM
  unit-test suite for the parser.
* Localization: English, 简体中文 (`values-zh`) and 繁體中文
  (`values-b+zh+Hant`), following the Android system language, with
  `android:localeConfig` for the Android 13+ per-app language picker.

### Known limitations

* `targetSdk = 28` is required for on-device `exec()`; the app is **not** Google Play
  distributable and must be sideloaded or shipped via F-Droid.
* The on-device build produces a **single-ABI** (`arm64-v8a`) APK; multi-ABI builds
  require a workstation with a real NDK.
* The bootstrap is arm64-only.
* API keys are stored unencrypted in `SharedPreferences` — see
  [`SECURITY.md`](SECURITY.md).
* Cost figures are estimates from a static table, not billed amounts.

[Unreleased]: https://github.com/hacksynth/termagent/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/hacksynth/termagent/releases/tag/v0.1.0
