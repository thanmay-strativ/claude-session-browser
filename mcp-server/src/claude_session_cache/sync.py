"""Team knowledge-base sync: export/import of redacted sessions through a git repo.

The SQLite cache never crosses the machine boundary — only redacted JSONL text does,
one file per session at `<repo>/<project>/<owner>/<session_id>.jsonl`. Each owner is
the only writer of their own directories, so two teammates' pushes can never
conflict. Marking a session private after it has synced is honoured by a tombstone
in `deletions.jsonl` plus deletion of the exported file; importers replay the
tombstones against their own caches.
"""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import subprocess
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .db import DEFAULT_CACHE_DIR
from .metadata import TeamSyncConfig, load_metadata, load_team_sync
from .redact import redact

EXPORT_FORMAT = 1
DELETIONS_FILE = "deletions.jsonl"
SYNC_STATUS_PATH = DEFAULT_CACHE_DIR / "sync-status.json"
GITLEAKS_CONFIG_PATH = DEFAULT_CACHE_DIR / "gitleaks.toml"

_GIT_TIMEOUT_SECONDS = 120
_UNSAFE_PATH_CHARS = re.compile(r"[^A-Za-z0-9._@+-]")

# Extends gitleaks' own default rules rather than replacing them — this only silences
# shapes we've confirmed are not secrets. `regexTarget = "match"` is required for the
# key-name prefix to be visible at all: gitleaks' default allowlist target is the bare
# secret value, which can't distinguish `fileKey=<id>` from a real key of the same length.
_GITLEAKS_ALLOWLIST_TOML = """\
[extend]
useDefault = true

[allowlist]
description = "Non-secret identifiers that match generic-api-key by shape (e.g. Figma file keys)"
regexTarget = "match"
regexes = [
  '''(?i)\\bfileKey\\s*=\\s*[A-Za-z0-9]{15,30}\\b''',
]
"""


@dataclass
class SyncStats:
    exported: int = 0
    export_skipped: int = 0
    tombstoned: int = 0
    imported: int = 0
    import_skipped: int = 0
    import_errors: int = 0
    deleted: int = 0
    steps: list[dict] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "exported": self.exported,
            "export_skipped": self.export_skipped,
            "tombstoned": self.tombstoned,
            "imported": self.imported,
            "import_skipped": self.import_skipped,
            "import_errors": self.import_errors,
            "deleted": self.deleted,
            "steps": self.steps,
        }


def _path_segment(value: str) -> str:
    """Make an owner or project name safe to use as a single directory name."""
    return _UNSAFE_PATH_CHARS.sub("_", value.strip()) or "_"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _compile_extra_patterns(patterns: list[str] | None, stats: SyncStats) -> list[re.Pattern[str]]:
    """Compile the user's own redaction regexes, reporting the ones that don't compile.

    A broken pattern is dropped rather than fatal — but it is named in the sync steps,
    because a redaction rule that silently never ran is the worst possible failure here.
    """
    compiled: list[re.Pattern[str]] = []
    for pattern in patterns or []:
        try:
            compiled.append(re.compile(pattern))
        except re.error as error:
            stats.steps.append(
                {
                    "step": "redaction",
                    "ok": False,
                    "detail": f"Custom pattern {pattern!r} is not a valid regex and was skipped: {error}",
                }
            )
    return compiled


def _apply_extra_redaction(text: str | None, patterns: list[re.Pattern[str]]) -> str | None:
    if not text or not patterns:
        return text
    redacted = text
    for pattern in patterns:
        redacted = pattern.sub("[REDACTED:custom]", redacted)
    return redacted


def _redaction_fingerprint(patterns: list[str] | None) -> str:
    """Identifies the redaction rules a file was written under.

    Stored in each exported header so that changing the patterns changes the header,
    which is what forces already-exported sessions to be rewritten under the new
    rules instead of being skipped as unchanged.
    """
    if not patterns:
        return "default"
    digest = hashlib.sha256("\n".join(sorted(patterns)).encode("utf-8"))
    return digest.hexdigest()[:12]


def export_sessions(
    connection: sqlite3.Connection,
    repo_path: Path,
    owner: str,
    projects: list[str],
    stats: SyncStats | None = None,
    min_messages: int = 0,
    max_age_days: int = 0,
    extra_redaction_patterns: list[str] | None = None,
) -> SyncStats:
    """Write this machine's eligible sessions into the owner's directories in the repo.

    Eligible means: locally owned (`owner IS NULL`), a primary session (not a
    subagent transcript), in an allowlisted project, not marked "excludeFromSync"
    in the plugin, and past the size/age floors. Every ineligible session that was
    exported earlier is retracted — file removed, tombstone appended — so tightening
    a filter cleans up what a looser one already published instead of orphaning it.
    """
    stats = stats or SyncStats()
    if not projects:
        stats.steps.append({"step": "export", "ok": True, "detail": "No projects allowlisted; nothing exported."})
        return stats

    placeholders = ",".join("?" for _ in projects)
    session_rows = connection.execute(
        f"""
        SELECT session_id, project_name, project_path,
               COALESCE(custom_title, title) AS title, first_prompt, model, git_branch,
               started_at, last_activity_at, message_count
        FROM sessions
        WHERE owner IS NULL AND is_subagent = 0 AND project_name IN ({placeholders})
        """,
        projects,
    ).fetchall()

    extra_patterns = _compile_extra_patterns(extra_redaction_patterns, stats)
    redaction_fingerprint = _redaction_fingerprint(extra_redaction_patterns)
    age_cutoff = (
        (datetime.now(timezone.utc) - timedelta(days=max_age_days)).isoformat()
        if max_age_days > 0
        else None
    )

    excluded_ids = {
        session_id
        for session_id, session_metadata in load_metadata().items()
        if session_metadata.exclude_from_sync
    }
    session_ids = [row["session_id"] for row in session_rows]
    tags_by_session = _grouped(connection, "SELECT session_id, tag FROM session_tags", session_ids, "tag")
    files_by_session = _grouped(connection, "SELECT session_id, file_path FROM files_touched", session_ids, "file_path")
    commits_by_session: dict[str, list[dict]] = {}
    for row in connection.execute("SELECT session_id, commit_sha, branch, subject FROM session_commits").fetchall():
        commits_by_session.setdefault(row["session_id"], []).append(
            {"commit_sha": row["commit_sha"], "branch": row["branch"], "subject": row["subject"]}
        )

    owner_segment = _path_segment(owner)
    for row in session_rows:
        session_id = row["session_id"]
        target_dir = repo_path / _path_segment(row["project_name"]) / owner_segment
        target_file = target_dir / f"{session_id}.jsonl"

        eligible = (
            session_id not in excluded_ids
            and (row["message_count"] or 0) >= min_messages
            and (age_cutoff is None or (row["last_activity_at"] or "") >= age_cutoff)
        )
        if not eligible:
            if target_file.is_file():
                target_file.unlink()
                _append_tombstone(target_dir, session_id)
                stats.tombstoned += 1
            else:
                stats.export_skipped += 1
            continue

        header = {
            "format": EXPORT_FORMAT,
            "type": "session",
            "session_id": session_id,
            "owner": owner,
            "redaction": redaction_fingerprint,
            "project_name": row["project_name"],
            "project_path": row["project_path"],
            "title": _apply_extra_redaction(row["title"], extra_patterns),
            "first_prompt": _apply_extra_redaction(row["first_prompt"], extra_patterns),
            "model": row["model"],
            "git_branch": row["git_branch"],
            "started_at": row["started_at"],
            "last_activity_at": row["last_activity_at"],
            "message_count": row["message_count"],
            "tags": sorted(tags_by_session.get(session_id, [])),
            "files_touched": sorted(files_by_session.get(session_id, [])),
            "commits": commits_by_session.get(session_id, []),
        }
        if _existing_header(target_file) == header:
            stats.export_skipped += 1
            continue

        message_rows = connection.execute(
            "SELECT seq, role, tool_name, timestamp, text FROM messages WHERE session_id = ? ORDER BY seq",
            (session_id,),
        ).fetchall()

        target_dir.mkdir(parents=True, exist_ok=True)
        with target_file.open("w", encoding="utf-8") as handle:
            handle.write(json.dumps(header, ensure_ascii=False) + "\n")
            for message_row in message_rows:
                handle.write(
                    json.dumps(
                        {
                            "type": "message",
                            "seq": message_row["seq"],
                            "role": message_row["role"],
                            "tool_name": message_row["tool_name"],
                            "timestamp": message_row["timestamp"],
                            "text": _apply_extra_redaction(message_row["text"], extra_patterns),
                        },
                        ensure_ascii=False,
                    )
                    + "\n"
                )
        stats.exported += 1

    stats.steps.append(
        {
            "step": "export",
            "ok": True,
            "detail": f"{stats.exported} written, {stats.export_skipped} unchanged, {stats.tombstoned} tombstoned.",
        }
    )
    return stats


def _grouped(
    connection: sqlite3.Connection, sql: str, session_ids: list[str], value_column: str
) -> dict[str, list]:
    wanted = set(session_ids)
    grouped: dict[str, list] = {}
    for row in connection.execute(sql).fetchall():
        if row["session_id"] in wanted:
            grouped.setdefault(row["session_id"], []).append(row[value_column])
    return grouped


def _existing_header(target_file: Path) -> dict | None:
    if not target_file.is_file():
        return None
    try:
        with target_file.open(encoding="utf-8") as handle:
            return json.loads(handle.readline())
    except (OSError, json.JSONDecodeError):
        return None


def _append_tombstone(target_dir: Path, session_id: str) -> None:
    target_dir.mkdir(parents=True, exist_ok=True)
    deletions_file = target_dir / DELETIONS_FILE
    already = {entry.get("session_id") for entry in _read_jsonl(deletions_file)}
    if session_id in already:
        return
    with deletions_file.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps({"session_id": session_id, "deleted_at": _utc_now()}) + "\n")


def _read_jsonl(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    entries: list[dict] = []
    try:
        with path.open(encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    parsed = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(parsed, dict):
                    entries.append(parsed)
    except OSError:
        return entries
    return entries


def import_sessions(
    connection: sqlite3.Connection,
    repo_path: Path,
    own_owner: str,
    stats: SyncStats | None = None,
) -> SyncStats:
    """Upsert every teammate's exported sessions into the local cache.

    Own directories are skipped entirely — the local rows are the richer originals
    and must never be overwritten by their own redacted exports. A tombstone only
    ever deletes rows imported from the owner who wrote it, so a malformed file
    cannot take out anyone else's data.
    """
    stats = stats or SyncStats()
    own_segment = _path_segment(own_owner)

    for project_dir in sorted(repo_path.iterdir()) if repo_path.is_dir() else []:
        if not project_dir.is_dir() or project_dir.name.startswith("."):
            continue
        for owner_dir in sorted(project_dir.iterdir()):
            if not owner_dir.is_dir() or owner_dir.name == own_segment:
                continue
            for session_file in sorted(owner_dir.glob("*.jsonl")):
                if session_file.name == DELETIONS_FILE:
                    continue
                _import_session_file(connection, session_file, owner_dir.name, stats)
            for tombstone in _read_jsonl(owner_dir / DELETIONS_FILE):
                session_id = tombstone.get("session_id")
                if not isinstance(session_id, str) or not session_id:
                    continue
                removed = connection.execute(
                    "DELETE FROM sessions WHERE session_id = ? AND owner = ?",
                    (session_id, owner_dir.name),
                ).rowcount
                stats.deleted += removed

    connection.commit()
    stats.steps.append(
        {
            "step": "import",
            "ok": True,
            "detail": f"{stats.imported} updated, {stats.import_skipped} unchanged, "
            f"{stats.deleted} tombstoned, {stats.import_errors} unreadable.",
        }
    )
    return stats


def _import_session_file(
    connection: sqlite3.Connection,
    session_file: Path,
    owner_segment: str,
    stats: SyncStats,
) -> None:
    entries = _read_jsonl(session_file)
    if not entries or entries[0].get("type") != "session":
        stats.import_errors += 1
        return

    header = entries[0]
    session_id = header.get("session_id")
    if not isinstance(session_id, str) or not session_id:
        stats.import_errors += 1
        return

    existing = connection.execute(
        "SELECT owner, message_count, last_activity_at FROM sessions WHERE session_id = ?",
        (session_id,),
    ).fetchone()
    if existing is not None and existing["owner"] is None:
        stats.import_skipped += 1
        return
    if (
        existing is not None
        and existing["message_count"] == header.get("message_count")
        and existing["last_activity_at"] == header.get("last_activity_at")
    ):
        stats.import_skipped += 1
        return

    file_stat = session_file.stat()
    connection.execute("DELETE FROM sessions WHERE session_id = ?", (session_id,))
    connection.execute(
        """
        INSERT INTO sessions (
            session_id, project_path, project_name, encoded_dir, title, first_prompt,
            model, git_branch, is_subagent, parent_session_id, started_at,
            last_activity_at, message_count, source_path, source_root, owner,
            source_mtime, source_size, ingested_lines
        ) VALUES (?, ?, ?, '', ?, ?, ?, ?, 0, NULL, ?, ?, ?, ?, NULL, ?, ?, ?, 0)
        """,
        (
            session_id,
            header.get("project_path") or header.get("project_name") or "",
            header.get("project_name") or "",
            header.get("title"),
            header.get("first_prompt"),
            header.get("model"),
            header.get("git_branch"),
            header.get("started_at"),
            header.get("last_activity_at"),
            header.get("message_count") or 0,
            str(session_file),
            owner_segment,
            file_stat.st_mtime,
            file_stat.st_size,
        ),
    )

    for entry in entries[1:]:
        if entry.get("type") != "message":
            continue
        connection.execute(
            "INSERT INTO messages (session_id, seq, role, tool_name, timestamp, text) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (
                session_id,
                entry.get("seq") or 0,
                entry.get("role") or "unknown",
                entry.get("tool_name"),
                entry.get("timestamp"),
                redact(str(entry.get("text") or "")),
            ),
        )

    tags = header.get("tags")
    if isinstance(tags, list):
        for tag in tags:
            if isinstance(tag, str) and tag.strip():
                connection.execute(
                    "INSERT OR IGNORE INTO session_tags (session_id, tag, source) VALUES (?, ?, 'import')",
                    (session_id, tag.strip().lower()),
                )
    files_touched = header.get("files_touched")
    if isinstance(files_touched, list):
        for file_path in files_touched:
            if isinstance(file_path, str) and file_path:
                connection.execute(
                    "INSERT OR IGNORE INTO files_touched (session_id, file_path) VALUES (?, ?)",
                    (session_id, file_path),
                )
    commits = header.get("commits")
    if isinstance(commits, list):
        for commit in commits:
            if isinstance(commit, dict) and commit.get("commit_sha"):
                connection.execute(
                    "INSERT OR IGNORE INTO session_commits (session_id, commit_sha, branch, subject) "
                    "VALUES (?, ?, ?, ?)",
                    (session_id, commit["commit_sha"], commit.get("branch"), commit.get("subject")),
                )

    stats.imported += 1


def run_sync(connection: sqlite3.Connection, config: TeamSyncConfig | None = None) -> SyncStats:
    """One full, idempotent sync cycle: pull → import → ingest → export → scan → push.

    Every step is safe to repeat, so this can run on any schedule; a failed pull
    (offline laptop) degrades to a local-only cycle whose changes push next time.
    The outcome is also written to `sync-status.json` so the plugin's health view can
    show what the last scheduled run actually did.
    """
    stats = SyncStats()
    try:
        _run_cycle(connection, config or load_team_sync(), stats)
    finally:
        _write_status(stats)
    return stats


def _write_status(stats: SyncStats) -> None:
    try:
        SYNC_STATUS_PATH.parent.mkdir(parents=True, exist_ok=True)
        SYNC_STATUS_PATH.write_text(
            json.dumps({"finished_at": _utc_now(), **stats.as_dict()}, indent=2),
            encoding="utf-8",
        )
    except OSError:
        pass


def _run_cycle(connection: sqlite3.Connection, config: TeamSyncConfig, stats: SyncStats) -> SyncStats:
    from .ingest import ingest
    if not config.is_usable():
        stats.steps.append(
            {
                "step": "config",
                "ok": False,
                "detail": "Team sync is disabled or missing repo path/owner; nothing to do.",
            }
        )
        return stats
    repo_path = config.repo_path
    assert repo_path is not None and config.owner is not None
    if not repo_path.is_dir():
        stats.steps.append(
            {"step": "config", "ok": False, "detail": f"Repo directory {repo_path} does not exist."}
        )
        return stats

    has_git = (repo_path / ".git").exists()
    if has_git:
        pulled = _pull(repo_path)
        stats.steps.append({"step": "pull", "ok": pulled[0], "detail": pulled[1]})

    import_sessions(connection, repo_path, config.owner, stats)

    ingest_stats = ingest(connection)
    stats.steps.append({"step": "ingest", "ok": True, "detail": json.dumps(ingest_stats.as_dict())})

    if config.paused:
        stats.steps.append(
            {
                "step": "export",
                "ok": True,
                "detail": "Sharing is paused in settings — teammates' sessions were still pulled in, "
                "but nothing of yours was exported or pushed.",
            }
        )
        return stats

    export_sessions(
        connection,
        repo_path,
        config.owner,
        config.projects,
        stats,
        min_messages=config.min_messages,
        max_age_days=config.max_age_days,
        extra_redaction_patterns=config.extra_redaction_patterns,
    )

    if not has_git:
        stats.steps.append({"step": "push", "ok": False, "detail": f"{repo_path} is not a git repository."})
        return stats

    own_paths = _own_paths(repo_path, config.owner)
    if own_paths and not _gitleaks_clean(repo_path, own_paths, stats):
        return stats

    for own_path in own_paths:
        _git(repo_path, "add", "-A", str(own_path.relative_to(repo_path)))
    staged = _git(repo_path, "diff", "--cached", "--quiet")
    if staged[0]:
        stats.steps.append({"step": "push", "ok": True, "detail": "Nothing new to push."})
        return stats

    committed = _git(repo_path, "commit", "-m", f"sync: {config.owner} {_utc_now()}")
    if not committed[0]:
        stats.steps.append({"step": "push", "ok": False, "detail": committed[1][-400:]})
        return stats
    pushed = _git(repo_path, "push")
    stats.steps.append({"step": "push", "ok": pushed[0], "detail": pushed[1][-400:] or "Pushed."})
    return stats


def _own_paths(repo_path: Path, owner: str) -> list[Path]:
    owner_segment = _path_segment(owner)
    if not repo_path.is_dir():
        return []
    return [
        project_dir / owner_segment
        for project_dir in sorted(repo_path.iterdir())
        if project_dir.is_dir() and not project_dir.name.startswith(".") and (project_dir / owner_segment).is_dir()
    ]


def _gitleaks_config() -> Path:
    """Writes the bundled allowlist to the cache dir, refreshing it if this build changed it."""
    if (
        not GITLEAKS_CONFIG_PATH.is_file()
        or GITLEAKS_CONFIG_PATH.read_text(encoding="utf-8") != _GITLEAKS_ALLOWLIST_TOML
    ):
        GITLEAKS_CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        GITLEAKS_CONFIG_PATH.write_text(_GITLEAKS_ALLOWLIST_TOML, encoding="utf-8")
    return GITLEAKS_CONFIG_PATH


def _gitleaks_clean(repo_path: Path, own_paths: list[Path], stats: SyncStats) -> bool:
    """Second redaction layer: refuse to push anything gitleaks flags in own files."""
    import shutil

    gitleaks = shutil.which("gitleaks")
    if gitleaks is None:
        stats.steps.append(
            {"step": "secret-scan", "ok": True, "detail": "gitleaks not installed; scan skipped."}
        )
        return True

    config_path = _gitleaks_config()
    for own_path in own_paths:
        result = _exec(
            [gitleaks, "detect", "--no-git", "--no-banner", "--config", str(config_path), "--source", str(own_path)],
            repo_path,
        )
        if not result[0]:
            stats.steps.append(
                {
                    "step": "secret-scan",
                    "ok": False,
                    "detail": f"gitleaks flagged {own_path}; push aborted. {result[1][-400:]}",
                }
            )
            return False
    stats.steps.append({"step": "secret-scan", "ok": True, "detail": f"{len(own_paths)} directories clean."})
    return True


def _pull(repo_path: Path) -> tuple[bool, str]:
    """Rebase onto this branch's upstream by name, never on whatever local tracking config says.

    A bare `git pull --rebase` reads `branch.<name>.merge`, so a clone with missing or
    duplicated tracking config fails with git's opaque "Cannot rebase onto multiple
    branches" and stays stuck every cycle. Naming the remote and branch bypasses that
    config entirely, which is right for a repo where every writer owns one directory.
    """
    branch = _git(repo_path, "symbolic-ref", "--short", "HEAD")
    if not branch[0]:
        return False, (
            f"{repo_path} is not on a branch (detached HEAD), so nothing can be pulled or "
            f"pushed. Run: git -C {repo_path} checkout main"
        )
    branch_name = branch[1].strip()
    configured_remote = _git(repo_path, "config", f"branch.{branch_name}.remote")
    remote_name = (configured_remote[1].strip() if configured_remote[0] else "") or "origin"

    pulled = _git(repo_path, "pull", "--rebase", "--autostash", remote_name, branch_name)
    if pulled[0]:
        return True, pulled[1][-400:]

    if _rebase_in_progress(repo_path):
        _git(repo_path, "rebase", "--abort")
    return False, f"Pulling {remote_name}/{branch_name} into {repo_path} failed. {pulled[1][-400:]}"


def _rebase_in_progress(repo_path: Path) -> bool:
    git_dir = _git(repo_path, "rev-parse", "--absolute-git-dir")
    if not git_dir[0]:
        return False
    state_root = Path(git_dir[1].strip())
    return (state_root / "rebase-merge").exists() or (state_root / "rebase-apply").exists()


def _git(repo_path: Path, *arguments: str) -> tuple[bool, str]:
    return _exec(["git", "-C", str(repo_path), *arguments], repo_path)


def _exec(command: list[str], working_dir: Path) -> tuple[bool, str]:
    try:
        completed = subprocess.run(
            command,
            cwd=working_dir,
            capture_output=True,
            text=True,
            timeout=_GIT_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return False, str(error)
    output = (completed.stdout + completed.stderr).strip()
    return completed.returncode == 0, output
