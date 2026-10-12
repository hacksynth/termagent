package com.termagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Refresh
import com.termagent.BuildConfig
import com.termagent.R
import com.termagent.agent.ApiConfig
import com.termagent.appContainer
import com.termagent.data.BootstrapState
import com.termagent.data.ThemeMode
import com.termagent.data.ThemeStyle
import com.termagent.provider.ProviderPreset
import com.termagent.ui.ChatUiState
import com.termagent.ui.theme.Spacing
import com.termagent.ui.theme.TermAgentPalette
import com.termagent.ui.theme.TermAgentTheme
import com.termagent.ui.theme.TerminalFontFamily
import com.termagent.ui.theme.paletteFor
import com.termagent.ui.theme.resolvesDark

/**
 * Settings: the provider catalog (178 presets ported from devx), the OpenAI-compatible
 * endpoint, model discovery, the safety switch for command execution, and the theme.
 */
@Composable
fun SettingsScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onUpdateSettings: (
        baseUrl: String?,
        apiKey: String?,
        model: String?,
        temperature: Float?,
        maxTokens: Int?,
        systemPrompt: String?,
        allowCommandExecution: Boolean?,
    ) -> Unit,
    onSelectProvider: (String) -> Unit,
    onDiscoverModels: () -> Unit,
    onSetTheme: (ThemeMode) -> Unit,
    onSetThemeStyle: (ThemeStyle) -> Unit,
    onResetData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TermAgentTheme.colors
    val scroll = rememberScrollState()

    // The bundled provider catalogue is read-only cached data, so it is read directly from
    // the app container (same pattern as EmbeddedTerminal's shell access).
    val context = LocalContext.current
    val providers = remember { context.appContainer.providers.presets }

    // Local drafts so typing does not write to prefs on every keystroke.
    var baseUrl by remember(state.settings.baseUrl) { mutableStateOf(state.settings.baseUrl) }
    var apiKey by remember(state.settings.apiKey) { mutableStateOf(state.settings.apiKey) }
    var model by remember(state.settings.model) { mutableStateOf(state.settings.model) }
    var temperature by remember(state.settings.temperature) {
        mutableStateOf(state.settings.temperature.toString())
    }
    var maxTokens by remember(state.settings.maxTokens) {
        mutableStateOf(state.settings.maxTokens.toString())
    }
    var systemPrompt by remember { mutableStateOf("") }

    var showProviderPicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    fun commit() {
        onUpdateSettings(
            baseUrl.trim(),
            apiKey.trim(),
            model.trim().ifBlank { ApiConfig.DEFAULT_MODEL },
            temperature.toFloatOrNull()?.coerceIn(0f, 2f),
            maxTokens.toIntOrNull()?.coerceIn(256, 128_000),
            systemPrompt.takeIf { it.isNotBlank() },
            null,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        // ------------------------------------------------------------------ top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    commit()
                    onBack()
                },
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
            Text(
                text = stringResource(R.string.settings_title),
                color = palette.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // ------------------------------------------------------------------ provider
            SectionCard(stringResource(R.string.settings_provider_section)) {
                PickerRow(
                    label = stringResource(R.string.settings_provider),
                    value = state.providerLabel.ifBlank { state.providerId },
                    onClick = { showProviderPicker = true },
                )
                Text(
                    text = stringResource(R.string.settings_provider_note, providers.size),
                    color = palette.textFaint,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }

            SectionCard(stringResource(R.string.settings_endpoint_section)) {
                SettingsField(
                    label = stringResource(R.string.settings_base_url),
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = ApiConfig.DEFAULT_BASE_URL,
                    monospace = true,
                )
                SettingsField(
                    label = stringResource(R.string.settings_api_key),
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "sk-…",
                    password = true,
                    monospace = true,
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            SettingsField(
                                label = stringResource(R.string.settings_model),
                                value = model,
                                onValueChange = { model = it },
                                placeholder = ApiConfig.DEFAULT_MODEL,
                                monospace = true,
                            )
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        TextButton(
                            text = if (state.isDiscoveringModels) {
                                "…"
                            } else {
                                stringResource(R.string.settings_fetch)
                            },
                            onClick = {
                                commit()
                                onDiscoverModels()
                            },
                            enabled = !state.isDiscoveringModels,
                        )
                    }

                    if (state.isDiscoveringModels) {
                        Spacer(Modifier.height(Spacing.xs))
                        InfiniteProgressIndicator()
                    }

                    state.modelDiscoveryError?.let { error ->
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = error,
                            color = palette.danger,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }

                    if (state.availableModels.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.xs))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showModelPicker = true }
                                .padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.settings_choose_models,
                                    state.availableModels.size,
                                ),
                                modifier = Modifier.weight(1f),
                                color = palette.accent,
                                fontSize = 13.sp,
                            )
                            Icon(
                                imageVector = MiuixIcons.Regular.ExpandMore,
                                contentDescription = stringResource(R.string.settings_browse_models),
                                modifier = Modifier.size(16.dp),
                                tint = palette.accent,
                            )
                        }
                    }
                }

                InfoRow(
                    label = stringResource(R.string.settings_context_window),
                    value = stringResource(R.string.settings_tokens, state.contextLimit),
                    valueColor = palette.textSecondary,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Box(modifier = Modifier.weight(1f)) {
                        SettingsField(
                            label = stringResource(R.string.settings_temperature),
                            value = temperature,
                            onValueChange = { temperature = it },
                            placeholder = "0.3",
                            numeric = true,
                            monospace = true,
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        SettingsField(
                            label = stringResource(R.string.settings_max_tokens),
                            value = maxTokens,
                            onValueChange = { maxTokens = it },
                            placeholder = "4096",
                            numeric = true,
                            monospace = true,
                        )
                    }
                }
                SettingsField(
                    label = stringResource(R.string.settings_system_prompt),
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    placeholder = stringResource(R.string.settings_system_prompt_hint),
                    monospace = false,
                )
                Text(
                    text = stringResource(R.string.settings_endpoint_note),
                    color = palette.textFaint,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }

            SectionCard(stringResource(R.string.settings_safety_section)) {
                SwitchRow(
                    title = stringResource(R.string.settings_allow_execution),
                    subtitle = stringResource(R.string.settings_allow_execution_note),
                    checked = state.allowCommandExecution,
                    onCheckedChange = {
                        commit()
                        onUpdateSettings(null, null, null, null, null, null, it)
                    },
                )
            }

            SectionCard(stringResource(R.string.settings_backend_section)) {
                InfoRow(
                    label = stringResource(R.string.settings_bootstrap),
                    value = when (val bootstrap = state.bootstrap) {
                        BootstrapState.Installed ->
                            stringResource(R.string.settings_bootstrap_installed)
                        is BootstrapState.Installing -> bootstrap.message
                        is BootstrapState.Error ->
                            stringResource(R.string.settings_bootstrap_error, bootstrap.message)
                        BootstrapState.NotInstalled ->
                            stringResource(R.string.settings_bootstrap_not_installed)
                    },
                    valueColor = when (state.bootstrap) {
                        BootstrapState.Installed -> palette.success
                        is BootstrapState.Error -> palette.danger
                        BootstrapState.NotInstalled -> palette.warning
                        is BootstrapState.Installing -> palette.accent
                    },
                )
                Text(
                    text = stringResource(R.string.settings_backend_note),
                    color = palette.textFaint,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }

            SectionCard(stringResource(R.string.settings_appearance_section)) {
                SubLabel(stringResource(R.string.settings_color_scheme))
                for (mode in ThemeMode.entries) {
                    ThemeRow(
                        mode = mode,
                        selected = state.themeMode == mode,
                        onClick = { onSetTheme(mode) },
                    )
                }

                SubLabel(stringResource(R.string.settings_theme_style))
                val previewDark = state.themeMode.resolvesDark()
                for (style in ThemeStyle.entries) {
                    ThemeStyleRow(
                        style = style,
                        preview = paletteFor(style, previewDark),
                        selected = state.themeStyle == style,
                        onClick = { onSetThemeStyle(style) },
                    )
                }
                Text(
                    text = stringResource(R.string.settings_theme_style_note),
                    color = palette.textFaint,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }

            SectionCard(stringResource(R.string.settings_data_section)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onResetData)
                        .padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_delete_all),
                            color = palette.danger,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = stringResource(R.string.settings_delete_all_note),
                            color = palette.textFaint,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.settings_about, BuildConfig.VERSION_NAME),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                color = palette.textFaint,
                fontSize = 10.sp,
            )
        }
    }

    if (showProviderPicker) {
        ProviderPickerDialog(
            providers = providers,
            selectedId = state.providerId,
            onSelect = { id ->
                // The baseUrl/apiKey drafts are remembered keyed on state.settings.*, so the
                // restored per-provider values re-seed them automatically on recomposition.
                onSelectProvider(id)
                showProviderPicker = false
            },
            onDismiss = { showProviderPicker = false },
        )
    }

    if (showModelPicker) {
        ModelPickerDialog(
            models = state.availableModels,
            selected = model,
            onSelect = { chosen ->
                model = chosen
                commit()
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

// ---------------------------------------------------------------------- dialogs

/** Searchable picker over the whole provider catalogue. */
@Composable
private fun ProviderPickerDialog(
    providers: List<ProviderPreset>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = TermAgentTheme.colors
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, providers) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            providers
        } else {
            providers.filter {
                it.value.lowercase().contains(q) || it.label.lowercase().contains(q)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(
                color = palette.surface,
                contentColor = palette.textPrimary,
            ),
        ) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Text(
                    text = stringResource(R.string.settings_select_provider),
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(Spacing.md))
                SettingsField(
                    label = stringResource(R.string.settings_filter),
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.settings_search_providers, providers.size),
                    monospace = false,
                )
                Spacer(Modifier.height(Spacing.sm))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(filtered, key = { it.value }) { preset ->
                        val selected = preset.value == selectedId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(preset.value) }
                                .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.label,
                                    color = if (selected) palette.accent else palette.textPrimary,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    text = preset.baseUrl.ifBlank {
                                        stringResource(R.string.settings_custom_endpoint)
                                    },
                                    color = palette.textFaint,
                                    fontSize = 10.sp,
                                    fontFamily = TerminalFontFamily,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) palette.accent else palette.surfaceElevated),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Picker over the models returned by the provider's `/models` endpoint. */
@Composable
private fun ModelPickerDialog(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = TermAgentTheme.colors
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, models) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) models else models.filter { it.lowercase().contains(q) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(
                color = palette.surface,
                contentColor = palette.textPrimary,
            ),
        ) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Text(
                    text = stringResource(R.string.settings_select_model),
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(Spacing.md))
                SettingsField(
                    label = stringResource(R.string.settings_filter),
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.settings_search_models, models.size),
                    monospace = false,
                )
                Spacer(Modifier.height(Spacing.sm))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(filtered) { id ->
                        val isSelected = id == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(id) }
                                .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = id,
                                modifier = Modifier.weight(1f),
                                color = if (isSelected) palette.accent else palette.textPrimary,
                                fontSize = 13.sp,
                                fontFamily = TerminalFontFamily,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- widgets

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val palette = TermAgentTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        colors = CardDefaults.defaultColors(color = palette.surface, contentColor = palette.textPrimary),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Spacing.md))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                content()
            }
        }
    }
}

/** A tappable "label — value ▸" row that opens a picker dialog. */
@Composable
private fun PickerRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val palette = TermAgentTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f), color = palette.textSecondary, fontSize = 13.sp)
        Text(text = value, color = palette.textPrimary, fontSize = 13.sp)
        Spacer(Modifier.width(Spacing.xs))
        Icon(
            imageVector = MiuixIcons.Regular.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = palette.textSecondary,
        )
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    numeric: Boolean = false,
    monospace: Boolean = false,
) {
    val palette = TermAgentTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = palette.textSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(Spacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(palette.background)
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = palette.textFaint,
                    fontSize = 13.sp,
                    fontFamily = if (monospace) TerminalFontFamily else null,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontFamily = if (monospace) TerminalFontFamily else null,
                ),
                singleLine = true,
                cursorBrush = SolidColor(palette.accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        password -> KeyboardType.Password
                        numeric -> KeyboardType.Number
                        else -> KeyboardType.Uri
                    },
                ),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = TermAgentTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = palette.textPrimary, fontSize = 14.sp)
            Text(
                text = subtitle,
                color = palette.textFaint,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color) {
    val palette = TermAgentTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, modifier = Modifier.weight(1f), color = palette.textSecondary, fontSize = 13.sp)
        Text(text = value, color = valueColor, fontSize = 12.sp, fontFamily = TerminalFontFamily)
    }
}

@Composable
private fun ThemeRow(mode: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    val palette = TermAgentTheme.colors
    // Resolved per composition so the row follows a system-language change like everything else.
    val label = stringResource(
        when (mode) {
            ThemeMode.Dark -> R.string.theme_dark
            ThemeMode.Light -> R.string.theme_light
            ThemeMode.System -> R.string.theme_system
            ThemeMode.MonetDark -> R.string.theme_monet_dark
            ThemeMode.MonetLight -> R.string.theme_monet_light
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f), color = palette.textPrimary, fontSize = 14.sp)
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (selected) palette.accent else palette.surfaceElevated),
        )
    }
}

/**
 * Brand-style row.
 *
 * [preview] is the palette of the style **being offered**, resolved at the currently selected
 * light/dark mode — so the swatch is a faithful "what you'd get" rather than a reuse of the
 * live theme (which would make every row look identical).
 */
@Composable
private fun ThemeStyleRow(
    style: ThemeStyle,
    preview: TermAgentPalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = TermAgentTheme.colors
    val label = stringResource(
        when (style) {
            ThemeStyle.Default -> R.string.theme_style_default
            ThemeStyle.DeepSeek -> R.string.theme_style_deepseek
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f), color = palette.textPrimary, fontSize = 14.sp)
        StyleSwatch(preview)
        Spacer(Modifier.width(Spacing.md))
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (selected) palette.accent else palette.surfaceElevated),
        )
    }
}

/** Two-tone chip: the style's canvas behind the style's accent. */
@Composable
private fun StyleSwatch(preview: TermAgentPalette) {
    Box(
        modifier = Modifier
            .size(width = 32.dp, height = 20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(preview.background)
            .border(1.dp, preview.divider, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 16.dp, height = 6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(preview.accent),
        )
    }
}

/** Caption that splits a settings card into labelled groups. */
@Composable
private fun SubLabel(text: String) {
    val palette = TermAgentTheme.colors
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        color = palette.textFaint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
}
