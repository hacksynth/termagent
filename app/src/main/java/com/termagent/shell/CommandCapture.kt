package com.termagent.shell

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * Reassembles one command's output from the shell's **raw pty byte stream**.
 *
 * The shell brackets each command with two sentinels it prints itself:
 *
 * ```sh
 * printf '%s\n' "__TERMAGENT_BEGIN_<id>__"
 * <the command>
 * printf '\n__TERMAGENT_END_<id>__ %s\n' "$?"
 * ```
 *
 * [accept] is fed the decoded stream as it arrives (in arbitrary slices) and returns the text that
 * has just become safe to emit. Because the input is the actual byte stream and therefore
 * *append-only*, a marker is either present or not — positions never shift, and no text is ever
 * delivered twice. That is precisely what the previous screen-diffing implementation got wrong:
 * it re-rendered the emulator's scrollback, so a resize (opening the terminal tab, rotating, the
 * IME appearing) reflowed the text, changed its length, and left an already-printed end marker
 * unrecognised — making a finished command block until its timeout.
 *
 * Deliberately free of Android types so the logic is unit-testable on the JVM; see
 * `CommandCaptureTest`.
 */
internal class CommandCapture(
    private val beginMarker: String,
    private val endMarker: String,
) {

    /** Text received but not yet *safely* emittable — see [accept]. */
    private val pending = StringBuilder()

    private var seenBegin = false
    private var firstChunk = true

    /**
     * Set once the end marker has been seen.
     *
     * Everything that arrives afterwards belongs to the *next* command (a prompt, the echo of what
     * the user types, a background job's chatter) and must be ignored outright. Without this latch
     * the scan kept running over the retained marker, which was only accidentally harmless: if the
     * post-marker text happened to contain a marker-like string followed by an integer, the text
     * between the two markers was emitted as the command's output and [exitCode] was overwritten
     * with that integer. It also means [pending] stops growing at a command's end instead of
     * retaining everything the pty delivers until the caller clears the execution.
     */
    private var finished = false

    /**
     * The command's exit status, or `null` while the end marker has not been seen.
     *
     * Non-null means the command finished; that is the only completion signal, so a missing marker
     * must never be inferred from a quiet stream.
     */
    var exitCode: Int? = null
        private set

    /**
     * How many characters are being held back, i.e. received but not yet emitted.
     *
     * Exposed for the tests, because retention is otherwise unobservable: without the [finished]
     * latch the scanner keeps the end marker and everything the shell writes afterwards in
     * [pending], and re-scans all of it on every read — quadratic CPU on a chatty background job.
     * With the latch it returns to zero as soon as the command is over.
     */
    internal val retainedLength: Int get() = pending.length

    init {
        require(beginMarker.isNotEmpty() && endMarker.isNotEmpty()) {
            "markers must not be empty"
        }
    }

    /**
     * Feed the next slice of the stream and return the newly-available output (empty if none).
     *
     * The returned text is *new* text only: everything already returned is never returned again.
     *
     * A trailing [holdBack] characters are withheld until the end marker is found, because they
     * could be the beginning of the marker itself. Without that, a marker split across two reads
     * would be emitted as ordinary output and then deleted — losing it forever.
     *
     * Once the end marker has been seen the scanner is [finished] and returns nothing, ever: the
     * caller keeps accumulating the returned slices itself, and the shell's next prompt is not part
     * of this command.
     */
    fun accept(text: String): String {
        // The command is over: this slice belongs to whatever the shell did next. Dropping it here
        // (rather than merely cutting at the marker) also releases the retained marker.
        if (finished) return ""

        pending.append(text)

        if (!seenBegin) {
            val index = pending.indexOf(beginMarker)
            if (index < 0) {
                // Not there yet. Keep only as much tail as a marker split across two slices could
                // need, so a command that prints a lot before the marker cannot grow this without
                // bound (the snippet's own echo is the usual content here).
                val keep = beginMarker.length - 1
                if (pending.length > keep) pending.delete(0, pending.length - keep)
                return ""
            }
            seenBegin = true
            // Drop the echoed snippet and the marker line: capture starts at the output.
            pending.delete(0, index + beginMarker.length)
        }

        val markerIndex = findEndMarker(pending, endMarker)

        // Read the status first: it sits right after the marker, and the deletion below shifts the
        // marker's position.
        if (markerIndex >= 0) {
            exitCode = trailingWord(pending, markerIndex + endMarker.length).toIntOrNull() ?: -1
            // Latched before the chunk below is emitted: this call still delivers the command's
            // final output, but every later one is dropped by the guard at the top of `accept`.
            finished = true
        }

        val bodyEnd = if (markerIndex >= 0) {
            // The shell's `printf '\n__TERMAGENT_END_…'` writes a newline *before* the marker that
            // is not part of the command's own output, so drop it — symmetrically to the newline
            // the begin marker's `printf '%s\n'` adds after itself.
            var end = markerIndex
            if (end > 0 && pending[end - 1] == '\n') {
                end--
                // The pty translates the newline to CRLF.
                if (end > 0 && pending[end - 1] == '\r') end--
            }
            end
        } else {
            (pending.length - holdBack).coerceAtLeast(0)
        }

        val chunk = if (bodyEnd > 0) {
            // `substring` then `delete` keeps this O(n) on the retained tail rather than O(n^2),
            // which matters because terminal output can be megabytes.
            val cut = pending.substring(0, bodyEnd)
            pending.delete(0, bodyEnd)
            cut
        } else {
            ""
        }

        // The shell's `printf '%s\n'` for the begin marker leaves that marker on its own line.
        val cleaned = if (chunk.isEmpty()) {
            ""
        } else if (firstChunk) {
            firstChunk = false
            chunk.removePrefix("\r\n").removePrefix("\n")
        } else {
            chunk
        }

        // The end marker (and the newline its `printf` put in front of it) is dead weight once
        // latched: nothing will read it again, and the shell keeps writing. Releasing it here is
        // what makes "nothing is retained after the command" an invariant rather than a hope.
        if (finished) pending.clear()

        return cleaned
    }

    /**
     * Characters that must not be emitted yet while the end marker is still outstanding.
     *
     * The marker itself, plus a little slack for the exit status that follows it.
     */
    private val holdBack: Int get() = endMarker.length + EXIT_STATUS_MARGIN

    private companion object {
        /**
         * Slack for the ` %s` status after the end marker. The shell's own `printf` adds a space and
         * a full `int`, so an exit status is never longer than this.
         */
        const val EXIT_STATUS_MARGIN = 16

        /**
         * Last occurrence of [marker] that is followed by a bare integer.
         *
         * The trailing integer is what distinguishes the real sentinel from the shell's echo of the
         * snippet, which contains the marker text only in its *unexpanded* `${__termagent_id}`
         * form — so it is not followed by one. The last match wins because a command may well print
         * something that looks like a marker.
         */
        fun findEndMarker(text: CharSequence, marker: String): Int {
            var index = text.lastIndexOf(marker)
            while (index >= 0) {
                if (trailingWord(text, index + marker.length).toIntOrNull() != null) return index
                index = text.lastIndexOf(marker, index - 1)
            }
            return -1
        }

        /** The first whitespace-delimited word at or after [fromIndex], up to end of line. */
        fun trailingWord(text: CharSequence, fromIndex: Int): String {
            if (fromIndex >= text.length) return ""
            val tail = text.subSequence(fromIndex, text.length)
            val lineEnd = tail.indexOf('\n').let { if (it == -1) tail.length else it }
            return tail.substring(0, lineEnd).trim()
        }
    }
}

/**
 * Turns the pty's byte stream into text across reads.
 *
 * A UTF-8 character can straddle two `read(2)` results, so slices are **not** decoded
 * independently: the bytes of an incomplete trailing sequence are carried over to the next call.
 * Decoding everything at once is not an option either — the shell is long-lived, so a buffer of
 * everything it has ever printed would grow without bound.
 */
internal class Utf8StreamDecoder {
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** Bytes of a partially received character, carried over from the previous call. */
    private val carry = ByteArrayOutputStream(8)

    fun decode(data: ByteArray, length: Int): String {
        carry.write(data, 0, length)
        val bytes = carry.toByteArray()
        carry.reset()

        val input = ByteBuffer.wrap(bytes)
        // UTF-8 never expands, so one char per byte is always enough.
        val output = CharBuffer.allocate(bytes.size + 1)
        decoder.decode(input, output, false)
        output.flip()

        // Whatever the decoder did not consume is an incomplete trailing sequence: keep it for the
        // next call rather than emitting a replacement character for a character that is still
        // arriving.
        if (input.hasRemaining()) {
            val rest = ByteArray(input.remaining())
            input.get(rest)
            carry.write(rest, 0, rest.size)
        }

        return output.toString()
    }
}
