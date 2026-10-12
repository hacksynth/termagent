package com.termagent.agent

import android.util.Log
import com.termagent.data.Attachment
import com.termagent.data.AttachmentKind
import com.termagent.data.ImagePrep
import java.io.File

/**
 * Turns "a caption plus a list of attachments" into the multimodal `content` sent to the model.
 *
 * This is the single place that decides how an attachment reaches the model, and it is deliberately
 * *structural*, not textual:
 *
 * | Kind | How it travels |
 * | --- | --- |
 * | [AttachmentKind.Image] | an `image_url` part carrying a downscaled base64 data URL — a real vision input, not a description of one |
 * | [AttachmentKind.Text] | its contents inlined into the message text, so the model reads it directly (no tool round-trip) |
 * | [AttachmentKind.Binary] | only its path, in the text, so the agent inspects it with `run_shell`/`read_file` |
 *
 * The same function rebuilds *historical* user messages when a conversation is replayed, which is
 * why image encoding is cached: re-sending a chat with 3 screenshots would otherwise re-encode all
 * three (decode + scale + JPEG) on every single turn.
 */
object UserContent {

    private const val TAG = "UserContent"

    /** Per-file inlining limit for text attachments. */
    const val MAX_INLINE_TEXT_BYTES = 128 * 1024

    /** Total inlining budget for one message, so 20 attached logs cannot blow up the prompt. */
    const val MAX_TOTAL_INLINE_BYTES = 256 * 1024

    /** How many encoded images to keep in memory. Each is a base64 string of a downscaled JPEG. */
    private const val CACHE_ENTRIES = 8

    /**
     * `path@mtime:size` → `data:image/…;base64,…`.
     *
     * A small LRU with an explicit bound: an unbounded cache of base64 images is a memory leak that
     * is easy to miss, since each entry is hundreds of kilobytes.
     */
    private val dataUrlCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > CACHE_ENTRIES
    }

    /**
     * Build the user message for [text] + [attachments].
     *
     * @param fileOf resolves an attachment to its on-disk file (`$HOME`-relative in the model, but
     *   absolute here — the store owns that mapping).
     * @return `null` when there is nothing to send at all.
     */
    fun build(
        text: String,
        attachments: List<Attachment>,
        fileOf: (Attachment) -> File,
    ): MessageContent? {
        val caption = text.trim()
        if (attachments.isEmpty()) return MessageContent.of(caption)

        val images = attachments.filter { it.kind == AttachmentKind.Image }
            .mapNotNull { imagePartFor(it, fileOf) }

        val description = describe(caption, attachments, fileOf)

        // Images first, then the text: the order the multimodal docs use, and it means the caption
        // ("what is wrong with this screen?") is read *after* the pixels it refers to.
        return MessageContent.of(description.ifBlank { null }, images)
            ?: MessageContent.of(caption)
    }

    /** The full text block for a message: caption plus one section per attachment. */
    private fun describe(
        caption: String,
        attachments: List<Attachment>,
        fileOf: (Attachment) -> File,
    ): String {
        val builder = StringBuilder(caption)
        var remainingInlineBytes = MAX_TOTAL_INLINE_BYTES

        attachments.forEachIndexed { index, attachment ->
            if (builder.isNotEmpty()) builder.append("\n\n")

            when (attachment.kind) {
                AttachmentKind.Image -> builder.append(imageNote(index, attachment))

                AttachmentKind.Text -> {
                    val inline = inlineText(attachment, fileOf, remainingInlineBytes)
                    remainingInlineBytes -= inline.length.coerceAtMost(remainingInlineBytes)
                    builder.append(inline)
                }

                AttachmentKind.Binary -> builder.append(binaryNote(attachment, fileOf))
            }
        }

        return builder.toString().trim()
    }

    /** `[image 1: screenshot.png, image/jpeg, 1568x1024, 220.4 KB]` */
    private fun imageNote(index: Int, attachment: Attachment): String = buildString {
        append("[image ").append(index + 1).append(": ").append(attachment.name)
        append(", ").append(attachment.mimeType)
        if (attachment.width > 0 && attachment.height > 0) {
            append(", ").append(attachment.width).append('x').append(attachment.height)
        }
        append(", ").append(attachment.readableSize)
        append("]")
    }

    /** The file's contents, fenced so the model can tell code from prose. */
    private fun inlineText(
        attachment: Attachment,
        fileOf: (Attachment) -> File,
        budget: Int,
    ): String {
        val file = fileOf(attachment)
        val header = buildString {
            append("[file: ").append(attachment.descriptor)
            attachment.preview.takeIf { it.isNotBlank() && it.length > 40 }
                ?.let { append(" — first line: ").append(it.take(120)) }
            append("]")
        }

        if (!file.isFile) return "$header\n(file is no longer on disk)"

        val limit = minOf(MAX_INLINE_TEXT_BYTES, maxOf(budget, 0))
        if (limit <= 0) {
            return "$header\n(omitted: the inline budget for this message is used up — read it " +
                "from ${absoluteNote(file)} with read_file)"
        }

        val bytes = runCatching { file.readBytes() }.getOrElse {
            Log.w(TAG, "Failed to read attachment ${file.absolutePath}", it)
            return "$header\n(could not be read)"
        }
        val truncated = bytes.size > limit
        val body = String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8)

        val fence = "```" + languageFor(attachment.name)
        return buildString {
            append(header).append('\n').append(fence).append('\n')
            append(body.trimEnd())
            if (truncated) {
                append("\n… truncated at ").append(limit).append(" bytes; the rest is at ")
                append(absoluteNote(file))
            }
            append("\n```")
        }
    }

    /** Path-only note, for files the model has to inspect with a tool. */
    private fun binaryNote(attachment: Attachment, fileOf: (Attachment) -> File): String =
        "[file: ${attachment.descriptor} — not inlined; stored at " +
            "${absoluteNote(fileOf(attachment))}. Inspect it with run_shell (e.g. `file`, " +
            "`pdfinfo`, `unzip -l`) or read_file.]"

    private fun absoluteNote(file: File): String = file.absolutePath

    /** Fence language from the file extension (best effort; `text` is a safe fallback). */
    private fun languageFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"
        "js", "mjs", "cjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py" -> "python"
        "rb" -> "ruby"
        "sh", "bash" -> "bash"
        "yml", "yaml" -> "yaml"
        "md", "markdown" -> "markdown"
        "html", "htm" -> "html"
        "json", "jsonl", "ndjson" -> "json"
        "c", "h" -> "c"
        "cpp", "hpp", "cc" -> "cpp"
        "cs" -> "csharp"
        "rs" -> "rust"
        "go" -> "go"
        "java" -> "java"
        "gradle" -> "groovy"
        "toml" -> "toml"
        "ini", "conf", "cfg" -> "ini"
        "sql" -> "sql"
        "diff", "patch" -> "diff"
        "xml" -> "xml"
        "css" -> "css"
        "csv" -> "csv"
        else -> ""
    }

    // ------------------------------------------------------------------ images

    /**
     * Read, downscale and encode an image attachment, then wrap it in a data URL.
     *
     * [ImagePrep] owns the resizing rules (1568 px long edge, JPEG q85); this only adds the
     * `data:` framing and the memoisation.
     */
    private fun imagePartFor(attachment: Attachment, fileOf: (Attachment) -> File): ContentPart? {
        val file = fileOf(attachment)
        if (!file.isFile) {
            Log.w(TAG, "Attachment ${attachment.name} is missing at ${file.absolutePath}")
            return null
        }

        val key = "${file.absolutePath}@${file.lastModified()}:${file.length()}"
        dataUrlCache[key]?.let { return ContentPart.Image(it) }

        val prepared = ImagePrep.prepare(file)
        if (prepared == null) {
            Log.w(TAG, "Could not encode ${file.absolutePath} for vision")
            return null
        }

        val url = ImagePrep.dataUrl(prepared)
        synchronized(dataUrlCache) { dataUrlCache[key] = url }
        return ContentPart.Image(url)
    }
}
