"""Reads the user metadata sidecar shared with the PyCharm plugin.

Custom titles, pins and tags are user-authored, so they cannot be recovered from the
transcripts. The plugin owns this file; the cache only reads it. Keeping it as plain
JSON outside any IDE config directory is what makes it readable from both sides.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_METADATA_PATH = Path(
    os.environ.get(
        "CLAUDE_SESSION_METADATA",
        Path.home() / ".claude-session-browser" / "metadata.json",
    )
)

DEFAULT_PROJECTS_DIR = Path(os.environ.get("CLAUDE_PROJECTS_DIR", Path.home() / ".claude" / "projects"))


@dataclass
class SessionMetadata:
    title: str | None = None
    pinned: bool = False
    tags: list[str] = field(default_factory=list)


def _clean_tags(tags: object) -> list[str]:
    """Normalise stored tags, tolerating a leading '#' left by older plugin builds."""
    if not isinstance(tags, list):
        return []
    cleaned = [tag.strip().lstrip("#").strip().lower() for tag in tags if isinstance(tag, str)]
    return [tag for tag in dict.fromkeys(cleaned) if tag]


def _read_sidecar(path: Path | None = None) -> dict:
    target = path or DEFAULT_METADATA_PATH
    if not target.is_file():
        return {}
    try:
        payload = json.loads(target.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return {}
    return payload if isinstance(payload, dict) else {}


def configured_session_root(path: Path | None = None) -> Path | None:
    """The session directory the plugin is pointed at, if the user changed it.

    Switching Claude accounts changes where transcripts live, so the cache has to follow
    the same setting or it would keep indexing the previous account.
    """
    root = _read_sidecar(path).get("sessionRoot")
    if isinstance(root, str) and root.strip():
        return Path(root.strip()).expanduser()
    return None


def active_session_root(path: Path | None = None) -> Path:
    """The account currently selected in the plugin, falling back to the default one.

    This is the cache's notion of "which account", so ingest and every query must derive
    it the same way — otherwise rows are written under one key and searched under another.
    """
    return configured_session_root(path) or DEFAULT_PROJECTS_DIR


def load_metadata(path: Path | None = None) -> dict[str, SessionMetadata]:
    """Load per-session user metadata, returning an empty map if the sidecar is absent."""
    payload = _read_sidecar(path)
    sessions = payload.get("sessions")
    if not isinstance(sessions, dict):
        return {}

    result: dict[str, SessionMetadata] = {}
    for session_id, raw in sessions.items():
        if not isinstance(raw, dict):
            continue
        title = raw.get("title")
        tags = raw.get("tags")
        result[session_id] = SessionMetadata(
            title=title.strip() if isinstance(title, str) and title.strip() else None,
            pinned=bool(raw.get("pinned", False)),
            tags=_clean_tags(tags),
        )
    return result
