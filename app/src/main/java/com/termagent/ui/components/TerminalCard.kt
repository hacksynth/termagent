package com.termagent.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import androidx.compose.foundation.background
import com.termagent.data.TerminalBlock
import com.termagent.R
import com.termagent.ui.theme.Spacing
import com.termagent.ui.theme.TermAgentTheme
import com.termagent.ui.theme.TerminalFontFamily

/**
 * A collapsible "terminal card": the command the agent ran, its live output, and its exit
 * status — the visual equivalent of the terminal blocks in a desktop agent UI.
 */
@Composable
fun TerminalCard(
    block: TerminalBlock,
    modifier: Modifier = Modifier,
) {
    val palette = TermAgentTheme.colors
    val clipboard = LocalClipboardManager.current

    // Expanded while running so the user watches the command work; collapsible afterwards.
    var expanded by remember(block.toolCallId) { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    val output = remember(block.output) { Ansi.strip(block.output) }

    // Follow the tail while the command is still running.
    LaunchedEffect(output, expanded) {
        if (expanded && block.isRunning && scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.terminalBackground)
            .animateContentSize(),
    ) {
        // ---------------------------------------------------------------- header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.terminalHeader)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(block)

            Spacer(Modifier.width(Spacing.sm))

            Text(
                text = "$ ${block.command.ifBlank { block.toolName }}",
                modifier = Modifier.weight(1f),
                color = palette.terminalText,
                fontFamily = TerminalFontFamily,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )

            if (block.exitCode != null && !block.isRunning) {
                Text(
                    text = stringResource(R.string.terminal_exit_code, block.exitCode),
                    color = if (block.exitCode == 0) palette.success else palette.danger,
                    fontFamily = TerminalFontFamily,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(Spacing.xs))
            }

            IconButton(
                onClick = { clipboard.setText(AnnotatedString(block.command)) },
                minWidth = 32.dp,
                minHeight = 32.dp,
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Copy,
                    contentDescription = stringResource(R.string.terminal_copy_command),
                    modifier = Modifier.size(16.dp),
                    tint = palette.textSecondary,
                )
            }

            IconButton(
                onClick = { expanded = !expanded },
                minWidth = 32.dp,
                minHeight = 32.dp,
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    imageVector = if (expanded) {
                        MiuixIcons.Regular.ExpandLess
                    } else {
                        MiuixIcons.Regular.ExpandMore
                    },
                    contentDescription = if (expanded) {
                        stringResource(R.string.terminal_collapse)
                    } else {
                        stringResource(R.string.terminal_expand)
                    },
                    modifier = Modifier.size(16.dp),
                    tint = palette.textSecondary,
                )
            }
        }

        HorizontalDivider(color = palette.terminalBorder)

        // ---------------------------------------------------------------- body
        if (expanded) {
            if (output.isBlank()) {
                Text(
                    text = stringResource(
                        if (block.isRunning) R.string.terminal_running else R.string.terminal_no_output,
                    ),
                    modifier = Modifier.padding(Spacing.md),
                    color = palette.textFaint,
                    fontFamily = TerminalFontFamily,
                    fontSize = 12.sp,
                )
            } else {
                SelectionContainer {
                    Text(
                        text = output,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(scrollState)
                            .padding(Spacing.md),
                        color = palette.terminalText,
                        fontFamily = TerminalFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(block: TerminalBlock) {
    val palette = TermAgentTheme.colors
    val color = when {
        block.isRunning -> palette.warning
        block.exitCode == 0 -> palette.success
        else -> palette.danger
    }

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color),
    )
}

/**
 * Strips ANSI/VT escape sequences so captured command output renders as clean text.
 *
 * Covers CSI (`ESC [ … final-byte`), OSC (`ESC ] … BEL/ST`) and the two-character
 * `ESC x` forms — enough for anything a build script realistically emits.
 */
object Ansi {

    private val PATTERN = Regex(
        "\u001B\\[[0-9;?]*[ -/]*[@-~]" + // CSI
            "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" + // OSC
            "|\u001B[@-Z\\\\-_]" + // 2-byte escapes
            "|[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]", // other control chars (keep \t \n \r)
    )

    fun strip(text: String): String = PATTERN.replace(text, "")
}

/** Small labelled chip used for tool names and metadata. */
@Composable
fun MetaChip(
    text: String,
    icon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = TermAgentTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.surfaceElevated)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Text(text = text, color = palette.textSecondary, fontSize = 11.sp)
    }
}
