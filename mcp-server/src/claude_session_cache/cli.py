"""Command line entry point: `ingest`, `serve`, `stats`, `export`, `import` and `sync`."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import queries
from .db import DEFAULT_DB_PATH, connect
from .ingest import DEFAULT_PROJECTS_DIR, ingest
from .metadata import load_team_sync


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

    export_parser = subparsers.add_parser("export", help="write own sessions into the team knowledge-base repo")
    _add_sync_arguments(export_parser)
    export_parser.add_argument(
        "--project", action="append", default=None, help="project allowlist (default: from plugin settings)"
    )

    import_parser = subparsers.add_parser("import", help="read teammates' sessions from the knowledge-base repo")
    _add_sync_arguments(import_parser)

    subparsers.add_parser("sync", help="full cycle: pull, import, ingest, export, secret-scan, push")

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

    if args.command in ("export", "import"):
        from .sync import export_sessions, import_sessions

        config = load_team_sync()
        repo_path = args.repo or config.repo_path
        owner = args.owner or config.owner
        if repo_path is None or not owner:
            print(
                "Team sync is not configured: set the repo path and owner in the plugin's "
                "settings, or pass --repo and --owner.",
                file=sys.stderr,
            )
            return 2

        if args.command == "export":
            projects = args.project or config.projects
            stats = export_sessions(
                connection,
                repo_path,
                owner,
                projects,
                min_messages=config.min_messages,
                max_age_days=config.max_age_days,
                extra_redaction_patterns=config.extra_redaction_patterns,
            )
        else:
            stats = import_sessions(connection, repo_path, owner)
        connection.commit()
        print(json.dumps(stats.as_dict(), indent=2))
        return 0

    if args.command == "sync":
        from .sync import run_sync

        stats = run_sync(connection)
        print(json.dumps(stats.as_dict(), indent=2))
        return 0 if all(step.get("ok") for step in stats.steps) else 1

    return 1


def sync_main() -> int:
    """Console script for the scheduled team sync, so macOS lists it under its own name."""
    return main([*sys.argv[1:], "sync"])


def _add_sync_arguments(subparser: argparse.ArgumentParser) -> None:
    subparser.add_argument(
        "--repo", type=Path, default=None, help="knowledge-base repo path (default: from plugin settings)"
    )
    subparser.add_argument(
        "--owner", default=None, help="this machine's owner id, e.g. a work email (default: from plugin settings)"
    )


if __name__ == "__main__":
    sys.exit(main())
