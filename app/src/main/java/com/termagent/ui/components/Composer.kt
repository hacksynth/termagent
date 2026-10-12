package com.termagent.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Stopwatch
import com.termagent.R
import com.termagent.data.Attachment
import com.termagent.ui.theme.Spacing
import com.termagent.ui.theme.TermAgentTheme
import com.termagent.ui.theme.TerminalFontFamily

/**
 * The bottom input bar: a multi-line field plus send/stop.
 *
 * Built on `BasicTextField` rather than the Miuix `TextField` because the composer needs
 * multi-line growth, an IME "send" action and full control over the send button state.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    enabled: Boolean,
    modelLabel: String,
    hint: String?,
    modifier: Modifier = Modifier,
    /** Files picked but not sent yet; rendered as a strip above the text field. */
    attachments: List<Attachment> = emptyList(),
    isImportingAttachments: Boolean = false,
    /** Attaching is allowed before a model is configured — only sending needs one. */
    allowAttachments: Boolean = true,
    onAddAttachment: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
) {
    val palette = TermAgentTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        if (hint != null) {
            Text(
                text = hint,
                modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
                color = palette.textFaint,
                fontSize = 11.sp,
            )
        }

        PendingAttachmentStrip(
            attachments = attachments,
            importing = isImportingAttachments,
            onRemove = onRemoveAttachment,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )

        if (attachments.isNotEmpty()) {
            Text(
                text = stringResource(R.string.attach_pending_count, attachments.size),
                modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
                color = palette.textFaint,
                fontSize = 11.sp,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(palette.surface)
                .padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = Spacing.md),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.composer_placeholder),
                        color = palette.textFaint,
                        fontSize = 15.sp,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 22.dp, max = 160.dp),
                    enabled = enabled,
                    textStyle = TextStyle(
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    ),
                    cursorBrush = SolidColor(palette.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { if (value.isNotBlank() && !isStreaming) onSend() },
                    ),
                )
            }

            Spacer(Modifier.width(Spacing.xs))

            if (isStreaming) {
                IconButton(
                    onClick = onStop,
                    minWidth = 40.dp,
                    minHeight = 40.dp,
                    backgroundColor = palette.danger,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Stopwatch,
                        contentDescription = stringResource(R.string.composer_stop),
                        modifier = Modifier.size(18.dp),
                        tint = Color.White,
                    )
                }
            } else {
                // The "+" is only useful while idle: a turn in flight owns the staged list.
                if (allowAttachments) {
                    IconButton(
                        onClick = onAddAttachment,
                        enabled = !isImportingAttachments,
                        minWidth = 40.dp,
                        minHeight = 40.dp,
                        backgroundColor = Color.Transparent,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Add,
                            contentDescription = stringResource(R.string.attach_open),
                            modifier = Modifier.size(20.dp),
                            tint = if (isImportingAttachments) palette.textFaint else palette.textSecondary,
                        )
                    }
                    Spacer(Modifier.width(Spacing.xs))
                }

                // A message may consist of attachments alone, so the send button lights up for
                // either a non-blank draft *or* a staged file.
                val canSend = enabled && (value.isNotBlank() || attachments.isNotEmpty())

                IconButton(
                    onClick = { if (canSend) onSend() },
                    enabled = canSend,
                    minWidth = 40.dp,
                    minHeight = 40.dp,
                    backgroundColor = if (canSend) palette.accent else palette.surfaceElevated,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Send,
                        contentDescription = stringResource(R.string.composer_send),
                        modifier = Modifier.size(18.dp),
                        tint = if (canSend) palette.onAccent else palette.textFaint,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm, start = Spacing.xs, end = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = modelLabel,
                color = palette.textFaint,
                fontFamily = TerminalFontFamily,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.composer_enter_to_send),
                color = palette.textFaint,
                fontSize = 11.sp,
            )
        }
    }
}
