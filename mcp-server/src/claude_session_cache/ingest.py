"""Incremental ingest of `~/.claude/projects` into the SQLite cache.

Transcripts are append-only, so each session stores a watermark (`source_mtime`,
`source_size`, `ingested_lines`) and a re-run parses only the lines that were added.
A file that shrank or lost its watermark is re-ingested in full.
"""

from __future__ import annotations

import re
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from .metadata import (
    DEFAULT_PROJECTS_DIR,
    SessionMetadata,
    active_session_root,
    load_metadata,
)
from .parser import ParsedSession, parse_transcript
from .redact import redact

_TICKET_IN_BRANCH = re.compile(r"\b[A-Z]{2,10}-\d+\b")


@dataclass
class IngestStats:
    sessions_seen: int = 0
    sessions_updated: int = 0
    sessions_skipped: int = 0
    messages_added: int = 0
    commits_found: int = 0

    def as_dict(self) -> dict[str, int]:
        return {
            "sessions_seen": self.sessions_seen,
            "sessions_updated": self.sessions_updated,
            "sessions_skipped": self.sessions_skipped,
            "messages_added": self.messages_added,
            "commits_found": self.commits_found,
        }


@dataclass
class _Source:
    session_id: str
    path: Path
    encoded_dir: str
    is_subagent: bool
    parent_session_id: str | None


def ingest(
    connection: sqlite3.Connection,
    projects_dir: Path | None = None,
    full: bool = False,
) -> IngestStats:
    """Scan every transcript and bring the cache up to date."""
    root = projects_dir or active_session_root()
    stats = IngestStats()
    if not root.is_dir():
        return stats

    user_metadata = load_metadata()

    for source in _discover_sources(root):
        stats.sessions_seen += 1
        if _ingest_source(connection, source, user_metadata, full, stats, str(root)):
            stats.sessions_updated += 1
        else:
            stats.sessions_skipped += 1

    _sync_user_metadata(connection, user_metadata)
    connection.commit()
    return stats


def _discover_sources(root: Path) -> list[_Source]:
    sources: list[_Source] = []
    for project_dir in sorted(root.iterdir()):
        if not project_dir.is_dir():
            continue
        encoded_dir = project_dir.name

        for transcript in sorted(project_dir.glob("*.jsonl")):
            sources.append(
                _Source(
                    session_id=transcript.stem,
                    path=transcript,
                    encoded_dir=encoded_dir,
                    is_subagent=False,
                    parent_session_id=None,
                )
            )

        for subagent_dir in sorted(project_dir.glob("*/subagents")):
            for transcript in sorted(subagent_dir.glob("*.jsonl")):
                sources.append(
                    _Source(
                        session_id=transcript.stem,
                        path=transcript,
                        encoded_dir=encoded_dir,
                        is_subagent=True,
                        parent_session_id=subagent_dir.parent.name,
                    )
                )
    return sources


def _ingest_source(
    connection: sqlite3.Connection,
    source: _Source,
    user_metadata: dict[str, SessionMetadata],
    full: bool,
    stats: IngestStats,
    account_root: str,
) -> bool:
    try:
        file_stat = source.path.stat()
    except OSError:
        return False

    existing = connection.execute(
        "SELECT source_mtime, source_size, ingested_lines FROM sessions WHERE session_id = ?",
        (source.session_id,),
    ).fetchone()

    skip_lines = 0
    if existing is not None and not full:
        unchanged = (
            abs(existing["source_mtime"] - file_stat.st_mtime) < 1e-6
            and existing["source_size"] == file_stat.st_size
        )
        if unchanged:
            return False
        if file_stat.st_size >= existing["source_size"]:
            skip_lines = existing["ingested_lines"]

    if skip_lines == 0:
        _purge_session_content(connection, source.session_id)
        start_seq = 0
    else:
        row = connection.execute(
            "SELECT COALESCE(MAX(seq), 0) AS max_seq FROM messages WHERE session_id = ?",
            (source.session_id,),
        ).fetchone()
        start_seq = row["max_seq"]

    parsed = parse_transcript(source.path, skip_lines=skip_lines, start_seq=start_seq)

    project_path = parsed.project_path or _decode_project_path(source.encoded_dir)
    project_name = Path(project_path).name or project_path

    _upsert_session(
        connection,
        source,
        parsed,
        project_path,
        project_name,
        file_stat.st_mtime,
        file_stat.st_size,
        skip_lines > 0,
        account_root,
    )

    for entry in parsed.entries:
        connection.execute(
            "INSERT INTO messages (session_id, seq, role, tool_name, timestamp, text) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (
                source.session_id,
                entry.seq,
                entry.role,
                entry.tool_name,
                entry.timestamp,
                redact(entry.text),
            ),
        )
    stats.messages_added += len(parsed.entries)

    for file_path in parsed.files_touched:
        connection.execute(
            "INSERT OR IGNORE INTO files_touched (session_id, file_path) VALUES (?, ?)",
            (source.session_id, file_path),
        )

    for commit in parsed.commits:
        connection.execute(
            "INSERT OR IGNORE INTO session_commits (session_id, commit_sha, branch, subject) "
            "VALUES (?, ?, ?, ?)",
            (source.session_id, commit.commit_sha, commit.branch, commit.subject),
        )
    stats.commits_found += len(parsed.commits)

    return True


def _purge_session_content(connection: sqlite3.Connection, session_id: str) -> None:
    connection.execute("DELETE FROM messages WHERE session_id = ?", (session_id,))
    connection.execute("DELETE FROM files_touched WHERE session_id = ?", (session_id,))
    connection.execute("DELETE FROM session_commits WHERE session_id = ?", (session_id,))


def _upsert_session(
    connection: sqlite3.Connection,
    source: _Source,
    parsed: ParsedSession,
    project_path: str,
    project_name: str,
    source_mtime: float,
    source_size: int,
    incremental: bool,
    account_root: str,
) -> None:
    if incremental:
        connection.execute(
            """
            UPDATE sessions SET
                source_root      = ?,
                title            = COALESCE(?, title),
                model            = COALESCE(?, model),
                git_branch       = COALESCE(?, git_branch),
                last_activity_at = COALESCE(?, last_activity_at),
                message_count    = message_count + ?,
                source_mtime     = ?,
                source_size      = ?,
                ingested_lines   = ?
            WHERE session_id = ?
            """,
            (
                account_root,
                parsed.title,
                parsed.model,
                parsed.git_branch,
                parsed.last_activity_at,
                len(parsed.entries),
                source_mtime,
                source_size,
                parsed.lines_read,
                source.session_id,
            ),
        )
        return

    connection.execute(
        """
        INSERT INTO sessions (
            session_id, project_path, project_name, encoded_dir, title, first_prompt,
            model, git_branch, is_subagent, parent_session_id, started_at,
            last_activity_at, message_count, source_path, source_root, source_mtime, source_size,
            ingested_lines
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(session_id) DO UPDATE SET
            project_path     = excluded.project_path,
            project_name     = excluded.project_name,
            encoded_dir      = excluded.encoded_dir,
            title            = excluded.title,
            first_prompt     = excluded.first_prompt,
            model            = excluded.model,
            git_branch       = excluded.git_branch,
            is_subagent      = excluded.is_subagent,
            parent_session_id= excluded.parent_session_id,
            started_at       = excluded.started_at,
            last_activity_at = excluded.last_activity_at,
            message_count    = excluded.message_count,
            source_path      = excluded.source_path,
            source_root      = excluded.source_root,
            source_mtime     = excluded.source_mtime,
            source_size      = excluded.source_size,
            ingested_lines   = excluded.ingested_lines
        """,
        (
            source.session_id,
            project_path,
            project_name,
            source.encoded_dir,
            parsed.title,
            parsed.first_prompt,
            parsed.model,
            parsed.git_branch,
            1 if source.is_subagent else 0,
            source.parent_session_id,
            parsed.started_at,
            parsed.last_activity_at,
            len(parsed.entries),
            str(source.path),
            account_root,
            source_mtime,
            source_size,
            parsed.lines_read,
        ),
    )


def _sync_user_metadata(connection: sqlite3.Connection, user_metadata: dict[str, SessionMetadata]) -> None:
    """Apply sidecar titles and rebuild every session's tags.

    This runs on every ingest regardless of transcript watermarks, because the user can
    retag or rename a session without its transcript changing. `custom_title` is kept
    separate from the transcript's own title so clearing the override restores it.

    Only locally-owned rows (`owner IS NULL`) are rebuilt: imported teammates' titles
    and tags come from their exports, and rebuilding from the local sidecar would
    silently erase them on every ingest.
    """
    connection.execute("UPDATE sessions SET custom_title = NULL WHERE owner IS NULL")
    for session_id, metadata in user_metadata.items():
        if metadata.title:
            connection.execute(
                "UPDATE sessions SET custom_title = ? WHERE session_id = ? AND owner IS NULL",
                (metadata.title, session_id),
            )

    connection.execute(
        "DELETE FROM session_tags WHERE session_id IN (SELECT session_id FROM sessions WHERE owner IS NULL)"
    )

    rows = connection.execute(
        "SELECT session_id, is_subagent, git_branch FROM sessions WHERE owner IS NULL"
    ).fetchall()
    committed = {
        row["session_id"]
        for row in connection.execute("SELECT DISTINCT session_id FROM session_commits").fetchall()
    }

    for row in rows:
        session_id = row["session_id"]
        tags: list[tuple[str, str]] = []

        metadata = user_metadata.get(session_id)
        if metadata:
            tags.extend((tag.lower(), "manual") for tag in metadata.tags)
            if metadata.pinned:
                tags.append(("pinned", "auto"))

        tags.extend(_branch_tags(row["git_branch"]))

        if session_id in committed:
            tags.append(("committed", "auto"))
        if row["is_subagent"]:
            tags.append(("subagent", "auto"))

        for tag, tag_source in tags:
            connection.execute(
                "INSERT OR IGNORE INTO session_tags (session_id, tag, source) VALUES (?, ?, ?)",
                (session_id, tag, tag_source),
            )


def _branch_tags(git_branch: str | None) -> list[tuple[str, str]]:
    """Derive tags from a branch name: the branch itself, and any ticket id inside it."""
    if not git_branch or not git_branch.strip():
        return []

    branch = git_branch.strip()
    tags: list[tuple[str, str]] = [(f"branch:{branch.lower()}", "auto")]

    ticket_match = _TICKET_IN_BRANCH.search(branch)
    if ticket_match:
        tags.append((ticket_match.group(0).lower(), "auto"))
    return tags


def _decode_project_path(encoded_dir: str) -> str:
    """Best-effort reverse of Claude's path encoding, used only when no cwd was recorded."""
    if not encoded_dir.startswith("-"):
        return encoded_dir
    return "/" + encoded_dir[1:].replace("-", "/")
