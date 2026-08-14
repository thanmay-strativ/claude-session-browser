"""Command line entry point: `ingest`, `serve` and `stats`."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import queries
from .db import DEFAULT_DB_PATH, connect
from .ingest import DEFAULT_PROJECTS_DIR, ingest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="claude-session-cache",
        description="Local searchable cache of Claude Code sessions, exposed over MCP.",
    )
    parser.add_argument("--db", type=Path, default=None, help=f"cache path (default: {DEFAULT_DB_PATH})")
    subparsers = parser.add_subparsers(dest="command", required=True)

    ingest_parser = subparsers.add_parser("ingest", help="scan transcripts into the cache")
    ingest_parser.add_argument(
        "--projects-dir",
        type=Path,
        default=None,
        help=f"transcript root (default: {DEFAULT_PROJECTS_DIR})",
    )
    ingest_parser.add_argument(
        "--full", action="store_true", help="rebuild every session instead of only what changed"
    )

    subparsers.add_parser("serve", help="run the MCP server on stdio")
    subparsers.add_parser("stats", help="print cache statistics as JSON")

    search_parser = subparsers.add_parser("search", help="search the cache from the terminal")
    search_parser.add_argument("query")
    search_parser.add_argument("--limit", type=int, default=10)

    args = parser.parse_args(argv)

    if args.command == "serve":
        from .server import serve

        serve(args.db)
        return 0

    connection = connect(args.db)

    if args.command == "ingest":
        stats = ingest(connection, projects_dir=args.projects_dir, full=args.full)
        print(json.dumps(stats.as_dict(), indent=2))
        return 0

    if args.command == "stats":
        print(json.dumps(queries.cache_stats(connection), indent=2, default=str))
        return 0

    if args.command == "search":
        results, match_mode = queries.search_messages(connection, query=args.query, limit=args.limit)
        for result in results:
            print(f"[{result['project_name']}] {result['title']}")
            print(f"  {result['role']}  {result['session_id']}")
            print(f"  {result['snippet']}\n")
        print(f"{len(results)} result(s) [{match_mode}]")
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
