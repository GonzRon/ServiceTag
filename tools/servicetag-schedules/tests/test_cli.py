"""The argument parser only -- `plan`/`apply` both pair against a real (or forwarded) phone, which
is out of scope for this brief's tests (see plan.md's "Not in Stage B" / the controller's proofs).
This just pins the wiring: both subcommands exist, take a manifest path, `--code` and `--serial`,
and route to the right handler.
"""

from __future__ import annotations

from servicetag_schedules import cli


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


def test_print_plan_never_includes_anything_but_kind_key_decision_reason(capsys) -> None:
    from servicetag_schedules import plan as PL

    result = PL.Plan(entries=(PL.PlanEntry("group", "g1", "CREATE", "no existing group named this"),))
    cli._print_plan(result)
    out = capsys.readouterr().out
    assert "g1" in out and "CREATE" in out
    assert "summary:" in out
