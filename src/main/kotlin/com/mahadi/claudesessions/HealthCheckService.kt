package com.mahadi.claudesessions

import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

private val LOG = logger<HealthCheckService>()

private const val LAUNCHCTL_TIMEOUT_SECONDS = 10L

private val LAST_EXIT_STATUS = Regex("\"LastExitStatus\"\\s*=\\s*(-?\\d+)")

/**
 * Answers "is everything this plugin set up actually working?" for the health view:
 * the scheduled launchd jobs, the MCP registrations, and the external tools the sync
 * cycle depends on. Every check is a plain filesystem or `launchctl` read, cheap
 * enough to run each time the view opens.
 */
object HealthCheckService {

    enum class State { OK, WARNING, PROBLEM, OFF }

    data class Check(val label: String, val state: State, val detail: String)

    data class Report(val jobs: List<Check>, val tools: List<Check>)

    fun load(): Report = Report(jobs = jobChecks(), tools = toolChecks())

    private fun jobChecks(): List<Check> = listOf(scheduledJobCheck(), syncOutcomeCheck())

    /**
     * The one launchd agent. Team sync is a step inside its run rather than a job of its own,
     * so what it does — and when — depends on whether sharing is configured.
     */
    private fun scheduledJobCheck(): Check {
        val label = "Scheduled job"
        if (!McpRuntime.isAgentInstalled()) {
            return Check(
                label,
                State.OFF,
                "Not scheduled. It is installed when session search (MCP) is enabled.",
            )
        }
        return when (val exitStatus = launchdLastExitStatus(AGENT_LABEL)) {
            null -> Check(
                label,
                State.PROBLEM,
                "The agent file exists but launchd has not loaded it — re-apply session search " +
                    "in Settings → General to reload it.",
            )
            0 -> Check(label, State.OK, "${scheduleSummary()}${lastActivitySuffix(cacheFile("refresh.log"))}.")
            else -> Check(
                label,
                State.PROBLEM,
                "Last run exited with status $exitStatus — see ~/.claude-session-cache/refresh.error.log.",
            )
        }
    }

    private fun scheduleSummary(): String {
        val config = SessionMetadataStore.teamSync()
        if (!config.enabled) return "Indexes your sessions daily at 03:00"
        val hours = config.syncHours.joinToString(", ") { String.format("%02d:%02d", it, SYNC_MINUTE) }
        return "Indexes and syncs at $hours"
    }

    private fun syncOutcomeCheck(): Check {
        val config = SessionMetadataStore.teamSync()
        if (!config.enabled) {
            return Check("Team sync", State.OFF, "Off. Enable it in Settings → Team Sync.")
        }
        if (!McpRuntime.isAgentInstalled()) {
            return Check(
                "Team sync",
                State.PROBLEM,
                "Enabled in settings but nothing is scheduled — open Settings → Team Sync and apply again.",
            )
        }
        return lastSyncOutcome(config)
    }

    /** The sync engine writes `sync-status.json` after every cycle; that file is the truth. */
    private fun lastSyncOutcome(config: TeamSyncConfig): Check {
        val hours = config.syncHours.joinToString(", ") { String.format("%02d:%02d", it, SYNC_MINUTE) }
        val nextRun = TeamSyncStatusService.untilNextRun(config.syncHours)?.let { " · next $it" }.orEmpty()
        val statusFile = cacheFile("sync-status.json")
        if (!statusFile.isFile) {
            return Check("Team sync", State.WARNING, "Scheduled at $hours$nextRun; it has not run yet.")
        }

        val status = TeamSyncStatusService.load()
            ?: return Check(
                "Team sync",
                State.WARNING,
                "Scheduled at $hours$nextRun; the last status file could not be read.",
            )

        val ranAgo = lastActivitySuffix(statusFile)
        if (!status.ok) {
            return Check(
                "Team sync",
                State.PROBLEM,
                "Last cycle$ranAgo failed at '${status.failedStep}': ${status.failedDetail.orEmpty().take(160)}",
            )
        }
        if (config.paused) {
            return Check(
                "Team sync",
                State.WARNING,
                "Sharing is paused — teammates' sessions still arrive, yours are not published. " +
                    "Runs at $hours$nextRun$ranAgo.",
            )
        }
        return Check(
            "Team sync",
            State.OK,
            "Runs at $hours$nextRun$ranAgo — last cycle: ${status.movementSummary()}.",
        )
    }

    private fun toolChecks(): List<Check> {
        val checks = mutableListOf(engineCheck())
        checks += registrationChecks()
        checks += claudeCliCheck()
        val config = SessionMetadataStore.teamSync()
        if (config.enabled) {
            checks += repoCheck(config)
            checks += gitleaksCheck()
        }
        return checks
    }

    private fun engineCheck(): Check = when {
        !McpRuntime.isInstalled() -> Check(
            "Session cache engine",
            State.PROBLEM,
            "Not installed — turn on session search in Settings → General.",
        )
        McpRuntime.isStale() -> Check(
            "Session cache engine",
            State.WARNING,
            "Older than this plugin build — it auto-updates the next time the panel opens.",
        )
        else -> Check("Session cache engine", State.OK, McpRuntime.executable.absolutePath)
    }

    private fun registrationChecks(): List<Check> =
        McpRegistrationService.registrationByAccount().map { account ->
            if (account.registered) {
                Check("MCP · ${account.environmentName}", State.OK, "Claude Code can search sessions here.")
            } else {
                Check(
                    "MCP · ${account.environmentName}",
                    State.PROBLEM,
                    "Not registered for this account — re-apply session search in " +
                        "Settings → General to register every account.",
                )
            }
        }

    private fun claudeCliCheck(): Check {
        val binary = ClaudeBinaryLocator.resolve()
        return if (File(binary).canExecute()) {
            Check("Claude CLI", State.OK, binary)
        } else {
            Check(
                "Claude CLI",
                State.PROBLEM,
                "'$binary' is not executable — resume, tagging and MCP registration all need it.",
            )
        }
    }

    private fun repoCheck(config: TeamSyncConfig): Check {
        val repoPath = config.repoPath
            ?: return Check("Knowledge-base repo", State.PROBLEM, "No local path configured.")
        val repoDir = File(repoPath)
        return when {
            !repoDir.isDirectory -> Check(
                "Knowledge-base repo",
                State.PROBLEM,
                "$repoPath does not exist — apply Settings → Team Sync to clone it.",
            )
            !File(repoDir, ".git").exists() -> Check(
                "Knowledge-base repo",
                State.WARNING,
                "$repoPath is not a git clone — exports land there but nothing is pushed or pulled.",
            )
            else -> Check("Knowledge-base repo", State.OK, repoPath)
        }
    }

    private fun gitleaksCheck(): Check {
        val home = System.getProperty("user.home")
        val found = listOf("/opt/homebrew/bin/gitleaks", "/usr/local/bin/gitleaks", "$home/.local/bin/gitleaks")
            .firstOrNull { File(it).canExecute() }
        return if (found != null) {
            Check("gitleaks", State.OK, "Every push is secret-scanned first ($found).")
        } else {
            Check(
                "gitleaks",
                State.WARNING,
                "Optional — 'brew install gitleaks' adds a second secret scan before every push.",
            )
        }
    }

    /**
     * Asks launchd about an agent. Exit 0 means loaded; the reported LastExitStatus is
     * how the previous run ended. Null means launchd does not know the label at all.
     */
    private fun launchdLastExitStatus(label: String): Int? = try {
        val process = ProcessBuilder("launchctl", "list", label)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(LAUNCHCTL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            val output = process.inputStream.bufferedReader().readText()
            if (process.exitValue() != 0) {
                null
            } else {
                LAST_EXIT_STATUS.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }
    } catch (throwable: Throwable) {
        LOG.warn("launchctl list $label failed", throwable)
        null
    }

    private fun cacheFile(name: String): File =
        File(System.getProperty("user.home"), ".claude-session-cache/$name")

    private fun lastActivitySuffix(file: File): String {
        if (!file.isFile) return ""
        val elapsed = Duration.between(Instant.ofEpochMilli(file.lastModified()), Instant.now())
        val text = when {
            elapsed.toMinutes() < 2 -> "just now"
            elapsed.toHours() < 1 -> "${elapsed.toMinutes()}m ago"
            elapsed.toDays() < 1 -> "${elapsed.toHours()}h ago"
            else -> "${elapsed.toDays()}d ago"
        }
        return " · last ran $text"
    }
}
