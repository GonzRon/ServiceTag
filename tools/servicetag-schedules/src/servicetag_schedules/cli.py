"""The `servicetag-schedules` command line: `plan`, `apply`. Both pair against the phone, read one
manifest, and print the entry table (kind, key, decision, reason) plus the summary counts — never a
pairing code, a serial or an asset id, only counts, decisions, manifest keys and reasons.

`SERVICETAG_ADB_SERIAL` (or `--serial`) pins the device, exactly as `servicetag-mcp` reads it.
`servicetag_mcp.server` is imported lazily, after `--serial` (if given) is written into the
environment: that module builds its `Device` at import time from the environment, so importing it
before the override would pin the wrong device for the rest of this process.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import sys
from typing import Any

from . import apply as applymod
from . import manifest as manifestmod
from . import phone
from . import plan as planmod

SERIAL_ENV = "SERVICETAG_ADB_SERIAL"


def _print_plan(result: planmod.Plan) -> None:
    for e in result.entries:
        print(f"{e.kind}\t{e.key}\t{e.decision}\t{e.reason}")
    counts = result.summary()
    print("summary: " + " ".join(f"{d}={counts[d]}" for d in ("CREATE", "IDENTICAL", "CONFLICT", "ERROR")))


def _load_manifest(path: str) -> manifestmod.Manifest | None:
    try:
        return manifestmod.load(path)
    except manifestmod.ManifestError as e:
        print(str(e), file=sys.stderr)
        return None


async def _paired_client(code: str) -> Any:
    """An entered async context manager: `mcp.Client(servicetag_mcp.server.mcp)`, already paired.
    Imported here (not at module scope) so `--serial` can be written into the environment first."""
    from mcp import Client
    from servicetag_mcp import server as mcp_server

    client_cm = Client(mcp_server.mcp)
    client = await client_cm.__aenter__()
    await phone.call_tool(client, "pair", {"code": code})
    return client_cm, client


async def _run_plan(args: argparse.Namespace) -> int:
    manifest = _load_manifest(args.manifest)
    if manifest is None:
        return 1
    if args.serial:
        os.environ[SERIAL_ENV] = args.serial

    client_cm, client = await _paired_client(args.code)
    try:
        inventory = await phone.snapshot(client)
    finally:
        await client_cm.__aexit__(None, None, None)

    result = planmod.plan(manifest, inventory)
    _print_plan(result)
    return 0 if result.clean else 1


async def _run_apply(args: argparse.Namespace) -> int:
    manifest = _load_manifest(args.manifest)
    if manifest is None:
        return 1
    if args.serial:
        os.environ[SERIAL_ENV] = args.serial

    client_cm, client = await _paired_client(args.code)
    try:
        inventory = await phone.snapshot(client)
        result = planmod.plan(manifest, inventory)
        _print_plan(result)
        if not result.clean:
            print("refusing to apply: the plan has CONFLICT/ERROR entries", file=sys.stderr)
            return 1
        outcome = await applymod.apply(manifest, result, client)
    finally:
        await client_cm.__aexit__(None, None, None)

    print(f"created: groups={outcome.groups_created} schedules={outcome.schedules_created}")
    _print_plan(outcome.reapply_plan)
    return 0 if outcome.reapply_plan.clean and outcome.reapply_plan.summary()["CREATE"] == 0 else 1


def _cmd_plan(args: argparse.Namespace) -> int:
    return asyncio.run(_run_plan(args))


def _cmd_apply(args: argparse.Namespace) -> int:
    return asyncio.run(_run_apply(args))


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="servicetag-schedules")
    sub = parser.add_subparsers(dest="command", required=True)

    p_plan = sub.add_parser("plan", help="Compute and print a plan for a manifest; writes nothing")
    p_plan.add_argument("manifest", help="Path to the manifest JSON document")
    p_plan.add_argument("--code", required=True, help="The Developer API pairing code")
    p_plan.add_argument("--serial", help=f"Overrides {SERIAL_ENV}")
    p_plan.set_defaults(func=_cmd_plan)

    p_apply = sub.add_parser("apply", help="Plan, then apply if clean, then re-plan and confirm")
    p_apply.add_argument("manifest", help="Path to the manifest JSON document")
    p_apply.add_argument("--code", required=True, help="The Developer API pairing code")
    p_apply.add_argument("--serial", help=f"Overrides {SERIAL_ENV}")
    p_apply.set_defaults(func=_cmd_apply)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
