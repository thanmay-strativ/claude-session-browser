package com.mahadi.claudesessions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val LOG = logger<TeamSyncStatusService>()

/** The minute past the hour the scheduled agent fires; matches McpRuntime's plist. */
internal const val SYNC_MINUTE = 17

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * What the last sync cycle did, read from the status file the Python engine writes
 * after every run. One reader for the panel's status strip, the Health view and the
 * failure notification, so all three can never disagree about whether sync is healthy.
 */
data class TeamSyncStatus(
    val finishedAt: Instant?,
    val exported: Int,
    val imported: Int,
    val deleted: Int,
    val failedStep: String?,
    val failedDetail: String?,
) {
    val ok: Boolean get() = failedStep == null

    /** "3 shared · 12 received · 1 retracted", trimmed to what actually happened. */
    fun movementSummary(): String {
        val parts = buildList {
            if (exported > 0) add("$exported shared")
            if (imported > 0) add("$imported received")
            if (deleted > 0) add("$deleted retracted")
        }
        return if (parts.isEmpty()) "nothing changed" else parts.joinToString(" · ")
    }
}

object TeamSyncStatusService {

    private val statusFile: File
        get() = File(System.getProperty("user.home"), ".claude-session-cache/sync-status.json")

    fun load(): TeamSyncStatus? {
        val file = statusFile
        if (!file.isFile) return null
        return try {
            val json = JsonParser.parseString(file.readText()).asJsonObject
            val failed = json.getAsJsonArray("steps")
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.get("ok")?.asBoolean == false }
            TeamSyncStatus(
                finishedAt = json.get("finished_at")?.asString?.let(::parseInstant),
                exported = json.get("exported")?.asInt ?: 0,
                imported = json.get("imported")?.asInt ?: 0,
                deleted = json.get("deleted")?.asInt ?: 0,
                failedStep = failed?.get("step")?.asString,
                failedDetail = failed?.get("detail")?.asString,
            )
        } catch (throwable: Throwable) {
            LOG.warn("Could not read ${file.absolutePath}", throwable)
            null
        }
    }

    /**
     * The next wall-clock time the agent will fire, or null when nothing is scheduled.
     * Rolls to tomorrow's first hour once today's last one has passed.
     */
    fun nextRun(hours: List<Int>, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val valid = hours.filter { it in 0..23 }.distinct().sorted()
        if (valid.isEmpty()) return null

        val today: LocalDate = now.toLocalDate()
        val upcoming = valid
            .map { hour -> ZonedDateTime.of(LocalDateTime.of(today, LocalTime.of(hour, SYNC_MINUTE)), now.zone) }
            .firstOrNull { it.isAfter(now) }
        return upcoming ?: ZonedDateTime.of(
            LocalDateTime.of(today.plusDays(1), LocalTime.of(valid.first(), SYNC_MINUTE)),
            now.zone,
        )
    }

    /** "in 3h 12m" — coarse on purpose; a second-by-second countdown would just churn. */
    fun untilNextRun(hours: List<Int>, now: ZonedDateTime = ZonedDateTime.now()): String? {
        val next = nextRun(hours, now) ?: return null
        val remaining = Duration.between(now, next)
        val totalMinutes = remaining.toMinutes().coerceAtLeast(0)
        return when {
            totalMinutes < 1 -> "any moment"
            totalMinutes < 60 -> "in ${totalMinutes}m"
            else -> "in ${totalMinutes / 60}h ${totalMinutes % 60}m"
        }
    }

    fun nextRunClock(hours: List<Int>, now: ZonedDateTime = ZonedDateTime.now()): String? =
        nextRun(hours, now)?.format(CLOCK)

    fun relativeTime(instant: Instant, now: Instant = Instant.now()): String {
        val elapsed = Duration.between(instant, now)
        return when {
            elapsed.toMinutes() < 2 -> "just now"
            elapsed.toHours() < 1 -> "${elapsed.toMinutes()}m ago"
            elapsed.toDays() < 1 -> "${elapsed.toHours()}h ago"
            else -> "${elapsed.toDays()}d ago"
        }
    }

    private fun parseInstant(value: String): Instant? = try {
        ZonedDateTime.parse(value).toInstant()
    } catch (ignored: DateTimeParseException) {
        try {
            Instant.parse(value)
        } catch (alsoIgnored: DateTimeParseException) {
            null
        }
    }

    fun statusFileTimestamp(): Long = statusFile.takeIf { it.isFile }?.lastModified() ?: 0L
}
