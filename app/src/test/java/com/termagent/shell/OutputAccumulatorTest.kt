package com.termagent.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputAccumulatorTest {

    @Test
    fun `output below the limits is kept verbatim`() {
        val accumulator = OutputAccumulator(headChars = 10, tailChars = 10)

        accumulator.append("hello")
        accumulator.append(" world")

        assertEquals("hello world", accumulator.toString())
        assertEquals(11, accumulator.totalChars)
        assertFalse(accumulator.isTruncated)
    }

    @Test
    fun `output exactly at the limits is kept verbatim`() {
        // The limits are per side, so the total that fits verbatim is head + tail.
        val accumulator = OutputAccumulator(headChars = 5, tailChars = 5)

        accumulator.append("AAAAABBBBB")

        assertEquals("AAAAABBBBB", accumulator.toString())
        assertEquals(10, accumulator.totalChars)
        assertFalse(accumulator.isTruncated)
    }

    @Test
    fun `one character over the combined limits starts dropping`() {
        val accumulator = OutputAccumulator(headChars = 5, tailChars = 5)

        accumulator.append("AAAAABBBBB" + "!")

        assertTrue(accumulator.isTruncated)
        // The head is full, so the tail window slides: the oldest tail character is dropped.
        assertEquals("AAAAA\n... [1 characters omitted] ...\nBBBB!", accumulator.toString())
    }

    @Test
    fun `the middle is dropped and the omission is counted`() {
        val accumulator = OutputAccumulator(headChars = 4, tailChars = 4)

        // 4 head + 4 tail retained, 6 in the middle dropped.
        accumulator.append("HEAD" + "012345" + "TAIL")

        assertTrue(accumulator.isTruncated)
        assertEquals(14, accumulator.totalChars)
        assertEquals("HEAD\n... [6 characters omitted] ...\nTAIL", accumulator.toString())
    }

    @Test
    fun `head and tail are kept from the right places`() {
        val accumulator = OutputAccumulator(headChars = 3, tailChars = 6)
        accumulator.append((1..100).joinToString("-"))

        val rendered = accumulator.toString()
        assertTrue("keeps the first characters", rendered.startsWith("1-2"))
        assertTrue("keeps the last characters", rendered.endsWith("99-100"))
        assertTrue("says how much was dropped", rendered.contains("characters omitted"))
    }

    @Test
    fun `feeding in slices matches feeding at once`() {
        val text = (1..500).joinToString("") { it.toString() }

        val atOnce = OutputAccumulator(headChars = 20, tailChars = 30).apply { append(text) }
        val inSlices = OutputAccumulator(headChars = 20, tailChars = 30).apply {
            text.chunked(7).forEach { append(it) }
        }

        assertEquals(atOnce.toString(), inSlices.toString())
        assertEquals(atOnce.totalChars, inSlices.totalChars)
    }

    @Test
    fun `byte at a time feeding matches feeding at once`() {
        val text = "abcdefghijklmnopqrstuvwxyz0123456789"

        val atOnce = OutputAccumulator(headChars = 5, tailChars = 5).apply { append(text) }
        val charByChar = OutputAccumulator(headChars = 5, tailChars = 5).apply {
            text.forEach { append(it.toString()) }
        }

        assertEquals(atOnce.toString(), charByChar.toString())
    }

    @Test
    fun `omitted count is accurate after many appends`() {
        val accumulator = OutputAccumulator(headChars = 10, tailChars = 10)

        repeat(100) { accumulator.append("0123456789") }

        // 1000 characters in, at most 20 retained.
        assertEquals(1000, accumulator.totalChars)
        val rendered = accumulator.toString()
        assertTrue(rendered.contains("[980 characters omitted]"))
        assertTrue(rendered.startsWith("0123456789"))
        assertTrue(rendered.endsWith("0123456789"))
    }

    @Test
    fun `an empty accumulator renders as the empty string`() {
        assertEquals("", OutputAccumulator(headChars = 4, tailChars = 4).toString())
    }

    @Test
    fun `a zero tail keeps only the head`() {
        val accumulator = OutputAccumulator(headChars = 3, tailChars = 0)

        accumulator.append("abcdef")

        assertTrue(accumulator.isTruncated)
        assertEquals("abc\n... [3 characters omitted] ...\n", accumulator.toString())
    }
}
