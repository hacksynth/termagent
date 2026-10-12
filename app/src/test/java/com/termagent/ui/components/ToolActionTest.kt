package com.termagent.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the devx-style action labels.
 *
 * Only the pure helpers are exercised: [rememberToolAction] is `@Composable` (it resolves its
 * verbs through `stringResource`) and cannot run on a plain JVM test, so the label assembly
 * itself is covered by the device build.
 */
class ToolActionTest {

    // ------------------------------------------------------------------ toolKind

    @Test
    fun `known tool names map onto their verb`() {
        assertEquals(ToolKind.Run, toolKind("run_shell"))
        assertEquals(ToolKind.Read, toolKind("read_file"))
        assertEquals(ToolKind.Write, toolKind("write_file"))
        assertEquals(ToolKind.List, toolKind("list_dir"))
        assertEquals(ToolKind.Look, toolKind("view_image"))
        assertEquals(ToolKind.Info, toolKind("get_system_info"))
        assertEquals(ToolKind.Attachments, toolKind("list_attachments"))
    }

    @Test
    fun `devx aliases are honoured`() {
        // devx's getActionDescription() accepts several spellings per verb because it also runs
        // against arbitrary MCP tool sets; keeping the same table costs nothing here.
        assertEquals(ToolKind.Run, toolKind("bash"))
        assertEquals(ToolKind.Run, toolKind("execute_command"))
        assertEquals(ToolKind.Read, toolKind("view_file"))
        assertEquals(ToolKind.Edit, toolKind("patch"))
        assertEquals(ToolKind.List, toolKind("ls"))
        assertEquals(ToolKind.Search, toolKind("grep_search"))
    }

    @Test
    fun `matching is case and whitespace insensitive`() {
        assertEquals(ToolKind.Run, toolKind("  RUN_SHELL "))
        assertEquals(ToolKind.Read, toolKind("Read_File"))
    }

    @Test
    fun `an unrecognised tool falls back to Other rather than throwing`() {
        assertEquals(ToolKind.Other, toolKind("deploy_to_production"))
        assertEquals(ToolKind.Other, toolKind(""))
    }

    // ------------------------------------------------------------------ argument extraction

    @Test
    fun `run_shell reads its command`() {
        val args = toolArguments("""{"command":"npm install"}""")
        assertEquals("npm install", toolCommand(args, fallback = ""))
    }

    @Test
    fun `a blank command falls back to the engine preview`() {
        // Older conversations persisted no `arguments`; the preview the engine derived is all
        // that is left, and the label must not degrade to "→ Run:".
        assertEquals("npm install", toolCommand(toolArguments("{}"), fallback = "npm install"))
        assertEquals("npm install", toolCommand(null, fallback = "npm install"))
    }

    @Test
    fun `a missing command yields null so the label can say pending`() {
        assertNull(toolCommand(toolArguments("{}"), fallback = ""))
        assertNull(toolCommand(null, fallback = "   "))
    }

    @Test
    fun `malformed arguments are tolerated instead of crashing the card`() {
        // Arguments stream in token by token and a partial frame is not valid JSON; the card
        // must still render, just without a target.
        assertNull(toolArguments("""{"path":"/tmp/a""""))
        assertNull(toolPath(toolArguments("""{"path":"/tmp/a"""")))
    }

    @Test
    fun `blank arguments are an empty object, not a parse failure`() {
        // A tool call with no arguments arrives as "" or "{}"; both mean "no target", which the
        // label renders as pending rather than as an error.
        assertEquals(0, toolArguments("")?.size)
        assertEquals(0, toolArguments("{}")?.size)
        assertNull(toolPath(toolArguments("")))
        assertNull(toolQuery(toolArguments("{}")))
    }

    @Test
    fun `path-ish tools accept every spelling the model might use`() {
        assertEquals("/tmp/a", toolPath(toolArguments("""{"path":"/tmp/a"}""")))
        assertEquals("/tmp/b", toolPath(toolArguments("""{"filePath":"/tmp/b"}""")))
        assertEquals("/tmp/c", toolPath(toolArguments("""{"file_path":"/tmp/c"}""")))
        assertNull(toolPath(toolArguments("""{"other":"x"}""")))
    }

    @Test
    fun `non-string values are ignored rather than stringified`() {
        // A model that sends `"path": 42` must not produce the label "Read 42".
        assertNull(toolPath(toolArguments("""{"path":42}""")))
        assertNull(toolPath(toolArguments("""{"path":null}""")))
        assertNull(toolPath(toolArguments("""{"path":{"nested":"x"}}""")))
    }

    @Test
    fun `list_dir defaults to the working directory`() {
        assertEquals("/tmp", toolDir(toolArguments("""{"path":"/tmp"}""")))
        assertEquals("/tmp", toolDir(toolArguments("""{"dirPath":"/tmp"}""")))
        assertEquals(".", toolDir(toolArguments("{}")))
        assertEquals(".", toolDir(null))
    }

    @Test
    fun `search reads its query and its scope independently`() {
        val args = toolArguments("""{"query":"TODO","dir":"app/src"}""")
        assertEquals("TODO", toolQuery(args))
        assertEquals("app/src", toolDir(args))
    }

    @Test
    fun `surrounding whitespace is trimmed out of targets`() {
        assertEquals("/tmp/a", toolPath(toolArguments("""{"path":"  /tmp/a  "}""")))
        assertNull(toolPath(toolArguments("""{"path":"   "}""")))
    }
}
