package com.termagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import com.termagent.R
import com.termagent.ui.theme.Spacing
import com.termagent.ui.theme.TermAgentPalette
import com.termagent.ui.theme.TermAgentTheme
import com.termagent.ui.theme.TerminalFontFamily

/**
 * Renders an assistant message as Markdown.
 *
 * [Markdown.parse] turns the source into block-level nodes; each block is then drawn by a
 * dedicated composable so headings, lists, quotes and tables get real layout instead of being
 * flattened into a single `Text`. Inline styling (emphasis, code spans, links, …) is applied
 * by [Markdown.annotate] as an [AnnotatedString].
 *
 * This is intentionally a *pragmatic* Markdown subset — the constructs an agent actually emits.
 * It is not a CommonMark-compliant parser: no HTML, no reference links, no nested blockquotes
 * inside lists, etc.
 */
@Composable
fun MarkdownBody(
    text: String,
    showCursor: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = TermAgentTheme.colors
    val uriHandler = LocalUriHandler.current
    val blocks = remember(text) { Markdown.parse(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        for (block in blocks) {
            BlockContent(block = block, palette = palette, uriHandler = uriHandler)
        }
        if (showCursor) {
            // A trailing caret so a streaming turn visibly ends mid-sentence.
            Text(text = "▍", color = palette.accent, fontSize = 13.sp)
        }
    }
}

// ---------------------------------------------------------------------- block rendering

@Composable
private fun BlockContent(
    block: Markdown.Block,
    palette: TermAgentPalette,
    uriHandler: UriHandler,
) {
    when (block) {
        is Markdown.Block.Heading -> HeadingBlock(block, palette, uriHandler)
        is Markdown.Block.Paragraph -> ParagraphBlock(block.text, palette, uriHandler)
        is Markdown.Block.Code -> CodeBlock(block.code, block.language)
        is Markdown.Block.Quote -> QuoteBlock(block, palette, uriHandler)
        is Markdown.Block.ListBlock -> ListBlockView(block, palette, uriHandler)
        is Markdown.Block.Table -> TableBlock(block, palette, uriHandler)
        Markdown.Block.Rule -> RuleBlock(palette)
    }
}

@Composable
private fun ParagraphBlock(text: String, palette: TermAgentPalette, uriHandler: UriHandler) {
    SelectionContainer {
        Text(
            text = annotated(text, palette, uriHandler),
            color = palette.textPrimary,
            fontSize = 15.sp,
            lineHeight = 23.sp,
        )
    }
}

@Composable
private fun HeadingBlock(block: Markdown.Block.Heading, palette: TermAgentPalette, uriHandler: UriHandler) {
    // Scale by depth; h4-h6 collapse to the same body-emphasis size.
    val size: TextUnit
    val lineHeight: TextUnit
    val topPadding: Dp
    val weight: FontWeight
    when (block.level) {
        1 -> { size = 21.sp; lineHeight = 28.sp; topPadding = 8.dp; weight = FontWeight.Bold }
        2 -> { size = 18.sp; lineHeight = 25.sp; topPadding = 6.dp; weight = FontWeight.Bold }
        3 -> { size = 16.sp; lineHeight = 23.sp; topPadding = 4.dp; weight = FontWeight.SemiBold }
        else -> { size = 15.sp; lineHeight = 22.sp; topPadding = 2.dp; weight = FontWeight.SemiBold }
    }

    SelectionContainer {
        Text(
            text = annotated(block.text, palette, uriHandler),
            modifier = Modifier.padding(top = topPadding),
            color = palette.textPrimary,
            fontSize = size,
            fontWeight = weight,
            lineHeight = lineHeight,
        )
    }
}

@Composable
private fun QuoteBlock(block: Markdown.Block.Quote, palette: TermAgentPalette, uriHandler: UriHandler) {
    if (block.blocks.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(palette.accent.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            for (inner in block.blocks) {
                BlockContent(block = inner, palette = palette, uriHandler = uriHandler)
            }
        }
    }
}

@Composable
private fun ListBlockView(block: Markdown.Block.ListBlock, palette: TermAgentPalette, uriHandler: UriHandler) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        for (item in block.items) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (item.level * 16).dp),
            ) {
                val marker = when {
                    item.checked != null -> if (item.checked) "☑" else "☐"
                    item.ordered -> "${item.number}."
                    else -> BULLETS[item.level % BULLETS.size]
                }
                Text(
                    text = marker,
                    modifier = Modifier.width(if (item.ordered) 26.dp else 18.dp),
                    color = palette.accent,
                    fontFamily = TerminalFontFamily,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
                SelectionContainer {
                    Text(
                        text = annotated(item.text, palette, uriHandler),
                        modifier = Modifier.weight(1f),
                        color = if (item.checked == true) palette.textSecondary else palette.textPrimary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textDecoration = if (item.checked == true) TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableBlock(block: Markdown.Block.Table, palette: TermAgentPalette, uriHandler: UriHandler) {
    // A single column of rows, separated by hairlines, with a tinted header — enough to read a
    // table on a phone without pulling in a scroll container.
    val columns = maxOf(
        block.header.size,
        block.alignments.size,
        block.rows.maxOfOrNull { it.size } ?: 0,
    ).coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.divider, RoundedCornerShape(8.dp)),
    ) {
        TableRow(
            cells = block.header,
            alignments = block.alignments,
            columns = columns,
            palette = palette,
            uriHandler = uriHandler,
            header = true,
        )
        for (row in block.rows) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
            TableRow(
                cells = row,
                alignments = block.alignments,
                columns = columns,
                palette = palette,
                uriHandler = uriHandler,
                header = false,
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    alignments: List<Markdown.ColumnAlign>,
    columns: Int,
    palette: TermAgentPalette,
    uriHandler: UriHandler,
    header: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (header) palette.surfaceElevated else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (column in 0 until columns) {
            val cell = cells.getOrNull(column).orEmpty()
            val align = alignments.getOrNull(column) ?: Markdown.ColumnAlign.Start
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                contentAlignment = align.toAlignment(),
            ) {
                SelectionContainer {
                    Text(
                        text = annotated(cell, palette, uriHandler),
                        color = if (header) palette.textPrimary else palette.textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = align.toTextAlign(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleBlock(palette: TermAgentPalette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .height(1.dp)
            .background(palette.divider),
    )
}

/** Fenced code block: a dark card with a language label and a copy button. */
@Composable
private fun CodeBlock(code: String, language: String) {
    val palette = TermAgentTheme.colors
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.terminalBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.terminalHeader)
                .padding(start = Spacing.md, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language.ifBlank { stringResource(R.string.message_code) },
                modifier = Modifier.weight(1f),
                color = palette.textFaint,
                fontFamily = TerminalFontFamily,
                fontSize = 11.sp,
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                minWidth = 32.dp,
                minHeight = 32.dp,
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Copy,
                    contentDescription = stringResource(R.string.message_copy_code),
                    modifier = Modifier.size(15.dp),
                    tint = palette.textSecondary,
                )
            }
        }
        SelectionContainer {
            Text(
                text = code.trimEnd('\n'),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                color = palette.terminalText,
                fontFamily = TerminalFontFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

/** Memoised inline annotation for [text], re-resolved only when the text or theme changes. */
@Composable
private fun annotated(text: String, palette: TermAgentPalette, uriHandler: UriHandler): AnnotatedString =
    remember(text, palette, uriHandler) {
        Markdown.annotate(
            text = text,
            codeBackground = palette.surfaceElevated,
            linkColor = palette.accent,
            onLink = { url -> runCatching { uriHandler.openUri(url) } },
        )
    }

private val BULLETS = listOf("•", "◦", "▪")

private fun Markdown.ColumnAlign.toTextAlign(): TextAlign = when (this) {
    Markdown.ColumnAlign.Start -> TextAlign.Start
    Markdown.ColumnAlign.Center -> TextAlign.Center
    Markdown.ColumnAlign.End -> TextAlign.End
}

private fun Markdown.ColumnAlign.toAlignment(): Alignment = when (this) {
    Markdown.ColumnAlign.Start -> Alignment.CenterStart
    Markdown.ColumnAlign.Center -> Alignment.Center
    Markdown.ColumnAlign.End -> Alignment.CenterEnd
}

// ---------------------------------------------------------------------- parsing

/**
 * Block-level parser plus inline styler.
 *
 * The parser is a single left-to-right scan over the lines: a fenced code block wins over
 * everything, then blank lines / rules / headings / tables / quotes / lists, and anything left
 * over accumulates into a paragraph.
 */
object Markdown {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class Code(val code: String, val language: String) : Block
        data class Quote(val blocks: List<Block>) : Block
        data class ListBlock(val items: List<Item>) : Block
        data object Rule : Block
        data class Table(
            val header: List<String>,
            val alignments: List<ColumnAlign>,
            val rows: List<List<String>>,
        ) : Block
    }

    enum class ColumnAlign { Start, Center, End }

    /**
     * One list entry.
     *
     * [level] is the nesting depth relative to the list's first item (0 = outermost); the model
     * is deliberately flat — each item carries its own marker metadata — which keeps mixed
     * ordered/unordered and nested lists from needing a tree.
     */
    data class Item(
        val text: String,
        val level: Int,
        val ordered: Boolean,
        val number: Int,
        /** `null` when the item is not a task; `true`/`false` for `- [x]` / `- [ ]`. */
        val checked: Boolean? = null,
    )

    private val FENCE = Regex("^```(.*)$")
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*$")
    private val RULE = Regex("^(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$")
    private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")
    private val TABLE_DELIM = Regex("^\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?$")

    /** Split [source] into blocks. Unterminated fences are surfaced as code, never dropped. */
    fun parse(source: String): List<Block> {
        val blocks = ArrayList<Block>()
        val lines = source.lines()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()

            // 1. Fenced code — takes precedence over every other construct.
            val fence = FENCE.find(line)
            if (fence != null) {
                flushParagraph()
                val language = fence.groupValues[1].trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && FENCE.find(lines[i].trim()) == null) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++ // consume the closing fence
                blocks += Block.Code(code.toString(), language)
                continue
            }

            // 2. Blank line — ends the open paragraph.
            if (line.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            // 3. Horizontal rule.
            if (RULE.matches(line)) {
                flushParagraph()
                blocks += Block.Rule
                i++
                continue
            }

            // 4. Heading.
            val heading = HEADING.find(line)
            if (heading != null) {
                flushParagraph()
                blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
                i++
                continue
            }

            // 5. Table — a header row followed by a delimiter row.
            if (line.contains('|') && i + 1 < lines.size && TABLE_DELIM.matches(lines[i + 1].trim())) {
                flushParagraph()
                val (table, next) = parseTable(lines, i)
                blocks += table
                i = next
                continue
            }

            // 6. Blockquote — recurse so lists/tables nested inside are still rendered.
            if (line.startsWith(">")) {
                flushParagraph()
                val quoted = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoted.append(lines[i].trim().removePrefix(">").removePrefix(" ")).append('\n')
                    i++
                }
                blocks += Block.Quote(parse(quoted.toString()))
                continue
            }

            // 7. List.
            if (BULLET.matches(raw) || ORDERED.matches(raw)) {
                flushParagraph()
                val (list, next) = parseList(lines, i)
                blocks += list
                i = next
                continue
            }

            // 8. Anything else is prose.
            paragraph.append(raw).append('\n')
            i++
        }

        flushParagraph()
        return blocks
    }

    private fun parseTable(lines: List<String>, start: Int): Pair<Block.Table, Int> {
        val header = splitRow(lines[start])
        val alignments = splitRow(lines[start + 1]).map { cell ->
            val left = cell.startsWith(":")
            val right = cell.endsWith(":")
            when {
                left && right -> ColumnAlign.Center
                right -> ColumnAlign.End
                else -> ColumnAlign.Start
            }
        }

        val rows = ArrayList<List<String>>()
        var i = start + 2
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || !line.contains('|')) break
            rows += splitRow(line)
            i++
        }
        return Block.Table(header, alignments, rows) to i
    }

    private fun splitRow(line: String): List<String> =
        line.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split('|')
            .map { it.trim() }

    private fun parseList(lines: List<String>, start: Int): Pair<Block.ListBlock, Int> {
        val items = ArrayList<Item>()
        var baseIndent = -1
        var i = start

        while (i < lines.size) {
            val raw = lines[i]
            val bullet = BULLET.find(raw)
            val ordered = ORDERED.find(raw)

            when {
                bullet != null -> {
                    val indent = indentWidth(bullet.groupValues[1])
                    if (baseIndent < 0) baseIndent = indent
                    val content = bullet.groupValues[3]
                    val task = TASK.find(content)
                    items += Item(
                        text = task?.groupValues?.get(2) ?: content,
                        level = levelOf(indent - baseIndent),
                        ordered = false,
                        number = 0,
                        checked = task?.let { it.groupValues[1].equals("x", ignoreCase = true) },
                    )
                    i++
                }

                ordered != null -> {
                    val indent = indentWidth(ordered.groupValues[1])
                    if (baseIndent < 0) baseIndent = indent
                    items += Item(
                        text = ordered.groupValues[3],
                        level = levelOf(indent - baseIndent),
                        ordered = true,
                        number = ordered.groupValues[2].toIntOrNull() ?: 1,
                    )
                    i++
                }

                // An indented, non-marked line continues the previous item (soft wrap).
                items.isNotEmpty() && raw.firstOrNull()?.isWhitespace() == true -> {
                    val last = items.removeAt(items.lastIndex)
                    items += last.copy(text = last.text + " " + raw.trim())
                    i++
                }

                else -> break
            }
        }

        return Block.ListBlock(items) to i
    }

    private fun indentWidth(whitespace: String): Int =
        whitespace.fold(0) { acc, ch -> acc + if (ch == '\t') 4 else 1 }

    /** Map a raw indentation delta onto a 0-based nesting level (2 spaces per level, capped). */
    private fun levelOf(delta: Int): Int = (delta / 2).coerceIn(0, 5)

    // ------------------------------------------------------------------ inline styling

    /**
     * Apply inline styling to a run of text.
     *
     * A hand-rolled scanner (rather than regex) is used because it nests correctly — bold that
     * contains a code span, a link whose label is bold — and never mangles an unbalanced marker.
     */
    fun annotate(
        text: String,
        codeBackground: Color,
        linkColor: Color,
        onLink: (String) -> Unit = {},
    ): AnnotatedString = buildAnnotatedString {
        appendInline(this, text, codeBackground, linkColor, onLink)
    }

    private fun appendInline(
        builder: AnnotatedString.Builder,
        text: String,
        codeBackground: Color,
        linkColor: Color,
        onLink: (String) -> Unit,
    ) {
        val linkStyles = TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        )
        var i = 0
        while (i < text.length) {
            when {
                // Image ![alt](url) — rendered as a labelled link (there is no image pipeline).
                text.startsWith("![", i) -> {
                    val parsed = parseLink(text, i + 1)
                    if (parsed != null) {
                        val (label, url, end) = parsed
                        builder.withLink(link(url, linkStyles, onLink)) {
                            append(if (label.isBlank()) "🖼 $url" else "🖼 $label")
                        }
                        i = end
                    } else {
                        builder.append("!["); i += 2
                    }
                }

                // Link [label](url).
                text[i] == '[' -> {
                    val parsed = parseLink(text, i)
                    if (parsed != null) {
                        val (label, url, end) = parsed
                        builder.withLink(link(url, linkStyles, onLink)) {
                            appendInline(this, label, codeBackground, linkColor, onLink)
                        }
                        i = end
                    } else {
                        builder.append('['); i++
                    }
                }

                text.startsWith("**", i) -> i = emphasize(builder, text, i, "**", SpanStyle(fontWeight = FontWeight.Bold), codeBackground, linkColor, onLink)
                text.startsWith("__", i) -> i = emphasize(builder, text, i, "__", SpanStyle(fontWeight = FontWeight.Bold), codeBackground, linkColor, onLink)
                text.startsWith("~~", i) -> i = emphasize(builder, text, i, "~~", SpanStyle(textDecoration = TextDecoration.LineThrough), codeBackground, linkColor, onLink)

                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > 0) {
                        builder.withStyle(SpanStyle(fontFamily = TerminalFontFamily, background = codeBackground)) {
                            append(" " + text.substring(i + 1, end) + " ")
                        }
                        i = end + 1
                    } else {
                        builder.append('`'); i++
                    }
                }

                // Emphasis with a single marker. `_` only counts at a word boundary so that
                // identifiers such as `foo_bar_baz` survive untouched.
                text[i] == '*' -> i = emphasize(builder, text, i, "*", SpanStyle(fontStyle = FontStyle.Italic), codeBackground, linkColor, onLink)
                text[i] == '_' && isWordBoundary(text, i) ->
                    i = emphasize(builder, text, i, "_", SpanStyle(fontStyle = FontStyle.Italic), codeBackground, linkColor, onLink)

                else -> {
                    builder.append(text[i]); i++
                }
            }
        }
    }

    /**
     * Emit `marker … marker` applying [style], recursing so the inner run keeps its own styling.
     * Returns the index just past the closing marker, or just past the opening one when there is
     * no closer (an unbalanced marker is rendered literally).
     */
    private fun emphasize(
        builder: AnnotatedString.Builder,
        text: String,
        start: Int,
        marker: String,
        style: SpanStyle,
        codeBackground: Color,
        linkColor: Color,
        onLink: (String) -> Unit,
    ): Int {
        val inner = start + marker.length
        val end = text.indexOf(marker, inner)
        if (end <= inner) {
            builder.append(marker)
            return inner
        }
        builder.withStyle(style) {
            appendInline(this, text.substring(inner, end), codeBackground, linkColor, onLink)
        }
        return end + marker.length
    }

    /** Parse `[label](url)` starting at the `[`, returning `(label, url, endExclusive)`. */
    private fun parseLink(text: String, open: Int): Triple<String, String, Int>? {
        if (open >= text.length || text[open] != '[') return null
        val close = text.indexOf(']', open + 1)
        if (close < 0 || close + 1 >= text.length || text[close + 1] != '(') return null
        val end = text.indexOf(')', close + 2)
        if (end < 0) return null

        val label = text.substring(open + 1, close)
        // Strip an optional title: [label](url "title").
        val url = text.substring(close + 2, end).trim().substringBefore(' ').trim()
        if (url.isEmpty()) return null
        return Triple(label, url, end + 1)
    }

    private fun link(url: String, styles: TextLinkStyles, onLink: (String) -> Unit): LinkAnnotation.Url =
        LinkAnnotation.Url(
            url = url,
            styles = styles,
            linkInteractionListener = object : LinkInteractionListener {
                override fun onClick(link: LinkAnnotation) {
                    (link as? LinkAnnotation.Url)?.url?.let(onLink)
                }
            },
        )

    private fun isWordBoundary(text: String, index: Int): Boolean =
        index == 0 || !text[index - 1].isLetterOrDigit()
}
