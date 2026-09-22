"""The `servicetag-bundle` command line: `check`, `build`, `inspect`. Every failure is reported as
one `path: message` line on stderr and a non-zero exit; nothing is ever written on a failure path.
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path
from typing import Any

from .archive import ArchiveTooLarge, MANIFEST_ENTRY, write_archive
from .source import SourceError, load_source


def _cmd_check(args: argparse.Namespace) -> int:
    source_path = Path(args.source)
    try:
        source = load_source(source_path)
    except SourceError as e:
        print(str(e), file=sys.stderr)
        return 1

    if source.deferred is not None:
        print(f"{source_path}: deferred payload present (ignored by this tool)")
    print(f"{source_path}: ok")
    return 0


def _cmd_build(args: argparse.Namespace) -> int:
    source_path = Path(args.source)
    out_path = Path(args.out)

    try:
        source = load_source(source_path)
    except SourceError as e:
        print(str(e), file=sys.stderr)
        return 1

    if out_path.exists() and not args.force:
        print(f"{out_path}: refusing to overwrite an existing file (use --force)", file=sys.stderr)
        return 1
    if not out_path.parent.is_dir():
        print(f"{out_path}: parent directory does not exist", file=sys.stderr)
        return 1

    try:
        manifest = write_archive(source, out_path)
    except ArchiveTooLarge as e:
        print(f"{out_path}: {e}", file=sys.stderr)
        return 1
    except OSError as e:
        print(f"{out_path}: {e.strerror or e}", file=sys.stderr)
        return 1

    print(f"{out_path}: backupSetId={manifest['backupSetId']}")
    for key, value in manifest["counts"].items():
        print(f"{key}={value}")
    return 0


def _cmd_inspect(args: argparse.Namespace) -> int:
    archive_path = Path(args.archive)
    try:
        size = archive_path.stat().st_size
        with zipfile.ZipFile(archive_path) as zf:
            manifest: dict[str, Any] = json.loads(zf.read(MANIFEST_ENTRY).decode("utf-8"))
    except OSError as e:
        print(f"{archive_path}: {e.strerror or e}", file=sys.stderr)
        return 1
    except (zipfile.BadZipFile, KeyError, json.JSONDecodeError) as e:
        print(f"{archive_path}: {e}", file=sys.stderr)
        return 1

    # `manifest.get(..., "-")` throughout: a format <=4 archive's manifest legitimately lacks
    # `backupSetId`/`artifact*` (the "four new" format-5 fields), and even older ones may lack
    # more -- `inspect` reports what's there rather than crashing on what isn't.
    print(f"{archive_path}: {size} bytes")
    print(
        f"formatVersion={manifest.get('formatVersion', '-')} "
        f"schemaVersion={manifest.get('schemaVersion', '-')} "
        f"appVersion={manifest.get('appVersion', '-')}"
    )
    print(f"backupSetId={manifest.get('backupSetId', '-')}")
    for key, value in manifest.get("counts", {}).items():
        print(f"{key}={value}")
    return 0


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="servicetag-bundle")
    sub = parser.add_subparsers(dest="command", required=True)

    p_check = sub.add_parser("check", help="Validate a source document (does not write anything)")
    p_check.add_argument("source", help="Path to the source JSON document")
    p_check.set_defaults(func=_cmd_check)

    p_build = sub.add_parser("build", help="Build a format-5 backup archive from a source document")
    p_build.add_argument("source", help="Path to the source JSON document")
    p_build.add_argument("out", help="Path to write the archive to")
    p_build.add_argument("--force", action="store_true", help="Overwrite an existing output file")
    p_build.set_defaults(func=_cmd_build)

    p_inspect = sub.add_parser(
        "inspect", help="Print an archive's manifest (counts, versions, size) without decoding rows"
    )
    p_inspect.add_argument("archive", help="Path to a built archive")
    p_inspect.set_defaults(func=_cmd_inspect)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
