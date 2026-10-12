package com.termagent.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block-level parser tests.
 *
 * Only [Markdown.parse] is exercised here: it is pure Kotlin, so it runs on a plain JVM unit
 * test. The inline styler needs `androidx.compose.ui.text` and is therefore covered by hand.
 */
class MarkdownParserTest {

    private fun blocks(source: String) = Markdown.parse(source)

    @Test
    fun `headings carry their level and strip closing hashes`() {
        val result = blocks(
            """
            # Title
            ## Section
            ### Deep ###
            ###### Tiny
            """.trimIndent(),
        )

        assertEquals(4, result.size)
        assertEquals(Markdown.Block.Heading(1, "Title"), result[0])
        assertEquals(Markdown.Block.Heading(2, "Section"), result[1])
        assertEquals(Markdown.Block.Heading(3, "Deep"), result[2])
        assertEquals(Markdown.Block.Heading(6, "Tiny"), result[3])
    }

    @Test
    fun `a bare hash is not a heading`() {
        val result = blocks("#not a heading")
        assertEquals(listOf(Markdown.Block.Paragraph("#not a heading")), result)
    }

    @Test
    fun `fenced code keeps its body verbatim including markup-looking lines`() {
        val result = blocks(
            """
            Intro.
            ```kotlin
            val a = 1
            # not a heading
            **not bold**
            ```
            Outro.
            """.trimIndent(),
        )

        assertEquals(3, result.size)
        assertEquals(Markdown.Block.Paragraph("Intro."), result[0])
        val code = result[1] as Markdown.Block.Code
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1\n# not a heading\n**not bold**\n", code.code)
        assertEquals(Markdown.Block.Paragraph("Outro."), result[2])
    }

    @Test
    fun `an unterminated fence is surfaced as code rather than dropped`() {
        val result = blocks("```sh\nls -la")
        assertEquals(1, result.size)
        assertEquals(Markdown.Block.Code("ls -la\n", "sh"), result[0])
    }

    @Test
    fun `blank lines split paragraphs and consecutive lines join one`() {
        val result = blocks("one\ntwo\n\nthree")
        assertEquals(
            listOf(Markdown.Block.Paragraph("one\ntwo"), Markdown.Block.Paragraph("three")),
            result,
        )
    }

    @Test
    fun `unordered list with two levels and a task marker`() {
        val result = blocks(
            """
            - first
              - nested
            - [x] done
            - [ ] todo
            """.trimIndent(),
        )

        assertEquals(1, result.size)
        val items = (result[0] as Markdown.Block.ListBlock).items
        assertEquals(4, items.size)
        assertEquals(Markdown.Item("first", 0, ordered = false, number = 0), items[0])
        assertEquals(Markdown.Item("nested", 1, ordered = false, number = 0), items[1])
        assertEquals(Markdown.Item("done", 0, ordered = false, number = 0, checked = true), items[2])
        assertEquals(Markdown.Item("todo", 0, ordered = false, number = 0, checked = false), items[3])
    }

    @Test
    fun `ordered list keeps the source numbering`() {
        val result = blocks("1. one\n2. two\n10. ten")
        val items = (result[0] as Markdown.Block.ListBlock).items
        assertEquals(listOf(1, 2, 10), items.map { it.number })
        assertTrue(items.all { it.ordered })
    }

    @Test
    fun `an indented continuation line is folded into the previous item`() {
        val result = blocks("- a long item\n  that wraps\n- second")
        val items = (result[0] as Markdown.Block.ListBlock).items
        assertEquals(2, items.size)
        assertEquals("a long item that wraps", items[0].text)
    }

    @Test
    fun `a list is terminated by a blank line and a paragraph`() {
        val result = blocks("- item\n\nplain text")
        assertEquals(2, result.size)
        assertTrue(result[0] is Markdown.Block.ListBlock)
        assertEquals(Markdown.Block.Paragraph("plain text"), result[1])
    }

    @Test
    fun `blockquote recurses so nested lists become list blocks`() {
        val result = blocks("> note\n> - a\n> - b")
        assertEquals(1, result.size)
        val quote = result[0] as Markdown.Block.Quote
        assertEquals(2, quote.blocks.size)
        assertEquals(Markdown.Block.Paragraph("note"), quote.blocks[0])
        assertEquals(2, (quote.blocks[1] as Markdown.Block.ListBlock).items.size)
    }

    @Test
    fun `table parses header alignments and rows`() {
        val result = blocks(
            """
            | Name | Value | Note |
            |:-----|:-----:|-----:|
            | a | 1 | x |
            | b | 2 | y |
            """.trimIndent(),
        )

        assertEquals(1, result.size)
        val table = result[0] as Markdown.Block.Table
        assertEquals(listOf("Name", "Value", "Note"), table.header)
        assertEquals(
            listOf(Markdown.ColumnAlign.Start, Markdown.ColumnAlign.Center, Markdown.ColumnAlign.End),
            table.alignments,
        )
        assertEquals(listOf(listOf("a", "1", "x"), listOf("b", "2", "y")), table.rows)
    }

    @Test
    fun `a pipe line without a delimiter row stays a paragraph`() {
        val result = blocks("a | b")
        assertEquals(listOf(Markdown.Block.Paragraph("a | b")), result)
    }

    @Test
    fun `horizontal rules are recognised in all three spellings`() {
        assertEquals(
            listOf(Markdown.Block.Rule, Markdown.Block.Rule, Markdown.Block.Rule),
            blocks("---\n***\n___"),
        )
        // Two dashes are prose, not a rule.
        assertEquals(listOf(Markdown.Block.Paragraph("--")), blocks("--"))
    }

    @Test
    fun `inline markers inside a paragraph do not create blocks`() {
        val result = blocks("Use `--flag` and **bold** and [a](https://x.y) here.")
        assertEquals(1, result.size)
        assertEquals(
            "Use `--flag` and **bold** and [a](https://x.y) here.",
            (result[0] as Markdown.Block.Paragraph).text,
        )
    }

    @Test
    fun `an empty document yields no blocks`() {
        assertTrue(blocks("").isEmpty())
        assertTrue(blocks("\n\n").isEmpty())
    }
}
