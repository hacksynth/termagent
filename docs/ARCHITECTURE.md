# Architecture

## Module graph

```
:app ──► :terminal-view ──► :terminal-emulator
 └───────────────────────────┘        │
                                      └──► libtermux.so (JNI, CMake, forkpty)
```

| Module | Language | Depends on |
| --- | --- | --- |
| `:terminal-emulator` | Java (+ C) | `androidx.annotation`, `libtermux.so` |
| `:terminal-view` | Java | `:terminal-emulator` (via `api`), `androidx.annotation` |
| `:app` | Kotlin | both, Compose, Miuix, OkHttp, `kotlinx.serialization` |

`libtermux.so` is loaded by `TerminalEmulator`'s `JNI` class on first use
(`System.loadLibrary("termux")`), which happens when the first `TerminalSession` is
initialised.

### Two ways to produce `libtermux.so`

| Mode | How | When |
| --- | --- | --- |
| Source | `externalNativeBuild { cmake { … } }` compiles `termux.c` for `arm64-v8a`, `armeabi-v7a`, `x86_64` | Default. Workstations with a real NDK → universal APK. |
| Prebuilt | `app/src/main/jniLibs/<abi>/libtermux.so` is packaged as-is; CMake is skipped | `-Ptermagent.prebuiltJni=true`. Required on aarch64 Termux, where the NDK cannot run. |

The prebuilt library is compiled by Termux's own native Android toolchain
(`clang` → `aarch64-linux-android` + `ndk-sysroot`), which makes an on-device build possible.
Because it can only target the device's own ABI, that mode produces a single-ABI APK.

---

## Layers, top to bottom

### 1. UI (Compose + Miuix)

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Single activity. Owns the drawer state and the `Surface` enum (Chat / Settings / Terminal). |
| `ChatScreen.kt` | Top bar, message list, composer, onboarding, bootstrap panel, error bar. |
| `SettingsScreen.kt` | Provider picker (178 presets), endpoint fields, `/models` discovery, safety switch, theme picker, destructive actions. |
| `SessionDrawer.kt` | Conversation list (the ChatGPT sidebar). |
| `MessageBubble.kt` | User bubble / assistant prose / tool card dispatch. Message chrome only — the Markdown renderer lives in its own file. |
| `Markdown.kt` | Markdown parser *and* renderer: headings, nested quotes/lists/task lists, tables, rules, fenced code (with copy), inline emphasis/strike/code/links. A flat `Block` model plus a hand-rolled recursive inline scanner; the parser half is JVM-tested (`MarkdownParserTest`). |
| `ToolAction.kt` | Maps a tool name + arguments to the header label (`→ Run: ls -la ~`, `→ Read /path`), following `devx`'s verb table. Resolved in the UI from stored arguments, never persisted. |
| `TerminalCard.kt` | Collapsible command+output card, ANSI-stripping, tail-follow auto-scroll. |
| `Composer.kt` | Multi-line input + send/stop. |
| `EmbeddedTerminal.kt` | `AndroidView` hosting the forked `TerminalView`. |
| `AgentForegroundService.kt` | Not a UI file, but it is started for the duration of a turn: a foreground service + `PARTIAL_WAKE_LOCK` that stops the cached-app freezer from suspending command capture, and the process from being killed, while a long command runs. `exported="false"`, `START_NOT_STICKY`, stopped in the turn's `finally`. |
| `Theme.kt` | `TermAgentPalette` (shell colours) + the Miuix `ThemeController` (controls, Monet). |

Miuix supplies the *control* look (`Button`, `Switch`, `Card`, `IconButton`, `Icon`, `Text`,
`LinearProgressIndicator`, `InfiniteProgressIndicator`, `TextButton`) and the icon set
(`MiuixIcons.Regular.*` / `MiuixIcons.Basic.*`). The chat shell's own colours come from
`TermAgentPalette`, because a ChatGPT-style layout needs a darker canvas, a distinct sidebar
and terminal-specific colours that a general-purpose component theme does not model.

#### Localization

The app follows the system language, and nothing in the code forces a locale.

* Every user-visible string is a resource, in `res/values/strings.xml` (English, the default),
  `res/values-zh/` (Simplified) and `res/values-b+zh+Hant/` (Traditional). The Traditional
  directory uses a **BCP 47 script subtag** rather than `values-zh-rTW`, so one directory covers
  Taiwan, Hong Kong and Macau instead of three near-identical ones; a bare `zh` device follows
  `values-zh`.
* Composables resolve text with `stringResource(...)` at composition time, so a language change
  is picked up when the tree is rebuilt.
* `android:configChanges` deliberately **excludes `locale`**. Handling it would keep the activity
  alive and rely on every consumer re-reading its resources; letting Android recreate the
  activity is the documented contract for following the system language, and guarantees no
  composable can keep showing a string from the previous locale.
* `res/xml/locales_config.xml` + `android:localeConfig` advertise the supported locales to the
  system, which is also what exposes them in Android 13+'s per-app language picker (verified on
  device with `cmd locale set-app-locales`).
* Text is resolved in the **view model** only where it is *produced* rather than *displayed* —
  bootstrap progress and model-discovery errors, which are transient status facts. Everything
  displayed (labels, buttons, placeholders) is resolved in the composable.
* `Conversation.isUntitled` replaced a `displayTitle` that returned `"New chat"`: the model layer
  is persisted to JSON and must never bake a translated string into a saved conversation.
* Tool descriptions and tool-result errors sent to the model stay in English on purpose — they
  are part of the wire protocol, not UI.

### 2. State (`ChatViewModel`)

A single `StateFlow<ChatUiState>` drives the whole UI.

`ChatUiState` = conversations + current conversation + streaming flag + composer text +
error + bootstrap state + settings snapshot + staged attachments.

**API history reconstruction.** The persisted entries are the UI's model, not the wire
format. `buildApiHistory()` converts them:

* `assistant` entry followed by `tool` cards → `assistant(content, tool_calls=[…])` followed
  by one `tool` message per call (the order OpenAI requires);
* only the last 40 messages are sent;
* the history is trimmed to start at a `user` message (the API rejects a leading `assistant`);
* `tool` cards whose result never arrived are skipped, since some servers error on an
  unanswered `tool_call`;
* a `user` entry with attachments is re-expanded by `UserContent.build(...)` into a
  multimodal `content` array — on **every** turn, because the model is stateless.

## Attachments and vision

```
                 ┌ Photo Picker / SAF ────────────────┐
   user picks ─► │ content:// URI                     │
                 └──────────────┬─────────────────────┘
                                ▼
                    AttachmentStore.import()            (IO dispatcher)
                      ├─ image  → ImagePrep (1568 px, JPEG q85) ─► $HOME/attachments/x-3f2a1b.jpg
                      └─ other  → copied verbatim                ─► $HOME/attachments/x-3f2a1b.bin
                                ▼
                    ChatUiState.pendingAttachments        (chips above the composer)
                                ▼
                            send() ──► ChatEntry.attachments ──► UserContent.build()
                                │                                    │
                                │                    ┌───────────────┼────────────────┐
                                │                    ▼               ▼                ▼
                                │               image/*          text/*         everything else
                                │            image_url part   inlined in the     path only; the
                                │            (base64 data URL)  prompt text    agent reads it
                                ▼
                        POST /chat/completions
```

**Why the pixels never enter the agent loop.** `ChatMessage.content` is a
`MessageContent` union (`Text` | `Parts`), so a multimodal turn is possible — but an image
part in *history* would be re-sent on every iteration and is rejected outright by a text-only
model. So images go in through the user's own turn, and the agent gets a second, independent
path for *reading* pictures: the `view_image` tool (`VisionClient`) makes a one-shot
non-streaming request with the downscaled image plus a question, and only the resulting prose
enters the loop. `list_attachments` tells the agent what the user attached, keyed by the
original file name, which is how a human refers to it.

The two directions are complementary: attaching an image puts it in front of the model
directly, `view_image` lets the agent go look at something (a screenshot *it* took) later.

Storage notes: attachment paths are persisted **relative to `$HOME`**, so the conversation
JSON stays portable; the on-disk file name carries a short id suffix (two `screenshot.png`
must not collide); deleting a conversation, clearing one, or removing a chip deletes its
bytes; and `$HOME/attachments` is readable by the shell, which is what makes the
"path only" branch work at all.

### 3. Agent (`agent/`)

```
AgentEngine.run(config, history)
    │
    ├─ system prompt + history + tool definitions ──► LlmClient.stream()
    │                                                    │
    │        TextDelta / ReasoningDelta / ToolCall ◄─────┘
    │
    ├─ no tool calls? ──► Done
    │
    └─ tool calls ──► AgentTools.execute(name, args, onOutput)
                            │
                            └─ run_shell ──► ShellManager.exec(cmd) { chunk }
                                                     │
                                          AgentEvent.ToolOutput(chunk)
```

Events (`AgentEvent`) are the only channel between the engine and the UI, so the engine is
testable with a fake client.

The engine depends on two **interfaces**, not on the concrete tools:

| Seam | Production implementation | Why it exists |
| --- | --- | --- |
| `LlmStreamer` | `LlmClient` | The loop consumes `Flow<LlmEvent>`; scripted event lists let a test drive any provider behaviour, including malformed ones. |
| `AgentToolRunner` | `AgentTools` | Canned `ToolResult`s let a test deliver streamed output without a pty or a filesystem. |

Both are public (not `internal`) only because [AgentEngine] is public and Kotlin forbids a public
constructor from naming an internal type. `AgentEngineTest` builds both fakes and asserts the loop's
*protocol* rules — which is where the real risk is: a tool call must be answered by exactly one
`tool` message, immediately after the assistant message that requested it, or the provider rejects
the request on some *later* turn. Failures there are otherwise only visible in production.

The engine also takes a `tooManyToolCalls: (Int) -> String` instead of an Android `Context`. It
needs exactly one translated string, and depending on a `Context` made the whole loop
uninstantiable in a JVM test.

`AgentEngine` uses `channelFlow` (not `flow`) because tool output is delivered from the
terminal's **main-thread** callback, where `trySend` is the only safe emission primitive.

Token accounting arrives on a final stream frame (`stream_options.include_usage`), is summed
across the iterations of a turn, and is emitted as a single `AgentEvent.Usage` before `Done`.

### 4. Providers (`provider/`)

The provider layer is ported from **termux-dev** (devx), and its data ships as bundled
assets so it can be refreshed without a code change:

| File | Responsibility |
| --- | --- |
| `ProviderRegistry.kt` | Loads `assets/providers/providers.json` — devx's `ALL_PROVIDERS` catalogue of 178 presets (id + label + base URL), including the `custom` entry. |
| `ModelCatalog.kt` | The context-window (`model-limits.json`, 6541 entries) and pricing (`model-pricing.json`, 6541 entries) tables, with devx's exact fuzzy lookup (id → last `/` segment → version-stripped base name → family heuristic → default). |
| `ModelDiscovery.kt` | `GET {baseUrl}/models` on a short-timeout derivative of the shared client, for the model picker. |

`estimateCost(model, in, out)` mirrors devx's `calculateCost`; `SettingsStore` remembers a
**base URL and API key per provider** (JSON-encoded maps in `SharedPreferences`), so
`selectProvider()` restores the previous endpoint exactly like devx's `baseUrls`/`apiKeys`.

### 5. Shell (`shell/`)

`ShellManager` owns exactly one `TerminalSession` for the process, created lazily by
`ensureSession()` and kept in the application-scoped container, so a rotation or navigating
away does not kill a running build.

It exposes two modes:

| Method | Use |
| --- | --- |
| `exec(command, timeout, onChunk)` | Structured execution for the agent (sentinel-bracketed, exit status, streamed output). |
| `writeRaw(data)` / `sendInterrupt()` | Direct PTY access for the interactive terminal tab. |

`exec` is serialised by a `Mutex` (a PTY has a single foreground job). The timeout path sends
Ctrl-C and sets `needsRecovery`, which resynchronises the prompt before the next command so a
half-typed continuation cannot swallow the sentinels.

**Capture reads the byte stream, not the screen.** `TerminalSession` gained a
`RawOutputListener` (`setRawOutputListener`) that is handed the untouched pty bytes on the main
thread, immediately before `mEmulator.append(...)`; `ShellManager.onRawOutput` decodes them with
`Utf8StreamDecoder` (a UTF-8 character can straddle two reads) and feeds the text to
`CommandCapture`, which owns every marker rule. The emulator's scrollback is never consulted.

The earlier implementation did the opposite — it re-rendered `getTranscriptTextWithFullLinesJoined()`
and diffed the result positionally — which cannot work, because the screen is a *lossy*
projection of the stream: `updateSize()` (terminal tab attached, rotation, IME) reflows every
line, scrollback is trimmed, and control sequences are interpreted. Text then changes length,
the diff desynchronises, and an end marker that has already been printed can fall outside the
recomputed region — so a *finished* command blocked until its timeout. The byte stream is
append-only, so a marker is either present or not and nothing is ever emitted twice.

`Utf8StreamDecoder` is a second decode on top of the emulator's own, which is the deliberate
cost of not depending on the emulator's view of the data; it is bounded by the read size
(64 KiB) and dwarfed by the emulator's per-byte work.

**Capture is bounded.** `OutputAccumulator` keeps the first 64 KB and the last 64 KB of what the
scanner emits and drops the middle behind an explicit `... [N characters omitted] ...` marker;
`ShellResult.truncated` carries the fact out of band, so a *command* that printed that sentence
cannot be mistaken for a truncated capture. Output goes to the model verbatim as a tool message,
so an uncapped buffer was both a memory limit and a context-window limit — one `cat` of a large
file away from a 400. The streamed `onChunk` slices are **not** bounded: the terminal card is a
live view. The card keeps its own separate 128 KB tail window in `ChatViewModel`, because it is
persisted with the conversation.

**Cancellation interrupts the job.** `exec` cancels on `CancellationException` by sending Ctrl-C
and setting `needsRecovery`. The coroutine dying does not kill the child process, so without this
the PTY keeps running the command and the *next* command's snippet is typed into its `stdin`;
the shell would also keep emitting output that the next capture scans as its own (the marker scan
is positional).

`TermuxEnvironment.buildEnvironment()` reproduces the environment real Termux exports —
`PREFIX`, `HOME`, `TMPDIR`, `PATH`, `LD_LIBRARY_PATH` (our own stand-in for the bootstrap's
baked-in `DT_RUNPATH`), `LD_PRELOAD` (`libtermux-exec-ld-preload.so`, Termux's `exec` shim),
the `TERMUX__*` variables and `ANDROID__BUILD_VERSION_SDK`. The `TERMUX__*`/`LD_PRELOAD`
spellings matter because the `$PREFIX` scripts and `termux-exec` helpers read them directly.

### `BootstrapInstaller` — beyond unpacking

Three things happen after extraction, and each is what makes a forked prefix behave like a
real one:

| Step | Why |
| --- | --- |
| `repointPrefix` | Rewrites the bootstrap's compiled-in `/data/data/com.termux/files/usr` (505 ELF binaries, 113 shebangs) at our package id, with a **strictly same-length** byte substitution. |
| `ensurePackageRepointHook` | Installs the apt `DPkg::Pre-Install-Pkgs` hook that repoints **downloaded `.deb` files** the same way. `repointPrefix` cannot see those, so without it `pkg install` fails with `unable to stat './data/data/com.termux/files' … Permission denied`. |
| `runBootstrapSecondStage` | Runs the bootstrap's own `termux-bootstrap-second-stage.sh` so the packages' `postinst` scripts execute (zip archives carry no mode bits), as upstream Termux does. Best-effort: the shell already works and the script locks itself. |

The hook is a rendered asset (`assets/termagent/repoint-deb.sh` → `$PREFIX/libexec/termagent/repoint-deb`)
plus one apt config file, and is re-asserted on every launch so older prefixes gain it without a
reinstall.

### 6. Persistence (`data/`)

* `SessionRepository` — one JSON file per conversation under `conversations/<id>.json`,
  written to a `.tmp` file and renamed (a crash mid-save cannot truncate a chat).
* `SettingsStore` — `SharedPreferences` for the active provider id, the per-provider API key
  / base-URL maps, the endpoint, model, temperature, system prompt, safety switch, theme mode
  and bootstrap flag.

---

## The PTY

```
ShellManager
   │  env.loginShellPath   = $PREFIX/bin/bash
   │  env.loginShellArguments = ["-bash"]      ← a *login* shell, so /etc/profile is sourced
   │  env.buildEnvironment()  = PREFIX, HOME, TMPDIR, PATH, TERM=xterm-256color, LANG, …
   ▼
TerminalSession(shellPath, cwd, args, env, transcriptRows, client)
   │  initializeEmulator(cols, rows, cellW, cellH)
   ▼
JNI.createSubprocess(cmd, cwd, args, env, fd)  →  forkpty(3)
   ▼
bash (login) ──► the app's private $PREFIX
```

`initializeEmulator` is called by `ShellManager` rather than by `TerminalView`, so the agent
works even with no terminal on screen; `TerminalView.attachSession()` later adopts the same
session (it does not re-spawn the PTY).

The 80×24 default geometry is replaced as soon as a `TerminalView` attaches and calls
`updateSize()`.

---

## Deliberate trade-offs

| Decision | Why |
| --- | --- |
| `targetSdk = 28` | Android 10+ mounts app-private storage `noexec`, which breaks `exec()` of `$PREFIX/bin/*`. Termux pins 28 for the same reason; this is a sideload/F-Droid app. |
| Bootstrap = official Termux archive | `$PREFIX` must be the real thing so `apt`, `RUNPATH`-based dynamic linking and `libtermux-exec` behave. |
| Sentinel markers instead of raw PTY parsing | Shell-expanded markers cannot be confused with the tty's echo of the command; no partial-sequence state machine needed. |
| Repoint `.deb`s at install time, not in the repo | The upstream packages are used **unmodified**; the length-preserving rewrite happens on the device (`DPkg::Pre-Install-Pkgs`), so a mirror can change without us rebuilding anything. The cost is one unpack/repack per archive. |
| `LinearProgressIndicator` for bootstrap, indeterminate spinner for streaming | The bootstrap has known byte counts; a streaming turn does not. |
| SharedPreferences, not DataStore | A handful of scalar values; DataStore would add a dependency and an async read path for no benefit. |
| One file per conversation | Cheap writes (only the active chat is rewritten) and a corrupt file is scoped to one chat. |
| Plain Activity + `viewModel()` | One screen graph and a tiny dependency set; Navigation-Compose would add indirection. The `Surface` enum plus `BackHandler` covers back behaviour. |

---

## Testing notes

Everything is a plain JVM test (`testDebugUnitTest`) — no Robolectric, no instrumentation, no
emulator. That is a deliberate constraint: the suite runs in seconds on the device itself, which is
the only machine most contributors to this project build on.

* `:terminal-emulator` ships 19 upstream JUnit tests.
* `unitTests.isReturnDefaultValues = true` is set for `:app` because the tested classes call
  `android.util.Log` for diagnostics. The caveat is real and worth knowing: it makes *every*
  unmocked `android.*` call return a default instead of throwing, so a test that accidentally
  reaches a real Android API will silently pass rather than fail loudly. Keep the classes under
  test free of Android types, and only `Log` benefits from this.
* `AgentEngineTest` drives the loop through the `LlmStreamer` / `AgentToolRunner` fakes and asserts
  the wire protocol (tool-call pairing, the safety switch, the iteration bound, usage summing).
* `ApiHistoryTest` covers the entry-log → wire-conversation translation, including the two shapes
  that only occur in practice — a card with no final result, and a trim that cuts into an assistant
  turn.
* `$PREFIX`-free by construction: nothing under test touches a terminal, a file or the network.

The suite was validated by **mutation testing**, not just by passing: each rule was disabled in turn
(drop the leading-user rule; ignore a card's streamed output; stop enforcing the safety switch; stop
carrying the exit code) and exactly the corresponding test failed each time. Tests that cannot fail
are worse than no tests, because they buy confidence they have not earned.
