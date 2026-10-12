package com.termagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.ScreenCapture
import com.termagent.R
import com.termagent.data.Conversation
import com.termagent.ui.theme.Spacing
import com.termagent.ui.theme.TermAgentTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The left-hand rail: conversation list, "new chat", and the settings/terminal entry points.
 * Mirrors the ChatGPT desktop sidebar.
 */
@Composable
fun SessionDrawer(
    conversations: List<Conversation>,
    selectedId: String?,
    bootstrapReady: Boolean,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TermAgentTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.sidebar),
    ) {
        // ------------------------------------------------------------------ brand
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.lg, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "T", color = palette.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.message_agent_name),
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        if (bootstrapReady) R.string.drawer_shell_ready else R.string.drawer_shell_missing,
                    ),
                    color = if (bootstrapReady) palette.success else palette.warning,
                    fontSize = 11.sp,
                )
            }
        }

        // ------------------------------------------------------------------ new chat
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.surface)
                .clickable(onClick = onNewChat)
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = palette.textPrimary,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(R.string.drawer_new_chat),
                color = palette.textPrimary,
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(Spacing.md))

        Text(
            text = stringResource(R.string.drawer_chats),
            modifier = Modifier.padding(start = Spacing.lg, bottom = Spacing.xs),
            color = palette.textFaint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )

        // ------------------------------------------------------------------ list
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.sm,
                end = Spacing.sm,
                bottom = Spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items = conversations, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    selected = conversation.id == selectedId,
                    onClick = { onSelect(conversation.id) },
                    onDelete = { onDelete(conversation.id) },
                )
            }
        }

        HorizontalDivider(color = palette.divider)

        // ------------------------------------------------------------------ footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            DrawerAction(
                icon = {
                    Icon(
                        imageVector = MiuixIcons.Regular.ScreenCapture,
                        contentDescription = stringResource(R.string.drawer_terminal),
                        modifier = Modifier.size(18.dp),
                        tint = palette.textSecondary,
                    )
                },
                label = stringResource(R.string.drawer_terminal),
                onClick = onOpenTerminal,
                modifier = Modifier.weight(1f),
            )
            DrawerAction(
                icon = {
                    Icon(
                        imageVector = MiuixIcons.Regular.Settings,
                        contentDescription = stringResource(R.string.drawer_settings),
                        modifier = Modifier.size(18.dp),
                        tint = palette.textSecondary,
                    )
                },
                label = stringResource(R.string.drawer_settings),
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = TermAgentTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) palette.sidebarSelected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (conversation.isUntitled) {
                    stringResource(R.string.chat_untitled)
                } else {
                    conversation.title
                },
                color = palette.textPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relativeTimeLabel(conversation.updatedAt),
                color = palette.textFaint,
                fontSize = 11.sp,
            )
        }

        // Always reachable, but visually quiet until the row is used.
        IconButton(
            onClick = onDelete,
            minWidth = 30.dp,
            minHeight = 30.dp,
            backgroundColor = Color.Transparent,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Delete,
                contentDescription = stringResource(R.string.drawer_delete_chat),
                modifier = Modifier.size(15.dp),
                tint = palette.textFaint,
            )
        }
    }
}

@Composable
private fun DrawerAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TermAgentTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.width(Spacing.sm))
        Text(text = label, color = palette.textSecondary, fontSize = 13.sp)
    }
}

private val timeFormat = SimpleDateFormat("MMM d", Locale.getDefault())

/**
 * "just now" / "12 min ago" / "3 h ago" / "Aug 4" — cheap and readable.
 *
 * `@Composable` because the three relative buckets come from resources (so they translate) while
 * the fallback stays a plain date, already localised by [Locale.getDefault].
 */
@Composable
private fun relativeTimeLabel(timestamp: Long): String {
    val delta = System.currentTimeMillis() - timestamp
    return when {
        delta < 60_000 -> stringResource(R.string.time_just_now)
        delta < 3_600_000 -> stringResource(R.string.time_minutes_ago, delta / 60_000)
        delta < 86_400_000 -> stringResource(R.string.time_hours_ago, delta / 3_600_000)
        else -> timeFormat.format(Date(timestamp))
    }
}
