package com.termagent.shell

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Adapts the forked `com.termux.terminal.TerminalSessionClient` interface onto Kotlin
 * lambdas.
 *
 * `TerminalSession` invokes these callbacks from the main thread (`MSG_NEW_INPUT` is
 * posted to its main-thread handler), so it is safe to touch Compose state directly.
 */
class TermuxSessionClient(
    private val onTextChangedCallback: (TerminalSession) -> Unit,
    private val onSessionFinishedCallback: (TerminalSession) -> Unit,
    private val onTitleChangedCallback: (TerminalSession) -> Unit = {},
    private val onCopyTextToClipboardCallback: (String) -> Unit = {},
    private val onPasteTextFromClipboardCallback: () -> Unit = {},
    private val onBellCallback: () -> Unit = {},
    private val onColorsChangedCallback: () -> Unit = {},
    private val onCursorStateChangedCallback: (Boolean) -> Unit = {},
    private val onShellPidCallback: (Int) -> Unit = {},
    private val logger: (level: String, tag: String, message: String) -> Unit = { _, _, _ -> },
) : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) = onTextChangedCallback(changedSession)

    override fun onTitleChanged(changedSession: TerminalSession) = onTitleChangedCallback(changedSession)

    override fun onSessionFinished(finishedSession: TerminalSession) = onSessionFinishedCallback(finishedSession)

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) = onCopyTextToClipboardCallback(text)

    override fun onPasteTextFromClipboard(session: TerminalSession?) = onPasteTextFromClipboardCallback()

    override fun onBell(session: TerminalSession) = onBellCallback()

    override fun onColorsChanged(session: TerminalSession) = onColorsChangedCallback()

    override fun onTerminalCursorStateChange(state: Boolean) = onCursorStateChangedCallback(state)

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = onShellPidCallback(pid)

    /**
     * Declared as `Int?` (not `Int`) on purpose: the Java signature is
     * `java.lang.Integer`, so a primitive `int` would not be a valid override.
     */
    override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) = logger("E", tag, message)

    override fun logWarn(tag: String, message: String) = logger("W", tag, message)

    override fun logInfo(tag: String, message: String) = logger("I", tag, message)

    override fun logDebug(tag: String, message: String) = logger("D", tag, message)

    override fun logVerbose(tag: String, message: String) = logger("V", tag, message)

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
        logger("E", tag, "$message\n${e.stackTraceToString()}")

    override fun logStackTrace(tag: String, e: Exception) = logger("E", tag, e.stackTraceToString())
}
