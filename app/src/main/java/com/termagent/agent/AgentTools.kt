package com.termagent.agent

import android.os.Build
import com.termagent.data.Attachment
import com.termagent.shell.ShellManager
import com.termagent.shell.TermuxEnvironment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * The outcome of one tool call: the text handed back to the model, plus whether it failed.
 *
 * The flag is carried explicitly rather than being re-derived from the text. The previous
 * `text.startsWith("Error")` heuristic silently marked a **timed-out or non-zero-exit**
 * `run_shell` as successful, because those results are phrased as `exit_code: 124\nnote: …` —
 * so the UI showed a green "Succeeded" badge on a command that had been killed.
 */
data class ToolResult(
    val text: String,
    val isError: Boolean,
    /**
     * The command's exit status, for tools that run one; `null` for every other tool.
     *
     * Carried explicitly for the same reason as [isError]: the UI needs it to render the exit badge,
     * and re-deriving it from [text] is unsound. Only `run_shell` replies *have* an exit status, yet
     * a `read_file` of any file that merely mentions the string `exit_code: 124` — the app's own
     * source and docs do — produced a fabricated "exit 124" badge on a perfectly successful read.
     * The reply text is a wire format the model reads; it is not a reliable place to encode metadata.
     */
    val exitCode: Int? = null,
) {
    companion object {
        /**
         * Classify a plain tool reply by its prefix.
         *
         * Only the non-shell tools use this: they *write* their replies, so "the text starts with
         * `Error`" is a convention they control. `run_shell` deliberately does not — its reply is
         * the wire format the model reads (`exit_code: …`), and it sets [isError] from the exit
         * status instead.
         */
        fun of(text: String): ToolResult = ToolResult(text, text.startsWith(ERROR_PREFIX))
    }
}

/** Prefix that marks a tool reply as a failure; see [ToolResult.of]. */
private const val ERROR_PREFIX = "Error"

/**
 * The tools the agent can call.
 *
 * Each tool is exposed to the model as an OpenAI "function" and implemented here against
 * the forked Termux backend. `run_shell` streams its output through [onOutput] so the UI
 * can render a live terminal card.
 */
/**
 * The tool-execution seam between [AgentEngine] and the real tools.
 *
 * Lets the loop be tested against a fake that returns canned results — including the streamed
 * `onOutput` slices — without a pty, a filesystem or an Android runtime. The production
 * implementation is [AgentTools]; see `AgentEngineTest`. Public (rather than internal) only so
 * the public [AgentEngine] constructor can name it without tripping Kotlin's
 * "public function exposes its internal parameter type" rule.
 */
interface AgentToolRunner {
    /** Tool schemas advertised to the model. */
    val definitions: List<ToolDefinition>

    /** Run one tool call; see [AgentTools.execute] for the contract. */
    suspend fun execute(
        name: String,
        argumentsJson: String,
        onOutput: (String) -> Unit,
    ): ToolResult

    companion object {
        /**
         * The one tool the safety switch can withhold.
         *
         * Declared here, not on [AgentTools], so the loop's special-casing of it does not have to
         * reach into the concrete tool class — [AgentTools] re-exports it under the same name.
         */
        const val RUN_SHELL = "run_shell"
    }
}

class AgentTools(
    private val shell: ShellManager,
    private val env: TermuxEnvironment,
    /** Backs `view_image`; owns the one-shot multimodal request. */
    private val vision: VisionClient,
    /** Files the user attached to the conversation currently open; see [AttachmentRegistry]. */
    private val attachments: () -> List<Attachment> = { emptyList() },
) : AgentToolRunner {

    companion object {
        private const val MAX_READ_BYTES = 200_000
        private const val DEFAULT_READ_BYTES = 64_000

        private const val ARG_COMMAND = "command"
        private const val ARG_TIMEOUT = "timeout_seconds"
        private const val ARG_PATH = "path"
        private const val ARG_CONTENT = "content"
        private const val ARG_MAX_BYTES = "max_bytes"
        private const val ARG_PROMPT = "prompt"

        /**
         * Default `run_shell` budget, in seconds, when the model does not ask for one.
         *
         * Derived from [ShellManager.DEFAULT_TIMEOUT_MS] for the same reason as
         * [MAX_TIMEOUT_SECONDS]: the tool advertised "default 120" in its schema while the shell
         * had its own copy of that number.
         */
        private val DEFAULT_TIMEOUT_SECONDS = (ShellManager.DEFAULT_TIMEOUT_MS / 1000L).toInt()

        /**
         * Ceiling for the model-supplied `run_shell` timeout, in seconds.
         *
         * **Derived** from [ShellManager.MAX_TIMEOUT_MS] rather than repeated: the two used to be
         * independent numbers, so the schema could advertise one maximum while the shell enforced
         * another. The schema value is what the model is told; the shell's clamp is what actually
         * happens, and it also covers a request that does not come from the model at all.
         */
        private val MAX_TIMEOUT_SECONDS = (ShellManager.MAX_TIMEOUT_MS / 1000L).toInt()

        /** Re-exported from [AgentToolRunner] so there is a single source of truth. */
        const val RUN_SHELL = AgentToolRunner.RUN_SHELL
        const val READ_FILE = "read_file"
        const val WRITE_FILE = "write_file"
        const val LIST_DIR = "list_dir"
        const val SYSTEM_INFO = "get_system_info"

        /**
         * `list_attachments` — enumerate what the user attached.
         *
         * Not run through the shell on purpose: the model has no other way to learn an
         * attachment's *original* name (the file on disk carries a collision-proof suffix), and the
         * names are how the user refers to them.
         */
        const val LIST_ATTACHMENTS = "list_attachments"

        /** `view_image` — inspect any image on disk with the configured vision model. */
        const val VIEW_IMAGE = "view_image"

        /**
         * Default question for `view_image`.
         *
         * Biased towards transcription on purpose: the common case for a coding agent is a
         * screenshot containing an error message or a config value, and a generic "describe this
         * image" tends to paraphrase exactly the part that had to be exact.
         */
        private const val DEFAULT_VISION_PROMPT =
            "Describe this image in detail for a software engineer. Transcribe every piece of " +
                "visible text exactly, character for character (error messages, file paths, " +
                "values), then describe the layout and anything that looks wrong or noteworthy. " +
                "Do not guess at content you cannot see."
    }

    /** Schemas handed to the model in every request. */
    override val definitions: List<ToolDefinition> = listOf(
        tool(
            name = RUN_SHELL,
            description = "Run a shell command in the persistent Termux (bash) PTY. " +
                "Working directory, exported variables and aliases persist between calls. " +
                "Streams stdout/stderr back to the user live. " +
                "Returns the exit code followed by the captured output.",
            properties = buildJsonObject {
                put(
                    ARG_COMMAND,
                    buildJsonObject {
                        put("type", "string")
                        put("description", "The shell command to execute.")
                    },
                )
                put(
                    ARG_TIMEOUT,
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "Kill the command after this many seconds (default $DEFAULT_TIMEOUT_SECONDS, maximum $MAX_TIMEOUT_SECONDS).")
                    },
                )
            },
            required = listOf(ARG_COMMAND),
        ),
        tool(
            name = READ_FILE,
            description = "Read a UTF-8 text file from the device and return its contents.",
            properties = buildJsonObject {
                put(
                    ARG_PATH,
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute path, or a path relative to \$HOME.")
                    },
                )
                put(
                    ARG_MAX_BYTES,
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum bytes to return (default $DEFAULT_READ_BYTES).")
                    },
                )
            },
            required = listOf(ARG_PATH),
        ),
        tool(
            name = WRITE_FILE,
            description = "Write a UTF-8 text file, replacing it entirely. Parent directories are created.",
            properties = buildJsonObject {
                put(
                    ARG_PATH,
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute path, or a path relative to \$HOME.")
                    },
                )
                put(
                    ARG_CONTENT,
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Full file contents to write.")
                    },
                )
            },
            required = listOf(ARG_PATH, ARG_CONTENT),
        ),
        tool(
            name = LIST_DIR,
            description = "List the entries of a directory. Directories are suffixed with '/'.",
            properties = buildJsonObject {
                put(
                    ARG_PATH,
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Directory to list (default: \$HOME).")
                    },
                )
            },
            required = emptyList<String>(),
        ),
        tool(
            name = SYSTEM_INFO,
            description = "Report environment facts: PREFIX, HOME, PATH, ABI, shell, Android version.",
            properties = buildJsonObject {},
            required = emptyList<String>(),
        ),
        tool(
            name = LIST_ATTACHMENTS,
            description = "List the files the user attached to this conversation, with their " +
                "original names, sizes, types and absolute paths on disk. Call this first when " +
                "the user mentions 'the file' or 'the image' without naming it.",
            properties = buildJsonObject {},
            required = emptyList<String>(),
        ),
        tool(
            name = VIEW_IMAGE,
            description = "Look at an image file and report what it shows. This is a real vision " +
                "call: the image's pixels are sent to a multimodal model, so it can read text in " +
                "a screenshot, describe a photo, or diagnose a UI from a capture. Use it whenever " +
                "a question depends on what an image *looks* like — file metadata is not enough. " +
                "Accepts an attachment name (see $LIST_ATTACHMENTS) or any path.",
            properties = buildJsonObject {
                put(
                    ARG_PATH,
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Path to the image, or the name of an attachment " +
                                "(e.g. 'screenshot.png').",
                        )
                    },
                )
                put(
                    ARG_PROMPT,
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "What to look for. Be specific: 'transcribe the error dialog' " +
                                "beats 'describe this image'.",
                        )
                    },
                )
            },
            required = listOf(ARG_PATH),
        ),
    )

    /**
     * Execute a tool call.
     *
     * @param onOutput called with each new slice of streamed output (shell only).
     * @return the tool result text fed back to the model — never throws.
     */
    override suspend fun execute(
        name: String,
        argumentsJson: String,
        onOutput: (String) -> Unit,
    ): ToolResult {
        val args = runCatching {
            ChatJson.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrElse {
            return ToolResult("Error: tool arguments were not valid JSON (${it.message}).", true)
        }

        return try {
            when (name) {
                RUN_SHELL -> runShell(args, onOutput)
                // The remaining tools signal failure by the "Error" prefix on their reply, which
                // is also exactly what the model reads — so classifying by prefix is correct here.
                READ_FILE -> ToolResult.of(readFile(args))
                WRITE_FILE -> ToolResult.of(writeFile(args))
                LIST_DIR -> ToolResult.of(listDir(args))
                SYSTEM_INFO -> ToolResult.of(systemInfo())
                LIST_ATTACHMENTS -> ToolResult.of(listAttachments())
                VIEW_IMAGE -> ToolResult.of(viewImage(args))
                else -> ToolResult("Error: unknown tool '$name'.", true)
            }
        } catch (t: Throwable) {
            ToolResult("Error running $name: ${t.message ?: t.javaClass.simpleName}", true)
        }
    }

    private suspend fun runShell(args: JsonObject, onOutput: (String) -> Unit): ToolResult {
        val command = args.str(ARG_COMMAND)
            ?: return ToolResult("Error: '$ARG_COMMAND' is required.", true)
        val timeoutSeconds = args.int(ARG_TIMEOUT)?.coerceIn(1, MAX_TIMEOUT_SECONDS)
            ?: DEFAULT_TIMEOUT_SECONDS

        val result = shell.exec(command, timeoutMillis = timeoutSeconds * 1000L, onChunk = onOutput)

        // The reply text is the wire format the model reads, so it stays exactly as it was — but
        // failure is now reported through [ToolResult.isError] instead of being guessed from the
        // text. A command killed at its timeout, or one that simply exited non-zero, is a failure
        // even though its reply does not start with "Error" (it starts with "exit_code:").
        val text = buildString {
            append("exit_code: ").append(result.exitCode)
            if (result.timedOut) {
                append("\nnote: command exceeded ${timeoutSeconds}s and was interrupted (Ctrl-C).")
            }
            // Stated in the reply, not only as the inline marker inside the body: this is the one
            // thing that stops the model from treating a partial capture as the whole output and
            // concluding a search found nothing. It points at the workaround (redirect to a file,
            // then read/tail it) rather than implying the data is gone.
            if (result.truncated) {
                append(
                    "\nnote: output was too large and the middle was omitted; the first and last " +
                        "lines are shown. Redirect to a file (and then read_file / grep / tail it) " +
                        "if you need the whole thing.",
                )
            }
            append("\n--- output ---\n")
            append(result.output.trimEnd().ifBlank { "(no output)" })
        }
        return ToolResult(
            text = text,
            isError = result.timedOut || result.exitCode != 0,
            exitCode = result.exitCode,
        )
    }

    private fun readFile(args: JsonObject): String {
        val path = args.str(ARG_PATH) ?: return "Error: '$ARG_PATH' is required."
        val file = resolve(path)
        if (!file.exists()) return "Error: no such file: ${file.absolutePath}"
        if (file.isDirectory) return "Error: ${file.absolutePath} is a directory (use $LIST_DIR)."

        val limit = (args.int(ARG_MAX_BYTES) ?: DEFAULT_READ_BYTES).coerceIn(1, MAX_READ_BYTES)
        val bytes = file.readBytes()
        val truncated = bytes.size > limit
        val text = String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8)

        return buildString {
            append("path: ").append(file.absolutePath)
            append("\nbytes: ").append(bytes.size)
            if (truncated) append(" (truncated to ").append(limit).append(')')
            append("\n--- contents ---\n")
            append(text)
        }
    }

    private fun writeFile(args: JsonObject): String {
        val path = args.str(ARG_PATH) ?: return "Error: '$ARG_PATH' is required."
        val content = args.str(ARG_CONTENT) ?: return "Error: '$ARG_CONTENT' is required."
        val file = resolve(path)

        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)

        return "Wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to ${file.absolutePath}"
    }

    private fun listDir(args: JsonObject): String {
        val dir = resolve(args.str(ARG_PATH) ?: env.homeDir.absolutePath)
        if (!dir.isDirectory) return "Error: not a directory: ${dir.absolutePath}"

        val entries = dir.listFiles()?.sortedBy { it.name.lowercase() }
            ?: return "Error: cannot read ${dir.absolutePath}"

        return buildString {
            append("path: ").append(dir.absolutePath)
            append("\nentries: ").append(entries.size)
            append("\n--- listing ---\n")
            if (entries.isEmpty()) {
                append("(empty)")
            } else {
                for (entry in entries.take(500)) {
                    append(if (entry.isDirectory) entry.name + "/" else entry.name)
                    if (!entry.isDirectory) append("  (").append(entry.length()).append(" B)")
                    append('\n')
                }
                if (entries.size > 500) append("... and ${entries.size - 500} more\n")
            }
        }
    }

    /**
     * Report the conversation's attachments.
     *
     * Includes the absolute on-disk path because that is what the model has to pass to
     * `read_file` / `run_shell`, and images additionally advertise their dimensions so the model can
     * decide whether `view_image` is worth a call.
     */
    private fun listAttachments(): String {
        val list = attachments()
        if (list.isEmpty()) {
            return "No files are attached to this conversation. Ask the user to attach one with " +
                "the '+' button in the composer (images and documents are both supported)."
        }

        return buildString {
            append("attachments: ").append(list.size)
            append("\n--- listing ---\n")
            list.forEachIndexed { index, attachment ->
                append(index + 1).append(". ").append(attachment.name)
                append("  [").append(attachment.mimeType).append(", ").append(attachment.readableSize)
                if (attachment.isImage && attachment.width > 0) {
                    append(", ").append(attachment.width).append('x').append(attachment.height)
                }
                append(']')
                append("\n   path: ").append(absolutePathFor(attachment))
                if (attachment.preview.isNotBlank()) {
                    append("\n   first line: ").append(attachment.preview.take(120))
                }
                append('\n')
            }
            append("Images attached here were already sent to you as real image input; use ")
            append(VIEW_IMAGE).append(" only for images that are NOT already in the conversation ")
            append("(e.g. a screenshot you just captured).")
        }
    }

    /**
     * Send one image to the vision model and return its description as a tool result.
     *
     * This is what makes image understanding *composable*: the pixels never enter the agent's main
     * conversation (which may be a text-only model, and where a base64 blob would poison every
     * subsequent turn), they enter a separate one-shot request, and the agent only ever sees the
     * textual conclusion.
     */
    private suspend fun viewImage(args: JsonObject): String {
        if (!vision.isConfigured) {
            return "Error: no model is configured, so the image cannot be analysed."
        }

        val reference = args.str(ARG_PATH) ?: return "Error: '$ARG_PATH' is required."
        val question = args.str(ARG_PROMPT) ?: DEFAULT_VISION_PROMPT

        val file = resolveImage(reference)
            ?: return "Error: no such image: $reference (checked the conversation attachments " +
                "and ${'$'}HOME). Call $LIST_ATTACHMENTS to see what is available."

        val description = vision.describe(file, question)
        return buildString {
            append("image: ").append(file.absolutePath)
            append("\nquestion: ").append(question)
            append("\n--- description ---\n")
            append(description)
        }
    }

    /** An attachment name first (how the user refers to it), then a path. */
    private fun resolveImage(reference: String): File? {
        val trimmed = reference.trim().removeSurrounding("\"").removeSurrounding("'")

        attachments().firstOrNull { candidate ->
            candidate.name.equals(trimmed, ignoreCase = true) ||
                candidate.path.substringAfterLast('/').equals(trimmed, ignoreCase = true) ||
                candidate.id == trimmed
        }?.let { attachment ->
            val file = absoluteFileFor(attachment)
            if (file.isFile) return file
        }

        val direct = resolve(trimmed)
        return direct.takeIf { it.isFile }
    }

    private fun absolutePathFor(attachment: Attachment): String = absoluteFileFor(attachment).absolutePath

    /**
     * Re-attach `$HOME` to an attachment's stored relative path.
     *
     * The store owns this mapping; the tools only need "where is it", so they do the same
     * `File(homeDir, path)` join rather than depending on [com.termagent.data.AttachmentStore].
     */
    private fun absoluteFileFor(attachment: Attachment): File {
        val path = File(attachment.path)
        return if (path.isAbsolute) path else File(env.homeDir, attachment.path)
    }

    private fun systemInfo(): String = buildString {
        append("prefix: ").append(env.prefixDir.absolutePath)
        append("\nhome: ").append(env.homeDir.absolutePath)
        append("\nshell: ").append(env.loginShellPath)
        append("\nbootstrap_installed: ").append(env.isBootstrapInstalled)
        append("\nabi: ").append(Build.SUPPORTED_ABIS.joinToString())
        append("\nandroid_sdk: ").append(Build.VERSION.SDK_INT)
        append("\nandroid_release: ").append(Build.VERSION.RELEASE)
        append("\ndevice: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
    }

    /** Relative paths resolve against `$HOME`, which is what the model expects. */
    private fun resolve(path: String): File =
        if (path.startsWith("/")) File(path) else File(env.homeDir, path)

    // ------------------------------------------------------------------ helpers

    private fun tool(
        name: String,
        description: String,
        properties: JsonObject,
        required: List<String>,
    ): ToolDefinition = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put(
                    "required",
                    buildJsonArray { required.forEach { add(JsonPrimitive(it)) } },
                )
                put("additionalProperties", false)
            },
        ),
    )

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull
}
