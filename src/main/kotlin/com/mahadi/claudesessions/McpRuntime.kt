package com.mahadi.claudesessions

import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val LOG = logger<McpRuntime>()

private const val SETUP_TIMEOUT_SECONDS = 300L

internal const val AGENT_LABEL = "com.mahadi.claude-session-cache"

/**
 * Retired. The team sync used to be scheduled as a second agent, which meant indexing ran twice
 * over — the sync cycle already ingests before it exports. One agent now does both, so an upgrade
 * has to unload the old one or it keeps firing against a plist nothing maintains.
 */
private const val LEGACY_SYNC_AGENT_LABEL = "com.mahadi.claude-session-sync"

/** With no team sync configured the job only indexes, so it runs once, out of the way. */
private const val INDEX_ONLY_HOUR = 3

/** launchd gives agents a bare PATH; the sync cycle shells out to git and (if present) gitleaks. */
private const val AGENT_PATH = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"

/** Must match `requires-python` in the bundled pyproject.toml. */
private const val MIN_PYTHON_MAJOR = 3
private const val MIN_PYTHON_MINOR = 11

/**
 * Owns the bundled MCP server's on-disk installation.
 *
 * The Python package ships inside the plugin jar, so a shared build works on a machine
 * that has never seen this project. On first enable it is extracted to
 * `~/.claude-session-browser/mcp-server`, given its own virtualenv, and installed into
 * it — after that the server runs from that venv's console script with no `uv`, no
 * PYTHONPATH and no reference to where the plugin was built.
 */
object McpRuntime {

    data class Step(val label: String, val ok: Boolean, val detail: String = "")

    val installDir: File
        get() = File(System.getProperty("user.home"), ".claude-session-browser/mcp-server")

    private val venvDir: File get() = File(installDir, ".venv")

    /** The console script the MCP registration and the stats reader both invoke. */
    val executable: File get() = File(venvDir, "bin/claude-session-cache")

    private val versionFile: File get() = File(installDir, ".bundle-version")

    fun isInstalled(): Boolean = executable.canExecute()

    /**
     * True when the installed server is older than the one in this plugin build.
     *
     * The package is installed non-editable, so copying files over the install dir is not enough —
     * [install] has to run again to put the new code into the venv. Nothing else triggered that, so
     * a server installed once stayed at that version through every later plugin update.
     */
    fun isStale(): Boolean {
        if (!isInstalled()) return false
        val installedFingerprint = versionFile.takeIf { it.isFile }?.readText()?.trim()
        return installedFingerprint != runCatching { bundleFingerprint() }.getOrNull()
    }

    /**
     * Extracts the bundled server and builds its environment. Safe to re-run; an existing
     * install is upgraded in place so a plugin update refreshes the Python code.
     */
    fun install(onProgress: (String) -> Unit): List<Step> {
        val steps = mutableListOf<Step>()

        onProgress("Unpacking the session cache…")
        val extracted = runCatching { extractBundle() }
        if (extracted.isFailure) {
            val message = extracted.exceptionOrNull()?.message ?: "unknown error"
            LOG.warn("Extracting bundled MCP server failed", extracted.exceptionOrNull())
            return steps + Step("Unpack server", false, message)
        }
        steps += Step("Unpack server", true, installDir.absolutePath)

        val python = findSystemPython() ?: return steps + Step("Find Python", false, pythonHelp())
        steps += Step("Find Python", true, python)

        onProgress("Creating an isolated environment…")
        val uv = findUv()
        val venvResult = if (uv != null) {
            exec(listOf(uv, "venv", "--python", python, venvDir.absolutePath), installDir)
        } else {
            exec(listOf(python, "-m", "venv", venvDir.absolutePath), installDir)
        }
        if (!venvResult.first) {
            return steps + Step("Create virtualenv", false, venvResult.second)
        }
        steps += Step("Create virtualenv", true, if (uv != null) "via uv" else "via python -m venv")

        onProgress("Installing dependencies (first run only, needs internet)…")
        val venvPython = File(venvDir, "bin/python").absolutePath
        val installResult = if (uv != null) {
            exec(
                listOf(uv, "pip", "install", "--python", venvPython, "--upgrade", installDir.absolutePath),
                installDir,
            )
        } else {
            exec(
                listOf(venvPython, "-m", "pip", "install", "--upgrade", installDir.absolutePath),
                installDir,
            )
        }
        if (!installResult.first) {
            return steps + Step("Install dependencies", false, installResult.second.takeLast(600))
        }
        steps += Step("Install dependencies", true, if (uv != null) "via uv" else "via pip")

        if (!isInstalled()) {
            return steps + Step(
                "Verify install",
                false,
                "Expected ${executable.absolutePath} to exist and be executable.",
            )
        }
        steps += Step("Verify install", true)
        return steps
    }

    /** Builds the initial index so the first search is not empty. */
    fun ingest(): Pair<Boolean, String> =
        exec(listOf(executable.absolutePath, "ingest"), installDir, timeoutSeconds = SETUP_TIMEOUT_SECONDS)

    private val agentFile: File
        get() = File(System.getProperty("user.home"), "Library/LaunchAgents/$AGENT_LABEL.plist")

    private val legacySyncAgentFile: File
        get() = File(System.getProperty("user.home"), "Library/LaunchAgents/$LEGACY_SYNC_AGENT_LABEL.plist")

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Mac")

    /**
     * Writes the one scheduled agent, matching its command and hours to [config].
     *
     * There is deliberately a single agent for both jobs: the sync cycle ingests before it
     * exports, so scheduling indexing separately just indexed the same sessions a second time —
     * and macOS listed the two as indistinguishable background items. The agent always runs
     * `refresh`, which syncs when team sync is configured and otherwise only indexes, so the
     * plist can never disagree with the settings about which work is due.
     *
     * The plist is generated from this machine's own home directory and the installed executable,
     * never shipped — a checked-in plist would carry whichever username built it. Re-running is
     * safe: the agent is unloaded before being loaded again.
     */
    fun installAgent(config: TeamSyncConfig): Pair<Boolean, String> {
        if (!isMac) return false to "Scheduled background work is macOS-only."

        retireLegacySyncAgent()
        val home = System.getProperty("user.home")
        return try {
            File(home, ".claude-session-cache").mkdirs()
            agentFile.parentFile?.mkdirs()
            agentFile.writeText(agentPlist(home, config))

            exec(listOf("launchctl", "unload", agentFile.absolutePath), installDir, timeoutSeconds = 20)
            val loaded = exec(
                listOf("launchctl", "load", "-w", agentFile.absolutePath),
                installDir,
                timeoutSeconds = 20,
            )
            if (!loaded.first) {
                LOG.warn("launchctl load failed for ${agentFile.absolutePath}: ${loaded.second}")
            }
            loaded
        } catch (throwable: Throwable) {
            LOG.warn("Could not install the scheduled agent at ${agentFile.absolutePath}", throwable)
            false to (throwable.message ?: "Unknown error")
        }
    }

    /** Turning everything off must also stop the scheduled work, or it keeps running unseen. */
    fun removeAgent() {
        if (!isMac) return
        retireLegacySyncAgent()
        if (!agentFile.isFile) return
        exec(listOf("launchctl", "unload", agentFile.absolutePath), installDir, timeoutSeconds = 20)
        if (!agentFile.delete()) LOG.warn("Could not delete ${agentFile.absolutePath}")
    }

    fun isAgentInstalled(): Boolean = agentFile.isFile

    /**
     * Migrates an agent written by an older build to the current plist, in place.
     *
     * A plugin update replaces code, never the launchd agents already registered on the machine.
     * Without this, an existing install would keep running the old pair — or, once the retired
     * sync agent was cleared away, stop syncing entirely until someone happened to reopen
     * settings and apply.
     *
     * Deliberately a no-op when the plist already matches: reloading an agent fires `RunAtLoad`,
     * so rewriting unconditionally would start a sync every time the tool window opened.
     */
    fun reconcileAgent(config: TeamSyncConfig) {
        if (!isMac) return
        val hasLegacyAgent = legacySyncAgentFile.isFile
        if (!agentFile.isFile && !hasLegacyAgent) return

        val desired = runCatching { agentPlist(System.getProperty("user.home"), config) }.getOrNull() ?: return
        val current = agentFile.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        if (!hasLegacyAgent && current == desired) return

        LOG.info("Migrating the scheduled agent to the merged index-and-sync job.")
        installAgent(config)
    }

    /**
     * Removes the separate sync agent earlier builds installed.
     *
     * Upgrading only replaces plugin code, not the launchd agents already registered on the
     * machine, so without this the retired agent would keep firing twice a day forever.
     */
    fun retireLegacySyncAgent() {
        if (!isMac || !legacySyncAgentFile.isFile) return
        exec(listOf("launchctl", "unload", legacySyncAgentFile.absolutePath), installDir, timeoutSeconds = 20)
        if (!legacySyncAgentFile.delete()) LOG.warn("Could not delete ${legacySyncAgentFile.absolutePath}")
    }

    /** Runs one sync cycle right now, for the status strip's "Sync now" feedback. */
    fun runSync(): Pair<Boolean, String> =
        exec(listOf(executable.absolutePath, "sync"), installDir, timeoutSeconds = SETUP_TIMEOUT_SECONDS)

    /**
     * The hours the job runs at, and why they differ.
     *
     * Sharing means the run also pushes and pulls, so it follows the hours chosen in settings —
     * work the user expects to see land during the day. With nothing to share it is pure indexing,
     * which nobody is waiting on, so it goes back to a single overnight pass.
     */
    private fun schedule(config: TeamSyncConfig): List<Pair<Int, Int>> =
        if (config.enabled) {
            config.syncHours.ifEmpty { TeamSyncConfig.DEFAULT_SYNC_HOURS }.map { hour -> hour to SYNC_MINUTE }
        } else {
            listOf(INDEX_ONLY_HOUR to 0)
        }

    private fun agentPlist(home: String, config: TeamSyncConfig): String {
        val intervals = schedule(config).joinToString("\n") { (hour, minute) ->
            """
                <dict>
                    <key>Hour</key>
                    <integer>$hour</integer>
                    <key>Minute</key>
                    <integer>$minute</integer>
                </dict>
            """.trimEnd()
        }
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>Label</key>
            <string>$AGENT_LABEL</string>
            <key>ProgramArguments</key>
            <array>
                <string>${executable.absolutePath}</string>
                <string>refresh</string>
            </array>
            <key>RunAtLoad</key>
            <true/>
            <key>StartCalendarInterval</key>
            <array>
$intervals
            </array>
            <key>StandardOutPath</key>
            <string>$home/.claude-session-cache/refresh.log</string>
            <key>StandardErrorPath</key>
            <string>$home/.claude-session-cache/refresh.error.log</string>
            <key>EnvironmentVariables</key>
            <dict>
                <key>HOME</key>
                <string>$home</string>
                <key>PATH</key>
                <string>$AGENT_PATH</string>
            </dict>
            <key>Nice</key>
            <integer>5</integer>
            <key>ProcessType</key>
            <string>Background</string>
        </dict>
        </plist>
        """.trimIndent()
    }

    private fun extractBundle() {
        val loader = McpRuntime::class.java.classLoader
        installDir.mkdirs()
        for (relativePath in bundledPaths()) {
            val bytes = loader.getResourceAsStream("mcp-server/$relativePath")?.use { it.readBytes() }
                ?: throw IllegalStateException("Bundled resource missing: $relativePath")
            val target = File(installDir, relativePath)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        versionFile.writeText(bundleFingerprint())
    }

    private fun bundledPaths(): List<String> {
        val manifest = McpRuntime::class.java.classLoader
            .getResourceAsStream("mcp-server/MANIFEST.txt")
            ?.bufferedReader()?.readText()
            ?: throw IllegalStateException("Plugin jar is missing the bundled MCP server.")
        return manifest.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Digest of the bundled Python, over content and not just the file list.
     *
     * The manifest alone cannot answer "is the installed copy current?" — editing a query changes
     * no filenames. This is what lets [isStale] notice that a plugin update carries newer server
     * code than the copy sitting in the home directory.
     */
    private fun bundleFingerprint(): String {
        val loader = McpRuntime::class.java.classLoader
        val digest = MessageDigest.getInstance("SHA-256")
        for (relativePath in bundledPaths()) {
            digest.update(relativePath.toByteArray())
            loader.getResourceAsStream("mcp-server/$relativePath")?.use { digest.update(it.readBytes()) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Finds a Python new enough to run the server.
     *
     * Version is probed rather than assumed: macOS ships 3.9 at `/usr/bin/python3`, and
     * picking it produces an opaque pip resolver error instead of a usable message.
     */
    private fun findSystemPython(): String? = pythonCandidates().firstOrNull { isSupported(it) }

    private fun pythonCandidates(): List<String> {
        val home = System.getProperty("user.home")
        val versioned = listOf("python3.14", "python3.13", "python3.12", "python3.11")
        val prefixes = listOf("/opt/homebrew/bin", "/usr/local/bin", "$home/.local/bin")

        val candidates = mutableListOf<String>()
        for (name in versioned) {
            for (prefix in prefixes) candidates.add("$prefix/$name")
        }
        for (prefix in prefixes) candidates.add("$prefix/python3")
        candidates.add("/usr/bin/python3")

        val (found, output) = exec(listOf("/usr/bin/env", "which", "python3"), null, timeoutSeconds = 10)
        if (found) output.trim().takeIf { it.isNotEmpty() }?.let(candidates::add)

        return candidates.distinct().filter { File(it).canExecute() }
    }

    private fun isSupported(python: String): Boolean {
        val (ok, output) = exec(
            listOf(python, "-c", "import sys;print(sys.version_info[0],sys.version_info[1])"),
            null,
            timeoutSeconds = 15,
        )
        if (!ok) return false
        val parts = output.trim().split(" ")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return major > 3 || (major == 3 && minor >= MIN_PYTHON_MINOR)
    }

    private fun pythonHelp(): String {
        val seen = pythonCandidates().joinToString(", ").ifBlank { "none" }
        return "Needs Python $MIN_PYTHON_MAJOR.$MIN_PYTHON_MINOR or newer.\n\n" +
            "Checked: $seen\n\n" +
            "macOS ships Python 3.9, which is too old. Install a newer one, for example:\n" +
            "    brew install python@3.12"
    }

    private fun findUv(): String? = listOf(
        "/opt/homebrew/bin/uv",
        "/usr/local/bin/uv",
        "${System.getProperty("user.home")}/.local/bin/uv",
    ).firstOrNull { File(it).canExecute() }

    private fun exec(
        command: List<String>,
        workingDir: File?,
        timeoutSeconds: Long = SETUP_TIMEOUT_SECONDS,
    ): Pair<Boolean, String> = try {
        val process = ProcessBuilder(command)
            .also { builder -> workingDir?.let { builder.directory(it) } }
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false to "`${command.joinToString(" ")}` timed out after ${timeoutSeconds}s."
        } else {
            val output = process.inputStream.bufferedReader().readText().trim()
            (process.exitValue() == 0) to output
        }
    } catch (throwable: Throwable) {
        LOG.warn("Command failed: ${command.joinToString(" ")}", throwable)
        false to (throwable.message ?: "Unknown error")
    }
}
