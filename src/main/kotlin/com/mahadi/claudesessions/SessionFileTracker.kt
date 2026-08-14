package com.mahadi.claudesessions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path

private val FILE_TOOLS = setOf("Read", "Write", "Edit", "NotebookEdit")

/**
 * Extracts the distinct file paths a session actually touched (Read/Write/Edit/
 * NotebookEdit tool calls), for a quick "what did I change here" glance without
 * scrolling the whole transcript.
 */
object SessionFileTracker {

    fun filesTouched(transcript: Path): List<String> {
        val file = transcript.toFile()
        if (!file.isFile) return emptyList()

        val files = LinkedHashSet<String>()
        file.bufferedReader().useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val obj = try {
                    JsonParser.parseString(line).asJsonObject
                } catch (ignored: Exception) {
                    continue
                }
                val message = obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val content = message.get("content")?.takeIf { it.isJsonArray } ?: continue
                for (element in content.asJsonArray) {
                    val block = element as? JsonObject ?: continue
                    collectFilePath(block)?.let { files.add(it) }
                }
            }
        }
        return files.toList()
    }

    private fun collectFilePath(block: JsonObject): String? {
        if (block.get("type")?.asString != "tool_use") return null
        val name = block.get("name")?.asString ?: return null
        if (name !in FILE_TOOLS) return null
        val input = block.get("input")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val filePath = input.get("file_path") ?: return null
        return if (filePath.isJsonPrimitive) filePath.asString else null
    }
}
