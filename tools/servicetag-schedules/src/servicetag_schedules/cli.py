"""The `servicetag-schedules` command line: `plan`, `apply`. Both pair against the phone, read one
manifest, and print the entry table (kind, key, decision, reason) plus the summary counts — never a
pairing code, a serial or an asset id, only counts, decisions, manifest keys and reasons.

`SERVICETAG_ADB_SERIAL` (or `--serial`) pins the device, exactly as `servicetag-mcp` reads it.
`servicetag_mcp.server` is imported lazily, after `--serial` (if given) is written into the
environment: that module builds its `Device` at import time from the environment, so importing it
before the override would pin the wrong device for the rest of this process.

Every phone-side failure — a wrong or expired pairing code (the single most likely operator
mistake, since the code is new every time the Developer API screen reopens), or any MCP `is_error`
reached while planning or applying — is caught in `_run_plan`/`_run_apply`, printed as one line
(carrying the manifest entry's key where `phone.PhoneError`/`apply.ApplyError` name one) and exits
**2**. Neither run function lets one of those propagate out as a traceback.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import sys
from contextlib import asynccontextmanager
from typing import AsyncIterator

from . import apply as applymod
from . import manifest as manifestmod
from . import phone
from . import plan as planmod

SERIAL_ENV = "SERVICETAG_ADB_SERIAL"

# Every exception this CLI treats as "the phone (or a write on its behalf) refused" rather than a
# bug: printed as one line and exit 2, never a traceback.
_PHONE_SIDE_ERRORS = (phone.PhoneError, applymod.ApplyError, applymod.ApplyRefused)


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


@asynccontextmanager
async def _paired_client(code: str) -> AsyncIterator[phone.ToolClient]:
    """`mcp.Client(servicetag_mcp.server.mcp)`, entered and paired. A context manager on purpose:
    a failed `pair` call (a wrong or expired code) still closes the underlying client instead of
    leaking it, because the failure happens *inside* the `async with` rather than between entering
    it and handing it back. Imported here (not at module scope) so `--serial` can be written into
    the environment first."""
    from mcp import Client
    from servicetag_mcp import server as mcp_server

    async with Client(mcp_server.mcp) as client:
        await phone.call_tool(client, "pair", {"code": code})
        yield client


async def _run_plan(args: argparse.Namespace) -> int:
    manifest = _load_manifest(args.manifest)
    if manifest is None:
        return 1
    if args.serial:
        os.environ[SERIAL_ENV] = args.serial

    try:
        async with _paired_client(args.code) as client:
            inventory = await phone.snapshot(client)
    except _PHONE_SIDE_ERRORS as e:
        print(str(e), file=sys.stderr)
        return 2

    result = planmod.plan(manifest, inventory)
    _print_plan(result)
    return 0 if result.clean else 1


async def _run_apply(args: argparse.Namespace) -> int:
    manifest = _load_manifest(args.manifest)
    if manifest is None:
        return 1
    if args.serial:
        os.environ[SERIAL_ENV] = args.serial

    try:
        async with _paired_client(args.code) as client:
            inventory = await phone.snapshot(client)
            result = planmod.plan(manifest, inventory)
            _print_plan(result)
            if not result.clean:
                print("refusing to apply: the plan has CONFLICT/ERROR entries", file=sys.stderr)
                return 1
            outcome = await applymod.apply(manifest, result, client)
    except _PHONE_SIDE_ERRORS as e:
        print(str(e), file=sys.stderr)
        return 2

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
