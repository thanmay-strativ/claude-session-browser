"""SQLite schema, migrations and connection handling for the session cache.

Full-text search uses an FTS5 external-content table over `messages`, kept in sync by
triggers so the message text is never stored twice. `schema_version` exists from v1 so
the database can be migrated in place rather than rebuilt from scratch.
"""

from __future__ import annotations

import os
import sqlite3
from pathlib import Path

SCHEMA_VERSION = 4

DEFAULT_CACHE_DIR = Path(os.environ.get("CLAUDE_SESSION_CACHE_DIR", Path.home() / ".claude-session-cache"))
DEFAULT_DB_PATH = DEFAULT_CACHE_DIR / "sessions.db"

_SCHEMA = """
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    session_id        TEXT PRIMARY KEY,
    project_path      TEXT NOT NULL,
    project_name      TEXT NOT NULL,
    encoded_dir       TEXT NOT NULL,
    title             TEXT,
    custom_title      TEXT,
    first_prompt      TEXT,
    model             TEXT,
    git_branch        TEXT,
    is_subagent       INTEGER NOT NULL DEFAULT 0,
    parent_session_id TEXT,
    started_at        TEXT,
    last_activity_at  TEXT,
    message_count     INTEGER NOT NULL DEFAULT 0,
    source_path       TEXT NOT NULL,
    source_root       TEXT,
    owner             TEXT,
    source_mtime      REAL NOT NULL,
    source_size       INTEGER NOT NULL,
    ingested_lines    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS messages (
    id         INTEGER PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    seq        INTEGER NOT NULL,
    role       TEXT NOT NULL,
    tool_name  TEXT,
    timestamp  TEXT,
    text       TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS files_touched (
    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    file_path  TEXT NOT NULL,
    PRIMARY KEY (session_id, file_path)
);

CREATE TABLE IF NOT EXISTS session_tags (
    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    tag        TEXT NOT NULL,
    source     TEXT NOT NULL DEFAULT 'manual',
    PRIMARY KEY (session_id, tag)
);

CREATE TABLE IF NOT EXISTS session_commits (
    session_id  TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    commit_sha  TEXT NOT NULL,
    branch      TEXT,
    subject     TEXT,
    PRIMARY KEY (session_id, commit_sha)
);

CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
    text,
    content='messages',
    content_rowid='id',
    tokenize='porter unicode61'
);

CREATE TRIGGER IF NOT EXISTS messages_after_insert AFTER INSERT ON messages BEGIN
    INSERT INTO messages_fts(rowid, text) VALUES (new.id, new.text);
END;

CREATE TRIGGER IF NOT EXISTS messages_after_delete AFTER DELETE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, text) VALUES ('delete', old.id, old.text);
END;

CREATE TRIGGER IF NOT EXISTS messages_after_update AFTER UPDATE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, text) VALUES ('delete', old.id, old.text);
    INSERT INTO messages_fts(rowid, text) VALUES (new.id, new.text);
END;
"""

# Applied after the version migrations, never with the tables: an index on a column that a
# migration is about to add would fail on every pre-existing database.
_INDEXES = """
CREATE INDEX IF NOT EXISTS idx_sessions_project   ON sessions(project_path);
CREATE INDEX IF NOT EXISTS idx_sessions_root      ON sessions(source_root);
CREATE INDEX IF NOT EXISTS idx_sessions_owner     ON sessions(owner);
CREATE INDEX IF NOT EXISTS idx_sessions_branch    ON sessions(git_branch);
CREATE INDEX IF NOT EXISTS idx_sessions_activity  ON sessions(last_activity_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_session   ON messages(session_id, seq);
CREATE INDEX IF NOT EXISTS idx_files_path         ON files_touched(file_path);
CREATE INDEX IF NOT EXISTS idx_tags_tag           ON session_tags(tag);
CREATE INDEX IF NOT EXISTS idx_commits_sha        ON session_commits(commit_sha);
"""


def connect(db_path: Path | None = None) -> sqlite3.Connection:
    """Open the cache database, creating and migrating it if needed."""
    target = db_path or DEFAULT_DB_PATH
    target.parent.mkdir(parents=True, exist_ok=True)

    connection = sqlite3.connect(target)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA foreign_keys=ON")
    connection.execute("PRAGMA synchronous=NORMAL")
    _migrate(connection)
    return connection


def _migrate(connection: sqlite3.Connection) -> None:
    connection.executescript(_SCHEMA)
    _apply_version_migrations(connection)
    connection.executescript(_INDEXES)
    connection.commit()


def _apply_version_migrations(connection: sqlite3.Connection) -> None:
    current = connection.execute("SELECT version FROM schema_version").fetchone()
    if current is None:
        connection.execute("INSERT INTO schema_version (version) VALUES (?)", (SCHEMA_VERSION,))
        return

    version = current["version"]
    if version > SCHEMA_VERSION:
        raise RuntimeError(
            f"Cache at schema version {version} is newer than this build supports "
            f"({SCHEMA_VERSION}). Upgrade claude-session-cache."
        )
    if version == SCHEMA_VERSION:
        return

    if version < 2:
        _add_column_if_missing(connection, "sessions", "custom_title", "TEXT")
    if version < 3:
        _add_column_if_missing(connection, "sessions", "source_root", "TEXT")
        _backfill_source_root(connection)
    if version < 4:
        _add_column_if_missing(connection, "sessions", "owner", "TEXT")

    connection.execute("UPDATE schema_version SET version = ?", (SCHEMA_VERSION,))


def _backfill_source_root(connection: sqlite3.Connection) -> None:
    """Derive each existing session's account root from the path it was ingested from.

    A transcript always lives at `<root>/<encoded_dir>/...`, so the root is recoverable
    exactly — no guessing which account pre-v3 rows belonged to, which matters because an
    unattributed row would silently drop out of every account-scoped query.
    """
    rows = connection.execute(
        "SELECT session_id, source_path, encoded_dir FROM sessions WHERE source_root IS NULL"
    ).fetchall()

    updates = []
    for row in rows:
        marker = f"/{row['encoded_dir']}/"
        index = row["source_path"].find(marker)
        if index > 0:
            updates.append((row["source_path"][:index], row["session_id"]))

    if updates:
        connection.executemany("UPDATE sessions SET source_root = ? WHERE session_id = ?", updates)


def _add_column_if_missing(connection: sqlite3.Connection, table: str, column: str, definition: str) -> None:
    existing = {row["name"] for row in connection.execute(f"PRAGMA table_info({table})").fetchall()}
    if column not in existing:
        connection.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")
