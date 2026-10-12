package com.termagent.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Conversations on disk, one pretty-printed JSON file per chat under
 * `filesDir/conversations/<id>.json`.
 *
 * One file per conversation keeps writes cheap (only the active chat is rewritten) and
 * makes a corrupted file scoped to a single chat instead of wiping the history.
 */
class SessionRepository(context: Context) {

    private val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Newest first. */
    suspend fun loadAll(): List<Conversation> = withContext(Dispatchers.IO) {
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" } ?: return@withContext emptyList()
        files.mapNotNull { file ->
            runCatching {
                json.decodeFromString(Conversation.serializer(), file.readText())
            }.onFailure {
                Log.w(TAG, "Skipping unreadable conversation ${file.name}", it)
            }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    suspend fun load(id: String): Conversation? = withContext(Dispatchers.IO) {
        val file = fileFor(id)
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(Conversation.serializer(), file.readText()) }
            .onFailure { Log.w(TAG, "Failed to load conversation $id", it) }
            .getOrNull()
    }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        val file = fileFor(conversation.id)
        val temp = File(dir, "${conversation.id}.tmp")
        // Write-then-rename so a crash mid-save cannot truncate an existing chat.
        temp.writeText(json.encodeToString(Conversation.serializer(), conversation))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        fileFor(id).delete()
    }

    private fun fileFor(id: String) = File(dir, "$id.json")

    companion object {
        private const val TAG = "SessionRepository"
        private const val DIR_NAME = "conversations"

        /** Build a fresh, empty conversation with a generated id. */
        fun newConversation(): Conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = "",
        )

        /** Derive a drawer title from the first user message. */
        fun deriveTitle(text: String): String =
            text.lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(42)
                .orEmpty()
    }
}
