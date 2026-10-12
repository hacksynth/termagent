package com.termagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "carry it, do not sniff it" contract of [ToolResult].
 *
 * Both regressions this file covers were real, and both came from re-deriving metadata out of the
 * reply text:
 *
 *  * `isError` used to be `text.startsWith("Error")`, which marked a timed-out `run_shell` as a
 *    success (its reply starts with `exit_code: 124`);
 *  * `exitCode` used to be parsed out of the reply text by the UI, which gave a `read_file` of any
 *    file that merely *mentioned* `exit_code: 124` a fabricated "exit 124" badge.
 */
class ToolResultTest {

    @Test
    fun `a non-error reply is not an error`() {
        val result = ToolResult.of("path/file.kt\nline 2\n")

        assertFalse(result.isError)
    }

    @Test
    fun `an Error-prefixed reply is an error`() {
        val result = ToolResult.of("Error: 'path' is required.")

        assertTrue(result.isError)
    }

    @Test
    fun `a plain tool reply never carries an exit code, even if it mentions one`() {
        // Exactly the shape that used to break the UI badge: the text contains a real-looking
        // `exit_code:` line because it is the *contents of a file that was read*, not because a
        // command ran.
        val result = ToolResult.of(
            "exit_code: 124\nnote: command exceeded 120s and was interrupted (Ctrl-C).",
        )

        assertNull(result.exitCode)
    }

    @Test
    fun `the exit code is carried, not parsed`() {
        val result = ToolResult(text = "exit_code: 3\n--- output ---\nboom", isError = true, exitCode = 3)

        assertEquals(3, result.exitCode)
        assertTrue(result.isError)
    }
}
