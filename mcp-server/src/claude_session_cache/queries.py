"""Read queries behind the MCP tools.

Every result is bounded. An unbounded transcript dump would flood the caller's context,
so search returns FTS snippets and `get_session` walks entries against a character
budget rather than returning everything.
"""

from __future__ import annotations

import re
import sqlite3
from datetime import datetime, timedelta, timezone

from .metadata import active_session_root

MAX_LIMIT = 50
DEFAULT_LIMIT = 20
DEFAULT_SESSION_CHARS = 20000

_TERM = re.compile(r"[A-Za-z0-9_./#-]+")

_STOPWORDS = frozenset(
    """
    a an and are as at be but by did do does for from had has have how i if in into is it
    its me my of on or our so that the their them then there these they this to was we were
    what when where which who why will with you your about can could would should
    """.split()
)


def build_fts_query(raw_query: str, operator: str = "AND") -> str:
    """Turn free text into a safe FTS5 MATCH expression.

    Raw input is never passed through: characters like `-`, `:` and `*` are FTS5 operators
    and would either raise a syntax error or silently change the query's meaning.
    """
    terms = _quoted_terms(_TERM.findall(raw_query or ""))
    return f" {operator} ".join(terms) if terms else ""


def _quoted_terms(terms: list[str]) -> list[str]:
    return [f'"{term}"' for term in terms if len(term) > 1 or term.isalnum()]


def _content_terms(raw_query: str) -> list[str]:
    """Drop stopwords so a conversational query ranks on its meaningful words."""
    terms = [term for term in _TERM.findall(raw_query or "") if len(term) > 1 or term.isalnum()]
    meaningful = [term for term in terms if term.lower() not in _STOPWORDS]
    return meaningful or terms


def _since_clause(since_days: int | None) -> tuple[str, list[object]]:
    if not since_days or since_days <= 0:
        return "", []
    cutoff = (datetime.now(timezone.utc) - timedelta(days=since_days)).isoformat()
    return " AND COALESCE(s.last_activity_at, '') >= ? ", [cutoff]


def _scope_clause(scope: str | None, all_accounts: bool) -> tuple[str, list[object]]:
    """Restrict results along two independent axes: whose sessions, and which account.

    `scope` picks the person: "mine" (default) is this machine's own sessions, "team"
    adds every imported teammate, and any other value names one teammate. `all_accounts`
    stays what it always was — which of *my own* Claude accounts — and only applies to
    my own rows, because imported rows never belong to a local account.
    """
    normalized = (scope or "mine").strip().lower()
    if normalized == "mine":
        if all_accounts:
            return " AND s.owner IS NULL ", []
        return " AND s.owner IS NULL AND s.source_root = ? ", [str(active_session_root())]
    if normalized == "team":
        if all_accounts:
            return "", []
        return " AND (s.owner IS NOT NULL OR s.source_root = ?) ", [str(active_session_root())]
    return " AND s.owner = ? ", [(scope or "").strip()]


def _filter_clause(
    project: str | None,
    tag: str | None,
    branch: str | None,
    include_subagents: bool,
    all_accounts: bool = False,
    scope: str | None = None,
) -> tuple[str, list[object]]:
    clause, params = _scope_clause(scope, all_accounts)

    if project:
        clause += " AND (s.project_name = ? OR s.project_path LIKE ?) "
        params.extend([project, f"%{project}%"])
    if branch:
        clause += " AND s.git_branch = ? "
        params.append(branch)
    if tag:
        clause += (
            " AND EXISTS (SELECT 1 FROM session_tags t WHERE t.session_id = s.session_id AND t.tag = ?) "
        )
        params.append(tag.lower())
    if not include_subagents:
        clause += " AND s.is_subagent = 0 "
    return clause, params


def search_messages(
    connection: sqlite3.Connection,
    query: str,
    project: str | None = None,
    tag: str | None = None,
    branch: str | None = None,
    role: str | None = None,
    since_days: int | None = None,
    include_subagents: bool = False,
    limit: int = DEFAULT_LIMIT,
    all_accounts: bool = False,
    scope: str | None = None,
) -> tuple[list[dict], str]:
    """Full-text search across message bodies, returning ranked snippets and the match mode.

    Two passes: every term (precise) first, then any term (recall). A conversational
    query rarely has all its words in one message, so an all-terms-only search would
    dead-end at zero results for exactly the questions this cache exists to answer.
    """
    bounded_limit = max(1, min(limit, MAX_LIMIT))
    filter_sql, filter_params = _filter_clause(project, tag, branch, include_subagents, all_accounts, scope)
    since_sql, since_params = _since_clause(since_days)

    role_sql = ""
    role_params: list[object] = []
    if role:
        role_sql = " AND m.role = ? "
        role_params.append(role)

    sql = f"""
        SELECT
            m.session_id,
            m.seq,
            m.role,
            m.tool_name,
            m.timestamp,
            snippet(messages_fts, 0, '«', '»', ' … ', 18) AS snippet,
            bm25(messages_fts) AS score,
            COALESCE(s.custom_title, s.title) AS title,
            s.project_name,
            s.git_branch,
            s.owner,
            s.last_activity_at,
            s.is_subagent
        FROM messages_fts
        JOIN messages m ON m.id = messages_fts.rowid
        JOIN sessions s ON s.session_id = m.session_id
        WHERE messages_fts MATCH ?
        {filter_sql}{since_sql}{role_sql}
        ORDER BY score
        LIMIT ?
    """

    def run(match_expression: str) -> list[dict]:
        if not match_expression:
            return []
        params = [match_expression, *filter_params, *since_params, *role_params, bounded_limit]
        return [dict(row) for row in connection.execute(sql, params).fetchall()]

    all_terms = _quoted_terms(_TERM.findall(query or ""))
    rows = run(" AND ".join(all_terms))
    if rows:
        return rows, "all-terms"

    any_terms = _quoted_terms(_content_terms(query))
    rows = run(" OR ".join(any_terms))
    return rows, "any-term"


def list_sessions(
    connection: sqlite3.Connection,
    project: str | None = None,
    tag: str | None = None,
    branch: str | None = None,
    since_days: int | None = None,
    include_subagents: bool = False,
    limit: int = DEFAULT_LIMIT,
    all_accounts: bool = False,
    scope: str | None = None,
) -> list[dict]:
    """List sessions newest-first, with their tags and commit count."""
    bounded_limit = max(1, min(limit, MAX_LIMIT))
    filter_sql, filter_params = _filter_clause(project, tag, branch, include_subagents, all_accounts, scope)
    since_sql, since_params = _since_clause(since_days)

    sql = f"""
        SELECT
            s.session_id, COALESCE(s.custom_title, s.title) AS title, s.project_name, s.project_path, s.git_branch,
            s.owner, s.model, s.message_count, s.started_at, s.last_activity_at, s.is_subagent,
            (SELECT GROUP_CONCAT(t.tag, ',') FROM session_tags t WHERE t.session_id = s.session_id) AS tags,
            (SELECT COUNT(*) FROM session_commits c WHERE c.session_id = s.session_id) AS commit_count
        FROM sessions s
        WHERE 1=1
        {filter_sql}{since_sql}
        ORDER BY COALESCE(s.last_activity_at, '') DESC
        LIMIT ?
    """
    params = [*filter_params, *since_params, bounded_limit]
    return [dict(row) for row in connection.execute(sql, params).fetchall()]


def get_session(
    connection: sqlite3.Connection,
    session_id: str,
    max_chars: int = DEFAULT_SESSION_CHARS,
    role: str | None = None,
) -> dict | None:
    """Return one session's metadata plus as much of its transcript as fits the budget."""
    session_row = connection.execute("SELECT * FROM sessions WHERE session_id = ?", (session_id,)).fetchone()
    if session_row is None:
        return None

    session = dict(session_row)
    session["title"] = session.get("custom_title") or session.get("title")
    session["tags"] = [
        row["tag"]
        for row in connection.execute(
            "SELECT tag FROM session_tags WHERE session_id = ? ORDER BY tag", (session_id,)
        ).fetchall()
    ]
    session["commits"] = [
        dict(row)
        for row in connection.execute(
            "SELECT commit_sha, branch, subject FROM session_commits WHERE session_id = ?",
            (session_id,),
        ).fetchall()
    ]
    session["files_touched"] = [
        row["file_path"]
        for row in connection.execute(
            "SELECT file_path FROM files_touched WHERE session_id = ? ORDER BY file_path",
            (session_id,),
        ).fetchall()
    ]

    role_sql = " AND role = ? " if role else ""
    role_params: list[object] = [role] if role else []
    entry_rows = connection.execute(
        f"SELECT seq, role, tool_name, timestamp, text FROM messages "
        f"WHERE session_id = ? {role_sql} ORDER BY seq",
        [session_id, *role_params],
    ).fetchall()

    budget = max(1000, max_chars)
    used = 0
    entries: list[dict] = []
    for row in entry_rows:
        entry = dict(row)
        text_length = len(entry["text"])
        if used + text_length > budget:
            remaining = budget - used
            if remaining > 200:
                entry["text"] = entry["text"][:remaining] + "\n… [truncated]"
                entries.append(entry)
                used = budget
            session["truncated"] = True
            break
        entries.append(entry)
        used += text_length

    session["entries"] = entries
    session.setdefault("truncated", len(entries) < len(entry_rows))
    session["total_entries"] = len(entry_rows)
    return session


def sessions_touching_file(
    connection: sqlite3.Connection,
    path_fragment: str,
    limit: int = DEFAULT_LIMIT,
    all_accounts: bool = False,
    scope: str | None = None,
) -> list[dict]:
    """Find sessions that read or edited a file whose path contains `path_fragment`."""
    if not path_fragment:
        return []
    bounded_limit = max(1, min(limit, MAX_LIMIT))
    account_sql, account_params = _scope_clause(scope, all_accounts)
    sql = f"""
        SELECT
            s.session_id, COALESCE(s.custom_title, s.title) AS title, s.project_name, s.git_branch,
            s.owner, s.last_activity_at,
            GROUP_CONCAT(DISTINCT f.file_path) AS matched_files
        FROM files_touched f
        JOIN sessions s ON s.session_id = f.session_id
        WHERE f.file_path LIKE ?
        {account_sql}
        GROUP BY s.session_id
        ORDER BY COALESCE(s.last_activity_at, '') DESC
        LIMIT ?
    """
    params = [f"%{path_fragment}%", *account_params, bounded_limit]
    rows = connection.execute(sql, params).fetchall()
    return [dict(row) for row in rows]


def find_commits(
    connection: sqlite3.Connection,
    commit_sha: str | None = None,
    branch: str | None = None,
    limit: int = DEFAULT_LIMIT,
    all_accounts: bool = False,
    scope: str | None = None,
) -> list[dict]:
    """Look up commits recorded in sessions, by SHA prefix and/or branch."""
    bounded_limit = max(1, min(limit, MAX_LIMIT))
    clause, params = _scope_clause(scope, all_accounts)
    if commit_sha:
        clause += " AND c.commit_sha LIKE ? "
        params.append(f"{commit_sha}%")
    if branch:
        clause += " AND (c.branch = ? OR s.git_branch = ?) "
        params.extend([branch, branch])

    sql = f"""
        SELECT c.commit_sha, c.branch, c.subject, c.session_id,
               COALESCE(s.custom_title, s.title) AS title, s.project_name, s.owner, s.last_activity_at
        FROM session_commits c
        JOIN sessions s ON s.session_id = c.session_id
        WHERE 1=1 {clause}
        ORDER BY COALESCE(s.last_activity_at, '') DESC
        LIMIT ?
    """
    return [dict(row) for row in connection.execute(sql, [*params, bounded_limit]).fetchall()]


def _tool_label(tool_name: str) -> str:
    """Collapse an MCP tool onto its server.

    Every MCP tool is named `mcp__<server>__<tool>`, so counting them individually buries the
    answer people actually want — which server am I leaning on — under a list of near-identical
    forty-character strings. Built-in tools keep their own names.
    """
    if not tool_name.startswith("mcp__"):
        return tool_name
    parts = tool_name.split("__")
    if len(parts) < 3:
        return tool_name
    server = parts[1].removeprefix("claude_ai_").replace("_", " ").replace("-", " ").strip()
    return f"{server or 'unknown'} (MCP)"


def cache_stats(connection: sqlite3.Connection, all_accounts: bool = False) -> dict:
    """Summarise what the cache holds for the active account.

    Counts are account-scoped so the plugin's coverage gauge compares like with like —
    the sessions on disk for the selected account against the rows cached for it. The
    `accounts` breakdown stays global so a second account is still discoverable.
    """
    account_root = None if all_accounts else str(active_session_root())
    session_clause = " AND s.owner IS NULL " + ("" if account_root is None else " AND s.source_root = ? ")
    params: list[object] = [] if account_root is None else [account_root]

    def child_clause(alias: str) -> str:
        root_clause = "" if account_root is None else "AND s.source_root = ? "
        return (
            f" AND EXISTS (SELECT 1 FROM sessions s WHERE s.session_id = {alias}.session_id "
            f"AND s.owner IS NULL {root_clause}) "
        )

    def scalar(sql: str) -> int:
        row = connection.execute(sql, params).fetchone()
        return int(row[0]) if row and row[0] is not None else 0

    def first(sql: str) -> object:
        row = connection.execute(sql, params).fetchone()
        return row[0] if row else None

    top_tags = [
        {"tag": row["tag"], "sessions": row["session_count"]}
        for row in connection.execute(
            f"SELECT t.tag, COUNT(*) AS session_count FROM session_tags t "
            f"WHERE 1=1 {child_clause('t')} "
            "GROUP BY t.tag ORDER BY session_count DESC LIMIT 15",
            params,
        ).fetchall()
    ]
    per_project = [
        {"project": row["project_name"], "sessions": row["session_count"]}
        for row in connection.execute(
            f"SELECT s.project_name, COUNT(*) AS session_count FROM sessions s "
            f"WHERE 1=1 {session_clause} "
            "GROUP BY s.project_name ORDER BY session_count DESC LIMIT 20",
            params,
        ).fetchall()
    ]
    branches = [
        {"branch": row["git_branch"], "sessions": row["session_count"]}
        for row in connection.execute(
            f"SELECT s.git_branch, COUNT(*) AS session_count FROM sessions s "
            f"WHERE s.git_branch IS NOT NULL {session_clause} "
            "GROUP BY s.git_branch ORDER BY session_count DESC LIMIT 15",
            params,
        ).fetchall()
    ]
    tool_calls: dict[str, int] = {}
    for row in connection.execute(
        f"SELECT m.tool_name, COUNT(*) AS call_count FROM messages m "
        f"WHERE m.tool_name IS NOT NULL AND m.tool_name <> '' {child_clause('m')} "
        "GROUP BY m.tool_name",
        params,
    ).fetchall():
        label = _tool_label(row["tool_name"])
        tool_calls[label] = tool_calls.get(label, 0) + row["call_count"]
    top_tools = [
        {"tool": label, "calls": calls}
        for label, calls in sorted(tool_calls.items(), key=lambda item: item[1], reverse=True)[:15]
    ]
    top_files = [
        {"path": row["file_path"], "sessions": row["session_count"]}
        for row in connection.execute(
            f"SELECT f.file_path, COUNT(*) AS session_count FROM files_touched f "
            f"WHERE 1=1 {child_clause('f')} "
            "GROUP BY f.file_path ORDER BY session_count DESC LIMIT 15",
            params,
        ).fetchall()
    ]
    accounts = [
        {"root": row["root"], "sessions": row["session_count"]}
        for row in connection.execute(
            "SELECT COALESCE(source_root, '(unattributed)') AS root, COUNT(*) AS session_count "
            "FROM sessions WHERE owner IS NULL GROUP BY source_root ORDER BY session_count DESC"
        ).fetchall()
    ]
    team = [
        {
            "owner": row["owner"],
            "sessions": row["session_count"],
            "newest_activity": row["newest_activity"],
        }
        for row in connection.execute(
            "SELECT owner, COUNT(*) AS session_count, MAX(last_activity_at) AS newest_activity "
            "FROM sessions WHERE owner IS NOT NULL "
            "GROUP BY owner ORDER BY newest_activity DESC"
        ).fetchall()
    ]

    return {
        "active_account_root": account_root,
        "accounts": accounts,
        "team": team,
        "sessions": scalar(f"SELECT COUNT(*) FROM sessions s WHERE 1=1 {session_clause}"),
        "primary_sessions": scalar(
            f"SELECT COUNT(*) FROM sessions s WHERE s.is_subagent = 0 {session_clause}"
        ),
        "subagent_sessions": scalar(
            f"SELECT COUNT(*) FROM sessions s WHERE s.is_subagent = 1 {session_clause}"
        ),
        "messages": scalar(f"SELECT COUNT(*) FROM messages m WHERE 1=1 {child_clause('m')}"),
        "redacted_messages": scalar(
            f"SELECT COUNT(*) FROM messages m WHERE m.text LIKE '%[REDACTED:%' {child_clause('m')}"
        ),
        "sessions_with_commits": scalar(
            f"SELECT COUNT(DISTINCT c.session_id) FROM session_commits c WHERE 1=1 {child_clause('c')}"
        ),
        "tagged_sessions": scalar(
            f"SELECT COUNT(DISTINCT t.session_id) FROM session_tags t WHERE 1=1 {child_clause('t')}"
        ),
        "files_touched": scalar(
            f"SELECT COUNT(DISTINCT f.file_path) FROM files_touched f WHERE 1=1 {child_clause('f')}"
        ),
        "commits": scalar(f"SELECT COUNT(*) FROM session_commits c WHERE 1=1 {child_clause('c')}"),
        "oldest_activity": first(
            f"SELECT MIN(s.last_activity_at) FROM sessions s WHERE 1=1 {session_clause}"
        ),
        "newest_activity": first(
            f"SELECT MAX(s.last_activity_at) FROM sessions s WHERE 1=1 {session_clause}"
        ),
        "top_tags": top_tags,
        "per_project": per_project,
        "top_branches": branches,
        "top_files": top_files,
        "top_tools": top_tools,
    }
