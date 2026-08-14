package com.mahadi.claudesessions

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Path

enum class EntryKind { USER, ASSISTANT, THINKING, TOOL_USE, TOOL_RESULT }

data class TranscriptEntry(
    val kind: EntryKind,
    val text: String,
    val toolName: String? = null,
)

private val LOG = logger<ClaudeTranscriptReader>()

/**
 * Reads a session transcript `.jsonl` into an ordered, human-readable list of
 * [TranscriptEntry] — the conversation as it would appear on screen. Sidechain
 * (subagent) and injected meta lines are skipped.
 */
class ClaudeTranscriptReader {

    fun read(transcript: Path): List<TranscriptEntry> {
        val entries = ArrayList<TranscriptEntry>()
        val file = transcript.toFile()
        if (!file.isFile) return entries

        file.bufferedReader().useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val obj = try {
                    JsonParser.parseString(line).asJsonObject
                } catch (ignored: Exception) {
                    continue
                }
                val type = obj.str("type") ?: continue
                if (type != "user" && type != "assistant") continue
                if (obj.bool("isSidechain") == true) continue
                if (obj.bool("isMeta") == true) continue
                val message = obj.obj("message") ?: continue
                appendMessage(type, message, entries)
            }
        }
        return entries
    }

    private fun appendMessage(type: String, message: JsonObject, out: MutableList<TranscriptEntry>) {
        val roleKind = if (type == "user") EntryKind.USER else EntryKind.ASSISTANT
        val content = message.get("content") ?: return

        if (content.isJsonPrimitive) {
            val text = content.asString.trim()
            if (text.isNotEmpty() && !isNoise(text)) out.add(TranscriptEntry(roleKind, text))
            return
        }
        if (!content.isJsonArray) return

        for (element in content.asJsonArray) {
            val block = element as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> block.str("text")?.trim()
                    ?.takeIf { it.isNotEmpty() && !isNoise(it) }
                    ?.let { out.add(TranscriptEntry(roleKind, it)) }

                "thinking" -> block.str("thinking")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { out.add(TranscriptEntry(EntryKind.THINKING, it)) }

                "tool_use" -> {
                    val name = block.str("name") ?: "tool"
                    out.add(TranscriptEntry(EntryKind.TOOL_USE, summarizeToolInput(name, block.get("input")), name))
                }

                "tool_result" -> {
                    val text = toolResultText(block)
                    if (text.isNotEmpty()) out.add(TranscriptEntry(EntryKind.TOOL_RESULT, text.take(4000)))
                }
            }
        }
    }

    private fun isNoise(text: String): Boolean {
        val head = text.trimStart()
        return head.startsWith("<local-command") ||
            head.startsWith("<command-name") ||
            head.startsWith("<command-message") ||
            head.startsWith("<system-reminder") ||
            head.startsWith("Caveat:")
    }

    private fun summarizeToolInput(name: String, input: JsonElement?): String {
        val obj = input as? JsonObject ?: return name
        val detail = when (name) {
            "Bash" -> obj.str("command")
            "Read", "Write", "Edit", "NotebookEdit" -> obj.str("file_path")
            "Grep" -> obj.str("pattern")
            "Glob" -> obj.str("pattern")
            "Task", "Agent" -> obj.str("description")
            else -> null
        }
        return if (detail.isNullOrBlank()) name else "$name: ${detail.lineSequence().first().take(200)}"
    }

    private fun toolResultText(block: JsonObject): String {
        val content = block.get("content") ?: return ""
        if (content.isJsonPrimitive) return content.asString.trim()
        if (content.isJsonArray) {
            val builder = StringBuilder()
            for (element in content.asJsonArray) {
                val part = element as? JsonObject ?: continue
                if (part.str("type") == "text") part.str("text")?.let { builder.append(it).append('\n') }
            }
            return builder.toString().trim()
        }
        return ""
    }

    private fun JsonObject.str(key: String): String? {
        val element = get(key) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val element = get(key) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) element.asBoolean else null
    }

    private fun JsonObject.obj(key: String): JsonObject? {
        val element = get(key) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }
}
