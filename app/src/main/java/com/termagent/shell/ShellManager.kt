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
 * The terminal emulator already renders everything into a scrollback buffer, so instead
 * of parsing raw bytes we bracket each command with *sentinel markers* printed by the
 * shell itself:
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
 */
class ShellManager(
    @Suppress("UNUSED_PARAMETER") context: Context,
    val env: TermuxEnvironment,
) {

    /** Incremental raw output of the shell (used by the interactive terminal view). */
    private val rawListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Repaint hooks for attached terminal views — see [addScreenUpdateObserver]. */
    private val screenObservers = CopyOnWriteArrayList<() -> Unit>()

    private val execMutex = Mutex()
    private val idGenerator = AtomicLong(0)

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
     * This is deliberately **separate** from [addRawOutputListener]: a repaint needs no text at
     * all, whereas the raw-output path has to materialise the whole transcript. Keeping them
     * apart is what allows [onSessionTextChanged] to skip that cost entirely while the user is
     * typing interactively.
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

        try {
            val snippet = buildSnippet(id, command)
            val bytes = snippet.toByteArray(Charsets.UTF_8)
            session.write(bytes, 0, bytes.size)

            val completed = withTimeoutOrNull(timeoutMillis) { execution.completion.await() }
            if (completed == null) {
                // Interrupt the foreground job and remember to clean up next time.
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
        val beginMarker: String,
        val endMarker: String,
        val onChunk: (String) -> Unit,
    ) {
        val buffer = StringBuilder()

        /** How much of the region *after* the begin marker has already been streamed. */
        var streamedChars = 0

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
     * That makes the ordering here a latency concern, not a style choice:
     *
     *  1. Repaint first, and unconditionally. The attached `TerminalView` only redraws when it is
     *     told the screen changed, so the echo of a keystroke is invisible until this runs. It
     *     needs no text, so it must not sit behind anything that can be expensive or bail out.
     *  2. Only then — and only if something actually consumes the text — materialise the
     *     transcript. [transcriptOf] walks every active transcript row (up to [TRANSCRIPT_ROWS],
     *     i.e. 2000) and builds a large string; doing that on each echoed character was pure
     *     main-thread jank during typing, so it is skipped whenever no agent command is pending
     *     and nothing is listening for raw output.
     */
    private fun onSessionTextChanged(session: TerminalSession) {
        for (observer in screenObservers) {
            runCatching { observer() }
                .onFailure { Log.w(TAG, "Screen update observer failed", it) }
        }

        // Fast path while the user is typing: repaint only, no transcript.
        val execution = pending
        if (execution == null && rawListeners.isEmpty()) return

        val text = transcriptOf(session) ?: return

        for (listener in rawListeners) {
            runCatching { listener(text) }
                .onFailure { Log.w(TAG, "Raw output listener failed", it) }
        }

        if (execution != null) consume(execution, text)
    }

    private fun consume(execution: PendingExecution, text: String) {
        val beginIndex = text.lastIndexOf(execution.beginMarker)
        if (beginIndex < 0) return

        val bodyStart = beginIndex + execution.beginMarker.length
        val endIndex = findEndMarker(text, execution.endMarker, bodyStart)
        val bodyEnd = if (endIndex >= 0) endIndex else text.length

        if (bodyEnd > bodyStart) {
            // The senders print the marker followed by its own newline; drop it so the
            // captured output starts exactly at the command's first character.
            val region = text.substring(bodyStart, bodyEnd).removePrefix("\r\n").removePrefix("\n")

            if (region.length > execution.streamedChars) {
                val chunk = region.substring(execution.streamedChars)
                execution.streamedChars = region.length
                execution.buffer.append(chunk)
                execution.onChunk(chunk)
            } else if (region.length < execution.streamedChars) {
                // Scrollback was trimmed out from under us — resync rather than lose data.
                Log.w(TAG, "Transcript shrank (${execution.streamedChars} -> ${region.length}); resyncing")
                execution.streamedChars = 0
            }
        }

        if (endIndex >= 0 && !execution.completion.isCompleted) {
            val exitCode = parseExitCode(text, endIndex + execution.endMarker.length)
            execution.completion.complete(ShellResult(exitCode, execution.buffer.toString()))
        }
    }

    /**
     * Last occurrence of [marker] at/after [fromIndex] that is followed by a bare integer —
     * this is what distinguishes the real sentinel from the shell's echo of the snippet.
     */
    private fun findEndMarker(text: String, marker: String, fromIndex: Int): Int {
        var index = text.lastIndexOf(marker)
        while (index >= fromIndex) {
            if (trailingWord(text, index + marker.length).toIntOrNull() != null) return index
            index = text.lastIndexOf(marker, index - 1)
        }
        return -1
    }

    private fun parseExitCode(text: String, fromIndex: Int): Int =
        trailingWord(text, fromIndex).toIntOrNull() ?: -1

    private fun trailingWord(text: String, fromIndex: Int): String {
        if (fromIndex >= text.length) return ""
        val tail = text.substring(fromIndex)
        val lineEnd = tail.indexOf('\n').let { if (it == -1) tail.length else it }
        return tail.substring(0, lineEnd).trim()
    }

    private fun transcriptOf(session: TerminalSession): String? =
        session.emulator?.screen?.transcriptTextWithFullLinesJoined

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

        const val DEFAULT_TIMEOUT_MS = 120_000L

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
