package com.mahadi.claudesessions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.util.concurrent.TimeUnit

private val LOG = logger<CacheStatsService>()

private const val STATS_TIMEOUT_SECONDS = 30L

/**
 * Reads statistics from the Python session cache by running its CLI and parsing the JSON
 * it prints. Going through the CLI keeps the plugin free of a SQLite dependency and means
 * there is exactly one implementation of the queries.
 */
object CacheStatsService {

    data class CacheStats(
        val indexedSessions: Int,
        val subagentSessions: Int,
        val messages: Int,
        val redactedMessages: Int,
        val sessionsWithCommits: Int,
        val taggedSessions: Int,
        val commits: Int,
        val filesTouched: Int,
        val newestActivity: String?,
        val topFiles: List<Counted>,
        val topTools: List<Counted>,
        val perProject: List<Counted>,
    )

    /** One labelled tally from the cache — a file path and its sessions, a tool and its calls. */
    data class Counted(val label: String, val count: Int)

    fun load(): CacheStats? {
        if (!McpRuntime.isInstalled()) return null

        return try {
            val process = ProcessBuilder(McpRuntime.executable.absolutePath, "stats")
                .redirectErrorStream(false)
                .start()

            if (!process.waitFor(STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                LOG.warn("claude-session-cache stats timed out")
                return null
            }
            val output = process.inputStream.bufferedReader().readText()
            if (process.exitValue() != 0) {
                LOG.warn("claude-session-cache stats exited ${process.exitValue()}")
                return null
            }
            parse(output)
        } catch (throwable: Throwable) {
            LOG.warn("Could not read session cache stats", throwable)
            null
        }
    }

    private fun parse(output: String): CacheStats? {
        val start = output.indexOf('{')
        if (start < 0) return null
        val json = JsonParser.parseString(output.substring(start)).asJsonObject
        return CacheStats(
            indexedSessions = json.int("primary_sessions"),
            subagentSessions = json.int("subagent_sessions"),
            messages = json.int("messages"),
            redactedMessages = json.int("redacted_messages"),
            sessionsWithCommits = json.int("sessions_with_commits"),
            taggedSessions = json.int("tagged_sessions"),
            commits = json.int("commits"),
            filesTouched = json.int("files_touched"),
            newestActivity = json.string("newest_activity"),
            topFiles = json.counted("top_files", "path", "sessions"),
            topTools = json.counted("top_tools", "tool", "calls"),
            perProject = json.counted("per_project", "project", "sessions"),
        )
    }

    /**
     * Reads one of the cache's ranked lists, tolerating its absence.
     *
     * A server installed by an older plugin build will not know a key that was added later, and an
     * empty list renders as "nothing yet" instead of failing the whole stats read.
     */
    private fun JsonObject.counted(key: String, labelKey: String, valueKey: String): List<Counted> {
        val element = get(key) ?: return emptyList()
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val label = entry.string(labelKey)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Counted(label, entry.int(valueKey))
        }
    }

    private fun JsonObject.int(key: String): Int {
        val element = get(key) ?: return 0
        return if (element.isJsonPrimitive) element.asInt else 0
    }

    private fun JsonObject.string(key: String): String? {
        val element = get(key) ?: return null
        return if (element.isJsonPrimitive) element.asString else null
    }


}
