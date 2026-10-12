package com.termagent.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import com.termagent.R
import com.termagent.data.Attachment
import com.termagent.data.ChatEntry
import com.termagent.data.Role
import com.termagent.ui.theme.Spacing
import com.termagent.ui.theme.TermAgentTheme
import com.termagent.ui.theme.TerminalFontFamily

/**
 * Renders one conversation entry.
 *
 * * user messages are right-aligned bubbles (the ChatGPT-desktop look);
 * * assistant messages are full-width prose with lightweight Markdown;
 * * tool entries are [TerminalCard]s.
 */
@Composable
fun MessageBubble(
    entry: ChatEntry,
    modifier: Modifier = Modifier,
    onOpenImage: (Attachment) -> Unit = {},
) {
    when (entry.role) {
        Role.User -> UserBubble(entry, modifier, onOpenImage)
        Role.Assistant -> AssistantMessage(entry, modifier)
        Role.Tool -> entry.terminal?.let { block ->
            Box(modifier = modifier.fillMaxWidth()) {
                TerminalCard(block = block)
            }
        }

        Role.System -> Unit
    }
}

/**
 * A user turn: its attachments above, the caption below.
 *
 * The two are separate blocks rather than one bubble so an image is not inset inside a coloured
 * capsule — the picture is the message, the caption reads as an annotation on it. A caption-less
 * turn (image only) renders just the attachments.
 */
@Composable
private fun UserBubble(entry: ChatEntry, modifier: Modifier, onOpenImage: (Attachment) -> Unit) {
    val palette = TermAgentTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SentAttachments(attachments = entry.attachments, onOpenImage = onOpenImage)

            if (entry.text.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.userBubble)
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                ) {
                    SelectionContainer {
                        Text(
                            text = entry.text,
                            color = palette.onUserBubble,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(entry: ChatEntry, modifier: Modifier) {
    val palette = TermAgentTheme.colors
    val clipboard = LocalClipboardManager.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentBadge()
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(R.string.message_agent_name),
                color = palette.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (entry.text.isNotBlank()) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(entry.text)) },
                    minWidth = 32.dp,
                    minHeight = 32.dp,
                    backgroundColor = Color.Transparent,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Copy,
                        contentDescription = stringResource(R.string.message_copy),
                        modifier = Modifier.size(16.dp),
                        tint = palette.textSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.width(Spacing.sm))

        if (entry.reasoning.isNotBlank()) {
            ReasoningBlock(entry.reasoning)
            Spacer(Modifier.width(Spacing.xs))
        }

        if (entry.text.isNotEmpty()) {
            MarkdownBody(
                text = entry.text,
                showCursor = entry.isStreaming,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        } else if (entry.isStreaming) {
            ThinkingIndicator()
        }

        // A turn that produced nothing at all. When we know why, that reason is shown instead
        // of an unhelpful "(no response)" — the error bar is dismissible, this is not.
        if (entry.error != null) {
            ErrorNote(entry.error)
        } else if (entry.text.isBlank() && !entry.isStreaming && entry.reasoning.isBlank()) {
            Text(
                text = stringResource(R.string.message_no_response),
                color = palette.textFaint,
                fontSize = 13.sp,
            )
        }

        UsageFooter(entry)
    }
}

/** Persistent, per-message failure reason (e.g. `Request failed (HTTP 401): …`). */
@Composable
private fun ErrorNote(message: String) {
    val palette = TermAgentTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surfaceElevated)
            .padding(Spacing.md),
    ) {
        SelectionContainer {
            Text(
                text = message,
                color = palette.danger,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

/**
 * Token / cost line under an assistant turn, filled in from the provider's usage frame and
 * the bundled pricing table. Hidden entirely while streaming or when no usage was reported.
 */
@Composable
private fun UsageFooter(entry: ChatEntry) {
    if (entry.isStreaming) return
    if (entry.promptTokens <= 0 && entry.completionTokens <= 0) return

    val palette = TermAgentTheme.colors
    // `stringResource` is not callable from inside `buildString` (that lambda is not @Composable),
    // so both pieces are resolved here and then joined with a second, translatable format.
    val usage = stringResource(R.string.message_usage, entry.promptTokens, entry.completionTokens)
    val text = if (entry.costUsd > 0) {
        stringResource(R.string.message_usage_cost, usage, "\$${formatCost(entry.costUsd)}")
    } else {
        usage
    }
    Column(modifier = Modifier.padding(top = Spacing.xs)) {
        Text(
            text = text,
            color = palette.textFaint,
            fontSize = 10.sp,
            fontFamily = TerminalFontFamily,
        )
    }
}

/** More decimals for the sub-cent costs that dominate short turns. */
private fun formatCost(cost: Double): String =
    if (cost >= 0.01) "%.4f".format(cost) else "%.6f".format(cost)

/** The "T" avatar shown next to assistant messages. */
@Composable
private fun AgentBadge() {
    val palette = TermAgentTheme.colors
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "T", color = palette.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ThinkingIndicator() {
    val palette = TermAgentTheme.colors
    Text(text = "▍", color = palette.accent, fontFamily = TerminalFontFamily, fontSize = 15.sp)
}

@Composable
private fun ReasoningBlock(text: String) {
    val palette = TermAgentTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surfaceElevated)
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) {
                    stringResource(R.string.message_reasoning)
                } else {
                    stringResource(R.string.message_reasoning_chars, text.length)
                },
                modifier = Modifier.weight(1f),
                color = palette.textSecondary,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
            )
            Icon(
                imageVector = if (expanded) {
                    MiuixIcons.Regular.ExpandLess
                } else {
                    MiuixIcons.Regular.ExpandMore
                },
                contentDescription = if (expanded) {
                    stringResource(R.string.message_reasoning_hide)
                } else {
                    stringResource(R.string.message_reasoning_show)
                },
                modifier = Modifier.size(16.dp),
                tint = palette.textSecondary,
            )
        }
        if (expanded) {
            Text(
                text = text,
                modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
                color = palette.textSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

