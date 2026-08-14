package com.mahadi.claudesessions

import com.intellij.testFramework.LightVirtualFile
import com.mahadi.claudesessions.model.ClaudeSession
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory virtual file that stands in for a session so the transcript viewer can
 * open as a normal editor tab. Instances are cached per session id so reopening the
 * same session focuses the existing tab instead of creating a duplicate.
 */
class ClaudeSessionVirtualFile private constructor(
    val session: ClaudeSession,
) : LightVirtualFile(session.title.take(40).ifBlank { session.sessionId }) {

    override fun getPath(): String = "claude-session://${session.sessionId}"

    override fun isWritable(): Boolean = false

    override fun equals(other: Any?): Boolean =
        other is ClaudeSessionVirtualFile && other.session.sessionId == session.sessionId

    override fun hashCode(): Int = session.sessionId.hashCode()

    companion object {
        private val cache = ConcurrentHashMap<String, ClaudeSessionVirtualFile>()

        fun of(session: ClaudeSession): ClaudeSessionVirtualFile =
            cache.getOrPut(session.sessionId) { ClaudeSessionVirtualFile(session) }
    }
}
