package com.mahadi.claudesessions.model

import java.nio.file.Path

/**
 * A single Claude Code session, reconstructed from its transcript `.jsonl` file
 * under `~/.claude/projects/<encoded-project>/<sessionId>.jsonl`.
 */
data class ClaudeSession(
    val sessionId: String,
    val title: String,
    val firstPrompt: String,
    val projectPath: String,
    val projectName: String,
    val encodedDirName: String,
    val transcriptPath: Path,
    val lastModifiedMillis: Long,
    val messageCount: Int,
    val model: String?,
    val gitBranch: String?,
)
