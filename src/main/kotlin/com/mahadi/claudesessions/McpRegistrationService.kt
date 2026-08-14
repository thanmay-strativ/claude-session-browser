package com.mahadi.claudesessions

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.util.concurrent.TimeUnit

private val LOG = logger<McpRegistrationService>()

private const val SERVER_NAME = "claude-sessions"
private const val COMMAND_TIMEOUT_SECONDS = 25L

/** User scope, not local: the cache spans every project, so the server should be too. */
private const val REGISTRATION_SCOPE = "user"

/**
 * Registers and unregisters the session-cache MCP server with Claude Code by shelling out
 * to `claude mcp add` / `claude mcp remove`.
 *
 * Claude Code has no enable/disable for a server, so "off" means removing the
 * registration. Claude Code reads this config at startup, so a running session keeps the
 * previous state until it restarts.
 *
 * Registration is per account: every call carries the active environment's
 * `CLAUDE_CONFIG_DIR`, so ticking MCP while on a second account registers with that
 * account rather than silently with the default one.
 */
object McpRegistrationService {

    data class Result(val success: Boolean, val output: String)

    /**
     * Reads user-scope registration straight out of the active account's `.claude.json`.
     *
     * `claude mcp get` would also answer this, but it health-checks by spawning the server
     * (~1.8s), which is far too slow for painting UI state.
     */
    fun isRegistered(): Boolean {
        val config = configFile()
        if (!config.isFile) return false
        return try {
            JsonParser.parseString(config.readText())
                .asJsonObject
                .getAsJsonObject("mcpServers")
                ?.has(SERVER_NAME) == true
        } catch (throwable: Throwable) {
            LOG.warn("Could not read MCP registration from ${config.absolutePath}", throwable)
            false
        }
    }

    fun register(): Result {
        if (!McpRuntime.isInstalled()) {
            return Result(false, "The session cache is not installed yet.")
        }
        return run(
            listOf(
                "mcp",
                "add",
                "-s",
                REGISTRATION_SCOPE,
                SERVER_NAME,
                "--",
                McpRuntime.executable.absolutePath,
                "serve",
            )
        )
    }

    fun unregister(): Result = run(listOf("mcp", "remove", SERVER_NAME, "-s", REGISTRATION_SCOPE))

    /**
     * Where the active account keeps its MCP registrations: `$CLAUDE_CONFIG_DIR/.claude.json`
     * for a second account, `~/.claude.json` for the default one.
     */
    private fun configFile(): File {
        val configDir = SessionMetadataStore.claudeConfigDir()
        return if (configDir != null) {
            File(configDir, ".claude.json")
        } else {
            File(System.getProperty("user.home"), ".claude.json")
        }
    }

    private fun run(arguments: List<String>): Result {
        val binary = ClaudeBinaryLocator.resolve()
        return try {
            val builder = ProcessBuilder(listOf(binary) + arguments).redirectErrorStream(true)
            SessionMetadataStore.claudeConfigDir()?.let {
                builder.environment()["CLAUDE_CONFIG_DIR"] = it
            }
            val process = builder.start()
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Result(false, "`claude ${arguments.joinToString(" ")}` timed out.")
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            Result(process.exitValue() == 0, output)
        } catch (throwable: Throwable) {
            LOG.warn("claude mcp ${arguments.joinToString(" ")} failed", throwable)
            Result(false, throwable.message ?: "Unknown error")
        }
    }

}
