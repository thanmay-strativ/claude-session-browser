package com.mahadi.claudesessions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

private val LOG = logger<UsageStatsService>()

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/**
 * How much each configured Claude environment has been used over a recent window.
 *
 * Every figure is read from that environment's own session directory, so an account's
 * numbers are its own — there is no combined total, and one account's activity can never
 * appear under another. The active account is flagged rather than treated specially.
 *
 * Only transcripts whose file was last written inside the window are opened, because a file
 * untouched since before the cutoff cannot hold a message inside it. Messages are then
 * counted by their own `timestamp`, not the file's, so a long-running session resumed
 * yesterday contributes only yesterday's messages instead of its whole history.
 */
object UsageStatsService {

    /**
     * Everything except [previousMessages] and [dailyMessages] describes the current window.
     * Token counts are the API's own: prompt tokens are billed in full, cache reads are not.
     */
    data class EnvironmentUsage(
        val environmentName: String,
        val isActive: Boolean,
        val rootExists: Boolean,
        val sessions: Int,
        val messages: Int,
        val previousMessages: Int,
        val promptTokens: Long,
        val outputTokens: Long,
        val cacheReadTokens: Long,
        val dailyMessages: Map<LocalDate, Int>,
    ) {
        /** Change against the window before this one, or null when there is nothing to compare to. */
        fun messageChangePercent(): Int? {
            if (previousMessages == 0) return null
            return ((messages - previousMessages) * 100.0 / previousMessages).roundToInt()
        }
    }

    private class Totals {
        var sessions: Int = 0
        var messages: Int = 0
        var previousMessages: Int = 0
        var promptTokens: Long = 0L
        var outputTokens: Long = 0L
        var cacheReadTokens: Long = 0L
        val daily: MutableMap<LocalDate, Int> = mutableMapOf()
    }

    /**
     * Usage for every environment over the last [days], plus the [days] before that.
     *
     * The comparison window is read in the same pass because a number on its own says nothing about
     * direction — twice the work for the answer to "more or less than last week?".
     */
    fun load(days: Int): List<EnvironmentUsage> {
        val now = System.currentTimeMillis()
        val currentCutoff = now - days * MILLIS_PER_DAY
        val previousCutoff = now - 2 * days * MILLIS_PER_DAY
        val activeName = SessionMetadataStore.activeEnvironment().name
        return SessionMetadataStore.environments().map { environment ->
            usageOf(environment, environment.name == activeName, currentCutoff, previousCutoff)
        }
    }

    private fun usageOf(
        environment: ClaudeEnvironment,
        isActive: Boolean,
        currentCutoff: Long,
        previousCutoff: Long,
    ): EnvironmentUsage {
        val root = File(environment.sessionRoot)
        if (!root.isDirectory) {
            LOG.info("No session directory for environment '${environment.name}' at ${root.path}")
            return EnvironmentUsage(environment.name, isActive, false, 0, 0, 0, 0L, 0L, 0L, emptyMap())
        }

        val totals = Totals()
        for (transcript in recentTranscripts(root, previousCutoff)) {
            try {
                if (accumulate(transcript, currentCutoff, previousCutoff, totals)) totals.sessions++
            } catch (throwable: Throwable) {
                LOG.warn("Failed to read usage from ${transcript.path}", throwable)
            }
        }

        return EnvironmentUsage(
            environmentName = environment.name,
            isActive = isActive,
            rootExists = true,
            sessions = totals.sessions,
            messages = totals.messages,
            previousMessages = totals.previousMessages,
            promptTokens = totals.promptTokens,
            outputTokens = totals.outputTokens,
            cacheReadTokens = totals.cacheReadTokens,
            dailyMessages = totals.daily,
        )
    }

    private fun recentTranscripts(root: File, cutoffMillis: Long): List<File> {
        val projectDirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return projectDirs.flatMap { projectDir ->
            projectDir.listFiles { file ->
                file.isFile && file.name.endsWith(".jsonl") && file.lastModified() >= cutoffMillis
            }?.toList() ?: emptyList()
        }
    }

    private fun accumulate(
        transcript: File,
        currentCutoff: Long,
        previousCutoff: Long,
        totals: Totals,
    ): Boolean {
        var active = false
        transcript.bufferedReader().useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                val entry = try {
                    JsonParser.parseString(line).asJsonObject
                } catch (ignored: Exception) {
                    continue
                }

                val type = entry.stringOrNull("type")
                if (type != "user" && type != "assistant") continue
                if (entry.booleanOrNull("isSidechain") == true) continue

                val writtenAt = millisOf(entry.stringOrNull("timestamp")) ?: continue
                if (writtenAt < previousCutoff) continue
                val counts = entry.booleanOrNull("isMeta") != true

                if (writtenAt < currentCutoff) {
                    if (counts) totals.previousMessages++
                    addToDay(totals, writtenAt, counts)
                    continue
                }

                if (counts) {
                    totals.messages++
                    active = true
                }
                addToDay(totals, writtenAt, counts)
                accumulateTokens(entry.objectOrNull("message")?.objectOrNull("usage"), totals)
            }
        }
        return active
    }

    private fun addToDay(totals: Totals, writtenAt: Long, counts: Boolean) {
        if (!counts) return
        val day = Instant.ofEpochMilli(writtenAt).atZone(ZoneId.systemDefault()).toLocalDate()
        totals.daily[day] = (totals.daily[day] ?: 0) + 1
    }

    private fun accumulateTokens(usage: JsonObject?, totals: Totals) {
        if (usage == null) return
        totals.promptTokens += usage.longOrZero("input_tokens") +
            usage.longOrZero("cache_creation_input_tokens")
        totals.outputTokens += usage.longOrZero("output_tokens")
        totals.cacheReadTokens += usage.longOrZero("cache_read_input_tokens")
    }

    private fun millisOf(timestamp: String?): Long? {
        if (timestamp.isNullOrBlank()) return null
        return try {
            Instant.parse(timestamp).toEpochMilli()
        } catch (ignored: DateTimeParseException) {
            null
        }
    }
}
