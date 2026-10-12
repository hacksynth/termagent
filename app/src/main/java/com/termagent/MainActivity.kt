package com.termagent

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import com.termagent.R
import com.termagent.data.BootstrapState
import com.termagent.ui.ChatUiState
import com.termagent.ui.ChatViewModel
import com.termagent.ui.components.EmbeddedTerminal
import com.termagent.ui.components.SessionDrawer
import com.termagent.ui.screens.ChatScreen
import com.termagent.ui.screens.SettingsScreen
import com.termagent.ui.theme.Spacing
import com.termagent.ui.theme.TermAgentTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: ChatViewModel = viewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            TermAgentTheme(mode = state.themeMode, style = state.themeStyle) {
                TermAgentRoot(viewModel = viewModel, state = state)
            }
        }
    }
}

/** Which full-screen surface is showing on top of the chat. */
private enum class Surface { Chat, Settings, Terminal }

@Composable
private fun TermAgentRoot(
    viewModel: ChatViewModel,
    state: ChatUiState,
) {
    val palette = TermAgentTheme.colors
    var surface by remember { mutableStateOf(Surface.Chat) }
    var drawerOpen by remember { mutableStateOf(false) }

    // Navigation is otherwise invisible from outside the process, which makes UI regressions
    // (e.g. a drawer covered by its own scrim, so every tap just dismissed it) impossible to
    // diagnose without a screenshot. One line per state change keeps it observable.
    LaunchedEffect(surface, drawerOpen) {
        Log.i(NAV_TAG, "surface=$surface drawerOpen=$drawerOpen")
    }

    BackHandler(enabled = drawerOpen || surface != Surface.Chat) {
        if (drawerOpen) drawerOpen = false else surface = Surface.Chat
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding(),
    ) {
        when (surface) {
            Surface.Chat -> ChatScreen(
                state = state,
                onInputChange = viewModel::onInputChange,
                onSend = { viewModel.send() },
                onStop = viewModel::stop,
                onNewChat = viewModel::newConversation,
                onOpenDrawer = { drawerOpen = true },
                onOpenSettings = { surface = Surface.Settings },
                onInstallBootstrap = viewModel::installBootstrap,
                onDismissError = viewModel::dismissError,
                onAttachFiles = viewModel::attachFiles,
                onRemoveAttachment = viewModel::removePendingAttachment,
            )

            Surface.Settings -> SettingsScreen(
                state = state,
                onBack = { surface = Surface.Chat },
                onUpdateSettings = { baseUrl, apiKey, model, temperature, maxTokens, systemPrompt, allowExec ->
                    viewModel.updateSettings(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        systemPrompt = systemPrompt,
                        allowCommandExecution = allowExec,
                    )
                },
                onSelectProvider = viewModel::selectProvider,
                onDiscoverModels = viewModel::discoverModels,
                onSetTheme = viewModel::setThemeMode,
                onSetThemeStyle = viewModel::setThemeStyle,
                onResetData = viewModel::deleteAllConversations,
            )

            Surface.Terminal -> TerminalSurface(onBack = { surface = Surface.Chat })
        }

        // ------------------------------------------------------------ drawer + scrim
        //
        // ORDER MATTERS. Children of a Box are painted in declaration order, so the scrim must
        // come *first* to sit *underneath* the drawer. Hit-testing runs from the topmost child
        // down, so declaring the scrim after the drawer made it swallow every tap inside the
        // drawer as well — tapping any row just dismissed the drawer instead of activating it.
        if (drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { drawerOpen = false },
            )
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it }),
        ) {
            SessionDrawer(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight(),
                conversations = state.conversations,
                selectedId = state.current?.id,
                bootstrapReady = state.bootstrap is BootstrapState.Installed,
                onNewChat = {
                    viewModel.newConversation()
                    drawerOpen = false
                },
                onSelect = { id ->
                    viewModel.selectConversation(id)
                    drawerOpen = false
                },
                onDelete = viewModel::deleteConversation,
                onOpenSettings = {
                    drawerOpen = false
                    surface = Surface.Settings
                },
                onOpenTerminal = {
                    drawerOpen = false
                    surface = Surface.Terminal
                },
            )
        }
    }
}

/**
 * Full-screen interactive terminal.
 *
 * This renders the *same* PTY the agent uses, so a user can take over manually (for a
 * `sudo`-style prompt, an interactive installer, or just to poke around) and the agent
 * picks the session back up afterwards.
 */
@Composable
private fun TerminalSurface(onBack: () -> Unit) {
    val palette = TermAgentTheme.colors

    // Bumped by the toolbar button. `TerminalView` is a plain native view, so nothing else in
    // Compose will ever reopen the IME for it — this is the user's guaranteed way in when an
    // OEM/third-party keyboard ignores the tap-driven request.
    var keyboardRequest by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                minWidth = 40.dp,
                minHeight = 40.dp,
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = stringResource(R.string.common_back),
                    modifier = Modifier.size(20.dp),
                    tint = palette.textPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.terminal_title),
                    color = palette.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.terminal_subtitle),
                    color = palette.textFaint,
                    fontSize = 11.sp,
                )
            }

            TextButton(
                text = stringResource(R.string.terminal_keyboard),
                onClick = { keyboardRequest++ },
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm),
        ) {
            EmbeddedTerminal(
                modifier = Modifier.fillMaxSize(),
                keyboardRequest = keyboardRequest,
            )
        }

        // Navigation-bar clearance only. There is deliberately no `imePadding()` here: the
        // activity declares `windowSoftInputMode="adjustResize"` (and upstream Termux relies on
        // exactly the same contract), so the window already shrinks by the keyboard height and
        // `TerminalView.onSizeChanged()` reflows the PTY to the reduced row count. Reserving the
        // IME inset a second time would shrink the terminal by the keyboard height twice,
        // leaving a sliver of screen at the exact moment the user is typing into it.
        Spacer(Modifier.size(Spacing.sm))
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.xs),
        )
    }
}

/** Log tag for navigation state changes (see the LaunchedEffect in TermAgentRoot). */
private const val NAV_TAG = "TermAgentNav"
