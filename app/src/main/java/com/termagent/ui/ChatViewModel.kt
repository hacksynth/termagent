package com.termagent.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termagent.AppContainer
import com.termagent.R
import com.termagent.TermAgentApp
import com.termagent.agent.AgentEvent
import com.termagent.agent.AgentForegroundService
import com.termagent.agent.ApiConfig
import com.termagent.agent.ChatMessage
import com.termagent.agent.FunctionCall
import com.termagent.agent.MessageContent
import com.termagent.agent.ToolCall
import com.termagent.agent.UserContent
import com.termagent.data.Attachment
import com.termagent.data.BootstrapState
import com.termagent.data.ChatEntry
import com.termagent.data.Conversation
import com.termagent.data.Role
import com.termagent.data.SessionRepository
import com.termagent.data.TerminalBlock
import com.termagent.data.ThemeMode
import com.termagent.data.ThemeStyle
import com.termagent.data.ToolStatus
import com.termagent.provider.ModelCatalog
import com.termagent.provider.ModelDiscovery
import com.termagent.provider.ProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Immutable snapshot the whole chat shell renders from. */
data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val current: Conversation? = null,
    val isStreaming: Boolean = false,
    val input: String = "",
    val error: String? = null,
    val bootstrap: BootstrapState = BootstrapState.NotInstalled,
    val settings: ApiConfig = ApiConfig(),
    val allowCommandExecution: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.Dark,
    /** Brand palette; orthogonal to [themeMode] so "DeepSeek · light" is expressible. */
    val themeStyle: ThemeStyle = ThemeStyle.Default,
    /** Catalogue id of the selected provider (e.g. `deepseek`). */
    val providerId: String = ProviderRegistry.DEFAULT_ID,
    /** Display name of [providerId], resolved from the bundled catalogue. */
    val providerLabel: String = "",
    /** Context window of the selected model, from the bundled model-limits table. */
    val contextLimit: Int = ModelCatalog.DEFAULT_CONTEXT,
    /** Models returned by the provider's `/models` endpoint (may be empty). */
    val availableModels: List<String> = emptyList(),
    val isDiscoveringModels: Boolean = false,
    val modelDiscoveryError: String? = null,
    /**
     * Files picked but not sent yet; they travel with the next message.
     *
     * Staged in the state rather than written into the conversation immediately, because an
     * attachment belongs to a *message*: a chip the user removes must leave no trace in the
     * history, and an unsent attachment must not be replayed to the API.
     */
    val pendingAttachments: List<Attachment> = emptyList(),
    /** True while picked files are being copied/compressed — the send button stays disabled. */
    val isImportingAttachments: Boolean = false,
) {
    val entries: List<ChatEntry> get() = current?.entries.orEmpty()
    val isConfigured: Boolean
        get() = settings.apiKey.isNotBlank() ||
            settings.baseUrl.contains("localhost") ||
            settings.baseUrl.contains("127.0.0.1")

    /** Total spend across the open conversation, for the header summary. */
    val conversationCost: Double get() = entries.sumOf { it.costUsd }
}

/**
 * Everything the chat screen does: conversation history, streaming a turn through
 * [com.termagent.agent.AgentEngine], and persisting the result.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container: AppContainer = (application as TermAgentApp).container

    private val _state = MutableStateFlow(emptyState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** The turn currently being streamed, if any. */
    private var turnJob: Job? = null

    /** Entry id of the assistant message being streamed. */
    private var streamingEntryId: String? = null

    /** When the conversation was last written to disk; gates [persistThrottled]. */
    private var lastPersistAt = 0L

    init {
        loadFromDisk()
    }

    // ------------------------------------------------------------------ bootstrap

    private fun emptyState(): ChatUiState {
        val settings = container.settings
        val config = settings.apiConfig()
        val providerId = settings.providerId
        return ChatUiState(
            bootstrap = if (container.isBootstrapInstalled) {
                BootstrapState.Installed
            } else {
                BootstrapState.NotInstalled
            },
            settings = config,
            allowCommandExecution = settings.allowCommandExecution,
            themeMode = settings.themeMode,
            themeStyle = settings.themeStyle,
            providerId = providerId,
            providerLabel = container.providers.labelFor(providerId),
            contextLimit = container.models.contextLimit(config.model),
        )
    }

    private fun loadFromDisk() {
        viewModelScope.launch {
            // Nothing can be streaming on a cold start: a turn is not resumed across process
            // death. An entry that says otherwise was written mid-turn and never finished — the
            // process was killed, or the final write did not land. Left as-is it would render a
            // permanently blinking cursor, hide the usage footer and suppress "(no response)".
            val loaded = container.repository.loadAll().map { conversation ->
                conversation.copy(
                    entries = conversation.entries.map { entry ->
                        if (entry.isStreaming) entry.copy(isStreaming = false) else entry
                    },
                )
            }
            val current = loaded.firstOrNull() ?: SessionRepository.newConversation()
            _state.update {
                it.copy(
                    conversations = loaded.ifEmpty { listOf(current) },
                    current = current,
                )
            }
            // A successful bootstrap in a previous run is authoritative.
            if (container.isBootstrapInstalled) {
                container.settings.bootstrapInstalled = true
                _state.update { it.copy(bootstrap = BootstrapState.Installed) }
            }
            publishAttachments()
        }
    }

    /**
     * Resolves a UI string.
     *
     * The view model is the seam between the pure model layer (which has no `Context` and must
     * never hold a translated string) and Compose. Strings that originate *here* — bootstrap
     * progress, model-discovery errors — are resolved eagerly, against the locale in force at the
     * moment they are produced, and the result is displayed as-is. That is deliberate: they are
     * transient status text, and re-translating a message that already happened would be wrong.
     *
     * Strings that are *displayed* rather than *stored* (labels, buttons, placeholders) are
     * resolved in the composables via `stringResource`, so they follow a language change live.
     */
    private fun str(@StringRes id: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(id, *formatArgs)

    fun installBootstrap() {
        if (_state.value.bootstrap is BootstrapState.Installing) return
        _state.update { it.copy(bootstrap = BootstrapState.Installing(str(R.string.bootstrap_starting))) }

        viewModelScope.launch {
            val result = container.bootstrap.install { progress ->
                val message = when (progress) {
                    is com.termagent.shell.BootstrapInstaller.State.Staging -> {
                        // The archive ships inside the APK, so this is a local copy — fast, and
                        // it needs no network.
                        val total = progress.totalBytes
                        if (total > 0) {
                            str(R.string.bootstrap_preparing_percent, progress.bytesRead * 100 / total)
                        } else {
                            str(R.string.bootstrap_preparing)
                        }
                    }

                    is com.termagent.shell.BootstrapInstaller.State.Downloading -> {
                        val total = progress.totalBytes
                        if (total > 0) {
                            str(
                                R.string.bootstrap_downloading_total,
                                progress.bytesRead / 1024 / 1024,
                                total / 1024 / 1024,
                            )
                        } else {
                            str(R.string.bootstrap_downloading, progress.bytesRead / 1024 / 1024)
                        }
                    }

                    is com.termagent.shell.BootstrapInstaller.State.Extracting ->
                        str(R.string.bootstrap_extracting, progress.entries)

                    com.termagent.shell.BootstrapInstaller.State.Linking ->
                        str(R.string.bootstrap_linking)
                    com.termagent.shell.BootstrapInstaller.State.Patching ->
                        str(R.string.bootstrap_patching)
                    com.termagent.shell.BootstrapInstaller.State.Configuring ->
                        str(R.string.bootstrap_configuring)
                    com.termagent.shell.BootstrapInstaller.State.Done ->
                        str(R.string.bootstrap_finishing)
                    com.termagent.shell.BootstrapInstaller.State.Idle ->
                        str(R.string.bootstrap_starting)
                    is com.termagent.shell.BootstrapInstaller.State.Failed ->
                        str(R.string.bootstrap_failed_prefix, progress.message)
                }
                _state.update { it.copy(bootstrap = BootstrapState.Installing(message)) }
            }

            if (result.isSuccess) {
                container.settings.bootstrapInstalled = true
                _state.update { it.copy(bootstrap = BootstrapState.Installed, error = null) }
            } else {
                val message = result.exceptionOrNull()?.message ?: str(R.string.error_unknown)
                _state.update { it.copy(bootstrap = BootstrapState.Error(message)) }
            }
        }
    }

    // ------------------------------------------------------------------ conversation management

    fun newConversation() {
        cancelTurn()
        val conversation = SessionRepository.newConversation()
        _state.update {
            it.copy(
                conversations = listOf(conversation) + it.conversations,
                current = conversation,
                input = "",
                error = null,
                // Staged files belong to the message that was about to be sent; the user moving to a
                // fresh chat is not sending them, so they are kept and stay visible as chips.
                pendingAttachments = it.pendingAttachments,
            )
        }
        publishAttachments()
    }

    fun selectConversation(id: String) {
        if (id == _state.value.current?.id) return
        cancelTurn()
        val conversation = _state.value.conversations.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(current = conversation, error = null) }
        publishAttachments()
    }

    fun deleteConversation(id: String) {
        // Grab the attachments before the list is rewritten — they are about to lose their only
        // reference and their bytes should go with them.
        _state.value.conversations.firstOrNull { it.id == id }?.let { deleteAttachmentsOf(listOf(it)) }

        viewModelScope.launch { container.repository.delete(id) }
        _state.update { snapshot ->
            val remaining = snapshot.conversations.filterNot { it.id == id }
            if (snapshot.current?.id != id) {
                snapshot.copy(conversations = remaining)
            } else {
                // The active chat was deleted: fall back to the newest survivor, or start
                // a brand-new one so the screen is never without a conversation.
                val next = remaining.firstOrNull() ?: SessionRepository.newConversation()
                snapshot.copy(
                    conversations = if (remaining.isEmpty()) listOf(next) else remaining,
                    current = next,
                )
            }
        }
        publishAttachments()
    }

    fun clearConversation() {
        cancelTurn()
        _state.value.current?.let { deleteAttachmentsOf(listOf(it)) }
        updateCurrent { it.copy(entries = emptyList(), title = "") }
        persist()
        publishAttachments()
    }

    /** Wipe every stored conversation (Settings → Data). */
    fun deleteAllConversations() {
        cancelTurn()
        val doomed = _state.value.conversations
        deleteAttachmentsOf(doomed)

        val toDelete = doomed.map { it.id }
        viewModelScope.launch {
            toDelete.forEach { runCatching { container.repository.delete(it) } }
        }
        val fresh = SessionRepository.newConversation()
        _state.update {
            it.copy(
                conversations = listOf(fresh),
                current = fresh,
                input = "",
                error = null,
                pendingAttachments = emptyList(),
            )
        }
        publishAttachments()
    }

    // ------------------------------------------------------------------ input

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------ attachments

    /**
     * Import picked documents into `$HOME/attachments/` and stage them for the next message.
     *
     * Importing is asynchronous and can be slow (a 12 MP photo has to be decoded and re-encoded),
     * so the pending list only gains the *successful* imports and any failure is reported through
     * the normal error bar — a partially failed multi-select must not lose the files that worked.
     */
    fun attachFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(isImportingAttachments = true) }

        viewModelScope.launch {
            val imported = ArrayList<Attachment>(uris.size)
            val failures = ArrayList<String>(uris.size)

            for (uri in uris) {
                container.attachmentStore.import(uri)
                    .onSuccess { imported += it }
                    .onFailure { failures += it.message ?: it.javaClass.simpleName }
            }

            _state.update { snapshot ->
                snapshot.copy(
                    pendingAttachments = snapshot.pendingAttachments + imported,
                    isImportingAttachments = false,
                    error = when {
                        failures.isEmpty() -> snapshot.error
                        failures.size == 1 -> failures.first()
                        else -> str(R.string.error_attachments_failed, failures.size)
                    },
                )
            }
        }
    }

    /** Drop a staged attachment, deleting its bytes — nothing else refers to them yet. */
    fun removePendingAttachment(id: String) {
        val attachment = _state.value.pendingAttachments.firstOrNull { it.id == id } ?: return
        container.attachmentStore.delete(attachment)
        _state.update { snapshot ->
            snapshot.copy(pendingAttachments = snapshot.pendingAttachments.filterNot { it.id == id })
        }
        publishAttachments()
    }

    // ------------------------------------------------------------------ the turn

    /**
     * Send [text] (defaults to the composer contents) to the agent, together with any staged
     * attachments.
     *
     * A turn is allowed to consist of attachments alone — "here is the screenshot" needs no caption.
     */
    fun send(text: String = _state.value.input) {
        val prompt = text.trim()
        val attachments = _state.value.pendingAttachments
        if ((prompt.isEmpty() && attachments.isEmpty()) || _state.value.isStreaming) return

        val conversation = _state.value.current ?: SessionRepository.newConversation()
        val userEntry = ChatEntry(
            id = UUID.randomUUID().toString(),
            role = Role.User,
            text = prompt,
            attachments = attachments,
        )
        val assistantEntryId = UUID.randomUUID().toString()

        // History to send *excludes* the placeholder assistant entry that is still empty.
        val historySource = conversation.entries + userEntry

        // A chat that starts with an attachment still needs a title, and the file name is the most
        // informative thing available.
        val titleSeed = prompt.ifBlank {
            attachments.firstOrNull()?.name.orEmpty()
        }

        val withEntries = conversation.copy(
            entries = conversation.entries + userEntry,
            title = conversation.title.ifBlank { SessionRepository.deriveTitle(titleSeed) },
            updatedAt = System.currentTimeMillis(),
        )
        _state.update {
            it.copy(
                current = withEntries,
                conversations = it.conversations.replace(withEntries),
                input = "",
                pendingAttachments = emptyList(),
                isStreaming = true,
                error = null,
            )
        }
        // The turn's images are now part of the open conversation, so `list_attachments` and
        // `view_image` can resolve them while the agent works.
        publishAttachments()
        persist(withEntries)

        streamingEntryId = assistantEntryId

        // Hold a foreground service (and a partial wake lock) for the duration of the turn.
        //
        // A turn can legitimately run for minutes (`apt install`, a build, a test suite). Without
        // this, backgrounding the app lets Android's cached-app freezer suspend the timers the
        // command capture depends on, and the process can be killed outright mid-turn — which is
        // exactly the failure the ShellManager KDoc describes as a 55-minute apparent hang.
        AgentForegroundService.start(getApplication<Application>())

        turnJob = viewModelScope.launch {
            try {
                val history = buildApiHistory(historySource)
                val config = _state.value.settings

                container.engine
                    .run(config, history, allowCommandExecution = _state.value.allowCommandExecution)
                    .collect { event -> handleEvent(event, assistantEntryId) }
            } catch (cancellation: CancellationException) {
                // Stopping a turn (or switching conversations) cancels this coroutine. That is a
                // deliberate user action, not a failure, so it must not surface as one: this used to
                // fall into the `Throwable` branch below and pop a red "Job was cancelled" bar every
                // time Stop was pressed. The shell's own cancellation handler has already sent
                // Ctrl-C to the foreground job.
                Log.i(TAG, "Turn cancelled")
                throw cancellation
            } catch (t: Throwable) {
                Log.e(TAG, "Turn failed", t)
                _state.update { it.copy(error = t.message ?: t.javaClass.simpleName) }
            } finally {
                finishAssistantEntry(assistantEntryId)
                _state.update { it.copy(isStreaming = false) }
                streamingEntryId = null
                turnJob = null
                // The turn is over, so the process no longer needs to be kept awake.
                AgentForegroundService.stop(getApplication())
                persist()
            }
        }
    }

    fun stop() {
        cancelTurn()
        _state.update { it.copy(isStreaming = false) }
        persist()
    }

    private fun cancelTurn() {
        turnJob?.cancel()
        turnJob = null
        streamingEntryId?.let { finishAssistantEntry(it) }
        streamingEntryId = null
    }

    private fun handleEvent(event: AgentEvent, assistantEntryId: String) {
        when (event) {
            is AgentEvent.TextDelta -> upsertAssistantEntry(assistantEntryId) { entry ->
                entry.copy(text = entry.text + event.text, isStreaming = true)
            }

            is AgentEvent.ReasoningDelta -> upsertAssistantEntry(assistantEntryId) { entry ->
                entry.copy(reasoning = entry.reasoning + event.text, isStreaming = true)
            }

            is AgentEvent.MessageCompleted -> {
                // Creates the entry on demand: a turn that answers directly never calls a tool,
                // so this is often the first event that guarantees the id exists. The safety net
                // also covers servers that send the whole message without streaming deltas.
                val content = event.message.textContent
                upsertAssistantEntry(assistantEntryId) { entry ->
                    if (!content.isNullOrEmpty() && entry.text.isEmpty()) {
                        entry.copy(text = content)
                    } else {
                        entry
                    }
                }
            }

            is AgentEvent.ToolStarted -> appendEntry(
                ChatEntry(
                    id = UUID.randomUUID().toString(),
                    role = Role.Tool,
                    terminal = TerminalBlock(
                        toolCallId = event.toolCallId,
                        toolName = event.name,
                        command = event.summary,
                        arguments = event.arguments,
                        status = ToolStatus.Running,
                    ),
                ),
            )

            is AgentEvent.ToolOutput -> updateTerminal(event.toolCallId) { block ->
                block.copy(output = capTerminalOutput(block.output + event.chunk))
            }

            is AgentEvent.ToolFinished -> updateTerminal(event.toolCallId) { block ->
                block.copy(
                    output = block.output.ifEmpty { event.result },
                    result = event.result,
                    exitCode = event.exitCode,
                    status = if (event.isError) ToolStatus.Failed else ToolStatus.Succeeded,
                )
            }

            is AgentEvent.ToolMessageCompleted -> Unit // already captured by ToolFinished

            is AgentEvent.Usage -> {
                val cost = container.models.estimateCost(
                    _state.value.settings.model,
                    event.promptTokens,
                    event.completionTokens,
                )
                upsertAssistantEntry(assistantEntryId) { entry ->
                    entry.copy(
                        promptTokens = event.promptTokens,
                        completionTokens = event.completionTokens,
                        costUsd = cost,
                    )
                }
            }

            is AgentEvent.Failure -> {
                // A failed turn may never emit MessageCompleted, so the entry has to be created
                // here before the reason can be attached to it.
                // Attach the reason to the message itself, not only to the dismissible error
                // bar: a failed turn used to render as a bare "(no response)", which hid the
                // real cause (e.g. an HTTP 401 from a key that belongs to another provider).
                upsertAssistantEntry(assistantEntryId) { entry ->
                    entry.copy(error = entry.error ?: event.message)
                }
                _state.update { it.copy(error = event.message) }
            }

            AgentEvent.Done -> finishAssistantEntry(assistantEntryId)
        }

        // Everything the turn produced lives in memory until it ends; make it durable as we go so
        // a slow (or stuck) tool does not leave the conversation looking empty.
        //
        // `Done` is written straight through rather than throttled. The turn job's own `finally`
        // would also persist it, but only once the coroutine unwinds — and a `Done` landing inside
        // the throttle window is the normal case (the last delta is milliseconds earlier), so
        // without this the last state to become true (`isStreaming = false`, the token counts)
        // would be the one left waiting on an unwinding job that a process kill can interrupt.
        if (event is AgentEvent.Done) persist() else persistThrottled()
    }

    /**
     * Apply [transform] to the assistant entry [id], creating it when the turn has not produced
     * it yet.
     *
     * Nothing exists for the assistant when [send] fires — the entry is created lazily, by the
     * first event that needs it. [updateEntry] only maps over *existing* entries, so a turn that
     * answers directly (no tool call) used to stream every delta into the void and persist an
     * empty message that still carried the token count (Usage arrives after MessageCompleted).
     * Creating on demand is what keeps the text.
     */
    private fun upsertAssistantEntry(id: String, transform: (ChatEntry) -> ChatEntry) {
        updateCurrent { conversation ->
            val exists = conversation.entries.any { it.id == id }
            val entries = if (exists) {
                conversation.entries.map { if (it.id == id) transform(it) else it }
            } else {
                conversation.entries + transform(ChatEntry(id = id, role = Role.Assistant))
            }
            conversation.copy(entries = entries, updatedAt = System.currentTimeMillis())
        }
    }

    private fun finishAssistantEntry(id: String) {
        updateEntry(id) { entry -> entry.copy(isStreaming = false) }
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(
        baseUrl: String? = null,
        apiKey: String? = null,
        model: String? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
        systemPrompt: String? = null,
        allowCommandExecution: Boolean? = null,
    ) {
        val settings = container.settings
        val providerId = settings.providerId
        // Mirror the effective endpoint/key into the per-provider memory, exactly like devx's
        // `baseUrls`/`apiKeys` maps, so switching away and back restores what was typed.
        baseUrl?.let {
            settings.baseUrl = it
            settings.setBaseUrlFor(providerId, it)
        }
        apiKey?.let {
            settings.apiKey = it
            settings.setApiKeyFor(providerId, it)
        }
        model?.let { settings.model = it }
        temperature?.let { settings.temperature = it }
        maxTokens?.let { settings.maxTokens = it }
        systemPrompt?.let { settings.systemPrompt = it }
        allowCommandExecution?.let { settings.allowCommandExecution = it }

        _state.update {
            it.copy(
                settings = settings.apiConfig(),
                allowCommandExecution = settings.allowCommandExecution,
                contextLimit = container.models.contextLimit(settings.model),
            )
        }
    }

    // ------------------------------------------------------------------ providers

    /**
     * Switch to another catalogue provider.
     *
     * Restores the provider's remembered base URL and API key (falling back to the preset URL
     * and an empty key for a first visit). Discovered models are dropped because they belong to
     * the previous endpoint.
     */
    fun selectProvider(id: String) {
        val settings = container.settings
        val preset = container.providers.byId(id)

        settings.providerId = id

        val restoredBaseUrl = settings.baseUrlFor(id)
            ?: preset?.baseUrl?.takeIf { it.isNotBlank() }
            ?: settings.baseUrl
        val restoredApiKey = settings.apiKeyFor(id)

        settings.baseUrl = restoredBaseUrl
        settings.apiKey = restoredApiKey

        _state.update {
            it.copy(
                providerId = id,
                providerLabel = container.providers.labelFor(id),
                settings = settings.apiConfig(),
                contextLimit = container.models.contextLimit(settings.model),
                availableModels = emptyList(),
                modelDiscoveryError = null,
            )
        }
    }

    /** Fetch the provider's model list from `GET {baseUrl}/models`. */
    fun discoverModels() {
        val snapshot = _state.value
        if (snapshot.isDiscoveringModels) return
        val config = snapshot.settings

        _state.update { it.copy(isDiscoveringModels = true, modelDiscoveryError = null) }

        viewModelScope.launch {
            val result = ModelDiscovery.fetch(container.http, config.baseUrl, config.apiKey)
            result.onSuccess { models ->
                _state.update {
                    it.copy(
                        isDiscoveringModels = false,
                        availableModels = models,
                        modelDiscoveryError = if (models.isEmpty()) {
                            str(R.string.error_no_models)
                        } else {
                            null
                        },
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isDiscoveringModels = false,
                        modelDiscoveryError = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        container.settings.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun setThemeStyle(style: ThemeStyle) {
        container.settings.themeStyle = style
        _state.update { it.copy(themeStyle = style) }
    }

    // ------------------------------------------------------------------ attachments registry

    /**
     * Make the open conversation's attachments visible to the agent tools.
     *
     * `view_image` and `list_attachments` need to resolve "screenshot.png" from a tool call, but the
     * agent layer has no access to conversations, state or Compose. This registry is the one-way
     * seam: the view model publishes, the tools read. Pending (unsent) attachments are included so a
     * file the user just picked is already resolvable when the turn that carries it starts.
     */
    private fun publishAttachments() {
        val snapshot = _state.value
        container.attachmentRegistry.publish(
            snapshot.entries.flatMap { it.attachments } + snapshot.pendingAttachments,
        )
    }

    /**
     * Delete the bytes of every attachment a discarded conversation held.
     *
     * Their only consumer was the message that referenced them, so leaving them would just grow
     * `$HOME/attachments` forever. Done off the main thread; failures are logged and ignored.
     */
    private fun deleteAttachmentsOf(conversations: List<Conversation>) {
        val attachments = conversations
            .flatMap { conversation -> conversation.entries.flatMap { it.attachments } }
            .distinctBy { it.id }
        if (attachments.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            attachments.forEach { container.attachmentStore.delete(it) }
        }
    }

    // ------------------------------------------------------------------ persistence

    private fun updateCurrent(transform: (Conversation) -> Conversation) {
        val current = _state.value.current ?: return
        val updated = transform(current)
        _state.update {
            it.copy(current = updated, conversations = it.conversations.replace(updated))
        }
    }

    private fun updateEntry(id: String, transform: (ChatEntry) -> ChatEntry) {
        updateCurrent { conversation ->
            conversation.copy(
                entries = conversation.entries.map { if (it.id == id) transform(it) else it },
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun appendEntry(entry: ChatEntry) {
        updateCurrent { conversation ->
            conversation.copy(
                entries = conversation.entries + entry,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /** Update the terminal card identified by its tool-call id. */
    private fun updateTerminal(toolCallId: String, transform: (TerminalBlock) -> TerminalBlock) {
        updateCurrent { conversation ->
            conversation.copy(
                entries = conversation.entries.map { entry ->
                    val terminal = entry.terminal
                    if (terminal != null && terminal.toolCallId == toolCallId) {
                        entry.copy(terminal = transform(terminal))
                    } else {
                        entry
                    }
                },
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun persist(conversation: Conversation? = _state.value.current) {
        val target = conversation ?: return
        if (target.entries.isEmpty() && target.title.isBlank()) return
        lastPersistAt = System.currentTimeMillis()
        viewModelScope.launch {
            // Save *target*, never `_state.value.current`: the default argument is evaluated at the
            // call site, so the two are normally the same conversation — but if the state moved on
            // between the call and this coroutine running (the user switched, deleted or cleared a
            // conversation), writing `current` would silently skip the conversation that was asked
            // for, and the edit that prompted the save would be lost.
            runCatching { container.repository.save(target) }
                .onFailure { Log.w(TAG, "Failed to persist conversation ${target.id}", it) }
        }
    }

    /**
     * Bound the output a terminal card keeps, in memory and on disk.
     *
     * The card is a live terminal view, so the *tail* is the part worth keeping; the model's copy of
     * the same command's output is capped separately by
     * [com.termagent.shell.OutputAccumulator]. Streamed chunks arrive verbatim, and the card's output
     * is persisted with the conversation, so without a cap a single `yes`-shaped command would grow
     * the saved JSON — and the in-memory conversation — without bound.
     */
    private fun capTerminalOutput(text: String): String =
        if (text.length <= MAX_TERMINAL_OUTPUT_CHARS) {
            text
        } else {
            // A bare ellipsis rather than a sentence: this is spliced into monospace command output,
            // which is content, not UI chrome, and must not carry language-specific prose.
            "\u2026\n" + text.takeLast(MAX_TERMINAL_OUTPUT_CHARS)
        }

    /**
     * Write the open conversation out while a turn is still streaming, at most every
     * [PERSIST_INTERVAL_MS].
     *
     * Without this the turn was only written when it *ended*, so anything a still-running turn had
     * produced — the assistant's text, the terminal cards and their streamed output — existed only
     * in memory. A turn stuck waiting on a slow tool (a long `pkg install`, say) therefore looked
     * like it had produced nothing at all, and if the process died the whole turn was lost.
     *
     * Throttled rather than called on every event: `TextDelta` arrives many times a second, and
     * [SessionRepository.save] rewrites the whole conversation file each time.
     */
    private fun persistThrottled() {
        if (System.currentTimeMillis() - lastPersistAt < PERSIST_INTERVAL_MS) return
        persist()
    }

    // ------------------------------------------------------------------ history reconstruction

    /**
     * Rebuild the wire history from stored entries.
     *
     * An assistant turn that requested tools must be replayed as
     * `assistant(tool_calls=[…])` followed by one `tool` message per call, or the server
     * rejects the request. The tool cards stored directly after an assistant entry give us
     * exactly the ids/names/arguments needed.
     *
     * Only the most recent [MAX_HISTORY_MESSAGES] messages are sent, to bound the prompt.
     */
    private fun buildApiHistory(entries: List<ChatEntry>): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>(entries.size)

        var index = 0
        while (index < entries.size) {
            val entry = entries[index]

            when (entry.role) {
                Role.User -> {
                    // Attachments are re-expanded here, not just the first time: the same entry is
                    // replayed on every turn of the conversation, and a historical image has to be
                    // re-encoded as a content part each time (UserContent caches the base64).
                    val content = UserContent.build(
                        text = entry.text,
                        attachments = entry.attachments,
                        fileOf = container.attachmentStore::fileOf,
                    )
                    if (content != null) messages += ChatMessage.user(content)
                    index++
                }

                Role.Assistant -> {
                    val toolEntries = ArrayList<ChatEntry>()
                    var cursor = index + 1
                    while (cursor < entries.size && entries[cursor].role == Role.Tool) {
                        toolEntries += entries[cursor]
                        cursor++
                    }

                    val toolCalls = toolEntries.mapNotNull { toolEntry ->
                        val block = toolEntry.terminal ?: return@mapNotNull null
                        ToolCall(
                            id = block.toolCallId,
                            function = FunctionCall(name = block.toolName, arguments = block.arguments),
                        )
                    }

                    if (entry.text.isNotBlank() || toolCalls.isNotEmpty()) {
                        messages += ChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = MessageContent.of(entry.text),
                            toolCalls = toolCalls.takeIf { it.isNotEmpty() },
                        )

                        // A `tool` message must directly follow the assistant message that
                        // requested it — and only for calls that actually ran, otherwise some
                        // servers error out on an unanswered tool_call.
                        toolEntries.forEach { toolEntry ->
                            val block = toolEntry.terminal ?: return@forEach
                            val result = block.result ?: block.output
                            if (result.isNotBlank()) {
                                messages += ChatMessage.tool(block.toolCallId, block.toolName, result)
                            }
                        }
                    }

                    index = cursor
                }

                Role.Tool -> index++ // orphaned tool card (no preceding assistant) — skip
                Role.System -> index++ // the system prompt is injected by the engine
            }
        }

        val trimmed = if (messages.size > MAX_HISTORY_MESSAGES) {
            messages.subList(messages.size - MAX_HISTORY_MESSAGES, messages.size).toList()
        } else {
            messages
        }

        // The API requires the first message after `system` to be a user message.
        return trimmed.dropWhile { it.role != ChatMessage.ROLE_USER }
    }

    private fun List<Conversation>.replace(updated: Conversation): List<Conversation> =
        map { if (it.id == updated.id) updated else it }
            .sortedByDescending { it.updatedAt }

    override fun onCleared() {
        super.onCleared()
        // The shell session deliberately outlives the ViewModel: it lives on the
        // application-scoped container so a rotation does not kill a running command.
    }

    private companion object {
        const val TAG = "ChatViewModel"
        const val MAX_HISTORY_MESSAGES = 40

        /**
         * Minimum spacing between mid-turn writes, in milliseconds.
         *
         * Small enough that a turn interrupted by the process being killed loses at most a second
         * or two of output, large enough that a fast stream of `TextDelta`s does not rewrite the
         * conversation file dozens of times a second.
         */
        const val PERSIST_INTERVAL_MS = 1_000L

        /**
         * Upper bound on the output a single terminal card retains, in characters (~128 KB).
         *
         * Keeper of the *tail*: this is what the user watches and what a re-opened conversation
         * shows. The model receives a separately capped copy (see
         * [com.termagent.shell.OutputAccumulator]), so the two limits are independent on purpose —
         * the card is a terminal view, not the tool result.
         */
        const val MAX_TERMINAL_OUTPUT_CHARS = 128_000
    }
}
