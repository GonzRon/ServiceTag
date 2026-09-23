"""The CLI: the argument parser, `_print_plan`'s exact output shape, the exit codes `plan`/`apply`
promise, and Q1's cleanup-on-pair-failure guarantee.

`_run_plan`/`_run_apply` are exercised end to end against a `FakeClient` (see `conftest.py`) by
monkeypatching `cli._paired_client` to hand one back already "paired", never touching a real `mcp`
client or a phone -- out of scope for this brief (see plan.md's "Not in Stage B" / the controller's
proofs).
"""

from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

import pytest

from servicetag_schedules import apply as applymod
from servicetag_schedules import cli
from servicetag_schedules import phone
from servicetag_schedules import plan as planmod


def _run(coro):
    return asyncio.run(coro)


# ---- argument parsing -------------------------------------------------------------------------


def test_plan_subcommand_parses() -> None:
    parser = cli._build_parser()
    args = parser.parse_args(["plan", "manifest.json", "--code", "ABCD1234"])
    assert args.command == "plan"
    assert args.manifest == "manifest.json"
    assert args.code == "ABCD1234"
    assert args.serial is None
    assert args.func is cli._cmd_plan


def test_apply_subcommand_parses_with_serial_override() -> None:
    parser = cli._build_parser()
    args = parser.parse_args(["apply", "manifest.json", "--code", "ABCD1234", "--serial", "R3CN1234"])
    assert args.command == "apply"
    assert args.serial == "R3CN1234"
    assert args.func is cli._cmd_apply


# ---- S6: `_print_plan`'s output shape, pinned exactly, not by substring ------------------------


def test_print_plan_prints_exactly_kind_key_decision_reason_then_the_summary(capsys) -> None:
    result = planmod.Plan(
        entries=(
            planmod.PlanEntry("group", "g1", "CREATE", "no existing group named this"),
            planmod.PlanEntry("schedule", "s1", "IDENTICAL", "matches the existing schedule"),
        )
    )
    cli._print_plan(result)
    out = capsys.readouterr().out
    assert out == (
        "group\tg1\tCREATE\tno existing group named this\n"
        "schedule\ts1\tIDENTICAL\tmatches the existing schedule\n"
        "summary: CREATE=1 IDENTICAL=1 CONFLICT=0 ERROR=0\n"
    )


def test_print_plan_each_entry_line_is_exactly_four_tab_separated_fields(capsys) -> None:
    result = planmod.Plan(entries=(planmod.PlanEntry("group", "g1", "CREATE", "no existing group named this"),))
    cli._print_plan(result)
    lines = capsys.readouterr().out.splitlines()
    entry_lines = [line for line in lines if not line.startswith("summary:")]
    assert len(entry_lines) == 1
    assert entry_lines[0].split("\t") == ["group", "g1", "CREATE", "no existing group named this"]


# ---- exit codes: plan exits 0 iff clean; apply exits 0 only when the final re-plan is IDENTICAL --


def _write_manifest(tmp_path: Path, obj: dict) -> str:
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(obj), encoding="utf-8")
    return str(path)


def _manifest_obj(target_asset: str = "Garden shed") -> dict:
    return {
        "manifestVersion": 1,
        "asOf": "2026-09-23",
        "groups": [],
        "schedules": [
            {
                "key": "s1",
                "title": "Inspect roof",
                "target": {"asset": target_asset},
                "time": {"interval": 30, "unit": "DAY", "basis": "FIXED", "anchorOn": "2026-01-01"},
                "leadDays": 0,
                "completionMode": "QUICK",
                "seasonBehavior": "IGNORE",
                "remindersEnabled": False,
            }
        ],
    }


def _patch_paired_client(monkeypatch: pytest.MonkeyPatch, client: object) -> None:
    """Replace `cli._paired_client` with an async context manager that just hands back `client` --
    same shape as the real one (`async with _paired_client(code) as client:`), without touching
    `mcp.Client` or a phone."""
    from contextlib import asynccontextmanager

    @asynccontextmanager
    async def _fake_paired_client(code: str):
        yield client

    monkeypatch.setattr(cli, "_paired_client", _fake_paired_client)


def test_run_plan_exits_0_when_the_plan_is_clean(tmp_path, fake_client, monkeypatch, capsys) -> None:
    fake_client.add_asset(name="Garden shed")
    _patch_paired_client(monkeypatch, fake_client)
    args = argparse.Namespace(manifest=_write_manifest(tmp_path, _manifest_obj()), code="SECRETCODE", serial=None)

    rc = _run(cli._run_plan(args))

    assert rc == 0
    out = capsys.readouterr().out
    assert "SECRETCODE" not in out  # the pairing code is never printed


def test_run_plan_exits_1_when_the_plan_is_not_clean(tmp_path, fake_client, monkeypatch) -> None:
    # no matching asset on the phone -> ERROR
    _patch_paired_client(monkeypatch, fake_client)
    args = argparse.Namespace(manifest=_write_manifest(tmp_path, _manifest_obj()), code="SECRETCODE", serial=None)

    rc = _run(cli._run_plan(args))

    assert rc == 1


def test_run_apply_exits_0_only_when_the_final_replan_is_all_identical(tmp_path, fake_client, monkeypatch, capsys) -> None:
    fake_client.add_asset(name="Garden shed")
    _patch_paired_client(monkeypatch, fake_client)
    args = argparse.Namespace(manifest=_write_manifest(tmp_path, _manifest_obj()), code="SECRETCODE", serial=None)

    rc = _run(cli._run_apply(args))

    assert rc == 0
    [schedule_row] = fake_client.schedules
    out = capsys.readouterr().out
    assert "SECRETCODE" not in out  # never the pairing code
    assert schedule_row["id"] not in out  # never a phone-assigned id


def test_run_apply_exits_1_and_writes_nothing_when_the_initial_plan_is_not_clean(tmp_path, fake_client, monkeypatch) -> None:
    _patch_paired_client(monkeypatch, fake_client)  # no matching asset -> ERROR
    args = argparse.Namespace(manifest=_write_manifest(tmp_path, _manifest_obj()), code="SECRETCODE", serial=None)

    rc = _run(cli._run_apply(args))

    assert rc == 1
    assert fake_client.schedules == []
    tool_names = [name for name, _ in fake_client.calls]
    assert "create_schedule" not in tool_names


# ---- Q1: a pair failure closes the client cleanly; phone-side errors print one line, exit 2 ------


def test_run_plan_exits_2_and_prints_one_line_when_a_write_path_call_fails(tmp_path, fake_client, monkeypatch, capsys) -> None:
    """`_run_plan` doesn't itself write, but `phone.snapshot` calling a tool that comes back
    `is_error` (the same shape a rejected `pair` takes) must be caught, printed as one line, and
    exit 2 -- not an unhandled traceback."""
    fake_client.fail_next("list_assets", "401 Unauthorized: pairing code expired")
    _patch_paired_client(monkeypatch, fake_client)
    args = argparse.Namespace(manifest=_write_manifest(tmp_path, _manifest_obj()), code="SECRETCODE", serial=None)

    rc = _run(cli._run_plan(args))

    assert rc == 2


def test_paired_client_closes_the_underlying_client_when_pair_fails(monkeypatch, capsys) -> None:
    """The real `_paired_client`, not the monkeypatched stand-in: a rejected pairing code must
    still close the entered `mcp.Client` rather than leak it (Q1)."""
    import mcp
    from types import SimpleNamespace

    class _FailingInnerClient:
        async def call_tool(self, name, arguments):
            assert name == "pair"
            # The same `is_error` shape a real refusal takes (see `phone._is_error`/`_text`).
            return SimpleNamespace(
                is_error=True, structured_content=None,
                content=[SimpleNamespace(text="401 Unauthorized: bad pairing code")],
            )

    class _FakeMcpClientCm:
        entered = False
        exited = False

        def __init__(self, target: object) -> None:
            self._target = target

        async def __aenter__(self) -> _FailingInnerClient:
            _FakeMcpClientCm.entered = True
            return _FailingInnerClient()

        async def __aexit__(self, *exc_info: object) -> bool:
            _FakeMcpClientCm.exited = True
            return False

    monkeypatch.setattr(mcp, "Client", _FakeMcpClientCm)

    with pytest.raises(phone.PhoneError):
        _run(cli._paired_client("WRONGCODE").__aenter__())

    assert _FakeMcpClientCm.entered is True
    assert _FakeMcpClientCm.exited is True
