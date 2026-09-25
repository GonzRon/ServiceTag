"""The fourteen 1.4 tools (spec §9.4; master plan §12) and the write tools' schema check.

One case per tool for its method, path and body, and one for a refusal arriving as a `ToolError`
carrying the app's code. The three that record a **new fact** — `start_season`, `end_season` and
`record_condition` — are pinned to send exactly their arguments with nothing read first. Nothing
here, or anywhere on this server, amends or deletes a condition or an activation, deletes a health
subject, or writes a health value (inv. 127), and nothing names an installed component or reads
stock (inv. 129).

The schema check (master plan §20, dec. 25): before any write, the server confirms
`/v1/status.schemaVersion ≥ 8` once per pairing and refuses an older app with `APP_SCHEMA_TOO_OLD`,
sending nothing; reads keep working against a 1.3 app.
"""

from __future__ import annotations

import inspect
import json
import re
import zipfile

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from conftest import subject_row
from servicetag_mcp import server as server_module

NEW_TOOLS = (
    "get_season", "start_season", "end_season", "set_season_mode", "set_maintenance_break",
    "list_conditions", "record_condition", "get_health", "set_health_policy",
    "list_health_subjects", "create_health_subject", "update_health_subject",
    "archive_health_subject", "list_attention",
)

STATUS_1_3 = {"appVersion": "1.3.0", "apiVersion": 1, "schemaVersion": 7, "backupFormatVersion": 7,
              "counts": {}}


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def _refusal(code: str) -> dict:
    return {"error": {"code": code, "message": f"refused: {code}", "problems": []}}


# --- registration, and what is deliberately absent -----------------------------------------------


def test_the_fourteen_are_registered_and_guarded() -> None:
    for name in NEW_TOOLS:
        assert callable(getattr(server_module, name)), name
        assert name in server_module.TOOL_NAMES, name
        tool = server_module.mcp._tool_manager.get_tool(name)
        assert tool is not None and tool.parameters.get("additionalProperties") is False, name


def test_no_tool_deletes_or_amends_a_fact_or_subject() -> None:
    """Inv. 127: no tool amends or deletes a condition or an activation, deletes a health subject,
    or writes a health value. `delete_event` (1.1) stays the one delete, and every tool touching
    health is a read or configuration."""
    names = set(server_module.TOOL_NAMES)
    assert {n for n in names if n.startswith("delete_")} == {"delete_event"}
    for forbidden in (
        "delete_health_subject", "delete_condition", "update_condition", "amend_condition",
        "delete_activation", "update_activation", "amend_activation", "delete_season",
        "update_season", "record_health", "set_health", "update_health", "write_health",
        "set_health_score",
    ):
        assert forbidden not in names, forbidden
        assert not hasattr(server_module, forbidden), forbidden
    for name in names:
        assert not re.match(r"^(update|amend|edit|delete)_(condition|activation)", name), name
    health = {n for n in names if "health" in n}
    assert health == {
        "get_health", "set_health_policy", "list_health_subjects", "create_health_subject",
        "update_health_subject", "archive_health_subject",
    }


def test_no_tool_names_an_installed_component_or_reads_stock() -> None:
    """Inv. 129, the tool half: no tool or argument name speaks of assemblies, installed components
    or stock."""
    pattern = re.compile(r"assembl|installed|stock", re.IGNORECASE)
    for name in server_module.TOOL_NAMES:
        assert not pattern.search(name), name
        tool = server_module.mcp._tool_manager.get_tool(name)
        for argument in tool.parameters.get("properties", {}):
            assert not pattern.search(argument), (name, argument)


# --- the three new facts: no overlay, every argument explicit -----------------------------------


def test_start_end_and_record_condition_send_exactly_their_arguments(paired) -> None:
    """Nothing is read first — the only request before each write is the schema check's
    `/v1/status`, and that only once for the pairing — and the body is exactly the arguments, with
    `None` sent as `null` rather than dropped."""
    server_module.start_season(asset_id="a1", occurred_on="2026-05-01", event_id="e1")
    server_module.end_season(asset_id="a1", occurred_on=None, event_id=None)
    server_module.record_condition(
        asset_id="a1", condition="DOWN", occurred_on="2026-02-05", occurred_time="08:30",
        tz_id="Etc/UTC", reason="will not start", event_id="e2",
    )
    assert [(r.method, r.path) for r in paired.requests] == [
        ("GET", "/v1/status"),
        ("POST", "/v1/assets/a1/season"),
        ("POST", "/v1/assets/a1/season"),
        ("POST", "/v1/assets/a1/conditions"),
    ]
    start, end, condition = (body_of(r) for r in paired.requests[1:])
    assert start == {"action": "START", "occurredOn": "2026-05-01", "eventId": "e1"}
    assert end == {"action": "END", "occurredOn": None, "eventId": None}
    assert condition == {
        "condition": "DOWN", "occurredOn": "2026-02-05", "occurredTime": "08:30",
        "tzId": "Etc/UTC", "reason": "will not start", "eventId": "e2",
    }


def test_the_three_facts_have_no_defaults_and_no_clear_fields() -> None:
    for name in ("start_season", "end_season", "record_condition"):
        signature = inspect.signature(getattr(server_module, name))
        for parameter in signature.parameters.values():
            assert parameter.default is inspect.Parameter.empty, (name, parameter.name)
        assert "clear_fields" not in signature.parameters, name


def test_every_fact_tool_says_its_row_can_never_be_amended_or_deleted() -> None:
    for name in ("start_season", "end_season", "record_condition"):
        doc = " ".join((getattr(server_module, name).__doc__ or "").split())
        assert "can never be amended or deleted" in doc, name


# --- one case per tool: method, path, body ---------------------------------------------------------


WRITE_CASES = [
    pytest.param(
        lambda: server_module.set_season_mode(
            asset_id="a1", season_mode="CALENDAR", season_start_mmdd="04-01", season_end_mmdd="10-31",
        ),
        "POST", "/v1/assets/a1/season-mode",
        {"seasonMode": "CALENDAR", "seasonStartMmdd": "04-01", "seasonEndMmdd": "10-31",
         "manualPhase": None},
        id="set_season_mode",
    ),
    pytest.param(
        lambda: server_module.set_season_mode(asset_id="a1", season_mode="MANUAL", manual_phase="IN_SEASON"),
        "POST", "/v1/assets/a1/season-mode",
        {"seasonMode": "MANUAL", "seasonStartMmdd": None, "seasonEndMmdd": None,
         "manualPhase": "IN_SEASON"},
        id="set_season_mode-manual",
    ),
    pytest.param(
        lambda: server_module.set_maintenance_break(
            asset_id="a1", blackout_start_mmdd="07-01", blackout_end_mmdd="07-14",
        ),
        "POST", "/v1/assets/a1/maintenance-break",
        {"blackoutStartMmdd": "07-01", "blackoutEndMmdd": "07-14"},
        id="set_maintenance_break",
    ),
    pytest.param(
        lambda: server_module.set_maintenance_break(
            asset_id="a1", blackout_start_mmdd=None, blackout_end_mmdd=None,
        ),
        "POST", "/v1/assets/a1/maintenance-break",
        {"blackoutStartMmdd": None, "blackoutEndMmdd": None},
        id="set_maintenance_break-clears",
    ),
    pytest.param(
        lambda: server_module.set_health_policy(
            asset_id="a1", health_aggregation="TRACK_ONE", health_primary_subject_id="h1",
        ),
        "POST", "/v1/assets/a1/health-policy",
        {"healthAggregation": "TRACK_ONE", "healthPrimarySubjectId": "h1"},
        id="set_health_policy",
    ),
    pytest.param(
        lambda: server_module.set_health_policy(asset_id="a1", health_aggregation="WORST"),
        "POST", "/v1/assets/a1/health-policy",
        {"healthAggregation": "WORST", "healthPrimarySubjectId": None},
        id="set_health_policy-no-primary",
    ),
    pytest.param(
        lambda: server_module.create_health_subject(
            asset_id="a1", name="Battery", kind="PART", driver="AGE", nominal_until_days=700,
            warning_from_days=900, critical_from_days=1100,
        ),
        "POST", "/v1/health-subjects",
        {"assetId": "a1", "name": "Battery", "kind": "PART", "driver": "AGE",
         "nominalUntilDays": 700, "warningFromDays": 900, "criticalFromDays": 1100},
        id="create_health_subject",
    ),
    pytest.param(
        lambda: server_module.create_health_subject(
            asset_id="a1", name="Blade service", kind="ASSET", driver="MAINTENANCE_OVERDUE",
            nominal_until_days=0, warning_from_days=14, critical_from_days=60, schedule_id="s1",
            weight=3, sort_order=2,
        ),
        "POST", "/v1/health-subjects",
        {"assetId": "a1", "name": "Blade service", "kind": "ASSET", "driver": "MAINTENANCE_OVERDUE",
         "scheduleId": "s1", "nominalUntilDays": 0, "warningFromDays": 14, "criticalFromDays": 60,
         "weight": 3, "sortOrder": 2},
        id="create_health_subject-every-field",
    ),
    pytest.param(
        lambda: server_module.archive_health_subject(subject_id="h1"),
        "POST", "/v1/health-subjects/h1/archive", {"archived": True},
        id="archive_health_subject",
    ),
    pytest.param(
        lambda: server_module.archive_health_subject(subject_id="h1", archived=False),
        "POST", "/v1/health-subjects/h1/archive", {"archived": False},
        id="archive_health_subject-restore",
    ),
    pytest.param(
        lambda: server_module.start_season(asset_id="a/1", occurred_on=None, event_id=None),
        "POST", "/v1/assets/a%2F1/season", {"action": "START", "occurredOn": None, "eventId": None},
        id="start_season-quotes-its-id",
    ),
]


@pytest.mark.parametrize(("call", "method", "path", "body"), WRITE_CASES)
def test_each_write_tool_sends_its_route_and_body(paired, call, method, path, body) -> None:
    call()
    assert paired.last().method == method
    assert paired.last().path == path
    assert body_of(paired.last()) == body


READ_CASES = [
    pytest.param(lambda: server_module.get_season(asset_id="a1"), "/v1/assets/a1/season", id="get_season"),
    pytest.param(lambda: server_module.list_conditions(asset_id="a1"), "/v1/assets/a1/conditions",
                 id="list_conditions"),
    pytest.param(lambda: server_module.get_health(asset_id="a1"), "/v1/assets/a1/health", id="get_health"),
    pytest.param(lambda: server_module.list_health_subjects(asset_id="a1"),
                 "/v1/assets/a1/health-subjects", id="list_health_subjects"),
    pytest.param(lambda: server_module.list_attention(), "/v1/attention", id="list_attention"),
]


@pytest.mark.parametrize(("call", "path"), READ_CASES)
def test_each_read_tool_gets_its_path_and_nothing_else(paired, call, path) -> None:
    call()
    assert [(r.method, r.path) for r in paired.requests] == [("GET", path)]
    assert paired.last().body == b""


def test_update_health_subject_overlays_every_key_but_the_asset(paired) -> None:
    paired.reply("GET", "/v1/health-subjects/h1", 200, subject_row())
    server_module.update_health_subject(subject_id="h1", name="Battery pack", weight=None)
    assert [(r.method, r.path) for r in paired.requests] == [
        ("GET", "/v1/status"), ("GET", "/v1/health-subjects/h1"), ("PATCH", "/v1/health-subjects/h1"),
    ]
    assert body_of(paired.last()) == {
        "name": "Battery pack", "kind": "PART", "driver": "AGE", "scheduleId": None,
        "baselineProfileId": "p1", "nominalUntilDays": 700, "warningFromDays": 900,
        "criticalFromDays": 1100, "weight": 2, "sortOrder": 0,
    }


def test_update_health_subject_clears_its_two_links_by_name_and_nothing_else(paired) -> None:
    paired.reply("GET", "/v1/health-subjects/h1", 200, subject_row(scheduleId="s1"))
    server_module.update_health_subject(subject_id="h1", baseline_profile_id=None)
    assert body_of(paired.last())["baselineProfileId"] == "p1", "None leaves it alone"
    server_module.update_health_subject(subject_id="h1", clear_fields=["baseline_profile_id"])
    assert body_of(paired.last())["baselineProfileId"] is None
    server_module.update_health_subject(subject_id="h1", clear_fields=["schedule_id"])
    assert body_of(paired.last())["scheduleId"] is None

    before = len(paired.requests)
    for field in ("name", "kind", "driver", "nominal_until_days", "weight", "sort_order", "asset_id"):
        with pytest.raises(ToolError, match="cannot be cleared"):
            server_module.update_health_subject(subject_id="h1", clear_fields=[field])
    with pytest.raises(ToolError, match="also given a value"):
        server_module.update_health_subject(
            subject_id="h1", schedule_id="s2", clear_fields=["schedule_id"],
        )
    assert len(paired.requests) == before, "every refusal before any request"


# --- one case per tool: a refusal arrives as a ToolError carrying the app's code ------------------


REFUSAL_CASES = [
    pytest.param(lambda: server_module.get_season(asset_id="a1"),
                 "GET", "/v1/assets/a1/season", 404, "no_such_asset", id="get_season"),
    pytest.param(lambda: server_module.start_season(asset_id="a1", occurred_on=None, event_id=None),
                 "POST", "/v1/assets/a1/season", 409, "SEASON_NOT_MANUAL", id="start_season"),
    pytest.param(lambda: server_module.end_season(asset_id="a1", occurred_on="2027-01-01", event_id=None),
                 "POST", "/v1/assets/a1/season", 422, "SEASON_DATE_OUT_OF_RANGE", id="end_season"),
    pytest.param(lambda: server_module.set_season_mode(asset_id="a1", season_mode="YEAR_ROUND"),
                 "POST", "/v1/assets/a1/season-mode", 409, "SEASON_MODE_STRANDS_POLICY",
                 id="set_season_mode"),
    pytest.param(lambda: server_module.set_maintenance_break(
                     asset_id="a1", blackout_start_mmdd="01-01", blackout_end_mmdd="12-31"),
                 "POST", "/v1/assets/a1/maintenance-break", 422, "BLACKOUT_COVERS_THE_YEAR",
                 id="set_maintenance_break"),
    pytest.param(lambda: server_module.list_conditions(asset_id="a1"),
                 "GET", "/v1/assets/a1/conditions", 404, "no_such_asset", id="list_conditions"),
    pytest.param(lambda: server_module.record_condition(
                     asset_id="a1", condition="DOWN", occurred_on="2099-01-01", occurred_time=None,
                     tz_id="Etc/UTC", reason="", event_id=None),
                 "POST", "/v1/assets/a1/conditions", 422, "CONDITION_DATE_IN_FUTURE",
                 id="record_condition"),
    pytest.param(lambda: server_module.get_health(asset_id="a1"),
                 "GET", "/v1/assets/a1/health", 404, "no_such_asset", id="get_health"),
    pytest.param(lambda: server_module.set_health_policy(asset_id="a1", health_aggregation="TRACK_ONE"),
                 "POST", "/v1/assets/a1/health-policy", 422, "HEALTH_PRIMARY_INVALID",
                 id="set_health_policy"),
    pytest.param(lambda: server_module.list_health_subjects(asset_id="a1"),
                 "GET", "/v1/assets/a1/health-subjects", 404, "no_such_asset",
                 id="list_health_subjects"),
    pytest.param(lambda: server_module.create_health_subject(
                     asset_id="a1", name="Battery", kind="PART", driver="AGE",
                     nominal_until_days=900, warning_from_days=900, critical_from_days=1100),
                 "POST", "/v1/health-subjects", 422, "HEALTH_THRESHOLDS_INVALID",
                 id="create_health_subject"),
    pytest.param(lambda: server_module.update_health_subject(subject_id="h9", name="x"),
                 "GET", "/v1/health-subjects/h9", 404, "NO_SUCH_HEALTH_SUBJECT",
                 id="update_health_subject"),
    pytest.param(lambda: server_module.archive_health_subject(subject_id="h1"),
                 "POST", "/v1/health-subjects/h1/archive", 409, "HEALTH_SUBJECT_IS_PRIMARY",
                 id="archive_health_subject"),
    pytest.param(lambda: server_module.list_attention(),
                 "GET", "/v1/attention", 503, "store_unavailable", id="list_attention"),
]


@pytest.mark.parametrize(("call", "method", "path", "status", "code"), REFUSAL_CASES)
def test_each_tool_surfaces_the_apps_code_as_a_tool_error(paired, call, method, path, status, code) -> None:
    paired.reply(method, path, status, _refusal(code))
    with pytest.raises(ToolError) as raised:
        call()
    assert type(raised.value) is ToolError
    assert f"{status} {code}" in str(raised.value)


def test_the_refusal_cases_cover_all_fourteen() -> None:
    covered = {case.id for case in REFUSAL_CASES}
    assert covered == set(NEW_TOOLS)


# --- the schema check ------------------------------------------------------------------------------


def _archive(tmp_path) -> str:
    path = tmp_path / "ServiceTag-data-test.zip"
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("manifest.json", "{}")
    return str(path)


def _every_kind_of_write(tmp_path) -> list:
    """Shipped writes and new ones, plain posts and overlays alike."""
    return [
        lambda: server_module.create_asset(name="Mower"),
        lambda: server_module.update_asset(asset_id="a1", name="Mower 2"),
        lambda: server_module.archive_asset(asset_id="a1"),
        lambda: server_module.log_event(asset_id="a1", kind="NOTE", occurred_on="2026-02-01",
                                        tz_id="Etc/UTC"),
        lambda: server_module.delete_event(event_id="e1"),
        lambda: server_module.save_definition(asset_id="a1", definition_id="d1", label="x"),
        lambda: server_module.update_group(group_id="g1", name="x"),
        lambda: server_module.create_schedule(title="x", target_asset_id="a1", time_interval=1,
                                              time_unit="YEAR", anchor_on="2026-03-01"),
        lambda: server_module.update_schedule(schedule_id="s1", title="x"),
        lambda: server_module.archive_schedule(schedule_id="s1"),
        lambda: server_module.postpone_schedule(schedule_id="s1"),
        lambda: server_module.add_reference(asset_id="a1", uri="https://example.com/m", display_name="Manual"),
        lambda: server_module.start_season(asset_id="a1", occurred_on=None, event_id=None),
        lambda: server_module.set_season_mode(asset_id="a1", season_mode="YEAR_ROUND"),
        lambda: server_module.set_maintenance_break(asset_id="a1", blackout_start_mmdd=None,
                                                    blackout_end_mmdd=None),
        lambda: server_module.record_condition(asset_id="a1", condition="OPERATIONAL", occurred_on=None,
                                               occurred_time=None, tz_id="Etc/UTC", reason="",
                                               event_id=None),
        lambda: server_module.set_health_policy(asset_id="a1", health_aggregation="WORST"),
        lambda: server_module.create_health_subject(asset_id="a1", name="Battery", kind="PART",
                                                    driver="AGE", nominal_until_days=1,
                                                    warning_from_days=2, critical_from_days=3),
        lambda: server_module.update_health_subject(subject_id="h1", name="x"),
        lambda: server_module.archive_health_subject(subject_id="h1"),
        lambda: server_module.import_merge(archive_path=_archive(tmp_path)),
    ]


def test_writes_refuse_an_app_older_than_schema_8(paired, tmp_path) -> None:
    """Every write — shipped and new, plain and overlay — refuses a 1.3 app by name and sends
    nothing: the only requests are the one `/v1/status` read for this pairing and the merge plan,
    which writes nothing. An overlay is refused before it reads its row."""
    paired.reply("GET", "/v1/status", 200, STATUS_1_3)
    paired.reply("POST", "/v1/import-merge/plan", 200, {"applicable": True, "conflicts": []})
    for write in _every_kind_of_write(tmp_path):
        with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as raised:
            write()
        assert "schema 7" in str(raised.value)
    assert [(r.method, r.path) for r in paired.requests] == [
        ("GET", "/v1/status"), ("POST", "/v1/import-merge/plan"),
    ]


def test_a_status_with_no_schema_version_is_refused_and_asked_again(paired) -> None:
    paired.reply("GET", "/v1/status", 200, {"appVersion": "1.0.0"})
    for _ in range(2):
        with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD"):
            server_module.archive_asset(asset_id="a1")
    assert [(r.method, r.path) for r in paired.requests] == [("GET", "/v1/status")] * 2


def test_reads_still_work(paired, tmp_path) -> None:
    """Against a 1.3 app every read answers, and none of them asks for the schema at all —
    `import_merge(plan_only=True)` included, since the plan writes nothing."""
    paired.reply("GET", "/v1/status", 200, STATUS_1_3)
    paired.reply("POST", "/v1/import-merge/plan", 200, {"applicable": True, "conflicts": []})
    for read in (
        lambda: server_module.list_assets(),
        lambda: server_module.get_asset(asset_id="a1"),
        lambda: server_module.list_events(asset_id="a1"),
        lambda: server_module.list_schedules(),
        lambda: server_module.get_schedule(schedule_id="s1"),
        lambda: server_module.list_due(),
        lambda: server_module.get_season(asset_id="a1"),
        lambda: server_module.list_conditions(asset_id="a1"),
        lambda: server_module.get_health(asset_id="a1"),
        lambda: server_module.list_health_subjects(asset_id="a1"),
        lambda: server_module.list_attention(),
        lambda: server_module.import_merge(archive_path=_archive(tmp_path), plan_only=True),
    ):
        read()
    assert all(r.path != "/v1/status" for r in paired.requests)
    assert ("POST", "/v1/import-merge/plan") in [(r.method, r.path) for r in paired.requests]


def test_the_status_is_read_once_per_pairing(paired) -> None:
    """Cached per pairing: two writes under one code ask once. A new code — a new pairing, perhaps
    another phone — asks again, so switching to a 1.3 phone cannot reuse the 1.4 phone's answer."""
    server_module.archive_asset(asset_id="a1")
    server_module.archive_schedule(schedule_id="s1")
    server_module.record_condition(asset_id="a1", condition="OPERATIONAL", occurred_on=None,
                                   occurred_time=None, tz_id="Etc/UTC", reason="", event_id=None)
    assert [r.path for r in paired.requests].count("/v1/status") == 1

    paired.reply("GET", "/v1/status", 200, STATUS_1_3)
    server_module.pair("WXYZ6789")
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD"):
        server_module.archive_asset(asset_id="a1")
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD"):
        server_module.archive_schedule(schedule_id="s1")
    assert [r.path for r in paired.requests].count("/v1/status") == 2
