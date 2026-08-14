package com.mahadi.claudesessions

import com.mahadi.claudesessions.model.ClaudeSession

private val TICKET_PATTERN = Regex("""\b([A-Za-z]{2,10})-(\d{1,6})\b""")

private const val SCANNED_PROMPT_CHARS = 60

/**
 * Topic patterns, matched against the session title only.
 *
 * Whole words, not substrings: `"fix "` never matched `"how to fix?"`, and padding keywords with
 * spaces to stop false positives fails at the end of a string.
 *
 * Title-only is a measured decision, not caution. Scanning the opening prompt as well lifted
 * coverage from 34% to 42% of sessions but tagged "Write standup update" and "Plan remaining work
 * for today" as `api`, because a prompt mentions things in passing that the session is not about.
 * One wrong tag costs more than ten missing ones — it makes filtering by tag untrustworthy, and a
 * missing tag can still be added by hand or by [SessionAutoTagger]. Untitled sessions are covered
 * anyway: [com.mahadi.claudesessions.model.ClaudeSession.title] already falls back to the opening
 * prompt, so their prompt is what gets scanned here.
 */
private val TOPIC_PATTERNS: Map<String, Regex> = mapOf(
    "bugfix" to Regex("""\b(bug|fix|fixes|fixed|fixing|broken|crash|crashes|regression)\b"""),
    "tests" to Regex("""\b(test|tests|testing|pytest|coverage)\b"""),
    "migration" to Regex("""\b(migration|migrations|makemigrations)\b"""),
    "refactor" to Regex("""\b(refactor|refactoring|clean ?up|rename|renaming)\b"""),
    "review" to Regex("""\b(review|reviewing|pull request|pr)\b"""),
    "deploy" to Regex("""\b(deploy|deployment|release|rollout|production)\b"""),
    "ui" to Regex("""\b(ui|ux|design|layout|styling|css|theme)\b"""),
    "api" to Regex("""\b(endpoint|endpoints|serializer|viewset|api)\b"""),
    "docs" to Regex("""\b(readme|documentation|docstring|docs)\b"""),
    "performance" to Regex("""\b(n\+1|slow|optimise|optimize|optimisation|optimization|performance)\b"""),
    "setup" to Regex("""\b(set ?up|install|installing|configure|configuration)\b"""),
    "debug" to Regex("""\b(debug|debugging|investigate|traceback|stack ?trace)\b"""),
)

/**
 * Tags for a session: the ones you set by hand, plus the ones derived from the session itself.
 *
 * Derived tags are computed on read and never written to `metadata.json`. Persisting them would
 * freeze a branch's ticket id into a session that later moves, and would quietly mix machine
 * guesses into the list you curate — [SessionMetadataStore.tags] stays authoritative for that,
 * which is also why the "Untagged" filter still means *you* haven't tagged it.
 *
 * This is the free, instant tier. It mirrors what the Python cache derives on ingest, so the
 * plugin and MCP search agree on a session's ticket. [SessionAutoTagger] remains the paid tier
 * for topic tags a keyword can't reach.
 */
object SessionTags {

    fun all(session: ClaudeSession): List<String> =
        (SessionMetadataStore.tags(session.sessionId) + derived(session)).distinct()

    fun derived(session: ClaudeSession): List<String> {
        val tags = mutableListOf<String>()
        ticketId(session)?.let(tags::add)
        tags.addAll(topics(session))
        return tags
    }

    /** Branch first: it names the ticket being worked on even when nobody wrote it in the prompt. */
    private fun ticketId(session: ClaudeSession): String? {
        val candidates = listOfNotNull(
            session.gitBranch,
            session.title,
            session.firstPrompt.take(SCANNED_PROMPT_CHARS),
        )
        for (candidate in candidates) {
            val match = TICKET_PATTERN.find(candidate) ?: continue
            return "${match.groupValues[1].lowercase()}-${match.groupValues[2]}"
        }
        return null
    }

    private fun topics(session: ClaudeSession): List<String> {
        val title = session.title.lowercase()
        return TOPIC_PATTERNS.filterValues { it.containsMatchIn(title) }.keys.toList()
    }
}
