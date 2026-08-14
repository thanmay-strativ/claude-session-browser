package com.mahadi.claudesessions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.mahadi.claudesessions.model.ClaudeSession
import java.io.File
import java.util.concurrent.TimeUnit

private val LOG = logger<SessionAutoTaggerService>()

private class SessionAutoTaggerService

private const val SUGGEST_MODEL = "claude-haiku-4-5-20251001"

/**
 * What a real tag looks like. The CLI prints warnings ("no stdin data received…") that
 * would otherwise be split on commas and saved as tags, so anything that is not a short
 * lowercase word or hyphenation is dropped rather than trusted.
 */
private val TAG_SHAPE = Regex("^[a-z0-9][a-z0-9-]{0,29}$")

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
        // stdin comes from /dev/null: an inherited open pipe makes the CLI wait 3s and
        // print a "no stdin data received" warning that used to end up saved as tags.
        val builder = ProcessBuilder(binary, "-p", "--model", SUGGEST_MODEL, prompt)
            .redirectInput(File("/dev/null"))
        SessionMetadataStore.claudeConfigDir()?.let { builder.environment()["CLAUDE_CONFIG_DIR"] = it }
        val process = builder.start()

        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("claude -p timed out")
        }

        val output = process.inputStream.bufferedReader().readText().trim()
        val errors = process.errorStream.bufferedReader().readText().trim()
        if (process.exitValue() != 0) {
            throw IllegalStateException("claude -p exited ${process.exitValue()}: ${errors.ifEmpty { output }}")
        }

        val answerLine = output.lines().lastOrNull { it.isNotBlank() } ?: return emptyList()
        return answerLine.split(",")
            .map { it.trim().trim('#').lowercase() }
            .filter { TAG_SHAPE.matches(it) }
            .take(4)
    }
}
