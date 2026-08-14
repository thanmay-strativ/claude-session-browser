package com.mahadi.claudesessions

/**
 * Team knowledge-base sync settings.
 *
 * Written by the settings dialog into the shared `metadata.json` sidecar, where the
 * bundled Python `claude-session-cache sync` command reads the same values — the
 * dialog and the sync engine can never disagree about what is configured.
 */
data class TeamSyncConfig(
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val repoPath: String? = null,
    val repoUrl: String? = null,
    val owner: String? = null,
    val projects: List<String> = emptyList(),
    val syncHours: List<Int> = DEFAULT_SYNC_HOURS,
    val defaultScope: String = SCOPE_MINE,
    val minMessages: Int = DEFAULT_MIN_MESSAGES,
    val maxAgeDays: Int = 0,
    val extraRedactionPatterns: List<String> = emptyList(),
    val notifyOnFailure: Boolean = true,
) {
    /** True when sharing is on and not temporarily held — receiving is unaffected by pause. */
    fun isSharing(): Boolean = enabled && !paused

    companion object {
        val DEFAULT_SYNC_HOURS: List<Int> = listOf(9, 18)

        const val SCOPE_MINE = "mine"
        const val SCOPE_TEAM = "team"

        /** Two-message throwaways carry no decision; they would only dilute team search. */
        const val DEFAULT_MIN_MESSAGES = 3
    }
}
