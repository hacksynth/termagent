package com.termagent.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.termagent.shell.TermuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** What an attachment is, which is what decides how it reaches the model. */
enum class AttachmentKind {
    /** Sent as an `image_url` content part (and viewable through the `view_image` tool). */
    Image,

    /** Read and inlined into the prompt, because text is cheap and the model reads it directly. */
    Text,

    /**
     * Nothing is inlined: the file is dropped into `$HOME/attachments/` and the model is told where
     * it is, so it can inspect it with `run_shell`/`read_file` (which is how a PDF, an APK or a
     * video has to be handled — base64-ing those into the prompt is not an option).
     */
    Binary,
}

/**
 * A file the user attached to a message.
 *
 * The bytes always live on disk, under `$HOME/attachments/`, and [path] is stored **relative to
 * `$HOME`** rather than absolute. Two reasons: the conversation JSON stays portable between
 * installs, and the absolute prefix (`/data/data/<pkg>/files/home`) is a property of the app
 * installation, not of the message — baking it into saved history would go stale if Android ever
 * reports a different spelling of the private directory.
 */
@Serializable
data class Attachment(
    val id: String,
    /** Display name, as the picker reported it (also how the model refers to it). */
    val name: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0,
    /** Path relative to `$HOME`, e.g. `attachments/screenshot-3f2a1b.png`. */
    val path: String,
    /** Pixel dimensions for images; zero otherwise. */
    val width: Int = 0,
    val height: Int = 0,
    /** First line of a text file, shown in the chip so the user can tell files apart. */
    val preview: String = "",
) {
    val kind: AttachmentKind
        get() = when {
            mimeType.startsWith("image/") -> AttachmentKind.Image
            mimeType.startsWith("text/") -> AttachmentKind.Text
            mimeType in TEXT_APPLICATION_TYPES -> AttachmentKind.Text
            else -> AttachmentKind.Binary
        }

    val isImage: Boolean get() = kind == AttachmentKind.Image

    /** Human-readable size for chips and for the model's context. */
    val readableSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
            else -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
        }

    /** `name (12.3 KB, image/png)` — used in the text block handed to the model. */
    val descriptor: String get() = "$name ($readableSize, $mimeType)"

    companion object {
        /**
         * MIME types that are text but do not start with `text/`.
         *
         * Kept as a set of exact types rather than a `contains("json")` style test so a binary
         * type that merely mentions a word (e.g. `application/vnd.ms-excel`) cannot slip in.
         */
        val TEXT_APPLICATION_TYPES = setOf(
            "application/json",
            "application/ld+json",
            "application/xml",
            "application/xhtml+xml",
            "application/javascript",
            "application/x-javascript",
            "application/ecmascript",
            "application/x-sh",
            "application/x-shellscript",
            "application/x-yaml",
            "application/yaml",
            "application/toml",
            "application/sql",
            "application/x-httpd-php",
            "application/x-python",
            "application/x-ruby",
            "application/x-perl",
            "application/x-ndjson",
            "application/graphql",
            "application/csv",
        )

        /** Extensions treated as text even when the resolver reports `octet-stream`. */
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "rst", "log", "csv", "tsv", "json", "jsonl", "ndjson",
            "yaml", "yml", "toml", "ini", "conf", "cfg", "env", "properties", "xml", "html",
            "htm", "css", "scss", "less", "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts",
            "java", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "rb", "php", "pl", "lua",
            "sh", "bash", "zsh", "fish", "ps1", "bat", "py", "sql", "gradle", "pro", "vim",
            "dockerfile", "makefile", "gitignore", "patch", "diff", "srt", "vtt",
        )

        /** Best-effort MIME type: the resolver first, then the file extension. */
        fun resolveMimeType(resolver: ContentResolver, uri: Uri, displayName: String?): String {
            val reported = runCatching { resolver.getType(uri) }.getOrNull()
            if (!reported.isNullOrBlank() && reported != "application/octet-stream") return reported

            val extension = (displayName ?: uri.lastPathSegment).orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('.', "")
                .lowercase()

            return when {
                extension in TEXT_EXTENSIONS -> "text/plain"
                extension == "png" -> "image/png"
                extension == "jpg" || extension == "jpeg" -> "image/jpeg"
                extension == "webp" -> "image/webp"
                extension == "gif" -> "image/gif"
                extension == "bmp" -> "image/bmp"
                extension == "heic" || extension == "heif" -> "image/heic"
                extension == "svg" -> "image/svg+xml"
                extension == "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
        }
    }
}

/**
 * Imports attachments into `$HOME/attachments/` and resolves them back to files.
 *
 * Everything arrives as a `content://` URI (the Photo Picker and the Storage Access Framework both
 * hand those out), so the bytes are copied into the app's own storage exactly once. That is what
 * makes the file readable from the shell — a `content://` URI is meaningless to `bash` — and it
 * means the attachment survives the granting app's permission being revoked.
 */
class AttachmentStore(
    context: Context,
    private val env: TermuxEnvironment,
) {

    private val resolver: ContentResolver = context.contentResolver

    /** `$HOME/attachments` — created eagerly, so `run_shell` can rely on it existing. */
    val dir: File = File(env.homeDir, DIR_NAME).apply { mkdirs() }

    /** Absolute file for [attachment]; the only place the `$HOME` prefix is re-attached. */
    fun fileOf(attachment: Attachment): File = File(env.homeDir, attachment.path)

    /** Absolute path handed to the model and to `run_shell`. */
    fun absolutePathOf(attachment: Attachment): String = fileOf(attachment).absolutePath

    /**
     * Copy [uri] into the attachment directory.
     *
     * Images go through [ImagePrep] first, so what lands on disk is already the downscaled JPEG
     * that will be sent — which keeps the on-disk copy small too, instead of parking a 12 MP
     * original in the app's private storage for every screenshot the user shares.
     *
     * @return the stored attachment, or a failure whose message is safe to show to the user.
     */
    suspend fun import(uri: Uri): Result<Attachment> = withContext(Dispatchers.IO) {
        runCatching {
            val id = UUID.randomUUID().toString()
            val displayName = queryDisplayName(uri)
            val mimeType = Attachment.resolveMimeType(resolver, uri, displayName)

            val isImage = mimeType.startsWith("image/") && mimeType != "image/svg+xml"
            // Keep the original basename (it is what the model and the user recognise) and append a
            // short id so two files called `screenshot.png` cannot collide.
            val safeName = sanitise(displayName ?: "file")
            val suffix = id.take(8)

            if (isImage) {
                val prepared = ImagePrep.prepare(resolver, uri)
                    ?: error("Could not decode $safeName as an image.")
                val extension = if (prepared.mimeType == "image/png") "png" else "jpg"
                val target = File(dir, "$safeName-$suffix.$extension")
                target.writeBytes(prepared.bytes)

                return@runCatching Attachment(
                    id = id,
                    name = displayName ?: safeName,
                    mimeType = prepared.mimeType,
                    sizeBytes = prepared.sizeBytes.toLong(),
                    path = "$DIR_NAME/${target.name}",
                    width = prepared.width,
                    height = prepared.height,
                )
            }

            val extension = (displayName ?: uri.lastPathSegment).orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.isNotEmpty() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
            val target = File(dir, if (extension != null) "$safeName-$suffix.$extension" else "$safeName-$suffix")

            val copied = copy(uri, target)
            if (copied > MAX_IMPORT_BYTES) {
                target.delete()
                error("File is too large (${copied / 1024 / 1024} MB, limit ${MAX_IMPORT_BYTES / 1024 / 1024} MB).")
            }

            val attachment = Attachment(
                id = id,
                name = displayName ?: safeName,
                mimeType = mimeType,
                sizeBytes = copied,
                path = "$DIR_NAME/${target.name}",
                preview = if (mimeType.startsWith("text/")) readPreview(target) else "",
            )

            // The extension-less MIME guess can say "octet-stream" for a file that is plainly text
            // (e.g. `Dockerfile`, `Makefile`). Sniff it, so the content gets inlined instead of the
            // model being told to go shell out for it.
            if (attachment.kind == AttachmentKind.Binary && looksLikeText(target)) {
                attachment.copy(mimeType = "text/plain", preview = readPreview(target))
            } else {
                attachment
            }
        }.onFailure { Log.w(TAG, "Failed to import attachment $uri", it) }
    }

    /** Delete one attachment's bytes. Called when a message is cleared or a chip is removed. */
    fun delete(attachment: Attachment) {
        runCatching { fileOf(attachment).delete() }
            .onFailure { Log.w(TAG, "Failed to delete ${attachment.path}", it) }
    }

    /** Copy a stream to [target], returning the number of bytes written. Aborts past the cap. */
    private fun copy(uri: Uri, target: File): Long {
        val input = resolver.openInputStream(uri) ?: error("Cannot open $uri")
        input.use { source ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_IMPORT_BYTES) error("File exceeds the ${MAX_IMPORT_BYTES / 1024 / 1024} MB limit.")
                    sink.write(buffer, 0, read)
                }
                return total
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** `../` and friends must never survive into a file name. */
    private fun sanitise(name: String): String =
        name.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)
            .trim('.', '_', '-')
            .ifBlank { "file" }

    private fun readPreview(file: File): String = runCatching {
        file.bufferedReader().use { reader ->
            val buffer = CharArray(PREVIEW_CHARS)
            val read = reader.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read).lineSequence().firstOrNull().orEmpty().trim()
        }
    }.getOrDefault("")

    /** Sniff a NUL-free prefix; also validates the file is really decodable as UTF-8 text. */
    private fun looksLikeText(file: File): Boolean = runCatching {
        val probe = ByteArray(SNIFF_BYTES.toInt())
        val read = file.inputStream().use { it.read(probe) }
        if (read <= 0) return@runCatching false
        val bytes = probe.copyOf(read)
        if (bytes.any { it == 0.toByte() }) return@runCatching false
        val decoded = String(bytes, Charsets.UTF_8)
        // A replacement character means the UTF-8 decode hit an invalid sequence, i.e. binary.
        !decoded.contains('\uFFFD')
    }.getOrDefault(false)

    companion object {
        private const val TAG = "AttachmentStore"

        /** `$HOME/attachments`. */
        const val DIR_NAME = "attachments"

        /**
         * Hard cap per file.
         *
         * Text is inlined into the prompt, so a 32 MB log file would have to be truncated anyway;
         * the cap exists so a mis-tap cannot fill the device's storage.
         */
        const val MAX_IMPORT_BYTES = 32L * 1024 * 1024

        private const val PREVIEW_CHARS = 160
        private const val SNIFF_BYTES = 4096L
    }
}

/**
 * The attachments belonging to the conversation currently open in the UI.
 *
 * Exists so the tools (`view_image`, `list_attachments`) can resolve an attachment the model refers
 * to by name without the agent layer having to know about conversations, the repository or Compose.
 * The view model publishes here whenever the open conversation changes.
 */
class AttachmentRegistry {

    @Volatile
    private var attachments: List<Attachment> = emptyList()

    fun publish(list: List<Attachment>) {
        attachments = list
    }

    fun current(): List<Attachment> = attachments
}
