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
    val repoPath: String? = null,
    val repoUrl: String? = null,
    val owner: String? = null,
    val projects: List<String> = emptyList(),
    val syncHours: List<Int> = DEFAULT_SYNC_HOURS,
) {
    companion object {
        val DEFAULT_SYNC_HOURS: List<Int> = listOf(9, 18)
    }
}
