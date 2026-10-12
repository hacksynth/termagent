package com.termagent.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Tests for the command-output scanner that replaced the screen-diffing implementation.
 *
 * The regression these lock down is the one that made a *finished* `pkg install` block for its
 * entire timeout: the old code re-rendered the terminal emulator's scrollback and diffed it
 * positionally, so a resize (opening the terminal tab, rotating, the IME appearing) reflowed the
 * text, shortened it, reset the read offset — and the already-printed end marker was never seen.
 */
class CommandCaptureTest {

    private val begin = "__TERMAGENT_BEGIN_1__"
    private val end = "__TERMAGENT_END_1__"

    /** Feeds [slices] and returns everything the scanner emitted, in order. */
    private fun capture(vararg slices: String): Pair<String, Int?> {
        val capture = CommandCapture(begin, end)
        val emitted = StringBuilder()
        slices.forEach { emitted.append(capture.accept(it)) }
        return emitted.toString() to capture.exitCode
    }

    /**
     * The whole stream for one command, as the shell produces it: the echoed snippet (with the
     * markers still *unexpanded*), the begin marker, the output, then the end marker plus status.
     */
    private fun fullStream(output: String, exitCode: Int = 0): String = buildString {
        append("__termagent_id=1\n")
        append("printf '%s\\n' \"\$__TERMAGENT_BEGIN_\${__termagent_id}__\"\n")
        append("ls -la\n")
        append("__termagent_rc=\$?\n")
        append("printf '\\n__TERMAGENT_END_%s__ %s\\n' \"\${__termagent_id}\" \"\${__termagent_rc}\"\n")
        append("~ \$ ls -la\n")
        append("$begin\n")
        append(output)
        append("\n$end $exitCode\n")
        append("~ \$ ")
    }

    @Test
    fun `captures output between the markers and the exit status`() {
        val (output, exitCode) = capture(fullStream("file1\nfile2"))

        assertEquals(0, exitCode)
        assertEquals("file1\nfile2", output)
    }

    @Test
    fun `the echoed snippet is not mistaken for the markers`() {
        // The echo contains the marker text only as `${__termagent_id}`, and crucially the line
        // that echoes the *end* marker is not followed by an exit status — so a naive
        // `endsWith(marker)` scan would complete the command immediately with an empty result.
        val (output, exitCode) = capture(fullStream("hello"))

        assertEquals("hello", output)
        assertEquals(0, exitCode)
    }

    @Test
    fun `non-zero exit status is captured`() {
        val (output, exitCode) = capture(fullStream("boom", exitCode = 1))

        assertEquals("boom", output)
        assertEquals(1, exitCode)
    }

    @Test
    fun `nothing is emitted until the end marker arrives`() {
        // The trailing hold-back must not swallow the whole body on a normal run.
        val (output, exitCode) = capture("$begin\nshort\n$end 0\n")

        assertEquals("short", output)
        assertEquals(0, exitCode)
    }

    @Test
    fun `output arriving in tiny slices is reassembled exactly once`() {
        // Character-at-a-time delivery: the worst case for marker detection, and how a busy pty
        // and a reflowing renderer actually behave.
        val stream = fullStream("alpha\nbeta", exitCode = 3)
        val (output, exitCode) = capture(*stream.map { it.toString() }.toTypedArray())

        assertEquals("alpha\nbeta", output)
        assertEquals(3, exitCode)
    }

    @Test
    fun `a marker split across two reads is still recognised`() {
        // Hold-back exists precisely for this: the `__TERMAGENT_END_1__ 0` must not be streamed as
        // ordinary output and then deleted, or the command would never complete.
        val (output, exitCode) = capture(
            "$begin\npartial output\n__TERMAGENT_END_1",
            "__ 0\n",
        )

        assertEquals("partial output", output)
        assertEquals(0, exitCode)
    }

    @Test
    fun `no output between markers yields an empty body and a zero status`() {
        val (output, exitCode) = capture("$begin\n$end 0\n")

        assertEquals("", output)
        assertEquals(0, exitCode)
    }

    @Test
    fun `exit status is unknown until the marker is seen`() {
        val capture = CommandCapture(begin, end)
        capture.accept("$begin\nstill working…\n")

        // Null is the only "not finished" signal: a quiet stream must never be treated as done.
        assertNull(capture.exitCode)
    }

    @Test
    fun `repeated identical reads produce the output once`() {
        // Regression guard for the actual bug: the old implementation re-emitted text every time
        // the rendered region's length changed. An append-only scan cannot do that, even when the
        // same content is fed repeatedly (as a reflowing screen effectively did).
        val capture = CommandCapture(begin, end)
        val emitted = StringBuilder()

        emitted.append(capture.accept("$begin\nline A\n"))
        emitted.append(capture.accept("line A\n"))
        emitted.append(capture.accept("line A\nline B\n"))
        emitted.append(capture.accept("$end 0\n"))

        // Whatever the order, every character is delivered exactly once and the marker is found.
        assertEquals("line A\nline A\nline A\nline B", emitted.toString())
        assertEquals(0, capture.exitCode)
    }

    @Test
    fun `output printed after the exit marker is not part of the command`() {
        // The shell's next prompt (or an async job's chatter) must not leak into the result.
        val (output, exitCode) = capture(fullStream("done") + "\nnoise from another job\n")

        assertEquals("done", output)
        assertEquals(0, exitCode)
        assertTrue(output.indexOf("noise") < 0)
    }

    @Test
    fun `a command that prints marker-like text still completes on the real sentinel`() {
        val (output, exitCode) = capture("$begin\n$end not-a-number\nreal output\n$end 7\n")

        // The decoy is kept as output; the genuine sentinel is the last marker followed by an int.
        assertEquals("$end not-a-number\nreal output", output)
        assertEquals(7, exitCode)
    }

    @Test
    fun `nothing after the sentinel is emitted, even in a later read`() {
        // The command is finished at the end marker, but its shell keeps producing output — the
        // prompt, the echo of what the user types, a background job. None of it is this command's.
        //
        // This one passes with or without the `finished` latch (the retained marker sits at the
        // start of the buffer, so there is nothing *between* markers to emit). It guards the
        // contract; the discriminating case is `post-sentinel text cannot overwrite the exit
        // status` below.
        val capture = CommandCapture(begin, end)
        val emitted = StringBuilder()

        emitted.append(capture.accept("$begin\nbody\n"))
        emitted.append(capture.accept("$end 0\n"))
        emitted.append(capture.accept("~ \$ ls\n"))
        emitted.append(capture.accept("noise from a background job\n"))

        assertEquals("body", emitted.toString())
        assertEquals(0, capture.exitCode)
    }

    @Test
    fun `post-sentinel text cannot overwrite the exit status`() {
        // The scanner used to keep scanning the retained marker, so a marker-like string followed
        // by an integer in the *next* command's output looked like a fresh sentinel: the text
        // between the two was emitted as output and the status was overwritten with that integer.
        val capture = CommandCapture(begin, end)
        val emitted = StringBuilder()

        emitted.append(capture.accept("$begin\nbody\n"))
        emitted.append(capture.accept("$end 0\n"))
        emitted.append(capture.accept("something $end 5\n"))

        assertEquals("body", emitted.toString())
        assertEquals(0, capture.exitCode)
    }

    @Test
    fun `a chatty shell after the command cannot make the scanner retain output`() {
        // The other half of the same defect: everything the pty delivered after the command stayed
        // in the held-back buffer and was re-scanned on every read, so a background job writing
        // megabytes made each read walk all of it (quadratic CPU). The latch releases it.
        val capture = CommandCapture(begin, end)
        assertTrue(capture.accept("$begin\nsmall\n$end 0\n").isNotEmpty())

        repeat(50) { capture.accept("x".repeat(1024)) }

        assertEquals(0, capture.retainedLength)
        assertEquals("", capture.accept("still nothing"))
    }

    @Test
    fun `the pty's CRLF is preserved rather than silently rewritten`() {
        // A pty with ONLCR (the default) translates every newline to CRLF, so this is the *normal*
        // case on device, not an exotic one. The carriage returns are kept on purpose: the captured
        // stream is the exact bytes the program wrote (see the class KDoc), and normalising them
        // would alter the contents of a file the model merely `cat`s. `run_shell` trims the tail of
        // its reply instead.
        val capture = CommandCapture(begin, end)

        val emitted = capture.accept("$begin\r\nline1\r\nline2\r\n\r\n$end 0\r\n")

        assertEquals("line1\r\nline2\r\n", emitted)
        assertEquals(0, capture.exitCode)
    }

    @Test
    fun `empty markers are rejected`() {
        // Guards a caller that builds the marker from an id and gets it wrong: an empty marker
        // matches at every position, which would complete the command instantly and with the wrong
        // output.
        assertThrows(IllegalArgumentException::class.java) { CommandCapture("", end) }
        assertThrows(IllegalArgumentException::class.java) { CommandCapture(begin, "") }
    }

    @Test
    fun `an enormous pre-marker echo does not accumulate`() {
        // The echo of a long snippet can be large; keeping it would grow without bound.
        val noise = "x".repeat(100_000)
        val (output, exitCode) = capture("$noise$begin\nbody\n$end 0\n")

        assertEquals("body", output)
        assertEquals(0, exitCode)
    }
}

/**
 * Tests for the streaming UTF-8 decoder.
 *
 * A pty `read` can slice a multi-byte character in half; a naive `String(bytes, UTF_8)` per read
 * turns that into a replacement character, which is what mangles non-ASCII output in the UI.
 */
class Utf8StreamDecoderTest {

    private fun decodeAll(vararg chunks: ByteArray): String {
        val decoder = Utf8StreamDecoder()
        val out = ByteArrayOutputStream()
        chunks.forEach { out.write(decoder.decode(it, it.size).toByteArray(Charsets.UTF_8)) }
        return out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun `decodes plain ascii`() {
        assertEquals("hello", decodeAll("hello".toByteArray()))
    }

    @Test
    fun `reassembles a multi-byte character split across reads`() {
        // 中 is E4 B8 AD; split it after the first byte, and again after the second.
        val bytes = "中".toByteArray(Charsets.UTF_8)
        assertEquals("中", decodeAll(bytes.copyOfRange(0, 1), bytes.copyOfRange(1, 3)))
        assertEquals("中", decodeAll(bytes.copyOfRange(0, 2), bytes.copyOfRange(2, 3)))
    }

    @Test
    fun `does not emit a replacement character for an incomplete tail`() {
        val decoder = Utf8StreamDecoder()
        val bytes = "中文".toByteArray(Charsets.UTF_8)

        // Feed all but the last byte of the second character: nothing valid should come out of the
        // split character, and no U+FFFD either.
        val first = decoder.decode(bytes, bytes.size - 1)
        assertEquals("中", first)

        val rest = byteArrayOf(bytes[bytes.size - 1])
        assertEquals("文", decoder.decode(rest, rest.size))
    }

    @Test
    fun `emoji split across three reads reassembles`() {
        val bytes = "🚀".toByteArray(Charsets.UTF_8)
        assertEquals("🚀", decodeAll(bytes.copyOfRange(0, 1), bytes.copyOfRange(1, 3), bytes.copyOfRange(3, 4)))
    }

    @Test
    fun `handles a trailing carriage return from the pty`() {
        // The pty normally gives CRLF; the decoder must not drop either half.
        assertEquals("line\r\n", decodeAll("line\r\n".toByteArray()))
    }

    @Test
    fun `invalid bytes are replaced rather than breaking the stream`() {
        val decoder = Utf8StreamDecoder()
        val bogus = byteArrayOf(0x41, 0xFF.toByte(), 0x42)

        val text = decoder.decode(bogus, bogus.size)
        assertTrue(text.startsWith("A"))
        assertTrue(text.endsWith("B"))
    }
}
