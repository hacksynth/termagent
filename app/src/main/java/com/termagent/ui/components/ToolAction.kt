package com.termagent.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.termagent.R
import com.termagent.agent.ChatJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The action label shown in a terminal card's header.
 *
 * Modelled on devx's `getActionDescription()` (see `dist/core/loop.js` in termux-dev): every
 * tool renders as `→ Verb target` instead of a dump of its raw JSON arguments, so a `read_file`
 * card reads
 *
 * ```
 * → Read /data/data/com.tagent/files/notes.kt
 * ```
 *
 * rather than `{"path":"…"}`, and a `run_shell` card reads `→ Run: npm install` — the same shape
 * devx prints before it executes a tool.
 *
 * The label is built here, in the UI layer, on purpose: [com.termagent.data.TerminalBlock] is
 * persisted, and a translated verb must never end up baked into the conversation JSON. The
 * arguments are stored verbatim (they have to be, they are replayed to the API), so the label can
 * always be re-derived for the language the user is running in *now*.
 */
data class ToolAction(
    /** The label **without** the leading arrow, e.g. `Run: npm install`. */
    val label: String,
    /**
     * What the header's copy button copies — the command, the path, the query.
     *
     * `null` when the action has no single meaningful payload (e.g. `list_attachments`), in which
     * case the button is hidden rather than copying something useless.
     */
    val copyText: String?,
)

/** The arrow every action is prefixed with. A symbol, so it is not translated. */
internal const val TOOL_ACTION_ARROW = "→"

/** The verb set an action label can be drawn from. */
internal enum class ToolKind {
    Run,
    Read,
    Write,
    Edit,
    Delete,
    Mkdir,
    List,
    Search,
    Look,
    Info,
    Attachments,
    Clarify,
    Todo,
    Plan,
    Other,
}

/**
 * Map a tool name onto its [ToolKind].
 *
 * The alias list mirrors devx's `getActionDescription()`, which accepts several spellings for the
 * same verb (it also runs against MCP servers that name their tools differently). termagent only
 * declares one spelling per tool, but a label that keeps working when a provider or a future tool
 * set uses `bash` instead of `run_shell` costs nothing here.
 */
internal fun toolKind(toolName: String): ToolKind = when (toolName.trim().lowercase()) {
    "run_shell", "bash", "sh", "shell", "exec", "run", "execute_command", "run_command",
    -> ToolKind.Run

    "read_file", "view_file", "read", "cat" -> ToolKind.Read
    "write_file", "create_file", "write_to_file", "write" -> ToolKind.Write
    "edit_file", "replace_file_content", "patch", "edit" -> ToolKind.Edit
    "delete_file", "remove_file", "rm" -> ToolKind.Delete
    "make_dir", "mkdir" -> ToolKind.Mkdir
    "list_dir", "list_files", "find_files", "ls" -> ToolKind.List
    "search", "grep_search", "grep" -> ToolKind.Search
    "view_image", "read_image", "look_at_image" -> ToolKind.Look
    "get_system_info", "system_info" -> ToolKind.Info
    "list_attachments" -> ToolKind.Attachments
    "ask_questions", "questions" -> ToolKind.Clarify
    "todo_list", "update_todos" -> ToolKind.Todo
    "plan_ready" -> ToolKind.Plan
    else -> ToolKind.Other
}

/** Parses the raw `arguments` JSON of a tool call; `null` when it is absent or malformed. */
internal fun toolArguments(argumentsJson: String): JsonObject? = runCatching {
    ChatJson.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject
}.getOrNull()

/** First present, non-blank string value among [keys]. Non-string JSON is ignored on purpose. */
private fun JsonObject?.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    val primitive = this?.get(key) as? JsonPrimitive ?: return@firstNotNullOfOrNull null
    if (!primitive.isString) return@firstNotNullOfOrNull null
    primitive.content.trim().takeIf { it.isNotEmpty() }
}

/**
 * The shell command of a `run_shell` call.
 *
 * [fallback] is the short preview the engine already derived (`TerminalBlock.command`); it is only
 * consulted for conversations saved by an older build, or if the arguments somehow fail to parse.
 */
internal fun toolCommand(args: JsonObject?, fallback: String): String? =
    args.string("command", "cmd", "script") ?: fallback.trim().takeIf { it.isNotEmpty() }

/** The file/directory a path-ish tool is pointed at. */
internal fun toolPath(args: JsonObject?): String? =
    args.string("path", "filePath", "file_path", "targetFile", "file", "image")

/** The directory a `list_dir` / `search` call is scoped to; `"."` when the model left it out. */
internal fun toolDir(args: JsonObject?): String = args.string("dir", "dirPath", "directory", "path") ?: "."

/** The needle of a `search` call. */
internal fun toolQuery(args: JsonObject?): String? = args.string("query", "pattern", "q", "term")

/**
 * Build the header label (and the copy payload) for a tool call.
 *
 * Memoised on the arguments — this runs on every recomposition of a streaming card, and parsing
 * JSON on each frame is exactly the kind of work that makes a chat list stutter.
 */
@Composable
fun rememberToolAction(toolName: String, arguments: String, fallback: String = ""): ToolAction {
    val args = remember(arguments) { toolArguments(arguments) }

    // Resolved unconditionally: it stands in for a target the model did not supply, and devx
    // renders that case as a bare "→ Read…".
    val pending = stringResource(R.string.tool_action_pending)

    return when (toolKind(toolName)) {
        ToolKind.Run -> {
            val command = remember(args, fallback) { toolCommand(args, fallback) }
            ToolAction(stringResource(R.string.tool_action_run, command ?: pending), command)
        }

        ToolKind.Read -> {
            val path = remember(args) { toolPath(args) }
            ToolAction(stringResource(R.string.tool_action_read, path ?: pending), path)
        }

        ToolKind.Write -> {
            val path = remember(args) { toolPath(args) }
            ToolAction(stringResource(R.string.tool_action_write, path ?: pending), path)
        }

        ToolKind.Edit -> {
            val path = remember(args) { toolPath(args) }
            ToolAction(stringResource(R.string.tool_action_edit, path ?: pending), path)
        }

        ToolKind.Delete -> {
            val path = remember(args) { toolPath(args) }
            ToolAction(stringResource(R.string.tool_action_delete, path ?: pending), path)
        }

        ToolKind.Mkdir -> {
            val path = remember(args) { toolPath(args) }
            ToolAction(stringResource(R.string.tool_action_mkdir, path ?: pending), path)
        }

        ToolKind.List -> {
            val path = remember(args) { toolPath(args) }
            val dir = remember(args) { path ?: toolDir(args) }
            ToolAction(stringResource(R.string.tool_action_list, dir), path)
        }

        ToolKind.Search -> {
            val query = remember(args) { toolQuery(args) }
            val dir = remember(args) { toolDir(args) }
            ToolAction(
                stringResource(R.string.tool_action_search, query ?: pending, dir),
                query,
            )
        }

        ToolKind.Look -> {
            val path = remember(args) { toolPath(args) }
            ToolAction(stringResource(R.string.tool_action_look, path ?: pending), path)
        }

        ToolKind.Info -> ToolAction(stringResource(R.string.tool_action_system_info), null)

        ToolKind.Attachments ->
            ToolAction(stringResource(R.string.tool_action_attachments), null)

        ToolKind.Clarify -> ToolAction(stringResource(R.string.tool_action_clarify), null)

        ToolKind.Todo -> ToolAction(stringResource(R.string.tool_action_todo), null)

        ToolKind.Plan -> ToolAction(stringResource(R.string.tool_action_plan), null)

        // devx's fallback: an unknown tool still gets a line, naming itself.
        ToolKind.Other ->
            ToolAction(stringResource(R.string.tool_action_other, toolName), null)
    }
}
