"""The overlays stop enumerating fields (spec §9.4; master plan §12, dec. 48, ruled M15).

`update_asset`, `update_schedule` and `update_health_subject` build their bodies from the command
key lists **vendored into the package** (`servicetag_mcp.command_shapes`). This file is the one
place the repository's golden `docs/api/command-shapes.json` is read, and only at test time: it
proves the vendored copy equal to the golden file, and every overlay's body equal to that copy —
in both schedule forms — so the app's JVM test, the golden file, this package and the wire all move
together or a test fails.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import command_shapes
from servicetag_mcp import server as server_module

GOLDEN = Path(__file__).resolve().parents[3] / "docs" / "api" / "command-shapes.json"
"""`tests` → `servicetag-mcp` → `tools` → the repository root."""


def golden() -> dict:
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def asset_row(**overrides) -> dict:
    row = {
        "id": "a1", "name": "Generator", "description": "", "category": "Power", "notes": "",
        "status": "ACTIVE", "createdAt": 1, "updatedAt": 1, "templateKey": None,
        "manufacturer": "", "model": "", "serialNumber": "", "purchaseOn": None,
        "inServiceOn": None, "purchasePriceMinor": None, "currency": None, "vendor": "",
        "location": "", "warrantyExpiresOn": None, "warrantyNotes": "", "retiredOn": None,
        "parentAssetId": None, "seasonStartMmdd": None, "seasonEndMmdd": None,
        "seasonMode": "YEAR_ROUND", "blackoutStartMmdd": None, "blackoutEndMmdd": None,
        "healthAggregation": "WORST", "healthPrimarySubjectId": None,
    }
    row.update(overrides)
    return {"asset": row}


def schedule_row(**overrides) -> dict:
    row = {
        "id": "s1", "assetId": "a1", "groupId": None, "title": "Load test", "description": "",
        "timeInterval": 1, "timeUnit": "MONTH", "timeBasis": "FIXED", "anchorOn": "2026-01-05",
        "leadDays": 3, "meterDefinitionId": None, "meterInterval": None, "anchorMeter": None,
        "meterLead": None, "servicePolicy": "CONTINUOUS", "policyOffsetDays": None,
        "completionMode": "QUICK", "profileId": None, "remindersEnabled": True, "status": "ACTIVE",
        "postponedDueOn": None, "createdAt": 1, "updatedAt": 1, "ruleChangedAt": 1,
        "providers": [{"provider": "LOCAL", "enabled": True}],
        "seasonBehavior": "IGNORE", "seasonReentry": None, "seasonReentryOffsetDays": None,
    }
    row.update(overrides)
    return {"schedule": row, "state": {}, "status": "OK", "computedForOn": "2026-02-01"}


def subject_row(**overrides) -> dict:
    row = {
        "id": "h1", "assetId": "a1", "name": "Battery", "kind": "PART", "driver": "AGE",
        "scheduleId": None, "baselineProfileId": "p1", "nominalUntilDays": 700,
        "warningFromDays": 900, "criticalFromDays": 1100, "weight": 2, "sortOrder": 0,
        "archivedAt": None, "createdAt": 1, "updatedAt": 1,
    }
    row.update(overrides)
    return {"subject": row}


# --- the vendored copy is the golden file ---------------------------------------------------------


def test_the_vendored_key_lists_equal_the_golden_file() -> None:
    """Keys, legacy keys, action flags and `rowToCommand`, in the golden file's own order."""
    shapes = golden()
    vendored = command_shapes.vendored()
    assert set(vendored) == {"asset", "schedule", "healthSubject"}
    for name, entry in vendored.items():
        assert entry == shapes[name], name


def test_the_server_names_only_vendored_schedule_keys_for_its_forms_and_flag() -> None:
    """The three names `server.py` uses outside the key lists — the two 1.4 policy keys that decide
    the form, and the action flag — are themselves vendored entries, so they cannot drift apart."""
    current = {server_module._wire(name) for name in server_module._CURRENT_POLICY_ARGUMENTS}
    assert current == {"servicePolicy", "policyOffsetDays"}
    assert current <= set(command_shapes.SCHEDULE_KEYS)
    legacy = {server_module._wire(name) for name in server_module._DEPRECATED_SEASON_ARGUMENTS}
    assert legacy == set(command_shapes.SCHEDULE_LEGACY_KEYS)
    assert server_module._UNLINK_HEALTH_SUBJECT in command_shapes.SCHEDULE_ACTION_FLAGS


# --- every overlay submits exactly those keys ------------------------------------------------------


def test_update_schedule_and_update_asset_submit_exactly_the_vendored_keys(paired) -> None:
    shapes = golden()

    paired.reply("GET", "/v1/assets/a1", 200, asset_row())
    server_module.update_asset(asset_id="a1", name="Generator 2")
    assert sorted(body_of(paired.last())) == sorted(shapes["asset"]["keys"])

    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row())
    server_module.update_schedule(schedule_id="s1", title="Load test, full")
    assert sorted(body_of(paired.last())) == sorted(shapes["schedule"]["keys"]), "the 1.4 form"

    server_module.update_schedule(schedule_id="s1", season_behavior="IGNORE")
    legacy_form = [k for k in shapes["schedule"]["keys"] if k not in ("servicePolicy", "policyOffsetDays")]
    legacy_form += shapes["schedule"]["legacyKeys"]
    assert sorted(body_of(paired.last())) == sorted(legacy_form), "the legacy form"

    paired.reply("GET", "/v1/health-subjects/h1", 200, subject_row())
    server_module.update_health_subject(subject_id="h1", weight=3)
    assert sorted(body_of(paired.last())) == sorted(
        k for k in shapes["healthSubject"]["keys"] if k != "assetId"
    ), "a subject never changes asset"


def test_a_new_vendored_key_is_carried_without_tool_changes(paired, monkeypatch) -> None:
    """The point of reading the vendored list: a key the app's command gains is carried by adding it
    to `command_shapes`, and no tool has to learn its name."""
    monkeypatch.setattr(command_shapes, "ASSET_KEYS", command_shapes.ASSET_KEYS + ("futureAssetKey",))
    monkeypatch.setattr(
        command_shapes, "SCHEDULE_KEYS", command_shapes.SCHEDULE_KEYS + ("futureRuleKey",)
    )
    monkeypatch.setattr(
        command_shapes, "HEALTH_SUBJECT_KEYS",
        command_shapes.HEALTH_SUBJECT_KEYS + ("futureSubjectKey",),
    )

    paired.reply("GET", "/v1/assets/a1", 200, asset_row(futureAssetKey="kept-a"))
    server_module.update_asset(asset_id="a1", name="Generator 2")
    assert body_of(paired.last())["futureAssetKey"] == "kept-a"

    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row(futureRuleKey="kept-s"))
    server_module.update_schedule(schedule_id="s1", lead_days=5)
    assert body_of(paired.last())["futureRuleKey"] == "kept-s"
    server_module.update_schedule(schedule_id="s1", season_behavior="IGNORE")
    assert body_of(paired.last())["futureRuleKey"] == "kept-s", "the legacy form carries it too"

    paired.reply("GET", "/v1/health-subjects/h1", 200, subject_row(futureSubjectKey="kept-h"))
    server_module.update_health_subject(subject_id="h1", name="Battery pack")
    assert body_of(paired.last())["futureSubjectKey"] == "kept-h"


def test_a_row_missing_a_vendored_key_is_refused_and_never_written(paired) -> None:
    """A malformed read never becomes a write: a key the command needs and the row does not report
    is a `ToolError` naming it, and no PATCH is sent."""
    row = schedule_row()
    del row["schedule"]["servicePolicy"]
    paired.reply("GET", "/v1/schedules/s1", 200, row)
    with pytest.raises(ToolError, match="servicePolicy"):
        server_module.update_schedule(schedule_id="s1", title="x")

    subject = subject_row()
    del subject["subject"]["sortOrder"]
    paired.reply("GET", "/v1/health-subjects/h1", 200, subject)
    with pytest.raises(ToolError, match="sortOrder"):
        server_module.update_health_subject(subject_id="h1", name="x")

    assert not any(r.method == "PATCH" for r in paired.requests)
