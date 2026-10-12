package com.termagent.agent

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Everything the UI needs to know while a turn is being produced. */
sealed interface AgentEvent {
    /** Visible assistant text, streamed token by token. */
    data class TextDelta(val text: String) : AgentEvent

    /** Reasoning/thinking text, streamed separately (rendered collapsed). */
    data class ReasoningDelta(val text: String) : AgentEvent

    /**
     * The model asked for a tool. [summary] is a short preview for the card header and
     * [arguments] is the raw JSON, kept so the conversation can be replayed to the API.
     */
    data class ToolStarted(
        val toolCallId: String,
        val name: String,
        val summary: String,
        val arguments: String,
    ) : AgentEvent

    /** Streamed stdout/stderr of a running tool (i.e. `run_shell`). */
    data class ToolOutput(val toolCallId: String, val chunk: String) : AgentEvent

    /** The tool returned; [result] is the full text handed back to the model. */
    data class ToolFinished(
        val toolCallId: String,
        val name: String,
        val result: String,
        val isError: Boolean,
        /**
         * The command's exit status, when the tool ran one; `null` otherwise.
         *
         * Passed through from [com.termagent.agent.ToolResult.exitCode] so the UI never has to
         * re-derive it from [result] — which is arbitrary text for most tools and therefore not a
         * place to encode metadata (a `read_file` of a file mentioning `exit_code: 124` would
         * otherwise show a fabricated exit badge).
         */
        val exitCode: Int? = null,
    ) : AgentEvent

    /**
     * A complete assistant message.
     *
     * Note: this does **not** persist anything by itself. The ViewModel mirrors it into the open
     * conversation and the turn is written to disk when it ends — see `ChatViewModel.persist`.
     */
    data class MessageCompleted(val message: ChatMessage) : AgentEvent

    /** A tool result message; the ViewModel turns it into (or updates) a terminal card. */
    data class ToolMessageCompleted(val message: ChatMessage) : AgentEvent

    /**
     * Token accounting for the whole turn (summed across iterations), emitted just before
     * [Done] when the provider reports it. The ViewModel turns this into a cost estimate.
     */
    data class Usage(val promptTokens: Int, val completionTokens: Int) : AgentEvent

    data class Failure(val message: String) : AgentEvent

    data object Done : AgentEvent
}

/**
 * The agent loop: stream a completion, run any requested tools, feed the results back and
 * repeat until the model answers without tool calls.
 *
 * Implemented with [channelFlow] rather than `flow` because tool output arrives from a
 * non-suspending callback (the terminal's main-thread handler), which needs [trySend].
 */
class AgentEngine(
    private val llm: LlmStreamer,
    private val tools: AgentToolRunner,
    /**
     * Resolves the one user-facing failure message (see [MAX_ITERATIONS]).
     *
     * A `(Int) -> String` rather than a `Context` on purpose: the engine needs exactly one
     * translated string, and taking a `Context` made the whole loop untestable off-device — a JVM
     * test cannot instantiate one. `AppContainer` supplies
     * `{ context.getString(R.string.error_too_many_tool_calls, it) }`.
     */
    private val tooManyToolCalls: (Int) -> String,
) {

    internal companion object {
        private const val TAG = "AgentEngine"

        /**
         * Safety net against a model that never stops calling tools.
         *
         * Internal so the test can assert the bound rather than hard-coding "12" a second time.
         */
        const val MAX_ITERATIONS = 12

        private const val ARG_COMMAND = "command"
    }

    /**
     * @param history prior conversation (user/assistant/tool messages), oldest first —
     *                the system message is prepended internally.
     * @param allowCommandExecution when false, `run_shell` is withheld from the model
     *                entirely (the UI exposes this as a safety switch).
     */
    fun run(
        config: ApiConfig,
        history: List<ChatMessage>,
        allowCommandExecution: Boolean = true,
    ): Flow<AgentEvent> = channelFlow {
        val availableTools = buildList {
            if (allowCommandExecution) {
                addAll(tools.definitions.filter { it.function.name == AgentToolRunner.RUN_SHELL })
            }
            addAll(tools.definitions.filter { it.function.name != AgentToolRunner.RUN_SHELL })
        }

        val messages = ArrayList<ChatMessage>(history.size + 2)
        messages += ChatMessage.system(config.systemPrompt ?: Prompt.SYSTEM_PROMPT)
        messages += history

        // Token totals for the turn, summed over every model call it took to resolve.
        var totalPromptTokens = 0
        var totalCompletionTokens = 0

        suspend fun emitUsage() {
            if (totalPromptTokens > 0 || totalCompletionTokens > 0) {
                send(AgentEvent.Usage(totalPromptTokens, totalCompletionTokens))
            }
        }

        var iteration = 0
        while (iteration < MAX_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            iteration++

            val text = StringBuilder()
            val pendingCalls = LinkedHashMap<Int, ToolCallAccumulator>()
            var finished: String? = null
            var failed = false

            llm.stream(config, messages, availableTools).collect { event ->
                when (event) {
                    is LlmEvent.Text -> {
                        text.append(event.delta)
                        send(AgentEvent.TextDelta(event.delta))
                    }

                    is LlmEvent.Reasoning -> send(AgentEvent.ReasoningDelta(event.delta))

                    is LlmEvent.ToolCall -> pendingCalls.accumulate(event)

                    is LlmEvent.Usage -> {
                        totalPromptTokens += event.promptTokens
                        totalCompletionTokens += event.completionTokens
                    }

                    // Don't let the `[DONE]` sentinel wipe a real reason: it also emits Finished,
                    // with a null reason, so a plain assignment would always log null.
                    is LlmEvent.Finished -> finished = event.finishReason ?: finished

                    is LlmEvent.Failure -> {
                        failed = true
                        send(AgentEvent.Failure(event.message))
                    }
                }
            }

            if (failed) {
                emitUsage()
                send(AgentEvent.Done)
                return@channelFlow
            }

            val toolCalls = pendingCalls.toToolCalls()
            val assistantMessage = ChatMessage(
                role = ChatMessage.ROLE_ASSISTANT,
                content = MessageContent.of(text.toString()),
                toolCalls = toolCalls.takeIf { it.isNotEmpty() },
            )
            messages += assistantMessage
            send(AgentEvent.MessageCompleted(assistantMessage))

            if (toolCalls.isEmpty()) {
                Log.d(TAG, "Turn finished after $iteration iteration(s), finishReason=$finished")
                emitUsage()
                send(AgentEvent.Done)
                return@channelFlow
            }

            for (call in toolCalls) {
                currentCoroutineContext().ensureActive()

                val name = call.function.name
                send(
                    AgentEvent.ToolStarted(
                        toolCallId = call.id,
                        name = name,
                        summary = summarise(name, call.function.arguments),
                        arguments = call.function.arguments,
                    ),
                )

                val allowed = allowCommandExecution || name != AgentToolRunner.RUN_SHELL
                val result = if (!allowed) {
                    ToolResult.of(
                        "Error: command execution is disabled by the user. Ask them to enable the " +
                            "'Allow command execution' switch and try again.",
                    )
                } else {
                    tools.execute(name, call.function.arguments) { chunk ->
                        // Invoked synchronously from the shell's main-thread callback.
                        trySend(AgentEvent.ToolOutput(call.id, chunk))
                    }
                }

                send(
                    AgentEvent.ToolFinished(
                        toolCallId = call.id,
                        name = name,
                        result = result.text,
                        isError = result.isError,
                        exitCode = result.exitCode,
                    ),
                )

                val toolMessage = ChatMessage.tool(call.id, name, result.text)
                messages += toolMessage
                send(AgentEvent.ToolMessageCompleted(toolMessage))
            }
        }

        Log.w(TAG, "Hit MAX_ITERATIONS ($MAX_ITERATIONS) without a final answer")
        emitUsage()
        send(AgentEvent.Failure(tooManyToolCalls(MAX_ITERATIONS)))
        send(AgentEvent.Done)
    }

    // ------------------------------------------------------------------ helpers

    private class ToolCallAccumulator {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private fun MutableMap<Int, ToolCallAccumulator>.accumulate(event: LlmEvent.ToolCall) {
        val accumulator = getOrPut(event.index) { ToolCallAccumulator() }
        event.id?.let { accumulator.id = it }
        event.name?.takeIf { it.isNotBlank() }?.let { accumulator.name = it }
        event.argumentsDelta?.let { accumulator.arguments.append(it) }
    }

    private fun Map<Int, ToolCallAccumulator>.toToolCalls(): List<ToolCall> =
        entries.sortedBy { it.key }.mapNotNull { (index, accumulator) ->
            val name = accumulator.name ?: return@mapNotNull null
            ToolCall(
                id = accumulator.id ?: "call_$index",
                function = FunctionCall(
                    name = name,
                    arguments = accumulator.arguments.toString().ifBlank { "{}" },
                ),
            )
        }

    /** A one-line preview of a tool call, for the terminal card header. */
    private fun summarise(name: String, argumentsJson: String): String {
        val command = runCatching {
            val element = ChatJson.parseToJsonElement(argumentsJson.ifBlank { "{}" })
            ((element as? JsonObject)?.get(ARG_COMMAND) as? JsonPrimitive)?.content
        }.getOrNull()

        if (!command.isNullOrBlank()) {
            return command.lineSequence().first().trim().take(200)
        }

        // Fall back to a compact "key=value" rendering of the raw arguments.
        return argumentsJson
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)
            .ifBlank { name }
    }
}
