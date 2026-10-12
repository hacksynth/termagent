package com.termagent.agent

import com.termagent.data.Attachment
import com.termagent.data.ChatEntry
import com.termagent.data.Role
import com.termagent.data.TerminalBlock
import com.termagent.data.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the entry-log → wire-conversation translation.
 *
 * These rules are invisible until a provider rejects a request, and the rejection then surfaces on
 * the *next* turn — by which time the entries that caused it are several messages back. That is
 * exactly the kind of failure worth pinning down with a test, so every branch of [ApiHistory] is
 * covered here: the pairing, the two ways a card can be incomplete, orphaned cards, the window
 * trim, and the requirement that the conversation start with a user message.
 */
class ApiHistoryTest {

    private val noFiles: (Attachment) -> java.io.File = { java.io.File("/nonexistent") }

    private fun build(entries: List<ChatEntry>) = ApiHistory.build(entries, noFiles)

    private fun user(id: String = "u1", text: String = "hi") =
        ChatEntry(id = id, role = Role.User, text = text)

    private fun assistant(id: String = "a1", text: String = "thinking out loud") =
        ChatEntry(id = id, role = Role.Assistant, text = text)

    private fun tool(
        id: String = "t1",
        callId: String = "call_1",
        name: String = AgentToolRunner.RUN_SHELL,
        arguments: String = """{"command":"ls"}""",
        result: String? = "exit_code: 0\nok",
        output: String = "",
        status: ToolStatus = ToolStatus.Succeeded,
    ) = ChatEntry(
        id = id,
        role = Role.Tool,
        terminal = TerminalBlock(
            toolCallId = callId,
            toolName = name,
            command = "ls",
            arguments = arguments,
            output = output,
            result = result,
            status = status,
        ),
    )

    // ------------------------------------------------------------------ the basic shapes

    @Test
    fun `a plain user turn becomes a user message`() {
        val messages = build(listOf(user(text = "hello")))

        assertEquals(1, messages.size)
        assertEquals(ChatMessage.ROLE_USER, messages[0].role)
        assertEquals("hello", messages[0].textContent)
    }

    @Test
    fun `an assistant turn with text is replayed as assistant content`() {
        val messages = build(listOf(user(), assistant(text = "an answer")))

        assertEquals(listOf(ChatMessage.ROLE_USER, ChatMessage.ROLE_ASSISTANT), messages.map { it.role })
        assertEquals("an answer", messages[1].textContent)
        assertNull(messages[1].toolCalls)
    }

    @Test
    fun `a tool call is replayed as assistant tool_calls plus one tool message`() {
        val messages = build(
            listOf(
                user(),
                assistant(text = ""),
                tool(result = "exit_code: 0\nfile.txt"),
            ),
        )

        assertEquals(
            listOf(ChatMessage.ROLE_USER, ChatMessage.ROLE_ASSISTANT, ChatMessage.ROLE_TOOL),
            messages.map { it.role },
        )

        val assistantMessage = messages[1]
        val call = assistantMessage.toolCalls?.single()
        assertNotNull(call)
        assertEquals("call_1", call?.id)
        assertEquals(AgentToolRunner.RUN_SHELL, call?.function?.name)
        assertEquals("""{"command":"ls"}""", call?.function?.arguments)

        val toolMessage = messages[2]
        assertEquals("call_1", toolMessage.toolCallId)
        assertEquals(AgentToolRunner.RUN_SHELL, toolMessage.name)
        assertEquals("exit_code: 0\nfile.txt", toolMessage.textContent)
    }

    @Test
    fun `several cards after one assistant turn become several tool_calls in stored order`() {
        val messages = build(
            listOf(
                user(),
                assistant(text = ""),
                tool(id = "t1", callId = "call_1", arguments = """{"command":"a"}""", result = "A"),
                tool(id = "t2", callId = "call_2", arguments = """{"command":"b"}""", result = "B"),
            ),
        )

        assertEquals(
            listOf("call_1", "call_2"),
            messages[1].toolCalls?.map { it.id },
        )
        assertEquals(listOf(ChatMessage.ROLE_TOOL, ChatMessage.ROLE_TOOL), messages.slice(2..3).map { it.role })
        assertEquals(listOf("call_1", "call_2"), messages.slice(2..3).map { it.toolCallId })
    }

    @Test
    fun `an assistant turn carries both its text and its tool_calls`() {
        val messages = build(listOf(user(), assistant(text = "let me check"), tool()))

        assertEquals("let me check", messages[1].textContent)
        assertEquals(1, messages[1].toolCalls?.size)
    }

    // ------------------------------------------------------------------ incomplete cards

    @Test
    fun `a card with no result falls back to its streamed output`() {
        // A turn that was interrupted, or a conversation reloaded mid-turn: the card has output but
        // no final result. Sending nothing would leave the tool_call unanswered, which some servers
        // reject outright.
        val messages = build(
            listOf(
                user(),
                assistant(text = ""),
                tool(result = null, output = "partial output", status = ToolStatus.Running),
            ),
        )

        assertEquals(listOf(ChatMessage.ROLE_USER, ChatMessage.ROLE_ASSISTANT, ChatMessage.ROLE_TOOL), messages.map { it.role })
        assertEquals("partial output", messages[2].textContent)
    }

    @Test
    fun `a card with neither result nor output is left unanswered`() {
        // The assistant message keeps the tool_call (the call really happened) but no tool message
        // is emitted for it: a blank tool reply would be worse than none.
        val messages = build(
            listOf(user(), assistant(text = ""), tool(result = null, output = "   ")),
        )

        assertEquals(listOf(ChatMessage.ROLE_USER, ChatMessage.ROLE_ASSISTANT), messages.map { it.role })
        assertEquals("the tool_call is still replayed", 1, messages[1].toolCalls?.size)
    }

    @Test
    fun `a card whose terminal block is missing is ignored`() {
        val messages = build(
            listOf(
                user(),
                assistant(text = ""),
                ChatEntry(id = "t1", role = Role.Tool), // no terminal block at all
            ),
        )

        // The assistant entry has blank text and the card carries no terminal block, so there is
        // nothing at all to replay: no tool call (no data) and no prose. The entry is dropped.
        assertEquals(listOf(ChatMessage.ROLE_USER), messages.map { it.role })
    }

    // ------------------------------------------------------------------ orphans and trimming

    @Test
    fun `an orphaned card is skipped`() {
        val messages = build(listOf(user(), tool()))

        assertEquals(listOf(ChatMessage.ROLE_USER), messages.map { it.role })
    }

    @Test
    fun `a stored system entry is not replayed`() {
        // The engine prepends the system prompt itself; replaying a stored one would duplicate it.
        val messages = build(
            listOf(
                ChatEntry(id = "s1", role = Role.System, text = "you are a shell"),
                user(text = "hi"),
            ),
        )

        assertEquals(listOf(ChatMessage.ROLE_USER), messages.map { it.role })
    }

    @Test
    fun `leading assistant and tool entries are dropped so the history starts with a user turn`() {
        // The API requires the first message after `system` to be a user message. This shape occurs
        // for real when the window trim cuts into the middle of a conversation.
        val messages = build(
            listOf(
                assistant(text = "orphaned assistant"),
                tool(),
                user(text = "the real first user turn"),
            ),
        )

        assertEquals(listOf(ChatMessage.ROLE_USER), messages.map { it.role })
        assertEquals("the real first user turn", messages[0].textContent)
    }

    @Test
    fun `an assistant entry with neither text nor calls is dropped`() {
        val messages = build(listOf(user(), assistant(text = "   ")))

        assertEquals(listOf(ChatMessage.ROLE_USER), messages.map { it.role })
    }

    @Test
    fun `the history is trimmed to the newest MAX_MESSAGES, never splitting a user turn off`() {
        // 30 user turns = 30 messages; the window keeps the newest 40 at most.
        val entries = (1..30).map { user(id = "u$it", text = "turn $it") }

        val messages = build(entries)

        assertEquals(30, messages.size)
        assertEquals("turn 1", messages.first().textContent)
    }

    @Test
    fun `a long conversation is cut to the trailing window and still starts with a user turn`() {
        // 25 pairs = 50 messages, trimmed to 40: messages[10..49], so the oldest surviving message
        // is q6. The cut happens to land on a user turn here, which is why this case is about the
        // window and the separate test above is about the leading-user rule.
        val entries = (1..25).flatMap {
            listOf(user(id = "u$it", text = "q$it"), assistant(id = "a$it", text = "a$it"))
        }

        val messages = build(entries)

        assertEquals(ApiHistory.MAX_MESSAGES, messages.size)
        assertEquals("the window must start with a user message", ChatMessage.ROLE_USER, messages.first().role)
        assertEquals("q6", messages.first().textContent)
    }

    @Test
    fun `an empty conversation produces an empty history`() {
        assertTrue(build(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------ attachments

    @Test
    fun `an attachment that no longer resolves does not break the turn`() {
        val entry = user(text = "look at this").copy(
            attachments = listOf(
                Attachment(
                    id = "att1",
                    name = "photo.png",
                    mimeType = "image/png",
                    path = "attachments/photo.png",
                    sizeBytes = 10,
                ),
            ),
        )

        // `fileOf` points at a path that does not exist here — the file was deleted from disk.
        val messages = build(listOf(entry))

        assertEquals(1, messages.size)
        assertEquals(ChatMessage.ROLE_USER, messages[0].role)

        // The caption survives, and the attachment is still named in the text (an image the model
        // cannot see is worth *mentioning*, since the user believes it is attached). What is
        // dropped is only the image part — the base64 payload there would be a lie.
        val text = messages[0].textContent ?: ""
        assertTrue("the caption must survive", text.startsWith("look at this"))
        assertTrue("the missing attachment is still named", text.contains("photo.png"))
        val parts = (messages[0].content as? MessageContent.Parts)?.parts.orEmpty()
        assertTrue("no image part may be sent for a file that is gone", parts.none { it is ContentPart.Image })
    }
}
