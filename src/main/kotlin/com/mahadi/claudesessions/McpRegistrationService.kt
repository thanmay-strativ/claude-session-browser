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
 * Registration covers every configured environment, not just the active one: user scope
 * lives in each account's own `.claude.json`, so registering only under the account that
 * happened to be selected left every other account without session search.
 */
object McpRegistrationService {

    data class Result(val success: Boolean, val output: String)

    /**
     * True only when every configured account has the server in its `.claude.json`.
     *
     * Read straight from the files: `claude mcp get` would also answer this, but it
     * health-checks by spawning the server (~1.8s), which is far too slow for painting
     * UI state — and it would only answer for one account anyway.
     */
    fun isRegistered(): Boolean = configDirs().all { isRegisteredFor(it) }

    fun register(): Result {
        if (!McpRuntime.isInstalled()) {
            return Result(false, "The session cache is not installed yet.")
        }
        return forEachAccount { configDir ->
            if (isRegisteredFor(configDir)) {
                Result(true, "already registered")
            } else {
                run(
                    listOf(
                        "mcp", "add", "-s", REGISTRATION_SCOPE, SERVER_NAME,
                        "--", McpRuntime.executable.absolutePath, "serve",
                    ),
                    configDir,
                )
            }
        }
    }

    fun unregister(): Result = forEachAccount { configDir ->
        if (isRegisteredFor(configDir)) {
            run(listOf("mcp", "remove", SERVER_NAME, "-s", REGISTRATION_SCOPE), configDir)
        } else {
            Result(true, "not registered")
        }
    }

    data class AccountRegistration(val environmentName: String, val registered: Boolean)

    /** Per-environment registration state, for the health view. */
    fun registrationByAccount(): List<AccountRegistration> =
        SessionMetadataStore.environments().map { environment ->
            val configDir = environment.configDir
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != SessionMetadataStore.defaultConfigDir() }
            AccountRegistration(environment.name, isRegisteredFor(configDir))
        }

    /** Every distinct account config directory; null stands for the default `~/.claude`. */
    private fun configDirs(): List<String?> =
        SessionMetadataStore.environments()
            .map { environment ->
                environment.configDir
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it != SessionMetadataStore.defaultConfigDir() }
            }
            .distinct()
            .ifEmpty { listOf(null) }

    private fun forEachAccount(action: (String?) -> Result): Result {
        val failures = mutableListOf<String>()
        for (configDir in configDirs()) {
            val result = action(configDir)
            if (!result.success) {
                failures += "${configDir ?: "default account"}: ${result.output}"
            }
        }
        return if (failures.isEmpty()) {
            Result(true, "")
        } else {
            Result(false, failures.joinToString("\n"))
        }
    }

    private fun isRegisteredFor(configDir: String?): Boolean {
        val config = configFile(configDir)
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

    /**
     * Where an account keeps its MCP registrations: `$CLAUDE_CONFIG_DIR/.claude.json`
     * for a second account, `~/.claude.json` for the default one.
     */
    private fun configFile(configDir: String?): File {
        return if (configDir != null) {
            File(configDir, ".claude.json")
        } else {
            File(System.getProperty("user.home"), ".claude.json")
        }
    }

    private fun run(arguments: List<String>, configDir: String?): Result {
        val binary = ClaudeBinaryLocator.resolve()
        return try {
            val builder = ProcessBuilder(listOf(binary) + arguments).redirectErrorStream(true)
            configDir?.let { builder.environment()["CLAUDE_CONFIG_DIR"] = it }
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
