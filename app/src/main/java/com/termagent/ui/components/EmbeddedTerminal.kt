package com.termagent.ui.components

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.Text
import com.termagent.R
import com.termagent.appContainer
import com.termagent.shell.ShellManager
import com.termagent.ui.theme.Spacing
import com.termagent.ui.theme.TermAgentTheme
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

/**
 * The real, interactive terminal — the forked `com.termux.view.TerminalView` rendering the
 * same persistent [TerminalSession] the agent executes into.
 *
 * This is the most direct demonstration that the backend is genuine Termux: `vim`, `top`,
 * `htop`, ANSI colours and mouse reporting all work, because this is literally Termux's
 * renderer plus its VT emulator.
 *
 * @param keyboardRequest bump this counter from the host (e.g. a toolbar button) to force the
 *                        soft keyboard up. Used as a fallback for IMEs that ignore focus-driven
 *                        requests.
 */
@Composable
fun EmbeddedTerminal(
    modifier: Modifier = Modifier,
    keyboardRequest: Int = 0,
) {
    val context = LocalContext.current
    val shell: ShellManager = remember { context.appContainer.shell }
    val density = LocalDensity.current

    // Without a bootstrap there is no `$PREFIX/bin/bash`. Spawning the PTY anyway yields a
    // session that dies instantly, which the user sees as an unexplained black rectangle —
    // so say what is actually wrong instead.
    if (!shell.isInstalled) {
        BootstrapMissingNotice(modifier = modifier)
        return
    }

    // ------------------------------------------------------------------ font size
    //
    // `TerminalView.setTextSize()` takes **pixels**, not scaled pixels. Termux passes
    // `12 * dipInPixels` and TerminalRenderer then feeds the value straight into
    // `Paint.setTextSize()`. Passing an "sp-looking" number such as 14 therefore renders
    // roughly a quarter of the intended size on a 2x display — the microscopic text this
    // screen used to show. Convert an explicit dp value instead, and keep it in a state so
    // pinch-to-zoom can change it.
    var fontPx by remember { mutableIntStateOf(with(density) { DEFAULT_FONT_DP.dp.roundToPx() }) }

    // Keep the view across recompositions; a new TerminalView would lose the scrollback
    // and the keyboard connection.
    val terminalView = remember {
        TerminalView(context, null).apply {
            setTerminalViewClient(
                AppTerminalViewClient(
                    // `this` is the TerminalView being configured — the client needs it to be
                    // able to request the IME.
                    onRequestKeyboard = { requestKeyboard(this) },
                    onZoom = { zoomIn ->
                        val step = FONT_ZOOM_STEP_DP.dpToPx(this).coerceAtLeast(1)
                        fontPx = (fontPx + if (zoomIn) step else -step).coerceIn(fontBoundsPx(this))
                    },
                ),
            )
            setBackgroundResource(com.termagent.R.drawable.terminal_background)
            isFocusable = true
            isFocusableInTouchMode = true
            // Must happen before attachSession(), because updateSize() dereferences the
            // renderer it installs.
            setTextSize(fontPx)
        }
    }

    // Re-apply on zoom. setTextSize() recreates the renderer and resizes the PTY, so the
    // shell reflows to the new column count exactly like Termux does.
    LaunchedEffect(fontPx) {
        terminalView.setTextSize(fontPx)
    }

    // Explicit keyboard request from the host's toolbar button.
    LaunchedEffect(keyboardRequest) {
        if (keyboardRequest > 0) requestKeyboard(terminalView)
    }

    // ------------------------------------------------------------------ repaint hook
    //
    // `TerminalView` is a plain Canvas-backed view: it redraws only when someone calls
    // `onScreenUpdated()` (which invalidates and re-anchors the scroll position). Nothing else in
    // this Compose tree ever does, and the forked modules deliberately contain no Compose
    // knowledge — so the shell output, including the echo of every keystroke, stayed invisible
    // until an unrelated event happened to repaint the view (the IME resizing the window, a
    // recomposition, …). That is precisely the "typing has enormous latency" report: the bytes
    // arrived instantly, the *pixels* did not.
    //
    // The session notifies on the main thread, but `TerminalSession.reset()` can also notify from
    // an arbitrary thread, so marshal defensively rather than trusting the caller.
    DisposableEffect(terminalView, shell) {
        val observer: () -> Unit = {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                terminalView.onScreenUpdated()
            } else {
                terminalView.post { terminalView.onScreenUpdated() }
            }
        }
        shell.addScreenUpdateObserver(observer)
        onDispose { shell.removeScreenUpdateObserver(observer) }
    }

    // Deliberately no detach on dispose: the TerminalSession is owned by the application
    // container, so leaving this screen must not kill the shell (or a running build).
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0D1117)),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            factory = { terminalView },
            update = { view ->
                val session = shell.ensureSession()
                if (view.getCurrentSession() !== session) {
                    view.attachSession(session)
                    // Focus *and* the IME can only be requested once the view is attached to
                    // the window and laid out, i.e. not during this composition pass. Posting
                    // defers it by a frame; requestKeyboard() then retries on its own account
                    // because the very first showSoftInput() is routinely dropped.
                    view.post {
                        view.requestFocus()
                        requestKeyboard(view)
                    }
                }
            },
        )
    }
}

/**
 * Focuses the terminal and brings up the soft keyboard, retrying for a few frames.
 *
 * `InputMethodManager.showSoftInput()` is a *request*, not a command: it is silently dropped
 * unless the target view already owns the IME connection, and that connection only exists once
 * the view has window focus **and** has been laid out. The call made as the screen first
 * appears (and often the one made from a tap that arrives with the view mid-relayout) therefore
 * fails — which is exactly the "terminal renders but cannot be typed into" symptom. Retrying
 * until the platform accepts the request makes the outcome independent of that race.
 *
 * This is also **not** something `TerminalView` does for you: it only calls `requestFocus()` on
 * tap and then `TerminalViewClient.onSingleTapUp()`.
 */
private fun requestKeyboard(view: TerminalView, attempt: Int = 0) {
    if (!view.isAttachedToWindow) return

    view.requestFocus()

    val imm = view.context
        .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return

    // 0 == SHOW_IMPLICIT. The named constant is deprecated, the value is not.
    if (imm.showSoftInput(view, 0)) {
        Log.d(IME_TAG, "soft keyboard requested (attempt ${attempt + 1})")
        return
    }

    if (attempt >= IME_MAX_ATTEMPTS) {
        Log.w(IME_TAG, "gave up showing the soft keyboard after ${attempt + 1} attempts")
        return
    }
    view.postDelayed({ requestKeyboard(view, attempt + 1) }, IME_RETRY_DELAY_MS)
}

// ------------------------------------------------------------------ font helpers

/** Terminal font size, in dp. Termux's own default is 12dp; this is a touch larger. */
private const val DEFAULT_FONT_DP = 14f
private const val FONT_MIN_DP = 7f
private const val FONT_MAX_DP = 36f
private const val FONT_ZOOM_STEP_DP = 2f

/**
 * Converts a dp value to pixels using the **view's** current display metrics.
 *
 * Reading the metrics off the view (rather than capturing `LocalDensity` in a lambda created
 * inside `remember`) keeps pinch-to-zoom correct even though `density` is in the activity's
 * `configChanges`, so a display-size change recomposes this screen without recreating it.
 */
private fun Float.dpToPx(view: View): Int =
    (this * view.resources.displayMetrics.density).roundToInt()

/** Inclusive pixel bounds for pinch-to-zoom, derived from the *current* display density. */
private fun fontBoundsPx(view: View): IntRange {
    val scale = view.resources.displayMetrics.density
    return (FONT_MIN_DP * scale).roundToInt()..(FONT_MAX_DP * scale).roundToInt()
}

/** Shown in place of the terminal until the Termux bootstrap has been installed. */
@Composable
private fun BootstrapMissingNotice(modifier: Modifier = Modifier) {
    val palette = TermAgentTheme.colors

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg).widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.terminal_missing_title),
                color = palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.terminal_missing_body),
                color = palette.textSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Bridges `TerminalView` back to the app: key handling, logging and copy/paste all route
 * through the session created by [ShellManager].
 */
private class AppTerminalViewClient(
    /** Opens the soft keyboard. `TerminalView` never does this by itself — see [requestKeyboard]. */
    private val onRequestKeyboard: () -> Unit,
    /** Pinch-to-zoom callback: `true` to grow the font, `false` to shrink it. */
    private val onZoom: (zoomIn: Boolean) -> Unit,
) : TerminalViewClient {

    /**
     * TerminalView multiplies the incoming factor onto `mScaleFactor` and resets it to whatever
     * we return, so acting on a step and returning `1f` restarts the accumulation — the same
     * trick Termux uses to turn a pinch into discrete font-size steps.
     */
    override fun onScale(scale: Float): Float = when {
        scale >= ZOOM_IN_THRESHOLD -> { onZoom(true); 1f }
        scale <= ZOOM_OUT_THRESHOLD -> { onZoom(false); 1f }
        else -> scale
    }

    /**
     * A tap on the terminal must bring the keyboard up.
     *
     * `TerminalView` handles the touch: it exits text-selection mode if needed, calls
     * `requestFocus()`, and then hands off here. Focusing alone is not enough — the IME stays
     * down, so the terminal renders and scrolls but silently accepts no typing.
     */
    override fun onSingleTapUp(e: MotionEvent) = onRequestKeyboard()

    /** Android's back button should close the screen, not send ESC to the shell. */
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /** Character-based input avoids stale modifier state on many IMEs. */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    /** No on-screen modifier bar in this UI; report them unpressed. */
    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    // `Log.*` returns Int, so these must be expression *bodies* (Unit) rather than the
    // direct return of the Log call — hence the braces.
    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "stack trace", e)
    }

    private companion object {
        /** Termux uses the same pair of thresholds to turn a pinch into one font step. */
        const val ZOOM_IN_THRESHOLD = 1.1f
        const val ZOOM_OUT_THRESHOLD = 0.9f
    }
}

/** Log tag for IME diagnostics. */
private const val IME_TAG = "TermAgentIme"

/**
 * How long to wait between `showSoftInput()` attempts, and how many to make.
 *
 * The IME connection is established asynchronously after the view gains window focus, so the
 * first request is normally rejected. Six attempts at 120 ms covers the ~0.5 s Termux itself
 * budgets for this race, and the retries stop as soon as the platform accepts the request.
 */
private const val IME_RETRY_DELAY_MS = 120L
private const val IME_MAX_ATTEMPTS = 6
