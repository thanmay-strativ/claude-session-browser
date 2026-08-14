package com.mahadi.claudesessions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.mahadi.claudesessions.model.ClaudeSession
import java.util.concurrent.TimeUnit

private val LOG = logger<SessionAutoTaggerService>()

private class SessionAutoTaggerService

private const val SUGGEST_MODEL = "claude-haiku-4-5-20251001"

/**
 * Suggests short tags for a session via a single, cheap one-shot call to the
 * `claude` CLI's print mode — only the title and first prompt are sent (no full
 * transcript), and it runs on Haiku to keep token spend minimal.
 */
object SessionAutoTagger {

    fun suggestTags(session: ClaudeSession, onResult: (List<String>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val tags = try {
                runClaudePrint(buildPrompt(session))
            } catch (throwable: Throwable) {
                LOG.warn("Auto-tag failed for session ${session.sessionId}", throwable)
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater { onResult(tags) }
        }
    }

    private fun buildPrompt(session: ClaudeSession): String =
        """
        Suggest 2-4 short tags for this coding session, lowercase, single words or
        hyphenated. Respond with ONLY the tags separated by commas — no explanation,
        no extra punctuation.

        Title: ${session.title}
        First message: ${session.firstPrompt.take(300)}
        """.trimIndent()

    private fun runClaudePrint(prompt: String): List<String> {
        val binary = ClaudeBinaryLocator.resolve()
        val builder = ProcessBuilder(binary, "-p", "--model", SUGGEST_MODEL, prompt)
            .redirectErrorStream(true)
        SessionMetadataStore.claudeConfigDir()?.let { builder.environment()["CLAUDE_CONFIG_DIR"] = it }
        val process = builder.start()

        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("claude -p timed out")
        }

        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.exitValue() != 0) {
            throw IllegalStateException("claude -p exited ${process.exitValue()}: $output")
        }

        return output.split(",")
            .map { it.trim().trim('#').lowercase() }
            .filter { it.isNotEmpty() }
            .take(4)
    }
}
