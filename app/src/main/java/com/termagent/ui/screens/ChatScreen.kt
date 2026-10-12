package com.termagent.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Sidebar
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import com.termagent.R
import com.termagent.appContainer
import com.termagent.data.Attachment
import com.termagent.data.BootstrapState
import com.termagent.data.ChatEntry
import com.termagent.ui.ChatUiState
import com.termagent.ui.components.AttachmentSourceDialog
import com.termagent.ui.components.Composer
import com.termagent.ui.components.ImageViewerDialog
import com.termagent.ui.components.MessageBubble
import com.termagent.ui.theme.Spacing
import com.termagent.ui.theme.TermAgentTheme
import com.termagent.ui.theme.TerminalFontFamily

/**
 * The main chat shell: title bar, streaming message list, composer, plus onboarding and
 * bootstrap-install states.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onInstallBootstrap: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    onAttachFiles: (List<Uri>) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
) {
    val palette = TermAgentTheme.colors
    val listState = rememberLazyListState()

    // ------------------------------------------------------------ attachment plumbing
    //
    // Both pickers hand back `content://` URIs, which the view model copies into `$HOME/attachments`.
    // No storage permission is declared: the Photo Picker and the Storage Access Framework grant
    // access to exactly the picked items, which is the only access the app needs.
    var showSourcePicker by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf<Attachment?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS_PER_PICK),
    ) { uris -> if (uris.isNotEmpty()) onAttachFiles(uris) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onAttachFiles(uris) }

    // Setting up the shell needs no network and no user input now that the bootstrap ships
    // inside the APK, so it starts by itself (Termux does the same on first launch). Keyed on
    // Unit: it fires once per entry into this screen, and a failure is NOT retried in a loop —
    // the panel offers an explicit Retry instead.
    LaunchedEffect(Unit) {
        if (state.bootstrap is BootstrapState.NotInstalled) {
            onInstallBootstrap()
        }
    }

    // Follow the tail as tokens stream in — unless the user scrolled up to read.
    val lastIndex = state.entries.lastIndex
    LaunchedEffect(lastIndex, state.entries.lastOrNull()?.text?.length) {
        if (lastIndex >= 0) {
            runCatching { listState.animateScrollToItem(lastIndex) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        ChatTopBar(
            title = state.current?.takeUnless { it.isUntitled }?.title
                ?: stringResource(R.string.chat_untitled),
            streaming = state.isStreaming,
            onOpenDrawer = onOpenDrawer,
            onNewChat = onNewChat,
            onOpenSettings = onOpenSettings,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                // The shell comes first on purpose. It is the app's backend and is completely
                // independent of the model, so it can be installed and exercised (Terminal tab)
                // before any API credentials exist. Gating it behind "configure a model" would
                // make the Install button unreachable until a key was entered.
                // NotInstalled is included here (rather than only Installing) because the
                // install is kicked off by the effect above, so this state is transient.
                state.bootstrap is BootstrapState.NotInstalled ||
                    state.bootstrap is BootstrapState.Error ||
                    state.bootstrap is BootstrapState.Installing ->
                    BootstrapPanel(
                        state = state.bootstrap,
                        onInstall = onInstallBootstrap,
                        onOpenSettings = onOpenSettings,
                    )

                !state.isConfigured -> OnboardingPanel(onOpenSettings = onOpenSettings)
                state.entries.isEmpty() -> EmptyStatePanel()
                else -> MessageList(
                    state = state,
                    listState = listState,
                    onOpenImage = { viewingImage = it },
                )
            }
        }

        AnimatedVisibility(visible = state.error != null) {
            ErrorBar(message = state.error.orEmpty(), onDismiss = onDismissError)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Composer(
                value = state.input,
                onValueChange = onInputChange,
                onSend = onSend,
                onStop = onStop,
                isStreaming = state.isStreaming,
                enabled = state.isConfigured && state.bootstrap is BootstrapState.Installed,
                modelLabel = state.settings.model,
                hint = if (state.bootstrap !is BootstrapState.Installed) {
                    stringResource(R.string.composer_shell_missing)
                } else {
                    null
                },
                attachments = state.pendingAttachments,
                isImportingAttachments = state.isImportingAttachments,
                onAddAttachment = { showSourcePicker = true },
                onRemoveAttachment = onRemoveAttachment,
            )
        }
    }

    if (showSourcePicker) {
        AttachmentSourceDialog(
            onPickPhotos = {
                showSourcePicker = false
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onPickFiles = {
                showSourcePicker = false
                filePicker.launch(arrayOf("*/*"))
            },
            onDismiss = { showSourcePicker = false },
        )
    }

    viewingImage?.let { attachment ->
        ImageViewerDialog(attachment = attachment, onDismiss = { viewingImage = null })
    }
}

/** Cap per pick, so one tap cannot stage 200 photos and blow up the next request. */
private const val MAX_PHOTOS_PER_PICK = 10

@Composable
private fun ChatTopBar(
    title: String,
    streaming: Boolean,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = TermAgentTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onOpenDrawer,
            minWidth = 40.dp,
            minHeight = 40.dp,
            backgroundColor = Color.Transparent,
        ) {
            Icon(
                imageVector = MiuixIcons.Basic.Sidebar,
                contentDescription = stringResource(R.string.chat_open_sidebar),
                modifier = Modifier.size(20.dp),
                tint = palette.textPrimary,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (streaming) {
                Text(
                    text = stringResource(R.string.chat_working),
                    color = palette.accent,
                    fontSize = 11.sp,
                )
            }
        }

        IconButton(
            onClick = onNewChat,
            minWidth = 40.dp,
            minHeight = 40.dp,
            backgroundColor = Color.Transparent,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Add,
                contentDescription = stringResource(R.string.chat_new_chat),
                modifier = Modifier.size(20.dp),
                tint = palette.textPrimary,
            )
        }

        IconButton(
            onClick = onOpenSettings,
            minWidth = 40.dp,
            minHeight = 40.dp,
            backgroundColor = Color.Transparent,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Settings,
                contentDescription = stringResource(R.string.chat_settings),
                modifier = Modifier.size(20.dp),
                tint = palette.textPrimary,
            )
        }
    }
}

@Composable
private fun MessageList(
    state: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenImage: (Attachment) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            top = Spacing.sm,
            bottom = Spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        items(items = state.entries, key = { it.id }) { entry: ChatEntry ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                    MessageBubble(entry = entry, onOpenImage = onOpenImage)
                }
            }
        }

        if (state.isStreaming) {
            item(key = "streaming-indicator") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InfiniteProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = TermAgentTheme.colors.accent,
                        size = 16.dp,
                        strokeWidth = 2.dp,
                        orbitingDotSize = 2.5.dp,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(R.string.chat_thinking),
                        color = TermAgentTheme.colors.textFaint,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- states

@Composable
private fun EmptyStatePanel() {
    val palette = TermAgentTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(Spacing.xl).widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "T", color = palette.onAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = stringResource(R.string.chat_empty_title),
                color = palette.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.chat_empty_body),
                color = palette.textSecondary,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(Spacing.lg))
            for (suggestion in SUGGESTIONS) {
                Text(
                    text = "•  ${stringResource(suggestion)}",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    color = palette.textFaint,
                    fontFamily = TerminalFontFamily,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** Starter prompts, kept as resource ids so they follow the UI language. */
@StringRes
private val SUGGESTIONS = listOf(
    R.string.chat_suggestion_list_files,
    R.string.chat_suggestion_disk_space,
    R.string.chat_suggestion_processes,
    R.string.chat_suggestion_script,
)

@Composable
private fun OnboardingPanel(onOpenSettings: () -> Unit) {
    val palette = TermAgentTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.padding(Spacing.xl).widthIn(max = 520.dp).fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(
                color = palette.surface,
                contentColor = palette.textPrimary,
            ),
        ) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Text(
                    text = stringResource(R.string.chat_onboarding_title),
                    color = palette.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_onboarding_body),
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(Spacing.lg))
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.chat_open_settings),
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BootstrapPanel(
    state: BootstrapState,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = TermAgentTheme.colors
    val failed = state is BootstrapState.Error
    // NotInstalled means "about to start" — the caller auto-triggers the install — so it is
    // presented as work in progress rather than as a question.
    val working = state is BootstrapState.Installing || state is BootstrapState.NotInstalled

    // The ABI is a property of the bundled asset, not of the locale: read it once per
    // composition rather than holding a translated copy anywhere.
    val context = LocalContext.current
    val abi = remember(context) {
        context.appContainer.bootstrap.bundledAssetName()
            ?.removePrefix("bootstrap-")
            ?.removeSuffix(".zip")
            .orEmpty()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.padding(Spacing.xl).widthIn(max = 520.dp).fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(
                color = palette.surface,
                contentColor = palette.textPrimary,
            ),
        ) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.bootstrap_title),
                        modifier = Modifier.weight(1f),
                        color = palette.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    when {
                        failed -> Icon(
                            imageVector = MiuixIcons.Regular.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = palette.danger,
                        )
                        working -> Icon(
                            imageVector = MiuixIcons.Regular.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = palette.accent,
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = when (state) {
                        BootstrapState.NotInstalled ->
                            stringResource(R.string.bootstrap_unpacking)
                        is BootstrapState.Installing -> state.message
                        is BootstrapState.Error ->
                            stringResource(R.string.bootstrap_failed, state.message)
                        BootstrapState.Installed -> stringResource(R.string.bootstrap_installed)
                    },
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )

                if (working) {
                    Spacer(Modifier.height(Spacing.md))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // The install is automatic; a button is only needed to recover from a failure.
                if (failed) {
                    Spacer(Modifier.height(Spacing.lg))
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White,
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = stringResource(R.string.bootstrap_retry),
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.bootstrap_footnote, abi),
                    color = palette.textFaint,
                    fontSize = 11.sp,
                )

                // Escape hatch: the model can be configured without the shell, and vice versa.
                if (!working) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = stringResource(R.string.bootstrap_configure_model_first),
                            onClick = onOpenSettings,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    val palette = TermAgentTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.danger.copy(alpha = 0.15f))
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            color = palette.danger,
            fontSize = 12.sp,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        TextButton(text = stringResource(R.string.common_dismiss), onClick = onDismiss)
    }
}
