package com.termagent.agent

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The `content` field of a chat message.
 *
 * The OpenAI wire format accepts exactly two shapes here:
 *
 * * a plain string — `"content": "hello"`, used by everything that is not multimodal, and
 * * an array of typed parts — `"content": [{"type":"text",…},{"type":"image_url",…}]`, which is
 *   how images are attached.
 *
 * Modelling this as a sealed type (instead of a raw `JsonElement`) keeps the call sites honest:
 * a caller that wants to send an image has to say so, and the two shapes cannot be mixed up.
 * The serializer below is hand-written because the two shapes have *different JSON kinds*, which
 * kotlinx.serialization's class-discriminator polymorphism cannot express on its own.
 *
 * Text-only messages are deliberately still encoded as a plain string: many
 * "OpenAI-compatible" servers accept the array form for images only, and a string is what every
 * one of them understands.
 */
@Serializable(with = MessageContentSerializer::class)
sealed interface MessageContent {

    /** The classic `"content": "…"` form. */
    data class Text(val text: String) : MessageContent

    /** The multimodal form, in order: an image part may precede the caption text. */
    data class Parts(val parts: List<ContentPart>) : MessageContent

    /**
     * The visible text of this content.
     *
     * Used for transcript rendering and for the "did the model send anything" checks; images and
     * other non-text parts are dropped, since they have no textual form.
     */
    val plainText: String
        get() = when (this) {
            is Text -> text
            is Parts -> parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
        }

    companion object {
        /** [text] as plain content, or `null` when it is empty (so the field is omitted). */
        fun of(text: String?): MessageContent? =
            text?.takeIf { it.isNotEmpty() }?.let(::Text)

        /**
         * Content for a user turn: a bare string when there is nothing multimodal, otherwise the
         * parts form with [text] appended last.
         */
        fun of(text: String?, parts: List<ContentPart>): MessageContent? {
            val trimmed = text?.takeIf { it.isNotEmpty() }
            return when {
                parts.isEmpty() -> of(trimmed)
                trimmed == null -> Parts(parts)
                else -> Parts(parts + ContentPart.Text(trimmed))
            }
        }
    }
}

/**
 * One element of a [MessageContent.Parts] array.
 *
 * [Other] exists so an unknown part type survives a decode/re-encode round-trip instead of being
 * silently dropped — providers keep adding kinds (audio, video, files) and a dropped part would
 * corrupt the replayed history.
 */
sealed interface ContentPart {

    data class Text(val text: String) : ContentPart

    /**
     * An `image_url` part.
     *
     * [url] is either a `data:` URL carrying the base64-encoded bytes (what the app sends) or a
     * plain `https:` URL (what some servers echo back). [detail] is the optional `low` / `high` /
     * `auto` hint.
     */
    data class Image(val url: String, val detail: String? = null) : ContentPart

    /** A part this app does not model, kept verbatim under its own `type`. */
    data class Other(val type: String, val raw: JsonObject) : ContentPart
}

/** Reads and writes [MessageContent] in either of its two wire shapes. */
object MessageContentSerializer : KSerializer<MessageContent> {

    private val delegate = JsonElement.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: MessageContent) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("MessageContent can only be encoded to JSON")
        json.encodeJsonElement(value.toJson())
    }

    override fun deserialize(decoder: Decoder): MessageContent {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("MessageContent can only be decoded from JSON")
        return fromJson(json.decodeJsonElement())
    }

    // ------------------------------------------------------------------ encode

    private fun MessageContent.toJson(): JsonElement = when (this) {
        is MessageContent.Text -> JsonPrimitive(text)
        is MessageContent.Parts -> buildJsonArray { parts.forEach { add(it.toJson()) } }
    }

    private fun ContentPart.toJson(): JsonObject = when (this) {
        is ContentPart.Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        is ContentPart.Image -> buildJsonObject {
            put("type", "image_url")
            put(
                "image_url",
                buildJsonObject {
                    put("url", url)
                    detail?.let { put("detail", it) }
                },
            )
        }

        is ContentPart.Other -> buildJsonObject {
            put("type", type)
            raw.forEach { (key, element) -> put(key, element) }
        }
    }

    // ------------------------------------------------------------------ decode

    private fun fromJson(element: JsonElement): MessageContent = when (element) {
        is JsonNull -> MessageContent.Text("")
        is JsonPrimitive -> MessageContent.Text(element.content)
        is JsonArray -> MessageContent.Parts(element.mapNotNull(::partFromJson))
        is JsonObject -> MessageContent.Text(element.toString())
    }

    private fun partFromJson(element: JsonElement): ContentPart? {
        val obj = element as? JsonObject ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull

        return when (type) {
            "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.let(ContentPart::Text)

            "image_url" -> {
                val image = obj["image_url"] as? JsonObject ?: return null
                val url = image["url"]?.jsonPrimitive?.contentOrNull ?: return null
                ContentPart.Image(url, image["detail"]?.jsonPrimitive?.contentOrNull)
            }

            null -> null

            else -> ContentPart.Other(
                type = type,
                raw = JsonObject(obj.filterKeys { it != "type" }),
            )
        }
    }
}

/**
 * Convenience for the one place that builds image parts from a prepared data URL.
 *
 * Kept next to the type so `ContentPart.Image(...)` never has to be spelled out with a `detail`
 * that the app does not use.
 */
fun imagePart(dataUrl: String): ContentPart = ContentPart.Image(dataUrl)
