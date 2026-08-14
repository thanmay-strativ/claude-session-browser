"""MCP server exposing the session cache over stdio.

Tools are deliberately thin: they validate, delegate to `queries`, and return plain
dicts. Result sizes are bounded in the query layer so a single call cannot flood the
caller's context.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Any

from mcp.server.fastmcp import FastMCP

from . import queries
from .db import connect
from .ingest import ingest

INSTRUCTIONS = """
Searchable archive of this user's past Claude Code sessions (their own prior
conversations, across every project on this machine).

Use it when the user refers to earlier work — "what did we decide about X", "I fixed
this before", "which session touched this file", "what was I doing on that branch".

Start with search_sessions to locate candidates, then get_session for the detail of a
specific one. Sessions carry auto tags: branch:<name>, the ticket id parsed from the
branch, committed (the session produced a git commit), subagent, and pinned — plus any
tags the user set by hand.
""".strip()

app: FastMCP = FastMCP(name="claude-session-cache", instructions=INSTRUCTIONS)

_connection: sqlite3.Connection | None = None
_db_path: Path | None = None


def configure(db_path: Path | None = None) -> None:
    """Point the server at a specific cache file. Must run before serving."""
    global _db_path
    _db_path = db_path


def _db() -> sqlite3.Connection:
    global _connection
    if _connection is None:
        _connection = connect(_db_path)
    return _connection


@app.tool(
    name="search_sessions",
    description=(
        "Full-text search across the bodies of past Claude Code sessions. Returns ranked "
        "snippets with the session id, title, project and branch. Use this first to find "
        "relevant past work, then call get_session for detail. Searches the Claude account "
        "the user currently has selected; pass all_accounts=true to search every account."
    ),
)
def search_sessions(
    query: str,
    project: str | None = None,
    tag: str | None = None,
    branch: str | None = None,
    role: str | None = None,
    since_days: int | None = None,
    include_subagents: bool = False,
    limit: int = 20,
    all_accounts: bool = False,
) -> dict[str, Any]:
    results, match_mode = queries.search_messages(
        _db(),
        query=query,
        project=project,
        tag=tag,
        branch=branch,
        role=role,
        since_days=since_days,
        include_subagents=include_subagents,
        limit=limit,
        all_accounts=all_accounts,
    )
    return {
        "query": query,
        "match_mode": match_mode,
        "count": len(results),
        "results": results,
    }


@app.tool(
    name="list_sessions",
    description=(
        "List past sessions newest-first with their tags and commit counts. Filter by "
        "project, tag (e.g. 'committed', 'vay-4499', 'branch:development'), branch, or age "
        "in days. Use when browsing rather than searching for specific text. Lists the "
        "account the user currently has selected; pass all_accounts=true for every account."
    ),
)
def list_sessions(
    project: str | None = None,
    tag: str | None = None,
    branch: str | None = None,
    since_days: int | None = None,
    include_subagents: bool = False,
    limit: int = 20,
    all_accounts: bool = False,
) -> dict[str, Any]:
    results = queries.list_sessions(
        _db(),
        project=project,
        tag=tag,
        branch=branch,
        since_days=since_days,
        include_subagents=include_subagents,
        limit=limit,
        all_accounts=all_accounts,
    )
    return {"count": len(results), "sessions": results}


@app.tool(
    name="get_session",
    description=(
        "Fetch one past session by id: metadata, tags, commits, files touched, and its "
        "transcript truncated to max_chars. Pass role to narrow to just 'user', "
        "'assistant', 'tool_use' or 'tool_result' entries."
    ),
)
def get_session(
    session_id: str,
    max_chars: int = 20000,
    role: str | None = None,
) -> dict[str, Any]:
    session = queries.get_session(_db(), session_id=session_id, max_chars=max_chars, role=role)
    if session is None:
        return {"error": f"No cached session with id {session_id}."}
    return session


@app.tool(
    name="sessions_touching_file",
    description=(
        "Find past sessions that read or edited a file whose path contains the given "
        "fragment. Use for 'have I worked on this file before' and to recover the reasoning "
        "behind an earlier change."
    ),
)
def sessions_touching_file(
    path_fragment: str,
    limit: int = 20,
    all_accounts: bool = False,
) -> dict[str, Any]:
    results = queries.sessions_touching_file(
        _db(),
        path_fragment=path_fragment,
        limit=limit,
        all_accounts=all_accounts,
    )
    return {"path_fragment": path_fragment, "count": len(results), "sessions": results}


@app.tool(
    name="find_commits",
    description=(
        "Look up git commits that were made during past sessions, by SHA prefix and/or "
        "branch. Answers 'which session produced this commit' and 'what was committed on "
        "this branch'."
    ),
)
def find_commits(
    commit_sha: str | None = None,
    branch: str | None = None,
    limit: int = 20,
    all_accounts: bool = False,
) -> dict[str, Any]:
    results = queries.find_commits(
        _db(),
        commit_sha=commit_sha,
        branch=branch,
        limit=limit,
        all_accounts=all_accounts,
    )
    return {"count": len(results), "commits": results}


@app.tool(
    name="cache_stats",
    description=(
        "Summarise the session cache: totals, date range, top tags, per-project counts and "
        "top branches. Useful for orienting before a search. Counts cover the selected "
        "Claude account; the 'accounts' list shows every account held in the cache."
    ),
)
def cache_stats(all_accounts: bool = False) -> dict[str, Any]:
    return queries.cache_stats(_db(), all_accounts=all_accounts)


@app.tool(
    name="refresh_cache",
    description=(
        "Re-scan ~/.claude/projects and bring the cache up to date. Incremental by default; "
        "pass full=true to rebuild every session from scratch. Call this if a search seems "
        "to be missing very recent work."
    ),
)
def refresh_cache(full: bool = False) -> dict[str, Any]:
    stats = ingest(_db(), full=full)
    return {"full_rebuild": full, **stats.as_dict()}


def serve(db_path: Path | None = None) -> None:
    """Run the MCP server on stdio."""
    configure(db_path)
    app.run(transport="stdio")
