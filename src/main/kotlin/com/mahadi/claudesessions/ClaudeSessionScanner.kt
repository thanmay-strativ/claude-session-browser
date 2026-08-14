package com.mahadi.claudesessions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.mahadi.claudesessions.model.ClaudeSession
import java.io.File
import java.nio.file.Path

private val LOG = logger<ClaudeSessionScanner>()

/**
 * Scans the configured session directory (Claude Code's `~/.claude/projects/` unless
 * pointed elsewhere) and reconstructs one [ClaudeSession] per transcript `.jsonl` file.
 * Parsing is line-by-line and defensive: a malformed line or file is skipped rather than
 * aborting the whole scan.
 */
class ClaudeSessionScanner {

    private val projectsRoot: Path
        get() = Path.of(SessionMetadataStore.sessionRoot())

    fun scan(): List<ClaudeSession> {
        val root = projectsRoot.toFile()
        if (!root.isDirectory) {
            LOG.info("No Claude projects directory at ${root.path}")
            return emptyList()
        }

        val sessions = ArrayList<ClaudeSession>()
        val projectDirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()

        for (projectDir in projectDirs) {
            val transcripts = projectDir.listFiles { file ->
                file.isFile && file.name.endsWith(".jsonl")
            } ?: continue

            for (transcript in transcripts) {
                try {
                    parseSession(projectDir.name, transcript)?.let(sessions::add)
                } catch (throwable: Throwable) {
                    LOG.warn("Failed to parse session ${transcript.path}", throwable)
                }
            }
        }
        return sessions
    }

    private fun parseSession(encodedDirName: String, transcript: File): ClaudeSession? {
        val sessionId = transcript.nameWithoutExtension
        var aiTitle: String? = null
        var firstPrompt: String? = null
        var cwd: String? = null
        var gitBranch: String? = null
        var model: String? = null
        var messageCount = 0

        transcript.bufferedReader().useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                val obj = try {
                    JsonParser.parseString(line).asJsonObject
                } catch (ignored: Exception) {
                    continue
                }

                when (obj.stringOrNull("type")) {
                    "ai-title" -> obj.stringOrNull("aiTitle")?.let { aiTitle = it }

                    "user", "assistant" -> {
                        if (obj.booleanOrNull("isSidechain") == true) continue
                        val isMeta = obj.booleanOrNull("isMeta") == true
                        if (!isMeta) messageCount++

                        if (cwd == null) cwd = obj.stringOrNull("cwd")
                        if (gitBranch == null) gitBranch = obj.stringOrNull("gitBranch")

                        val message = obj.objectOrNull("message")
                        if (model == null && obj.stringOrNull("type") == "assistant") {
                            model = message?.stringOrNull("model")
                        }
                        if (firstPrompt == null && obj.stringOrNull("type") == "user") {
                            extractUserText(message)?.let { text ->
                                val trimmed = text.trim()
                                if (trimmed.isNotEmpty() &&
                                    !trimmed.startsWith("<") &&
                                    !trimmed.contains("command-name")
                                ) {
                                    firstPrompt = trimmed
                                }
                            }
                        }
                    }
                }
            }
        }

        val resolvedCwd = cwd ?: decodeDirName(encodedDirName)
        val projectName = resolvedCwd.trimEnd('/').substringAfterLast('/')
            .ifEmpty { encodedDirName }

        val title = SessionMetadataStore.customTitle(sessionId)
            ?: aiTitle?.takeIf { it.isNotBlank() }
            ?: firstPrompt?.take(80)
            ?: "(untitled session)"

        return ClaudeSession(
            sessionId = sessionId,
            title = title,
            firstPrompt = firstPrompt.orEmpty(),
            projectPath = resolvedCwd,
            projectName = projectName,
            encodedDirName = encodedDirName,
            transcriptPath = transcript.toPath(),
            lastModifiedMillis = transcript.lastModified(),
            messageCount = messageCount,
            model = model?.let(::shortenModel),
            gitBranch = gitBranch,
        )
    }

    private fun extractUserText(message: JsonObject?): String? {
        val content = message?.get("content") ?: return null
        if (content.isJsonPrimitive) return content.asString
        if (content.isJsonArray) {
            val builder = StringBuilder()
            for (element in content.asJsonArray) {
                val block = element as? JsonObject ?: continue
                if (block.stringOrNull("type") == "text") {
                    block.stringOrNull("text")?.let { builder.append(it).append(' ') }
                }
            }
            return builder.toString().ifBlank { null }
        }
        return null
    }

    private fun shortenModel(rawModel: String): String = when {
        rawModel.contains("opus") -> "opus"
        rawModel.contains("sonnet") -> "sonnet"
        rawModel.contains("haiku") -> "haiku"
        rawModel.contains("fable") -> "fable"
        else -> rawModel
    }

    private fun decodeDirName(encodedDirName: String): String =
        "/" + encodedDirName.removePrefix("-").replace('-', '/')
}
