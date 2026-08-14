# claude-session-cache

Local searchable archive of your Claude Code sessions, exposed to Claude over MCP.

Claude Code writes every session to `~/.claude/projects/<encoded-path>/<id>.jsonl`.
Searching that directly means re-reading hundreds of megabytes per query, and Claude
itself has no way to look at it. This indexes it into SQLite with FTS5 and serves it as
MCP tools, so Claude can answer "what did we decide about X" from your own history.

## What it indexes

| Dimension | Source |
| --- | --- |
| Message text (FTS5) | user / assistant / thinking / tool_use / tool_result |
| Session metadata | title, project, model, git branch, timestamps |
| Files touched | `Read` / `Write` / `Edit` / `NotebookEdit` tool calls |
| Commits | git commit output parsed out of `Bash` results |
| Tags | your manual tags, plus derived ones (see below) |
| Subagents | `<session>/subagents/*.jsonl`, linked to the parent session |

### Derived tags

Applied automatically on every ingest:

- `branch:<name>` — the session's git branch
- `<ticket>` — a ticket id parsed from the branch (`feature/PROJ-1234-x` → `proj-1234`)
- `committed` — the session produced at least one git commit
- `subagent` — the session is a subagent transcript
- `pinned` — pinned in the PyCharm plugin

Manual tags come from the sidecar the plugin writes (see *Shared metadata*).

## Secret redaction

Transcripts contain raw shell output, so they routinely hold API keys and tokens.
Redaction runs **on ingest**, not on read — a secret that never enters the database
cannot leak out of it. Covered: Anthropic/OpenAI-style keys, GitHub and Slack tokens,
AWS key ids, JWTs, bearer tokens, private key blocks, DB URLs with credentials, and
`*_SECRET` / `*_PASSWORD` / `*_TOKEN` style assignments.

Redaction is pattern-based, so treat it as a strong reduction in exposure rather than a
guarantee. The cache is local-only; don't sync it anywhere without re-reviewing this.

## Install

**Normally you don't.** This package is bundled inside the PyCharm plugin jar. Ticking
**MCP** in the plugin's panel extracts it to `~/.claude-session-browser/mcp-server`,
builds a virtualenv, installs it, indexes your sessions and registers it with Claude
Code — one click, no `uv`, nothing pointing back at this checkout.

Requirements on the target machine: **Python 3.11+** and network access on first run
(to fetch `mcp`). macOS ships Python 3.9, which is too old — the plugin detects that and
says so rather than failing inside pip. `uv` is used automatically if present, purely
because it's faster.

### Manual / development install

```bash
cd mcp-server
uv sync
uv run claude-session-cache ingest        # first run indexes everything
```

Register manually:

```bash
claude mcp add -s user claude-sessions -- \
  ~/.claude-session-browser/mcp-server/.venv/bin/claude-session-cache serve
```

### Staying fresh

Ticking **MCP** in the plugin also installs a per-user launchd agent that re-indexes daily
at 03:00 (and on login). The plist is *generated* at that moment from the machine's own
home directory and venv path — the copy in `launchd/` is a reference only, and contains the
username of whoever wrote it. Un-ticking MCP unloads and deletes the agent, so a disabled
server never leaves a scheduled job behind.

To manage it by hand:

```bash
launchctl load -w ~/Library/LaunchAgents/com.mahadi.claude-session-cache.plist
launchctl unload  ~/Library/LaunchAgents/com.mahadi.claude-session-cache.plist
```

## CLI

```bash
uv run claude-session-cache ingest [--full] [--projects-dir DIR]
uv run claude-session-cache search "some query" [--limit N]
uv run claude-session-cache stats
uv run claude-session-cache serve
```

Ingest is incremental: each session records a watermark (`mtime`, size, lines read) and
a re-run parses only appended lines. A re-run over an unchanged store takes ~80ms.

## MCP tools

| Tool | Purpose |
| --- | --- |
| `search_sessions` | FTS across message bodies, returns ranked snippets |
| `list_sessions` | Browse newest-first, filter by project / tag / branch / age |
| `get_session` | One session's metadata, tags, commits, files, transcript |
| `sessions_touching_file` | "Have I worked on this file before?" |
| `find_commits` | Which session produced a commit; what landed on a branch |
| `cache_stats` | Totals, date range, top tags, per-project, top branches |
| `refresh_cache` | Re-scan on demand (`full=true` to rebuild) |

### Search behaviour

Two passes. All terms first (precise); if that returns nothing, any term (recall). The
response reports which ran as `match_mode`, since a conversational question rarely has
all its words inside a single message.

Results are always bounded — snippets rather than transcripts, `limit` capped at 50, and
`get_session` walks entries against a character budget. This is deliberate: an unbounded
transcript dump would flood the caller's context.

## Shared metadata

Custom titles, pins and tags are user-authored and cannot be recovered from transcripts.
The PyCharm plugin writes them to:

```
~/.claude-session-browser/metadata.json
```

```json
{
  "version": 1,
  "sessionRoot": "/Users/you/.claude-work/projects",
  "claudeBinary": "/Users/you/.local/bin/claude-work",
  "environments": [
    { "name": "claude" },
    {
      "name": "claude-work",
      "sessionRoot": "/Users/you/.claude-work/projects",
      "configDir": "/Users/you/.claude-work"
    }
  ],
  "activeEnvironment": "claude-work",
  "sessions": {
    "<session-id>": { "title": "...", "pinned": true, "tags": ["bug", "vay-4499"] }
  }
}
```

Plain JSON outside any IDE config directory is what makes it readable from both the
Kotlin plugin and this Python package.

`environments` are the Claude accounts the plugin's dropdown switches between; an omitted
key means "the default". `configDir` is the account's `CLAUDE_CONFIG_DIR` — normally the
only thing a second account needs, since Claude Code keeps its transcripts in
`<configDir>/projects` and both accounts share one `claude` binary. `claudeBinary` is only
for accounts with a genuinely separate install.

The top-level `sessionRoot` / `claudeBinary` are a **mirror of the active environment**,
rewritten on every switch — this package only reads those two, so it always ingests
whichever account is currently selected and needs to know nothing about environments.

Both accounts' sessions accumulate in one cache — ingest never deletes rows outside the
current root — but every query is **scoped to the selected account** via each session's
`source_root`. So switching environments changes what Claude can see, and nothing from a
work account surfaces while browsing a personal one. Pass `all_accounts=true` to
`search_sessions`, `list_sessions`, `sessions_touching_file`, `find_commits` or
`cache_stats` to deliberately reach across all of them; `cache_stats` always reports an
`accounts` breakdown so a second account stays discoverable.

## Paths

| What | Where | Override |
| --- | --- | --- |
| Cache DB | `~/.claude-session-cache/sessions.db` | `CLAUDE_SESSION_CACHE_DIR` |
| Transcripts | `~/.claude/projects` | `CLAUDE_PROJECTS_DIR` |
| Shared metadata | `~/.claude-session-browser/metadata.json` | `CLAUDE_SESSION_METADATA` |
