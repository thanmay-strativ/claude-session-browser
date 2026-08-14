"""Parses a Claude Code transcript `.jsonl` into structured records for the cache.

Transcripts are append-only, so parsing can resume from a line offset: `skip_lines`
lets an incremental ingest read only what was added since the last run.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

MAX_ENTRY_CHARS = 8000

FILE_TOOLS = {"Read", "Write", "Edit", "NotebookEdit"}

NOISE_PREFIXES = (
    "<local-command",
    "<command-name",
    "<command-message",
    "<command-args",
    "<system-reminder",
    "Caveat:",
)

_COMMIT_OUTPUT = re.compile(
    r"\[([^\]\s]+)(?:\s+\(root-commit\))?\s+([0-9a-f]{7,40})\]\s*([^\n]*)",
)


@dataclass
class Entry:
    seq: int
    role: str
    text: str
    tool_name: str | None = None
    timestamp: str | None = None


@dataclass
class CommitRef:
    commit_sha: str
    branch: str | None
    subject: str | None


@dataclass
class ParsedSession:
    title: str | None = None
    first_prompt: str | None = None
    project_path: str | None = None
    git_branch: str | None = None
    model: str | None = None
    started_at: str | None = None
    last_activity_at: str | None = None
    lines_read: int = 0
    entries: list[Entry] = field(default_factory=list)
    files_touched: list[str] = field(default_factory=list)
    commits: list[CommitRef] = field(default_factory=list)


def parse_transcript(path: Path, skip_lines: int = 0, start_seq: int = 0) -> ParsedSession:
    """Parse a transcript, ignoring the first `skip_lines` lines already ingested."""
    parsed = ParsedSession()
    sequence = start_seq
    seen_files: set[str] = set()
    seen_commits: set[str] = set()

    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for line_number, raw_line in enumerate(handle):
            parsed.lines_read = line_number + 1
            if line_number < skip_lines:
                continue

            line = raw_line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(record, dict):
                continue

            record_type = record.get("type")
            if record_type == "ai-title":
                title = record.get("aiTitle")
                if isinstance(title, str) and title.strip():
                    parsed.title = title.strip()
                continue
            if record_type not in ("user", "assistant"):
                continue

            _absorb_metadata(record, parsed)

            message = record.get("message")
            if not isinstance(message, dict):
                continue

            timestamp = record.get("timestamp") if isinstance(record.get("timestamp"), str) else None
            sequence = _absorb_content(
                record_type,
                message,
                timestamp,
                parsed,
                sequence,
                seen_files,
                seen_commits,
            )

    return parsed


def _absorb_metadata(record: dict, parsed: ParsedSession) -> None:
    cwd = record.get("cwd")
    if isinstance(cwd, str) and cwd and parsed.project_path is None:
        parsed.project_path = cwd

    branch = record.get("gitBranch")
    if isinstance(branch, str) and branch.strip() and branch != "HEAD":
        parsed.git_branch = branch.strip()

    timestamp = record.get("timestamp")
    if isinstance(timestamp, str) and timestamp:
        if parsed.started_at is None:
            parsed.started_at = timestamp
        parsed.last_activity_at = timestamp

    message = record.get("message")
    if isinstance(message, dict):
        model = message.get("model")
        if isinstance(model, str) and model:
            parsed.model = model


def _absorb_content(
    record_type: str,
    message: dict,
    timestamp: str | None,
    parsed: ParsedSession,
    sequence: int,
    seen_files: set[str],
    seen_commits: set[str],
) -> int:
    role = "user" if record_type == "user" else "assistant"
    content = message.get("content")

    if isinstance(content, str):
        text = content.strip()
        if text and not _is_noise(text):
            sequence += 1
            parsed.entries.append(Entry(seq=sequence, role=role, text=_cap(text), timestamp=timestamp))
            _remember_first_prompt(role, text, parsed)
        return sequence

    if not isinstance(content, list):
        return sequence

    for block in content:
        if not isinstance(block, dict):
            continue
        block_type = block.get("type")

        if block_type == "text":
            text = (block.get("text") or "").strip()
            if text and not _is_noise(text):
                sequence += 1
                parsed.entries.append(Entry(seq=sequence, role=role, text=_cap(text), timestamp=timestamp))
                _remember_first_prompt(role, text, parsed)

        elif block_type == "thinking":
            text = (block.get("thinking") or "").strip()
            if text:
                sequence += 1
                parsed.entries.append(
                    Entry(seq=sequence, role="thinking", text=_cap(text), timestamp=timestamp)
                )

        elif block_type == "tool_use":
            tool_name = block.get("name") or "tool"
            summary = _summarize_tool_input(tool_name, block.get("input"))
            sequence += 1
            parsed.entries.append(
                Entry(
                    seq=sequence,
                    role="tool_use",
                    text=_cap(summary),
                    tool_name=tool_name,
                    timestamp=timestamp,
                )
            )
            _collect_file_path(tool_name, block.get("input"), parsed, seen_files)

        elif block_type == "tool_result":
            text = _tool_result_text(block)
            if text:
                sequence += 1
                parsed.entries.append(
                    Entry(seq=sequence, role="tool_result", text=_cap(text), timestamp=timestamp)
                )
                _collect_commits(text, parsed, seen_commits)

    return sequence


def _remember_first_prompt(role: str, text: str, parsed: ParsedSession) -> None:
    if role == "user" and parsed.first_prompt is None:
        parsed.first_prompt = text[:500]


def _is_noise(text: str) -> bool:
    head = text.lstrip()
    return head.startswith(NOISE_PREFIXES)


def _cap(text: str) -> str:
    return text if len(text) <= MAX_ENTRY_CHARS else text[:MAX_ENTRY_CHARS] + "\n… [truncated]"


def _summarize_tool_input(tool_name: str, tool_input: object) -> str:
    if not isinstance(tool_input, dict):
        return tool_name
    detail_keys = {
        "Bash": "command",
        "Read": "file_path",
        "Write": "file_path",
        "Edit": "file_path",
        "NotebookEdit": "file_path",
        "Grep": "pattern",
        "Glob": "pattern",
        "Task": "description",
        "Agent": "description",
        "WebFetch": "url",
    }
    detail = tool_input.get(detail_keys.get(tool_name, ""), None)
    if isinstance(detail, str) and detail.strip():
        return f"{tool_name}: {detail.strip()}"
    return tool_name


def _collect_file_path(
    tool_name: str, tool_input: object, parsed: ParsedSession, seen_files: set[str]
) -> None:
    if tool_name not in FILE_TOOLS or not isinstance(tool_input, dict):
        return
    file_path = tool_input.get("file_path")
    if isinstance(file_path, str) and file_path and file_path not in seen_files:
        seen_files.add(file_path)
        parsed.files_touched.append(file_path)


def _tool_result_text(block: dict) -> str:
    content = block.get("content")
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts: list[str] = []
        for element in content:
            if isinstance(element, dict) and element.get("type") == "text":
                text = element.get("text")
                if isinstance(text, str):
                    parts.append(text)
        return "\n".join(parts).strip()
    return ""


def _collect_commits(text: str, parsed: ParsedSession, seen_commits: set[str]) -> None:
    for match in _COMMIT_OUTPUT.finditer(text):
        branch, commit_sha, subject = match.group(1), match.group(2), match.group(3)
        if commit_sha in seen_commits:
            continue
        seen_commits.add(commit_sha)
        parsed.commits.append(
            CommitRef(commit_sha=commit_sha, branch=branch, subject=(subject or "").strip() or None)
        )
