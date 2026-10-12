package com.termagent

import android.app.Application
import android.content.Context
import com.termagent.agent.AgentEngine
import com.termagent.agent.AgentTools
import com.termagent.agent.LlmClient
import com.termagent.agent.VisionClient
import com.termagent.data.AttachmentRegistry
import com.termagent.data.AttachmentStore
import com.termagent.data.SessionRepository
import com.termagent.data.SettingsStore
import com.termagent.provider.ModelCatalog
import com.termagent.provider.ProviderRegistry
import com.termagent.shell.BootstrapInstaller
import com.termagent.shell.ShellManager
import com.termagent.shell.TermuxEnvironment

/**
 * Hand-rolled dependency container.
 *
 * The graph is tiny and entirely singletons, so a DI framework would be pure overhead;
 * this keeps the forked Termux session, the OkHttp client and the repositories alive for
 * the life of the process (which matters — a `TerminalSession` must not be recreated on
 * every configuration change).
 */
class AppContainer(context: Context) {

    val environment: TermuxEnvironment = TermuxEnvironment(context)

    val settings: SettingsStore = SettingsStore(context)

    val repository: SessionRepository = SessionRepository(context)

    /** Bundled provider catalogue (178 presets, ported from devx). */
    val providers: ProviderRegistry = ProviderRegistry(context)

    /** Model context-window / pricing tables (ported from devx). */
    val models: ModelCatalog = ModelCatalog(context)

    /** Shared HTTP client: no read timeout, so SSE streams are not cut off mid-turn. */
    val http = LlmClient.defaultHttpClient()

    val llm: LlmClient = LlmClient(http, context)

    val shell: ShellManager = ShellManager(context, environment)

    /**
     * Non-streaming multimodal client behind the `view_image` tool.
     *
     * The config lambda reads live settings, so switching provider or model in Settings takes effect
     * on the very next tool call.
     */
    val vision: VisionClient = VisionClient(http, context) { settings.apiConfig() }

    /**
     * The attachments of the conversation the UI currently has open.
     *
     * The agent layer must not know about conversations or the repository, so the view model
     * publishes here instead and the tools read from it. Nothing else needs to be kept in sync:
     * attachments are immutable once imported.
     */
    val attachmentRegistry: AttachmentRegistry = AttachmentRegistry()

    /** Imports picked files into `$HOME/attachments/` and resolves them back to files. */
    val attachmentStore: AttachmentStore = AttachmentStore(context, environment)

    val tools: AgentTools = AgentTools(
        shell = shell,
        env = environment,
        vision = vision,
        attachments = { attachmentRegistry.current() },
    )

    // The engine takes a string resolver, not a Context: it needs exactly one translated
    // message, and depending on a Context made the whole agent loop untestable off-device.
    val engine: AgentEngine = AgentEngine(llm, tools) {
        context.getString(R.string.error_too_many_tool_calls, it)
    }

    val bootstrap: BootstrapInstaller = BootstrapInstaller(context, environment, http)

    init {
        // Keep the apt `.deb` repoint hook in step with the app.
        //
        // A prefix installed by an earlier build (or by a build that predates the hook) has no
        // way to gain it from a fresh install, so it is re-asserted on every launch. The write is
        // skipped when the on-disk script already matches, so the steady-state cost is one small
        // file read, and it is done off the main thread because it still touches the filesystem.
        if (environment.isBootstrapInstalled) {
            Thread {
                bootstrap.ensurePackageRepointHook()
            }.apply { name = "termagent-repoint-hook"; isDaemon = true }.start()
        }
    }

    /** True when `$PREFIX/bin/bash` is present, regardless of the persisted flag. */
    val isBootstrapInstalled: Boolean get() = environment.isBootstrapInstalled
}

class TermAgentApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Convenience accessor for the container from a composable's context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as TermAgentApp).container
