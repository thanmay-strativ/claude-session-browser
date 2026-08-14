package com.mahadi.claudesessions

import java.io.File

/**
 * Resolves the `claude` CLI to drive, by absolute path so callers running it from an
 * embedded shell or a plain `ProcessBuilder` aren't tripped up by PATH differences.
 *
 * An explicitly configured binary always wins: a second Claude account is usually a
 * separate executable (`claude-work` next to `claude`), and auto-detection cannot tell
 * which one the user means.
 */
object ClaudeBinaryLocator {

    fun resolve(): String = SessionMetadataStore.claudeBinary()?.takeIf { File(it).canExecute() }
        ?: autoDetect()
        ?: "claude"

    fun autoDetect(): String? = candidates().firstOrNull { File(it).canExecute() }

    fun candidates(): List<String> {
        val home = System.getProperty("user.home")
        return listOf(
            "$home/.local/bin/claude",
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
            "$home/.claude/local/claude",
        )
    }
}
