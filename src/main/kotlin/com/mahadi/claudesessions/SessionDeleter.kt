package com.mahadi.claudesessions

import com.intellij.openapi.diagnostic.logger
import com.mahadi.claudesessions.model.ClaudeSession
import java.io.File

private val LOG = logger<SessionDeleterService>()

private class SessionDeleterService

/**
 * Deletes a session's transcript file and its sibling data directory (subagents,
 * tool-results) from `~/.claude/projects/<encoded-project>/`, plus any custom title
 * stored for it. Irreversible — callers must confirm with the user before invoking.
 */
object SessionDeleter {

    fun delete(session: ClaudeSession): Boolean {
        val transcriptFile = session.transcriptPath.toFile()
        val sessionDataDir = File(transcriptFile.parentFile, session.sessionId)

        val transcriptDeleted = !transcriptFile.exists() || transcriptFile.delete()
        val dataDirDeleted = !sessionDataDir.exists() || sessionDataDir.deleteRecursively()

        if (!transcriptDeleted || !dataDirDeleted) {
            LOG.warn(
                "Failed to fully delete session ${session.sessionId} " +
                    "(transcript=$transcriptDeleted, dataDir=$dataDirDeleted)",
            )
            return false
        }

        SessionMetadataStore.forget(session.sessionId)
        LOG.info("Deleted Claude session ${session.sessionId}")
        return true
    }
}
