"""Secret redaction applied to every message before it reaches the cache.

Transcripts capture raw shell output, so they routinely contain API keys, tokens and
connection strings. The cache is queryable by an LLM, so redaction happens on ingest
rather than on read — a secret that never enters the database cannot leak out of it.
"""

from __future__ import annotations

import re

_PATTERNS: list[tuple[re.Pattern[str], str]] = [
    (
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", re.S),
        "private-key",
    ),
    (re.compile(r"\bsk-ant-[A-Za-z0-9_\-]{20,}"), "anthropic-key"),
    (re.compile(r"\bsk-[A-Za-z0-9]{32,}"), "api-key"),
    (re.compile(r"\bghp_[A-Za-z0-9]{20,}"), "github-token"),
    (re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}"), "github-token"),
    (re.compile(r"\bxox[baprs]-[A-Za-z0-9\-]{10,}"), "slack-token"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "aws-key-id"),
    (re.compile(r"\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"), "jwt"),
    (
        re.compile(r"\b(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis|amqp)://[^\s:@/]+:[^\s@/]+@"),
        "db-url-credentials",
    ),
    (re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._\-]{20,}"), "bearer-token"),
    (
        re.compile(
            r"(?i)\b([A-Z0-9_]*(?:SECRET|PASSWORD|PASSWD|TOKEN|API_KEY|APIKEY|ACCESS_KEY|PRIVATE_KEY)[A-Z0-9_]*)"
            r"\s*[:=]\s*['\"]?([^\s'\"]{6,})"
        ),
        "env-secret",
    ),
]


def redact(text: str) -> str:
    """Replace anything matching a known secret shape with a labelled placeholder."""
    if not text:
        return text

    redacted = text
    for pattern, label in _PATTERNS:
        if label == "env-secret":
            redacted = pattern.sub(lambda match: f"{match.group(1)}=[REDACTED:env-secret]", redacted)
        elif label == "db-url-credentials":
            redacted = pattern.sub("[REDACTED:db-url-credentials]@", redacted)
        else:
            redacted = pattern.sub(f"[REDACTED:{label}]", redacted)
    return redacted
