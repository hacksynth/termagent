package com.termagent.shell

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Result of one command executed inside the persistent shell. */
data class ShellResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * Owns the single, long-lived PTY [TerminalSession] and executes commands inside it.
 *
 * ### Why a persistent session and not `ProcessBuilder`?
 * A PTY is what makes the forked Termux backend useful: the shell keeps its working
 * directory, environment, aliases and running jobs between calls, and full-screen
 * programs (`top`, `vim`, `apt`) behave correctly.
 *
 * ### How output is captured
 * Each command is bracketed by *sentinel markers* printed by the shell itself, and the
 * **raw pty byte stream** — not the rendered screen — is scanned for them (see
 * [TerminalSession.setRawOutputListener]):
 *
 * ```sh
 * __termagent_id=42
 * printf '%s\n' "__TERMAGENT_BEGIN_${__termagent_id}__"
 * <your command>
 * __termagent_rc=$?
 * printf '\n__TERMAGENT_END_%s__ %s\n' "${__termagent_id}" "${__termagent_rc}"
 * ```
 *
 * Two details make the parsing reliable:
 *  * the markers are produced by shell *expansion*, so the text the tty echoes back
 *    (`__TERMAGENT_BEGIN_${__termagent_id}__`) never equals the expanded marker —
 *    i.e. markers only ever appear in real command output;
 *  * the end marker is validated by requiring a numeric exit status after it.
 *
 * The command runs in the *current* shell (not a subshell), so `cd`, `export` and
 * `alias` persist across calls.
 *
 * ### Why the raw byte stream and not the emulator's screen
 * An earlier version derived the output from `TerminalBuffer.getTranscriptTextWithFullLinesJoined()`
 * — i.e. by re-rendering the emulator's scrollback and diffing it. That is unsound, because the
 * screen is a *lossy projection* of the byte stream, not a buffer of it:
 *
 *  * **Resizing reflows text.** Attaching a `TerminalView` (opening the Terminal tab), rotating
 *    the device or showing the IME all call `updateSize()`, which re-wraps every line. The same
 *    logical output then yields a *different number of characters*, so a position-based diff
 *    desynchronises: text gets re-emitted and, worse, an already-scanned end marker can fall
 *    outside the new region and never be recognised.
 *  * **Scrollback is trimmed and lines are right-trimmed**, so the text can legitimately shrink.
 *  * Escape sequences are interpreted rather than preserved.
 *
 * The failure mode was severe: a command that had *already finished* (its end marker printed and
 * visible in the scrollback) went unrecognised, so `exec` blocked until its timeout — which the
 * model itself chooses (`timeout_seconds`). Combined with the cached-app freezer suspending
 * timers, a completed `pkg install` appeared to hang for 55 minutes.
 *
 * Reading the bytes before emulation makes the capture an **append-only** stream, which is what
 * the marker scan actually needs: positions never shift, so a marker is either present or not,
 * and output is never re-emitted.
 */
class ShellManager(
    @Suppress("UNUSED_PARAMETER") context: Context,
    val env: TermuxEnvironment,
) {

    /**
     * Incremental raw output of the shell (used by the interactive terminal view).
     *
     * Driven by [onRawOutput] — i.e. by the pty byte stream, not by the rendered screen — so the
     * slices are exactly what the child process wrote and are never re-delivered after a reflow.
     */
    private val rawListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Repaint hooks for attached terminal views — see [addScreenUpdateObserver]. */
    private val screenObservers = CopyOnWriteArrayList<() -> Unit>()

    private val execMutex = Mutex()
    private val idGenerator = AtomicLong(0)

    /**
     * Decodes the pty's byte stream into text across reads. Owned by the session's main-thread
     * output handler, so it needs no lock — see [onRawOutput].
     */
    private val rawDecoder = Utf8StreamDecoder()

    private val sessionLock = Any()

    @Volatile
    private var terminalSession: TerminalSession? = null

    @Volatile
    private var pending: PendingExecution? = null

    /** Set when the previous call timed out, so the shell may be mid-continuation. */
    @Volatile
    private var needsRecovery = false

    /** The underlying forked-Termux session, creating (and spawning) it on first use. */
    fun ensureSession(): TerminalSession {
        synchronized(sessionLock) {
            val existing = terminalSession
            if (existing != null && existing.isRunning) return existing

            env.prepareDirectories()

            val client = TermuxSessionClient(
                onTextChangedCallback = ::onSessionTextChanged,
                onSessionFinishedCallback = { s ->
                    Log.i(TAG, "Shell exited, status=${s.exitStatus}")
                },
                onPasteTextFromClipboardCallback = { },
                logger = { level, tag, message -> Log.println(level.toLogPriority(), tag, message) },
            )

            val created = TerminalSession(
                env.loginShellPath,
                env.homeDir.absolutePath,
                env.loginShellArguments,
                env.buildEnvironment(),
                TRANSCRIPT_ROWS,
                client,
            )
            // Spawns the PTY child. Done here (rather than by TerminalView) so the
            // agent works even when no terminal UI is on screen.
            created.initializeEmulator(DEFAULT_COLUMNS, DEFAULT_ROWS, CELL_WIDTH_PX, CELL_HEIGHT_PX)

            // Capture happens on the raw byte stream, before emulation — see the class KDoc for
            // why the rendered screen cannot be used for it.
            created.setRawOutputListener { data, length -> onRawOutput(data, length) }

            terminalSession = created
            Log.i(TAG, "Started shell pid=${created.pid} (${env.loginShellPath})")
            return created
        }
    }

    /** Currently attached session, if any (used by the UI to render a real terminal). */
    fun currentSession(): TerminalSession? = terminalSession

    /**
     * Whether `$PREFIX/bin/bash` actually exists yet.
     *
     * Callers must check this before touching [ensureSession]: spawning a PTY whose program
     * does not exist yields a session that dies instantly, which looks like a blank terminal
     * rather than an error.
     */
    val isInstalled: Boolean get() = env.isBootstrapInstalled

    /**
     * Observe every slice the child process writes, decoded from the pty's byte stream.
     *
     * The text is *exactly* what was written — no emulation, so no reflow, no trimmed scrollback
     * and no escape-sequence interpretation (see [onRawOutput]). It is the same stream command
     * capture scans, which makes this the right hook for logging or for mirroring output
     * somewhere other than a terminal view.
     *
     * Currently nothing in the app registers one: the interactive `TerminalView` renders the
     * emulator's screen itself and only needs to be *told* when to repaint, which is
     * [addScreenUpdateObserver]. Kept as a documented seam rather than deleted, because it is the
     * only way to see the stream as written.
     *
     * Called on the main thread, while a command's output is being captured.
     */
    fun addRawOutputListener(listener: (String) -> Unit) {
        rawListeners += listener
    }

    fun removeRawOutputListener(listener: (String) -> Unit) {
        rawListeners -= listener
    }

    /**
     * Registers a callback invoked whenever the emulator's screen changes, so a `TerminalView`
     * can repaint.
     *
     * Deliberately **separate** from [addRawOutputListener], because the two have opposite cost
     * profiles: a repaint needs no text at all, whereas the raw-output path decodes bytes and runs
     * the marker scan. This fires on every read from the pty, including ones that carry no output
     * (`reset()`, a reflow), so it is kept free of work and can never be skipped.
     *
     * The callback fires on the main thread (see [onSessionTextChanged]).
     */
    fun addScreenUpdateObserver(observer: () -> Unit) {
        screenObservers += observer
    }

    fun removeScreenUpdateObserver(observer: () -> Unit) {
        screenObservers -= observer
    }

    /** Feed raw keystrokes/commands straight to the PTY (interactive terminal mode). */
    fun writeRaw(data: String) {
        val session = ensureSession()
        val bytes = data.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    /** Send Ctrl-C to the foreground job. */
    fun sendInterrupt() {
        terminalSession?.let { session ->
            val ctrlC = byteArrayOf(0x03)
            session.write(ctrlC, 0, ctrlC.size)
        }
    }

    /**
     * Run [command] inside the persistent shell and return its output.
     *
     * @param onChunk invoked (on the main thread) with each new slice of stdout/stderr as it
     *                arrives, so the UI can stream it live.
     */
    suspend fun exec(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        onChunk: (String) -> Unit = {},
    ): ShellResult = execMutex.withLock {
        val session = ensureSession()

        if (needsRecovery) {
            recoverPrompt(session)
            needsRecovery = false
        }

        val id = idGenerator.incrementAndGet().toString()
        val execution = PendingExecution(
            beginMarker = "$BEGIN_PREFIX${id}__",
            endMarker = "$END_PREFIX${id}__",
            onChunk = onChunk,
        )
        pending = execution

        // Clamp whatever the caller (ultimately the model) passed: an unbounded budget turns any
        // capture problem into an apparently hung app.
        val budget = timeoutMillis.coerceIn(1L, MAX_TIMEOUT_MS)

        try {
            val snippet = buildSnippet(id, command)
            val bytes = snippet.toByteArray(Charsets.UTF_8)
            session.write(bytes, 0, bytes.size)

            val completed = withTimeoutOrNull(budget) { execution.completion.await() }
            if (completed == null) {
                // Interrupt the foreground job and remember to clean up next time.
                Log.w(TAG, "Command exceeded ${budget}ms; interrupting")
                sendInterrupt()
                needsRecovery = true
                ShellResult(TIMEOUT_EXIT_CODE, execution.buffer.toString(), timedOut = true)
            } else {
                completed
            }
        } finally {
            pending = null
            if (!execution.completion.isCompleted) execution.completion.cancel()
        }
    }

    fun close() {
        terminalSession?.finishIfRunning()
        terminalSession = null
    }

    // ---------------------------------------------------------------- internals

    private class PendingExecution(
        val onChunk: (String) -> Unit,
        beginMarker: String,
        endMarker: String,
    ) {
        /** Scanner over the raw byte stream — see [CommandCapture]. */
        val capture = CommandCapture(beginMarker, endMarker)

        /** Everything captured so far; what the model ultimately receives. */
        val buffer = StringBuilder()

        val completion = CompletableDeferred<ShellResult>()
    }

    private fun buildSnippet(id: String, command: String): String = buildString {
        append("__termagent_id=").append(id).append('\n')
        append("printf '%s\\n' \"$BEGIN_PREFIX\${__termagent_id}__\"\n")
        append(command)
        if (!command.endsWith('\n')) append('\n')
        append("__termagent_rc=$?\n")
        append("printf '\\n$END_PREFIX%s__ %s\\n' \"\${__termagent_id}\" \"\${__termagent_rc}\"\n")
    }

    /** Ctrl-C, then a fresh prompt, so a half-typed continuation cannot swallow the sentinels. */
    private fun recoverPrompt(session: TerminalSession) {
        val ctrlC = byteArrayOf(0x03)
        repeat(2) {
            session.write(ctrlC, 0, ctrlC.size)
        }
        val newline = "\n".toByteArray(Charsets.UTF_8)
        session.write(newline, 0, newline.size)
        Log.w(TAG, "Recovered shell prompt after a timed-out command")
    }

    /**
     * Called by the session on **every** slice of PTY output, on the **main thread**
     * (`TerminalSession.MainThreadHandler` posts `MSG_NEW_INPUT` per read, then calls
     * `notifyScreenUpdate()`).
     *
     * This exists purely so an attached `TerminalView` can repaint: it is the only thing that
     * tells the view the screen changed, so it must run unconditionally and must never be put
     * behind anything expensive. Command capture deliberately does **not** happen here — it runs
     * off the raw byte stream in [onRawOutput], precisely because this callback also fires for
     * events that carry no output at all (`reset()`, scrollback trimming, a reflow).
     */
    private fun onSessionTextChanged(@Suppress("UNUSED_PARAMETER") session: TerminalSession) {
        for (observer in screenObservers) {
            runCatching { observer() }
                .onFailure { Log.w(TAG, "Screen update observer failed", it) }
        }
    }

    /**
     * Every byte the child process wrote, straight from the pty and *before* terminal emulation.
     *
     * Runs on the main thread (the session's output handler), so the decoder and the pending
     * execution need no synchronisation. [data] is the reader's reused buffer, so it is never
     * retained — it is decoded immediately.
     */
    private fun onRawOutput(data: ByteArray, length: Int) {
        val text = rawDecoder.decode(data, length)
        if (text.isEmpty()) return

        for (listener in rawListeners) {
            runCatching { listener(text) }
                .onFailure { Log.w(TAG, "Raw output listener failed", it) }
        }

        // Only accumulate while a command is actually running; an idle shell must not be buffered.
        val execution = pending ?: return
        consume(execution, text)
    }

    /**
     * Hand [text] to the scanner and forward whatever it now considers final.
     *
     * All the marker-scanning rules live in [CommandCapture] (which is pure and unit-tested); this
     * only decides what to do with the pieces.
     */
    private fun consume(execution: PendingExecution, text: String) {
        val chunk = execution.capture.accept(text)
        if (chunk.isNotEmpty()) {
            execution.buffer.append(chunk)
            execution.onChunk(chunk)
        }

        // Non-null exit status means the end marker was found: the command is finished.
        val exitCode = execution.capture.exitCode
        if (exitCode != null && !execution.completion.isCompleted) {
            execution.completion.complete(ShellResult(exitCode, execution.buffer.toString()))
        }

    }
    private fun String.toLogPriority(): Int = when (this) {
        "V" -> Log.VERBOSE
        "D" -> Log.DEBUG
        "I" -> Log.INFO
        "W" -> Log.WARN
        else -> Log.ERROR
    }

    companion object {
        private const val TAG = "ShellManager"

        /** Expanded sentinel prefixes — see the class KDoc. */
        private const val BEGIN_PREFIX = "__TERMAGENT_BEGIN_"
        private const val END_PREFIX = "__TERMAGENT_END_"

        /** Exit status used when we had to kill a command because it exceeded its budget. */
        const val TIMEOUT_EXIT_CODE = 124

        /** How long a command may run before it is interrupted. */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /**
         * Hard ceiling for a command's budget, whatever the model asks for.
         *
         * The tool lets the model pick a timeout (`timeout_seconds`), but it used to allow an
         * hour. That is a poor trade: while a timeout is pending, a detection bug looks exactly
         * like a hung app, and Android's cached-app freezer suspends the timer anyway, so the
         * failure lasts far longer than the budget suggests. A command that legitimately needs
         * more than this should be run as a background job (`nohup … &`) instead.
         */
        const val MAX_TIMEOUT_MS = 600_000L

        /** Scrollback depth; Termux's own default is 2000. */
        private const val TRANSCRIPT_ROWS = 2000

        /**
         * Initial geometry. The emulator is resized as soon as a `TerminalView` attaches,
         * but the agent needs a sane size even with no terminal on screen.
         */
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        private const val CELL_WIDTH_PX = 8
        private const val CELL_HEIGHT_PX = 16
    }
}
