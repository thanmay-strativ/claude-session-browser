# Claude Session Browser — Plan & Progress

A PyCharm/IntelliJ plugin that lists all Claude Code sessions in a left-hand
tool window, lets you read the full transcript, and resumes a session with
one click.

## Why

Resuming an old Claude Code session meant running `claude --resume` in a
terminal and picking from a bare list of session IDs — no titles, no context,
no way to tell sessions apart without opening each one. This plugin makes
browsing and resuming sessions visual instead of terminal guesswork.

## Status: working, installed locally

## What's done

- **Left tool window ("Claude Sessions")**
  - Scans `~/.claude/projects/*/*.jsonl` for every session on the machine.
  - Groups sessions by project; current project pinned to the top.
  - Each row shows: title, relative time (`3h ago`), model (`sonnet`),
    message count, and git branch.
  - Search box filters by title / first prompt / project name.

- **Transcript viewer**
  - Click a session → opens the full conversation as a read-only editor tab
    (You / Claude / tool calls, color-coded, monospace for tool I/O).
  - Header shows title, project, date, model, branch.
  - **"Continue session" button** at the top of the transcript.

- **Resume flow**
  - Continue button opens a new tab in PyCharm's integrated Terminal, cd's
    into the session's project directory, and runs `claude --resume <id>`.
  - Resolves the `claude` binary by absolute path (`~/.local/bin/claude` etc.)
    so PATH issues in the embedded terminal can't break it.
  - Falls back to copying the resume command to the clipboard if the
    terminal API can't be driven (logged as a warning either way).

- **Rename**
  - Right-click a session → "Rename title…" to set a custom display title.
  - Stored in the plugin's own settings (`PropertiesComponent`), keyed by
    session id — Claude's transcript files are never modified.
  - "Reset title" restores the original AI-generated title.

- **Delete**
  - Right-click → "Delete session…" (single) or select multiple sessions
    (ctrl/cmd-click) → "Delete N sessions…" (bulk). Confirmation dialog first.
  - Removes the transcript `.jsonl` and its sibling data directory (subagents,
    tool-results) from `~/.claude/projects/`, plus any stored title/tags/pin
    for that session. Closes the transcript tab first if it's open. Logged as
    a warning (not silently swallowed) if a file can't be removed.

- **Pin / favorite**
  - Right-click → "Pin session" / "Unpin session". Pinned sessions float to
    the top of their project group and show a ★ marker.
  - Stored in `PropertiesComponent`, same pattern as custom titles.

- **Tags**
  - Right-click → "Edit tags…", comma-separated free-form tags shown inline
    in the row (`#tag` in purple) and matched by the search box.
  - Stored in `PropertiesComponent`, same pattern as custom titles.
  - Right-click → "Suggest tags (AI)…" gets 2-4 tags from a single, cheap
    one-shot call to the `claude` CLI's print mode (`claude -p --model
    claude-haiku-4-5-20251001 "…"`) — only the session title + first message
    are sent (no full transcript) to keep token spend minimal. Suggested
    tags merge with any existing ones.

- **Files touched**
  - Transcript header shows "Files touched (N): a.py, b.ts, …" — the distinct
    file paths from Read/Write/Edit/NotebookEdit tool calls in that session,
    parsed straight from the transcript JSONL (no extra dependency). Full
    paths on hover via tooltip.

- **Quick filter chips**
  - Toggle buttons above the tree — Today, This week, Pinned, Untagged —
    AND-combine with the text search instead of replacing it.

- **Auto-refresh**
  - The tree rescans `~/.claude/projects` every 30s on a lightweight Swing
    timer (stopped automatically once the panel is no longer displayable).
    The manual refresh button stays as an explicit, always-available
    fallback — this was a deliberate choice over a native VFS file watcher,
    to keep the lifecycle simple and avoid watch-root registration.

- **Inline quick-resume**
  - A small ▶ glyph at the end of each session row resumes that session
    directly (same as "Continue in Terminal") without opening the transcript
    first — implemented as a clickable text fragment via
    `SimpleColoredComponent`'s tag/hit-testing API, not a separate button.

- **Content search**
  - "Content" checkbox next to the search box. When checked, matching also
    scans full message text inside transcripts (not just title/prompt/project
    /tags), on a background thread so typing stays responsive. Runs
    additively alongside the instant title/prompt filter and cancels a
    stale scan if the query changes again before it finishes.

- **Export**
  - Right-click → "Export as Markdown…" opens a native save dialog and writes
    the transcript (title, project, branch, model, then each message) as a
    `.md` file — handy for pasting into a PR or a Jira ticket.

- **Stats dashboard**
  - "Stats" button in the toolbar opens a read-only dialog: stat tiles for
    total sessions/messages/projects plus an oldest→newest activity range,
    and a hand-painted horizontal bar chart per breakdown (by project, by
    model, top tags) — no charting library, just Java2D. Computed from the
    already-scanned session list — no extra transcript reads.

- **Right-click menu**: Open transcript, Continue in Terminal, Rename title,
  Reset title, Edit tags, Pin/Unpin session, Export as Markdown, Copy session
  id, Copy resume command, Reveal transcript in Finder, Delete session(s).
  Multi-select (ctrl/cmd-click) collapses the menu to just bulk delete.

## Decisions made along the way

- **Native Kotlin/IntelliJ plugin**, not a standalone Python app — user
  wanted it docked in PyCharm's left sidebar like the Copilot Chat panel.
- **Click → read transcript → explicit Continue button**, not an immediate
  jump to the terminal — user wanted to skim the conversation first.
- **Resume launches in PyCharm's integrated Terminal.** A version that
  launched Terminal.app instead (to fix TUI color/statusline rendering) was
  tried and reverted — the integrated-terminal experience was preferred
  despite the rendering quirks.
- **Toolchain**: machine had no JDK. Installed `openjdk@21` via Homebrew
  (PyCharm's bundled JBR is JDK 25, too new for Gradle) and generated a
  self-contained Gradle 8.14 wrapper. Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew buildPlugin`.
- Built against IntelliJ Community 2025.2, `since-build=242` / `until-build=299.*`
  so it loads on PyCharm 2026.2 (and other JetBrains IDEs in that range).

## How to build & install (for reference)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew buildPlugin
```

Output: `build/distributions/claude-session-browser-1.0.0.zip`

Install: PyCharm → Settings → Plugins → ⚙ → Install Plugin from Disk → pick
the zip → restart. Fully self-contained (gson bundled), no Java needed on
the installing machine.

## Sharing with others

- **Now**: hand people the zip directly; install-from-disk as above. Each
  person sees their own `~/.claude/projects` sessions.
- **Later options considered**: push source to a git repo (GitHub/Bitbucket)
  with the zip attached as a release; an internal update-repository URL for
  auto-updates across the team; JetBrains Marketplace for public
  distribution (would need to drop "Claude" branding per their guidelines).

## Component 2: session cache + MCP server (built)

Lives in `mcp-server/` — a Python package (`uv`), separate toolchain from the
Gradle plugin but the same product. See `mcp-server/README.md` for full detail.

### Premise correction

This was originally justified as "rescue sessions before Claude Code prunes
them after ~2 weeks." **That was wrong.** `~/.claude/settings.json` sets
`cleanupPeriodDays: 99999`, so nothing is being deleted — 383 transcripts
survive back to 2026-05-04, 134 of them older than 60 days. The real
justifications are:

- Claude itself can query past sessions (the actual goal).
- Speed: the plugin's Content search re-read all 387 MB per query; FTS5 makes
  it instant.
- Structured cross-session queries (files touched, branches, commits).
- A safety net if that setting ever changes, or on a machine using the default.

### What it does

- **SQLite + FTS5** at `~/.claude-session-cache/sessions.db` (80 MB from 387 MB
  of JSONL, tool results capped at 8 KB each).
- **Incremental ingest** — per-session watermark (mtime, size, lines read);
  a re-run over an unchanged store takes **~80 ms** instead of rescanning.
- **Secret redaction on ingest**, not on read — a secret that never enters the
  DB cannot leak out. Verified: 700 messages sanitised, zero raw `sk-ant-` keys.
- **Subagent transcripts** indexed too (205 of them), linked to their parent.
- **Schema versioning** with in-place migration (already exercised: v1 → v2
  added `custom_title` without a rebuild).

### Tags, branch and commits

Tags are a first-class dimension, not decoration:

- `branch:<name>` — every session tagged with its git branch
- `<ticket>` — ticket id parsed out of the branch (`feature/PROJ-1234-x` → `proj-1234`)
- `committed` — the session produced a git commit; SHAs, branch and subject are
  parsed out of `git commit` output in Bash tool results and stored separately
- `subagent`, `pinned` — derived
- manual tags — from the shared sidecar

### Shared metadata sidecar

Custom titles / pins / tags used to live in IntelliJ's `PropertiesComponent`,
which Python cannot read portably. They now live in
`~/.claude-session-browser/metadata.json`, written by the plugin
(`SessionMetadataStore`, which migrates old `PropertiesComponent` values across
lazily per session) and read by the cache. `custom_title` is stored separately
from the transcript title so clearing an override restores the original.

### MCP tools (7)

`search_sessions`, `list_sessions`, `get_session`, `sessions_touching_file`,
`find_commits`, `cache_stats`, `refresh_cache`. Registered at **user** scope so
it works in every project, not just the one it was added from.

Search runs two passes — all terms (precise), then any term (recall) — and
reports which as `match_mode`. An all-terms-only search returned zero results
for conversational questions, which are exactly what this exists to answer.
All results are bounded (snippets, `limit` capped at 50, `get_session` walks a
character budget) so no single call can flood the caller's context.

### Freshness

`launchd` agent at `~/Library/LaunchAgents/com.mahadi.claude-session-cache.plist`
— daily at 03:00 plus on load, so it works with PyCharm closed. The
`refresh_cache` MCP tool covers "index this right now".

### Plugin-side integration

- **MCP on/off checkbox** beside the filter dropdown. Claude Code has no
  enable/disable for a server, so off means `claude mcp remove` — the toggle
  reflects real registration state via `claude mcp get`'s exit code. Config is
  read at Claude Code startup, so a running session keeps the old state until
  restart.
- **"Claude memory (MCP)" section in Stats** — a coverage gauge (indexed vs on
  disk, amber if anything is unindexed), tiles for indexed messages / sessions
  with commits / secrets hidden, and registration status. Read by shelling out
  to `claude-session-cache stats` and parsing its JSON, so the plugin needs no
  SQLite dependency and there is one implementation of the queries.

## Future scope (not started)

1. **Semantic search** — FTS5 covers keyword recall; embeddings would catch
   "find where I discussed this idea" when the wording differs.
2. **Plugin search backed by the cache** — would make Content search instant
   instead of re-reading JSONL. Deliberately deferred: costs a sqlite-jdbc
   dependency and a staleness story.
3. **Session diff/compare** — pick two sessions on the same project and see
   what changed (files touched, decisions made).
4. **"Continue in new worktree"** — resume a session but in a fresh git
   worktree instead of the original directory, for parallel work.
5. **Soft delete / restore** — the plugin's delete is irreversible, but the
   cache now holds an indexed copy of every session. "Delete from disk but keep
   it searchable", or restore a transcript back out of the cache, is a cheap
   safety net on top of what already exists.
6. **Related-sessions detection** — same project + overlapping files-touched
   sessions surfaced as "See also" links in the transcript header. The
   `files_touched` table makes this a single query now.

*Dropped:* an "at risk of deletion" badge — pointless once it was established
that `cleanupPeriodDays: 99999` means nothing is being pruned.

Ideas considered and already built (see "What's done" above): full-text
content search, export to Markdown, pin/favorite, bulk delete, tags (manual
and AI-suggested), the stats dashboard, files-touched, quick filter chips,
auto-refresh, and inline quick-resume.
