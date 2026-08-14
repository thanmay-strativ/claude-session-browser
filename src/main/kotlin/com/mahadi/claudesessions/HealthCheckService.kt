package com.mahadi.claudesessions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

    private fun jobChecks(): List<Check> = listOf(refreshJobCheck(), syncJobCheck())

    private fun refreshJobCheck(): Check {
        if (!McpRuntime.isRefreshAgentInstalled()) {
            return Check(
                "Daily re-index",
                State.OFF,
                "Not scheduled. It is installed when session search (MCP) is enabled.",
            )
        }
        return when (val exitStatus = launchdLastExitStatus(REFRESH_AGENT_LABEL)) {
            null -> Check(
                "Daily re-index",
                State.PROBLEM,
                "The agent file exists but launchd has not loaded it — re-enable MCP to reload it.",
            )
            0 -> Check(
                "Daily re-index",
                State.OK,
                "Runs daily at 03:00${lastActivitySuffix(cacheFile("ingest.log"))}.",
            )
            else -> Check(
                "Daily re-index",
                State.PROBLEM,
                "Last run exited with status $exitStatus — see ~/.claude-session-cache/ingest.error.log.",
            )
        }
    }

    private fun syncJobCheck(): Check {
        val config = SessionMetadataStore.teamSync()
        if (!config.enabled) {
            return Check("Team sync", State.OFF, "Off. Enable it in Settings → Team Sync.")
        }
        if (!McpRuntime.isSyncAgentInstalled()) {
            return Check(
                "Team sync",
                State.PROBLEM,
                "Enabled in settings but not scheduled — open Settings → Team Sync and apply again.",
            )
        }
        if (launchdLastExitStatus(SYNC_AGENT_LABEL) == null) {
            return Check(
                "Team sync",
                State.PROBLEM,
                "The agent file exists but launchd has not loaded it — apply the settings again to reload it.",
            )
        }
        return lastSyncOutcome(config)
    }

    /** The sync engine writes `sync-status.json` after every cycle; that file is the truth. */
    private fun lastSyncOutcome(config: TeamSyncConfig): Check {
        val hours = config.syncHours.joinToString(", ") { String.format("%02d:17", it) }
        val statusFile = cacheFile("sync-status.json")
        if (!statusFile.isFile) {
            return Check("Team sync", State.WARNING, "Scheduled at $hours; it has not run yet.")
        }

        val status = readSyncStatus(statusFile)
            ?: return Check(
                "Team sync",
                State.WARNING,
                "Scheduled at $hours; the last status file could not be read.",
            )

        val failed = status.steps.firstOrNull { !it.ok }
        val ranAgo = lastActivitySuffix(statusFile).ifEmpty { "" }
        return if (failed == null) {
            Check(
                "Team sync",
                State.OK,
                "Runs at $hours$ranAgo — last cycle: ${status.exported} exported, " +
                    "${status.imported} imported, ${status.deleted} retracted.",
            )
        } else {
            Check(
                "Team sync",
                State.PROBLEM,
                "Last cycle$ranAgo failed at '${failed.step}': ${failed.detail.take(160)}",
            )
        }
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
            "Not installed — tick MCP in the panel toolbar to set it up.",
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
                    "Not registered for this account — tick MCP in the panel toolbar to register all accounts.",
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

    private fun readSyncStatus(statusFile: File): SyncStatus? = try {
        val json = JsonParser.parseString(statusFile.readText()).asJsonObject
        SyncStatus(
            exported = json.get("exported")?.asInt ?: 0,
            imported = json.get("imported")?.asInt ?: 0,
            deleted = json.get("deleted")?.asInt ?: 0,
            steps = json.getAsJsonArray("steps")?.mapNotNull { element ->
                val step = element as? JsonObject ?: return@mapNotNull null
                SyncStep(
                    step = step.get("step")?.asString ?: "?",
                    ok = step.get("ok")?.asBoolean ?: false,
                    detail = step.get("detail")?.asString ?: "",
                )
            }.orEmpty(),
        )
    } catch (throwable: Throwable) {
        LOG.warn("Could not parse ${statusFile.absolutePath}", throwable)
        null
    }

    private data class SyncStatus(
        val exported: Int,
        val imported: Int,
        val deleted: Int,
        val steps: List<SyncStep>,
    )

    private data class SyncStep(val step: String, val ok: Boolean, val detail: String)

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
