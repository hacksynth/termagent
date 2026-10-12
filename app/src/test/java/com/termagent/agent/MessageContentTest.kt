package com.termagent.agent

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for the multimodal `content` union.
 *
 * This is the part of the attachment feature that a wrong guess silently breaks: providers accept
 * `"content": "…"` and `"content": [ … ]` but nothing else, and a malformed request surfaces as an
 * opaque HTTP 400 from a third-party server. So the exact JSON is asserted here rather than
 * verified by hand against a live endpoint.
 *
 * Pure JVM: [MessageContent] and [ChatMessage] deliberately have no Android dependencies, and these
 * tests use the production codec ([ChatJson]).
 */
class MessageContentTest {

    private val json = ChatJson

    @Test
    fun `text-only content is encoded as a plain string`() {
        val message = ChatMessage.user("hello")
        val encoded = json.encodeToString(ChatMessage.serializer(), message)

        assertEquals("""{"role":"user","content":"hello"}""", encoded)
    }

    @Test
    fun `null content is omitted entirely`() {
        val message = ChatMessage(role = "assistant")
        val encoded = json.encodeToString(ChatMessage.serializer(), message)

        assertFalse("content must not be serialised as null", encoded.contains("content"))
        assertEquals("""{"role":"assistant"}""", encoded)
    }

    @Test
    fun `image content is encoded as an array of typed parts`() {
        val message = ChatMessage.user(
            MessageContent.Parts(
                listOf(
                    ContentPart.Image("data:image/jpeg;base64,AAAA"),
                    ContentPart.Text("[image 1: shot.png]\nwhat is wrong here?"),
                ),
            ),
        )

        val encoded = json.encodeToString(ChatMessage.serializer(), message)
        val root = json.parseToJsonElement(encoded).jsonObject
        val parts = root["content"]!!.jsonArray

        assertEquals(2, parts.size)

        val image = parts[0].jsonObject
        assertEquals("image_url", image["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,AAAA",
            image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )

        val text = parts[1].jsonObject
        assertEquals("text", text["type"]!!.jsonPrimitive.content)
        assertTrue(text["text"]!!.jsonPrimitive.content.contains("what is wrong here?"))
    }

    @Test
    fun `image part carries no detail field when it is not set`() {
        val encoded = json.encodeToString(
            MessageContent.serializer(),
            MessageContent.Parts(listOf(ContentPart.Image("data:image/png;base64,BB"))),
        )

        assertFalse(encoded.contains("detail"))
    }

    @Test
    fun `a parts array round-trips through decode`() {
        val original = MessageContent.Parts(
            listOf(
                ContentPart.Image("data:image/jpeg;base64,AAAA", "high"),
                ContentPart.Text("caption"),
            ),
        )

        val text = json.encodeToString(MessageContent.serializer(), original)
        val decoded = json.decodeFromString(MessageContent.serializer(), text)

        assertEquals(original, decoded)
    }

    @Test
    fun `a plain string decodes to Text`() {
        val decoded = json.decodeFromString(MessageContent.serializer(), "\"just text\"")

        assertEquals(MessageContent.Text("just text"), decoded)
    }

    @Test
    fun `an unknown part type is preserved rather than dropped`() {
        val wire = """[{"type":"text","text":"hi"},{"type":"audio","audio":{"url":"x"}}]"""

        val decoded = json.decodeFromString(MessageContent.serializer(), wire)
        val parts = (decoded as MessageContent.Parts).parts

        assertEquals(2, parts.size)
        val other = parts[1] as ContentPart.Other
        assertEquals("audio", other.type)
        assertEquals("x", other.raw["audio"]!!.jsonObject["url"]!!.jsonPrimitive.content)

        // Re-encoding must not lose it (the model's own history is replayed verbatim).
        val reencoded = json.encodeToString(MessageContent.serializer(), decoded)
        assertTrue(reencoded.contains("\"type\":\"audio\""))
        assertTrue(reencoded.contains("\"url\":\"x\""))
    }

    @Test
    fun `plainText concatenates text parts and ignores images`() {
        val content = MessageContent.Parts(
            listOf(
                ContentPart.Image("data:image/jpeg;base64,AAAA"),
                ContentPart.Text("first"),
                ContentPart.Text("second"),
            ),
        )

        assertEquals("first\nsecond", content.plainText)
    }

    @Test
    fun `of puts the caption last so the model reads the image first`() {
        val content = MessageContent.of(
            text = "what is this?",
            parts = listOf(ContentPart.Image("data:image/jpeg;base64,AAAA")),
        )

        val parts = (content as MessageContent.Parts).parts
        assertTrue(parts[0] is ContentPart.Image)
        assertEquals(ContentPart.Text("what is this?"), parts[1])
    }

    @Test
    fun `of returns null for nothing at all`() {
        assertNull(MessageContent.of(""))
        assertNull(MessageContent.of(null))
        assertNull(MessageContent.of(null, emptyList()))
    }

    @Test
    fun `of with only parts keeps the parts form`() {
        val content = MessageContent.of(null, listOf(ContentPart.Image("data:image/jpeg;base64,AA")))

        assertTrue(content is MessageContent.Parts)
    }

    @Test
    fun `tool message keeps its id and name`() {
        val encoded = json.encodeToString(
            ChatMessage.serializer(),
            ChatMessage.tool("call_1", "view_image", "a cat"),
        )

        val root = json.parseToJsonElement(encoded).jsonObject
        assertEquals("tool", root["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", root["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("view_image", root["name"]!!.jsonPrimitive.content)
        assertEquals("a cat", root["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a full multimodal request keeps the vision payload on the user turn`() {
        val request = ChatRequest(
            model = "gpt-4o-mini",
            messages = listOf(
                ChatMessage.system("you are a shell agent"),
                ChatMessage.user(
                    MessageContent.Parts(
                        listOf(
                            ContentPart.Image("data:image/jpeg;base64,AAAA"),
                            ContentPart.Text("[image 1: bug.png]"),
                        ),
                    ),
                ),
            ),
            stream = false,
        )

        val encoded = json.encodeToString(ChatRequest.serializer(), request)
        val root = json.parseToJsonElement(encoded).jsonObject
        val messages = root["messages"]!!.jsonArray

        // The system prompt stays a plain string — only the user turn is multimodal.
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("shell agent"))
        assertEquals(2, messages[1].jsonObject["content"]!!.jsonArray.size)
    }

    @Test
    fun `the non-streaming response envelope decodes a vision answer`() {
        val wire = """
            {
              "id": "x",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "A terminal window."},
                 "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 900, "completion_tokens": 12, "total_tokens": 912}
            }
        """.trimIndent()

        val parsed = json.decodeFromString(ChatResponse.serializer(), wire)

        assertEquals("A terminal window.", parsed.choices[0].message?.textContent)
        assertEquals("stop", parsed.choices[0].finishReason)
        assertEquals(900, parsed.usage?.promptTokens)
        assertEquals(12, parsed.usage?.completionTokens)
    }

    @Test
    fun `an error envelope decodes the provider message`() {
        val wire = """{"error":{"message":"model does not support image input","type":"invalid_request_error"}}"""

        val parsed = json.decodeFromString(ApiErrorEnvelope.serializer(), wire)

        assertEquals("model does not support image input", parsed.error?.message)
    }

    @Test
    fun `an unexpected content shape decodes without throwing`() {
        // Some servers echo `content` as an object or as `null`. Decoding must not throw: an
        // exception while reading a chunk would abort a turn that is otherwise fine.
        val asObject = json.decodeFromString(
            MessageContent.serializer(),
            """{"type":"text","text":"flat"}""",
        )
        assertEquals(MessageContent.Text("""{"type":"text","text":"flat"}"""), asObject)

        assertEquals(
            MessageContent.Text(""),
            json.decodeFromString(MessageContent.serializer(), "null"),
        )

        assertEquals(
            MessageContent.Parts(emptyList()),
            json.decodeFromString(MessageContent.serializer(), "[]"),
        )
    }

    @Test
    fun `assistant tool-call messages still encode their calls`() {
        val encoded = json.encodeToString(
            ChatMessage.serializer(),
            ChatMessage(
                role = ChatMessage.ROLE_ASSISTANT,
                content = null,
                toolCalls = listOf(
                    ToolCall(
                        id = "call_9",
                        function = FunctionCall("view_image", """{"path":"shot.png"}"""),
                    ),
                ),
            ),
        )

        val root = json.parseToJsonElement(encoded).jsonObject
        // A tool-call-only turn must not send `"content": null` — some servers reject it.
        assertFalse(root.containsKey("content"))
        assertEquals("call_9", root["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }
}
