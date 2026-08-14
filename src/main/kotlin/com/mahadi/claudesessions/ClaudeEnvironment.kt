package com.mahadi.claudesessions

/**
 * One Claude account the panel can talk to.
 *
 * Claude Code keeps an account's whole state under a config directory (`~/.claude` by
 * default), with transcripts in `<configDir>/projects`. A second account is normally the
 * *same* executable pointed at a different config directory via `CLAUDE_CONFIG_DIR` — so
 * [configDir] is what makes resume, tagging and MCP act as that account. [claudeBinary]
 * only needs setting when the accounts genuinely have separate installs; null means
 * auto-detect. [configDir] null means the default `~/.claude`.
 */
data class ClaudeEnvironment(
    val name: String,
    val sessionRoot: String,
    val claudeBinary: String?,
    val configDir: String?,
) {
    override fun toString(): String = name
}
