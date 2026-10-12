package com.termagent.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the agent loop.
 *
 * `ARCHITECTURE.md` has always claimed the loop "is designed to take a fake `LlmClient`" — this is
 * that fake, and these are the tests it was designed for. The loop is where the protocol rules live
 * (a tool call must be answered by exactly one `tool` message; the assistant message that requested
 * it must precede it; the iteration count is bounded), and every one of those rules is invisible
 * until a real provider rejects a request — usually several turns later, in a *different* request
 * from the one that broke it. So the loop is tested here against scripted streams instead.
 *
 * Two seams make this possible, both added for these tests:
 *  * [LlmStreamer] — a scripted list of [LlmEvent]s per call, and a record of the messages the loop
 *    *sent*, which is what the protocol assertions are actually about;
 *  * [AgentToolRunner] — canned [ToolResult]s, which also lets a test deliver streamed output
 *    without a pty.
 */
class AgentEngineTest {

    // ------------------------------------------------------------------ fakes

    /** One scripted completion: the events a provider would stream for one request. */
    private class Reply(vararg events: LlmEvent) {
        val events: List<LlmEvent> = events.toList()
    }

    private class FakeLlm(private val replies: List<Reply>) : LlmStreamer {
        /** The `messages` argument of every call, in order — the wire conversation per iteration. */
        val sentMessages = mutableListOf<List<ChatMessage>>()

        /** The `tools` argument of every call, in order. */
        val sentTools = mutableListOf<List<ToolDefinition>?>()

        private var call = 0

        override fun stream(
            config: ApiConfig,
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
        ): Flow<LlmEvent> {
            sentMessages += messages.toList()
            sentTools += tools
            // Past the end of the script: behave like a model that answers with nothing, so a test
            // that forgot a reply fails on the assertions rather than on an exception.
            val reply = replies.getOrNull(call) ?: Reply(LlmEvent.Finished("stop"))
            call++
            return flow { reply.events.forEach { emit(it) } }
        }
    }

    private class FakeTools(
        /** Tool schemas the loop advertises; defaults to one shell tool. */
        override val definitions: List<ToolDefinition> = listOf(shellTool()),
        private val result: (String) -> ToolResult = { ToolResult("exit_code: 0\nok", false, 0) },
        private val chunks: List<String> = emptyList(),
    ) : AgentToolRunner {
        val executed = mutableListOf<Pair<String, String>>()

        override suspend fun execute(
            name: String,
            argumentsJson: String,
            onOutput: (String) -> Unit,
        ): ToolResult {
            executed += name to argumentsJson
            chunks.forEach(onOutput)
            return result(name)
        }
    }

    private fun engine(llm: LlmStreamer, tools: AgentToolRunner) =
        AgentEngine(llm, tools) { "too many tool calls ($it)" }

    private fun run(engine: AgentEngine, history: List<ChatMessage> = listOf(ChatMessage.user("hi")),
                    allowCommandExecution: Boolean = true): List<AgentEvent> = runBlocking {
        engine.run(ApiConfig(), history, allowCommandExecution).toList()
    }

    private fun List<AgentEvent>.types(): List<String> = map { it::class.simpleName ?: "?" }

    private val oneShellTool = listOf(shellTool())

    private fun shellCall(
        index: Int = 0,
        id: String = "call_1",
        name: String = AgentToolRunner.RUN_SHELL,
        arguments: String = """{"command":"ls"}""",
    ): LlmEvent.ToolCall = LlmEvent.ToolCall(index, id, name, arguments)

    // ------------------------------------------------------------------ a direct answer

    @Test
    fun `a direct answer streams text and finishes without touching a tool`() {
        val llm = FakeLlm(
            listOf(Reply(LlmEvent.Text("Hello"), LlmEvent.Text(" there"), LlmEvent.Finished("stop"))),
        )
        val tools = FakeTools()

        val events = run(engine(llm, tools))

        assertEquals(
            listOf("TextDelta", "TextDelta", "MessageCompleted", "Done"),
            events.types(),
        )
        assertEquals("Hello there", events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text })
        assertTrue("no tool may run", tools.executed.isEmpty())
        assertNull("no tool was advertised to run", events.filterIsInstance<AgentEvent.ToolStarted>().firstOrNull())
    }

    @Test
    fun `the system prompt is prepended exactly once, before the history`() {
        val llm = FakeLlm(listOf(Reply(LlmEvent.Finished("stop"))))

        run(engine(llm, FakeTools()), history = listOf(ChatMessage.user("hi")))

        val sent = llm.sentMessages.single()
        assertEquals(ChatMessage.ROLE_SYSTEM, sent.first().role)
        assertEquals(Prompt.SYSTEM_PROMPT, sent.first().textContent)
        assertEquals("hi", sent[1].textContent)
        assertEquals("only one system message", 1, sent.count { it.role == ChatMessage.ROLE_SYSTEM })
    }

    @Test
    fun `a custom system prompt replaces the default`() {
        val llm = FakeLlm(listOf(Reply(LlmEvent.Finished("stop"))))

        runBlocking {
            engine(llm, FakeTools())
                .run(ApiConfig(systemPrompt = "be terse"), listOf(ChatMessage.user("hi")))
                .toList()
        }

        assertEquals("be terse", llm.sentMessages.single().first().textContent)
    }

    // ------------------------------------------------------------------ one tool call

    @Test
    fun `a tool call is executed and its result fed back before the answer`() {
        val llm = FakeLlm(
            listOf(
                Reply(shellCall(), LlmEvent.Finished("tool_calls")),
                Reply(LlmEvent.Text("done"), LlmEvent.Finished("stop")),
            ),
        )
        val tools = FakeTools(
            result = { ToolResult("exit_code: 0\n--- output ---\nfile.txt", false, 0) },
            chunks = listOf("file", ".txt"),
        )

        val events = run(engine(llm, tools))

        assertEquals(
            listOf(
                // The assistant turn is persisted FIRST, before its cards: ChatViewModel keys card
                // ordering off the assistant entry, so a card emitted before it would show up
                // before the message that asked for it.
                "MessageCompleted",
                "ToolStarted",
                "ToolOutput",
                "ToolOutput",
                "ToolFinished",
                "ToolMessageCompleted",
                "TextDelta",
                "MessageCompleted",
                "Done",
            ),
            events.types(),
        )
        assertEquals(listOf(AgentToolRunner.RUN_SHELL to """{"command":"ls"}"""), tools.executed)

        val started = events.filterIsInstance<AgentEvent.ToolStarted>().single()
        assertEquals("call_1", started.toolCallId)
        assertEquals("ls", started.summary)

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals(0, finished.exitCode)
        assertFalse(finished.isError)
    }

    @Test
    fun `the second request replays the assistant tool call and the tool result`() {
        val llm = FakeLlm(
            listOf(
                Reply(shellCall(), LlmEvent.Finished("tool_calls")),
                Reply(LlmEvent.Text("done"), LlmEvent.Finished("stop")),
            ),
        )

        run(engine(llm, FakeTools(result = { ToolResult("exit_code: 0\nOUTPUT", false, 0) })))

        val second = llm.sentMessages[1]
        val assistant = second.single { it.role == ChatMessage.ROLE_ASSISTANT }
        val tool = second.single { it.role == ChatMessage.ROLE_TOOL }

        assertEquals(1, assistant.toolCalls?.size)
        assertEquals("call_1", assistant.toolCalls?.first()?.id)
        assertEquals(AgentToolRunner.RUN_SHELL, assistant.toolCalls?.first()?.function?.name)
        assertEquals("""{"command":"ls"}""", assistant.toolCalls?.first()?.function?.arguments)

        assertEquals("call_1", tool.toolCallId)
        assertEquals(AgentToolRunner.RUN_SHELL, tool.name)
        assertEquals("exit_code: 0\nOUTPUT", tool.textContent)

        // Order matters: the assistant message must come immediately before its tool message.
        assertTrue(
            "the tool message must directly follow the assistant message",
            second.indexOf(assistant) < second.indexOf(tool),
        )
    }

    @Test
    fun `tool arguments split across frames are reassembled`() {
        val llm = FakeLlm(
            listOf(
                Reply(
                    // Exactly how a provider streams it: the id/name arrive once, then the JSON
                    // argument string is chopped into arbitrary fragments.
                    LlmEvent.ToolCall(0, "call_9", AgentToolRunner.RUN_SHELL, """{"comm"""),
                    LlmEvent.ToolCall(0, null, null, """and":"ec"""),
                    LlmEvent.ToolCall(0, null, null, """ho hi"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                Reply(LlmEvent.Text("ok"), LlmEvent.Finished("stop")),
            ),
        )
        val tools = FakeTools()

        val events = run(engine(llm, tools))

        assertEquals(listOf(AgentToolRunner.RUN_SHELL to """{"command":"echo hi"}"""), tools.executed)
        assertEquals(
            """{"command":"echo hi"}""",
            events.filterIsInstance<AgentEvent.ToolStarted>().single().arguments,
        )
    }

    @Test
    fun `index order decides the order of calls, not the order they arrive in`() {
        val llm = FakeLlm(
            listOf(
                Reply(
                    shellCall(index = 1, id = "second", arguments = """{"command":"b"}"""),
                    shellCall(index = 0, id = "first", arguments = """{"command":"a"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                Reply(LlmEvent.Text("ok"), LlmEvent.Finished("stop")),
            ),
        )
        val tools = FakeTools()

        run(engine(llm, tools))

        assertEquals(
            listOf(AgentToolRunner.RUN_SHELL to """{"command":"a"}""", AgentToolRunner.RUN_SHELL to """{"command":"b"}"""),
            tools.executed,
        )
    }

    @Test
    fun `a tool call with no name is dropped rather than executed`() {
        val llm = FakeLlm(
            listOf(
                Reply(
                    // A provider that sends arguments without ever naming a tool. Executing this
                    // would mean guessing; dropping it leaves a plain assistant turn.
                    LlmEvent.ToolCall(0, "call_x", null, """{"command":"ls"}"""),
                    LlmEvent.Finished("stop"),
                ),
            ),
        )
        val tools = FakeTools()

        val events = run(engine(llm, tools))

        assertTrue(tools.executed.isEmpty())
        assertTrue(events.none { it is AgentEvent.ToolStarted })
        assertTrue(events.contains(AgentEvent.Done))
    }

    // ------------------------------------------------------------------ the safety switch

    @Test
    fun `the safety switch withholds run_shell but keeps the other tools`() {
        val other = ToolDefinition("function", FunctionDefinition("read_file", "read", JsonObject(emptyMap())))
        val llm = FakeLlm(listOf(Reply(LlmEvent.Finished("stop"))))
        val tools = FakeTools(definitions = listOf(shellTool(), other))

        run(engine(llm, tools), allowCommandExecution = false)

        val advertised = llm.sentTools.single()?.map { it.function.name }
        assertEquals(listOf("read_file"), advertised)
    }

    @Test
    fun `the safety switch is enforced even if the model calls run_shell anyway`() {
        val llm = FakeLlm(
            listOf(
                Reply(shellCall(), LlmEvent.Finished("tool_calls")),
                Reply(LlmEvent.Text("ok"), LlmEvent.Finished("stop")),
            ),
        )
        val tools = FakeTools()

        val events = run(engine(llm, tools), allowCommandExecution = false)

        assertTrue("the tool must not run", tools.executed.isEmpty())
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.isError)
        assertTrue(finished.result.contains("disabled"))
    }

    // ------------------------------------------------------------------ usage accounting

    @Test
    fun `usage is summed across iterations and reported once, before Done`() {
        val llm = FakeLlm(
            listOf(
                Reply(shellCall(), LlmEvent.Usage(10, 5), LlmEvent.Finished("tool_calls")),
                Reply(LlmEvent.Text("ok"), LlmEvent.Usage(20, 7), LlmEvent.Finished("stop")),
            ),
        )

        val events = run(engine(llm, FakeTools()))

        val usage = events.filterIsInstance<AgentEvent.Usage>().single()
        assertEquals(30, usage.promptTokens)
        assertEquals(12, usage.completionTokens)
        assertEquals("Done", events.last().let { it::class.simpleName })
        assertEquals("Usage must precede Done", "Usage", events[events.size - 2]::class.simpleName)
    }

    @Test
    fun `no usage event is emitted when the provider reports none`() {
        val llm = FakeLlm(listOf(Reply(LlmEvent.Text("hi"), LlmEvent.Finished("stop"))))

        val events = run(engine(llm, FakeTools()))

        assertTrue(events.none { it is AgentEvent.Usage })
    }

    // ------------------------------------------------------------------ failure

    @Test
    fun `a failure is surfaced and ends the turn without a tool result`() {
        val llm = FakeLlm(
            listOf(Reply(LlmEvent.Text("partial"), LlmEvent.Failure("connection reset"))),
        )
        val tools = FakeTools()

        val events = run(engine(llm, tools))

        assertEquals(listOf("TextDelta", "Failure", "Done"), events.types())
        assertEquals("connection reset", events.filterIsInstance<AgentEvent.Failure>().single().message)
        assertTrue("no assistant message is persisted for a failed turn", events.none { it is AgentEvent.MessageCompleted })
        assertTrue(tools.executed.isEmpty())
    }

    @Test
    fun `a failure in a later iteration still reports accumulated usage`() {
        val llm = FakeLlm(
            listOf(
                Reply(shellCall(), LlmEvent.Usage(10, 5), LlmEvent.Finished("tool_calls")),
                Reply(LlmEvent.Usage(1, 1), LlmEvent.Failure("boom")),
            ),
        )

        val events = run(engine(llm, FakeTools()))

        val usage = events.filterIsInstance<AgentEvent.Usage>().single()
        assertEquals(11, usage.promptTokens)
        assertEquals(6, usage.completionTokens)
    }

    // ------------------------------------------------------------------ the iteration bound

    @Test
    fun `a model that never stops calling tools is cut off at the iteration cap`() {
        // Always asks for a tool: the loop must stop on its own rather than spin forever.
        val llm = FakeLlm(List(AgentEngine.MAX_ITERATIONS * 2) { Reply(shellCall(), LlmEvent.Finished("tool_calls")) })
        val tools = FakeTools()

        val events = run(engine(llm, tools))

        assertEquals(AgentEngine.MAX_ITERATIONS, tools.executed.size)
        assertEquals(AgentEngine.MAX_ITERATIONS, llm.sentMessages.size)
        assertEquals("Done", events.last()::class.simpleName)
        val failure = events.filterIsInstance<AgentEvent.Failure>().single()
        assertEquals("too many tool calls (${AgentEngine.MAX_ITERATIONS})", failure.message)
    }

    // ------------------------------------------------------------------ finish reason handling

    @Test
    fun `a null finish reason does not overwrite a real one`() {
        // The `[DONE]` sentinel also emits Finished, with a null reason. A plain assignment would
        // always leave the loop logging null. Asserted through the observable effect: the loop
        // still ends normally, and the real reason was the one that stuck (it is only logged, so
        // the observable part is that the turn completes with exactly one iteration).
        val llm = FakeLlm(
            listOf(
                Reply(LlmEvent.Text("hi"), LlmEvent.Finished("stop"), LlmEvent.Finished(null)),
            ),
        )

        val events = run(engine(llm, FakeTools()))

        assertEquals(1, llm.sentMessages.size)
        assertTrue(events.contains(AgentEvent.Done))
    }

    // ------------------------------------------------------------------ summaries

    @Test
    fun `the card summary is the command's first line, without a trailing newline`() {
        val llm = FakeLlm(
            listOf(
                Reply(
                    shellCall(arguments = """{"command":"ls -la\nrm -rf /"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                Reply(LlmEvent.Text("ok"), LlmEvent.Finished("stop")),
            ),
        )

        val events = run(engine(llm, FakeTools()))

        assertEquals("ls -la", events.filterIsInstance<AgentEvent.ToolStarted>().single().summary)
    }

    @Test
    fun `a non-shell tool falls back to a rendering of its arguments`() {
        val other = ToolDefinition("function", FunctionDefinition("read_file", "read", JsonObject(emptyMap())))
        val llm = FakeLlm(
            listOf(
                Reply(
                    LlmEvent.ToolCall(0, "call_r", "read_file", """{"path":"a.txt"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                Reply(LlmEvent.Text("ok"), LlmEvent.Finished("stop")),
            ),
        )

        val events = run(engine(llm, FakeTools(definitions = listOf(shellTool(), other))))

        assertEquals("""{"path":"a.txt"}""", events.filterIsInstance<AgentEvent.ToolStarted>().single().summary)
    }

    // ------------------------------------------------------------------ reasoning

    @Test
    fun `reasoning deltas are forwarded separately from the visible text`() {
        val llm = FakeLlm(
            listOf(Reply(LlmEvent.Reasoning("think"), LlmEvent.Text("answer"), LlmEvent.Finished("stop"))),
        )

        val events = run(engine(llm, FakeTools()))

        assertEquals("think", events.filterIsInstance<AgentEvent.ReasoningDelta>().single().text)
        val completed = events.filterIsInstance<AgentEvent.MessageCompleted>().single()
        assertEquals("answer", completed.message.textContent)
        assertNotNull(completed.message)
    }
}

/** The one tool schema these tests advertise unless a case needs a second one. */
private fun shellTool(): ToolDefinition =
    ToolDefinition("function", FunctionDefinition(AgentToolRunner.RUN_SHELL, "run", JsonObject(emptyMap())))
