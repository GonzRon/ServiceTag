"""The seventeen 1.2 tools: the method, the path and the body each one sends — and the
`clear_fields` audit the null-means-unchanged convention makes necessary.

One case per hazard, not per permutation. The hazard that carries the most risk here is the one
`postpone_schedule` names: under the shipped convention an **omitted** argument and an explicit
**`null`** both mean "leave alone", so a nullable argument with no `clear_fields` entry is a value
that can be set and never unset. Every nullable argument of every overlay tool below therefore has
a test that it is *not* cleared by `None` and *is* cleared by name.
"""

from __future__ import annotations

import json

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import server as server_module

MAINTENANCE_TOOLS = (
    "list_groups",
    "get_group",
    "list_asset_groups",
    "create_group",
    "update_group",
    "archive_group",
    "list_schedules",
    "get_schedule",
    "create_schedule",
    "update_schedule",
    "pause_schedule",
    "archive_schedule",
    "postpone_schedule",
    "complete_schedule",
    "close_round",
    "list_closures",
    "list_due",
)
"""Master plan §10's seventeen, named exactly as it names them. `list_schedules` covers all three
schedule listings — every schedule, one asset's, one group's — because §10 names one tool and the
three are one question asked of three scopes. The registered total is 21 + 17 = **38**, which
`test_argument_guard.py` pins."""


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


# --- registration -------------------------------------------------------------------------------


def test_every_maintenance_tool_is_registered_and_guarded() -> None:
    for name in MAINTENANCE_TOOLS:
        assert callable(getattr(server_module, name)), f"{name} is missing"
        assert name in server_module.TOOL_NAMES, f"{name} is not in TOOL_NAMES"
        tool = server_module.mcp._tool_manager.get_tool(name)
        assert tool is not None, name
        assert tool.parameters.get("additionalProperties") is False, name


def test_nothing_destructive_and_no_snooze_tool_exists() -> None:
    """The cheap check that catches a tool added without a route (README's own promise)."""
    for forbidden in (
        "delete_group",
        "delete_schedule",
        "delete_closure",
        "delete_member",
        "snooze",
        "snooze_schedule",
        "amend_closure",
        "update_closure",
    ):
        assert not hasattr(server_module, forbidden), forbidden
        assert forbidden not in server_module.TOOL_NAMES, forbidden


# --- the read tools -----------------------------------------------------------------------------


def test_the_maintenance_read_tools_get_their_paths(paired) -> None:
    for call, path in (
        (lambda: server_module.list_groups(), "/v1/groups"),
        (lambda: server_module.get_group(group_id="g1"), "/v1/groups/g1"),
        (lambda: server_module.list_asset_groups(asset_id="a1"), "/v1/assets/a1/groups"),
        (lambda: server_module.list_schedules(), "/v1/schedules"),
        (lambda: server_module.list_schedules(asset_id="a1"), "/v1/assets/a1/schedules"),
        (lambda: server_module.list_schedules(group_id="g1"), "/v1/groups/g1/schedules"),
        (lambda: server_module.get_schedule(schedule_id="s1"), "/v1/schedules/s1"),
        (lambda: server_module.list_closures(schedule_id="s1"), "/v1/schedules/s1/closures"),
        (lambda: server_module.list_due(), "/v1/due"),
    ):
        call()
        assert paired.last().method == "GET"
        assert paired.last().path == path


def test_list_schedules_refuses_both_scopes_at_once(paired) -> None:
    """Two scopes is two questions, and the tool will not guess which one was meant."""
    with pytest.raises(ToolError, match="asset_id or group_id"):
        server_module.list_schedules(asset_id="a1", group_id="g1")
    assert paired.requests == []


def test_an_empty_id_refuses_before_any_request(paired) -> None:
    for call in (
        lambda: server_module.get_group(group_id=""),
        lambda: server_module.get_schedule(schedule_id=""),
        lambda: server_module.list_closures(schedule_id=""),
        lambda: server_module.list_asset_groups(asset_id=""),
    ):
        with pytest.raises(ToolError, match="must not be empty"):
            call()
    assert paired.requests == []


# --- the creates: every field explicit, an omitted one is the API's own default ------------------


def test_create_group_sends_only_what_it_was_given(paired) -> None:
    server_module.create_group(name="North run", members=[{"assetId": "a1", "sortOrder": 0}])
    assert paired.last().method == "POST"
    assert paired.last().path == "/v1/groups"
    assert body_of(paired.last()) == {
        "name": "North run",
        "members": [{"assetId": "a1", "sortOrder": 0}],
    }


def test_create_schedule_sends_only_what_it_was_given(paired) -> None:
    server_module.create_schedule(
        title="Winterise",
        target_group_id="g1",
        time_interval=1,
        time_unit="MONTH",
        anchor_on="2026-02-01",
    )
    assert body_of(paired.last()) == {
        "title": "Winterise",
        "targetGroupId": "g1",
        "timeInterval": 1,
        "timeUnit": "MONTH",
        "anchorOn": "2026-02-01",
    }


# --- the state tools ----------------------------------------------------------------------------


def test_the_state_tools_send_their_boolean(paired) -> None:
    for call, path, body in (
        (lambda: server_module.archive_group(group_id="g1"), "/v1/groups/g1/archive", {"archived": True}),
        (
            lambda: server_module.archive_group(group_id="g1", archived=False),
            "/v1/groups/g1/archive",
            {"archived": False},
        ),
        (
            lambda: server_module.pause_schedule(schedule_id="s1"),
            "/v1/schedules/s1/pause",
            {"paused": True},
        ),
        (
            lambda: server_module.pause_schedule(schedule_id="s1", paused=False),
            "/v1/schedules/s1/pause",
            {"paused": False},
        ),
        (
            lambda: server_module.archive_schedule(schedule_id="s1"),
            "/v1/schedules/s1/archive",
            {"archived": True},
        ),
    ):
        call()
        assert paired.last().method == "POST"
        assert paired.last().path == path
        assert body_of(paired.last()) == body


# --- update_group: the overlay, and the member list that has to be clearable by name ------------


def _group_row(members: list[dict] | None = None) -> dict:
    return {
        "group": {
            "id": "g1",
            "name": "North run",
            "description": "the top paddock",
            "archivedAt": None,
            "createdAt": 1,
            "updatedAt": 2,
            "members": members
            if members is not None
            else [
                {"id": "m1", "assetId": "a1", "sortOrder": 0, "addedAt": 1, "removedAt": None},
                {"id": "m2", "assetId": "a2", "sortOrder": 1, "addedAt": 1, "removedAt": None},
            ],
        }
    }


def test_update_group_overlays_and_keeps_every_member_by_id(paired) -> None:
    paired.reply("GET", "/v1/groups/g1", 200, _group_row())
    server_module.update_group(group_id="g1", name="North paddock")

    assert paired.last().method == "PATCH"
    assert paired.last().path == "/v1/groups/g1"
    assert body_of(paired.last()) == {
        "name": "North paddock",
        "description": "the top paddock",
        # Every kept window travels by its own id, stripped of the server-stamped dates.
        "members": [
            {"id": "m1", "assetId": "a1", "sortOrder": 0},
            {"id": "m2", "assetId": "a2", "sortOrder": 1},
        ],
    }


def test_update_group_leaves_everything_alone_for_an_explicit_none(paired) -> None:
    paired.reply("GET", "/v1/groups/g1", 200, _group_row())
    server_module.update_group(group_id="g1", name=None, description=None, members=None)
    assert body_of(paired.last()) == {
        "name": "North run",
        "description": "the top paddock",
        "members": [
            {"id": "m1", "assetId": "a1", "sortOrder": 0},
            {"id": "m2", "assetId": "a2", "sortOrder": 1},
        ],
    }


def test_update_group_clears_the_member_list_by_name(paired) -> None:
    """§4.3's sharpest group case: an overlay that treats an omitted `members` as "leave alone"
    makes closing every membership impossible, so it is clearable **by name**."""
    paired.reply("GET", "/v1/groups/g1", 200, _group_row())
    server_module.update_group(group_id="g1", clear_fields=["members"])
    assert body_of(paired.last())["members"] == []


def test_update_group_clears_the_description_by_name(paired) -> None:
    paired.reply("GET", "/v1/groups/g1", 200, _group_row())
    server_module.update_group(group_id="g1", clear_fields=["description"])
    assert body_of(paired.last())["description"] == ""


def test_update_group_replaces_a_supplied_member_list_wholesale(paired) -> None:
    paired.reply("GET", "/v1/groups/g1", 200, _group_row())
    server_module.update_group(group_id="g1", members=[{"id": "m1", "assetId": "a1", "sortOrder": 0}])
    assert body_of(paired.last())["members"] == [{"id": "m1", "assetId": "a1", "sortOrder": 0}]


def test_update_group_refuses_clearing_the_name_and_a_field_given_a_value(paired) -> None:
    paired.reply("GET", "/v1/groups/g1", 200, _group_row())
    with pytest.raises(ToolError, match="cannot be cleared"):
        server_module.update_group(group_id="g1", clear_fields=["name"])
    with pytest.raises(ToolError, match="also given a value"):
        server_module.update_group(group_id="g1", description="x", clear_fields=["description"])
    assert paired.requests == [], "both refusals happen before any request"


# --- update_schedule: the overlay over every rule field ------------------------------------------


def _schedule_row(**overrides) -> dict:
    row = {
        "id": "s1",
        "assetId": "a1",
        "groupId": None,
        "title": "Filter change",
        "description": "every month",
        "timeInterval": 1,
        "timeUnit": "MONTH",
        "timeBasis": "FIXED",
        "anchorOn": "2026-02-01",
        "leadDays": 7,
        "meterDefinitionId": None,
        "meterInterval": None,
        "anchorMeter": None,
        "meterLead": None,
        "seasonBehavior": "IGNORE",
        "seasonReentry": None,
        "seasonReentryOffsetDays": None,
        "completionMode": "QUICK",
        "profileId": "p1",
        "remindersEnabled": True,
        "status": "ACTIVE",
        "postponedDueOn": "2026-02-20",
        "createdAt": 1,
        "updatedAt": 2,
        "providers": [{"provider": "LOCAL", "enabled": True}],
    }
    row.update(overrides)
    return {"schedule": row, "state": {}, "status": "DUE", "computedForOn": "2026-02-10"}


def test_update_schedule_overlays_one_field_and_renames_the_target(paired) -> None:
    paired.reply("GET", "/v1/schedules/s1", 200, _schedule_row())
    server_module.update_schedule(schedule_id="s1", title="Filter swap")

    assert paired.last().method == "PATCH"
    assert paired.last().path == "/v1/schedules/s1"
    body = body_of(paired.last())
    # The row reports `assetId`/`groupId`; the command takes `targetAssetId`/`targetGroupId`.
    assert body["targetAssetId"] == "a1"
    assert body["targetGroupId"] is None
    assert "assetId" not in body and "groupId" not in body
    assert body["title"] == "Filter swap"
    assert body["description"] == "every month"
    assert body["leadDays"] == 7
    assert body["profileId"] == "p1"
    assert body["providers"] == [{"provider": "LOCAL", "enabled": True}]
    # Identity, bookkeeping and the postponement are in no command.
    for absent in ("id", "status", "createdAt", "updatedAt", "postponedDueOn"):
        assert absent not in body, absent


def test_update_schedule_leaves_every_field_alone_for_an_explicit_none(paired) -> None:
    paired.reply("GET", "/v1/schedules/s1", 200, _schedule_row())
    server_module.update_schedule(
        schedule_id="s1",
        title=None,
        description=None,
        target_asset_id=None,
        target_group_id=None,
        time_interval=None,
        time_unit=None,
        time_basis=None,
        anchor_on=None,
        lead_days=None,
        meter_definition_id=None,
        meter_interval=None,
        anchor_meter=None,
        meter_lead=None,
        season_behavior=None,
        season_reentry=None,
        season_reentry_offset_days=None,
        completion_mode=None,
        profile_id=None,
        reminders_enabled=None,
        providers=None,
    )
    body = body_of(paired.last())
    assert body["title"] == "Filter change"
    assert body["targetAssetId"] == "a1"
    assert body["timeInterval"] == 1
    assert body["anchorOn"] == "2026-02-01"
    assert body["leadDays"] == 7
    assert body["profileId"] == "p1"
    assert body["remindersEnabled"] is True
    assert body["providers"] == [{"provider": "LOCAL", "enabled": True}]


@pytest.mark.parametrize(
    ("name", "wire", "cleared", "row"),
    [
        ("description", "description", "", {}),
        ("target_asset_id", "targetAssetId", None, {}),
        # A group-targeted row, so clearing this one empties a value it actually held rather than
        # agreeing with a `None` that was already there (review S4).
        ("target_group_id", "targetGroupId", None, {"assetId": None, "groupId": "g1"}),
        ("time_interval", "timeInterval", None, {}),
        ("time_unit", "timeUnit", None, {}),
        ("anchor_on", "anchorOn", None, {}),
        ("meter_definition_id", "meterDefinitionId", None, {}),
        ("meter_interval", "meterInterval", None, {}),
        ("anchor_meter", "anchorMeter", None, {}),
        ("meter_lead", "meterLead", None, {}),
        ("season_reentry", "seasonReentry", None, {}),
        ("season_reentry_offset_days", "seasonReentryOffsetDays", None, {}),
        ("profile_id", "profileId", None, {}),
        ("providers", "providers", [], {}),
    ],
)
def test_every_clearable_schedule_field_clears_by_name(paired, name, wire, cleared, row) -> None:
    """The audit, one row per clearable argument — all fourteen of
    `_SCHEDULE_CLEARABLE_FIELDS`: `None` left each of them alone in the test above, and the
    documented `clear_fields` name is what empties it.

    Each row is seeded with a value to lose, so a passing assertion cannot be a `None` agreeing
    with a `None`.
    """
    seeded = {
        "meterDefinitionId": "d1", "meterInterval": 100.0, "anchorMeter": 0.0, "meterLead": 10.0,
        "seasonReentry": "04-01", "seasonReentryOffsetDays": 7,
    }
    seeded.update(row)
    paired.reply("GET", "/v1/schedules/s1", 200, _schedule_row(**seeded))
    # The row reports `assetId`/`groupId` where the command takes `targetAssetId`/`targetGroupId`,
    # so the "something to lose" check reads the row's own key.
    row_key = {"targetAssetId": "assetId", "targetGroupId": "groupId"}.get(wire, wire)
    assert _schedule_row(**seeded)["schedule"][row_key] != cleared, (
        f"{name} must start with something to lose"
    )
    server_module.update_schedule(schedule_id="s1", clear_fields=[name])
    assert body_of(paired.last())[wire] == cleared


def test_moving_a_schedule_to_a_group_target_needs_the_old_target_cleared(paired) -> None:
    """Exactly one target may be set, so supplying the other one alone is not enough — the overlay
    would keep both. The two ids are clearable for that reason and no other."""
    paired.reply("GET", "/v1/schedules/s1", 200, _schedule_row())
    server_module.update_schedule(
        schedule_id="s1", target_group_id="g1", clear_fields=["target_asset_id"],
    )
    body = body_of(paired.last())
    assert body["targetAssetId"] is None
    assert body["targetGroupId"] == "g1"


def test_the_schedule_fields_that_cannot_be_cleared_are_refused_by_name(paired) -> None:
    paired.reply("GET", "/v1/schedules/s1", 200, _schedule_row())
    for field in ("title", "time_basis", "season_behavior", "completion_mode", "lead_days",
                  "reminders_enabled", "postponed_due_on", "status"):
        with pytest.raises(ToolError, match="cannot be cleared"):
            server_module.update_schedule(schedule_id="s1", clear_fields=[field])
    assert paired.requests == []


# --- postpone_schedule: the case the whole audit exists for --------------------------------------


def test_postpone_schedule_sets_a_date(paired) -> None:
    paired.reply("GET", "/v1/schedules/s1", 200, _schedule_row(postponedDueOn=None))
    server_module.postpone_schedule(schedule_id="s1", postponed_due_on="2026-03-01")
    assert paired.last().method == "POST"
    assert paired.last().path == "/v1/schedules/s1/postpone"
    assert body_of(paired.last()) == {"postponedDueOn": "2026-03-01"}


def test_postpone_schedule_with_none_leaves_the_postponement_alone(paired) -> None:
    """The hole this audit was written for: under the null-means-unchanged rule an explicit `None`
    **cannot** clear a postponement, even though the wire route accepts a literal `null`."""
    paired.reply("GET", "/v1/schedules/s1", 200, _schedule_row(postponedDueOn="2026-02-20"))
    server_module.postpone_schedule(schedule_id="s1", postponed_due_on=None)
    assert body_of(paired.last()) == {"postponedDueOn": "2026-02-20"}


def test_postpone_schedule_clears_the_postponement_by_name(paired) -> None:
    paired.reply("GET", "/v1/schedules/s1", 200, _schedule_row(postponedDueOn="2026-02-20"))
    server_module.postpone_schedule(schedule_id="s1", clear_fields=["postponed_due_on"])
    assert body_of(paired.last()) == {"postponedDueOn": None}


def test_postpone_schedule_refuses_a_date_and_a_clear_together(paired) -> None:
    with pytest.raises(ToolError, match="also given a value"):
        server_module.postpone_schedule(
            schedule_id="s1", postponed_due_on="2026-03-01", clear_fields=["postponed_due_on"],
        )
    assert paired.requests == []


# --- the two facts with no overlay ---------------------------------------------------------------


def test_complete_schedule_sends_every_argument_and_reads_nothing_back(paired) -> None:
    server_module.complete_schedule(
        schedule_id="s1",
        occurred_on="2026-02-10",
        tz_id="Etc/UTC",
        asset_id="a1",
        occurred_time="09:30",
        notes="new cartridge",
        values={"d1": "3.2"},
        consumables=[{"name": "Cartridge", "quantity": "1", "unit": "ea"}],
    )
    assert len(paired.requests) == 1, "a new fact is never overlaid onto a row it read back"
    assert paired.last().method == "POST"
    assert paired.last().path == "/v1/schedules/s1/complete"
    assert body_of(paired.last()) == {
        "occurredOn": "2026-02-10",
        "tzId": "Etc/UTC",
        "assetId": "a1",
        "occurredTime": "09:30",
        "notes": "new cartridge",
        "values": {"d1": "3.2"},
        "consumables": [{"name": "Cartridge", "quantity": "1", "unit": "ea"}],
    }


def test_close_round_sends_every_argument_and_reads_nothing_back(paired) -> None:
    server_module.close_round(schedule_id="s1", closed_on="2026-02-08")
    assert len(paired.requests) == 1
    assert paired.last().method == "POST"
    assert paired.last().path == "/v1/schedules/s1/close-round"
    assert body_of(paired.last()) == {"closedOn": "2026-02-08"}

    server_module.close_round(schedule_id="s1", closed_on=None)
    assert body_of(paired.last()) == {"closedOn": None}


def test_the_two_new_facts_have_no_clear_fields_argument() -> None:
    """A tool with no overlay has no `clear_fields` hole to audit, because nothing is inherited."""
    for name in ("complete_schedule", "close_round", "create_group", "create_schedule"):
        tool = server_module.mcp._tool_manager.get_tool(name)
        assert "clear_fields" not in tool.parameters.get("properties", {}), name


_OVERLAY_TOOLS = frozenset({"update_group", "update_schedule", "postpone_schedule"})
"""The only 1.2 tools that read a row back and overlay onto it. Everything else either sends only
what it was given (the creates, the state tools) or requires every argument (the two new facts)."""


def test_every_maintenance_tool_that_overlays_has_a_clear_path_and_no_other_does() -> None:
    """The `clear_fields` audit closed over all seventeen, not just the three interesting ones.

    A nullable argument on an **overlay** tool is a value that can be set and never unset unless
    that tool publishes `clear_fields` — so the audit is: every overlay tool has one, and every tool
    that does not overlay does not, because it has nothing inherited to clear. A future tool that
    overlays without a clear path fails here rather than shipping a write-only field.
    """
    for name in MAINTENANCE_TOOLS:
        tool = server_module.mcp._tool_manager.get_tool(name)
        properties = tool.parameters.get("properties", {})
        has_clear = "clear_fields" in properties
        assert has_clear == (name in _OVERLAY_TOOLS), name
        if has_clear:
            # And its docstring says what "cleared" means, per field — the README's table is the
            # long form of the same promise.
            assert "clear_fields" in (getattr(server_module, name).__doc__ or ""), name


def test_the_schedule_audit_covers_every_clearable_field() -> None:
    """The parametrize list above and `_SCHEDULE_CLEARABLE_FIELDS` must be the same set — a field
    added to one and not the other is exactly the hole this audit exists to close (review S4)."""
    marker = next(
        m for m in test_every_clearable_schedule_field_clears_by_name.pytestmark
        if m.name == "parametrize"
    )
    covered = {row[0] for row in marker.args[1]}
    assert covered == set(server_module._SCHEDULE_CLEARABLE_FIELDS)


def test_every_clearable_name_an_overlay_publishes_is_one_of_its_own_arguments() -> None:
    """A `clear_fields` set naming something the tool has no argument for would be a name a caller
    could pass and nothing could act on."""
    for name, clearable in (
        ("update_group", server_module._GROUP_CLEARABLE_FIELDS),
        ("update_schedule", server_module._SCHEDULE_CLEARABLE_FIELDS),
        ("postpone_schedule", server_module._POSTPONE_CLEARABLE_FIELDS),
    ):
        tool = server_module.mcp._tool_manager.get_tool(name)
        arguments = set(tool.parameters.get("properties", {}))
        assert clearable <= arguments, (name, sorted(clearable - arguments))


def test_close_rounds_docstring_says_what_a_closure_does_not_claim() -> None:
    """Required content, not optional prose (the brief's Strings section)."""
    doc = server_module.close_round.__doc__ or ""
    assert "serviced" in doc
    assert "amended" in doc and "deleted" in doc


# --- the refusals a caller actually meets --------------------------------------------------------


def test_a_domain_refusal_arrives_as_a_tool_error_carrying_the_code(paired) -> None:
    paired.reply(
        "POST", "/v1/schedules/s1/close-round", 409,
        {"error": {"code": "OCCURRENCE_ALREADY_CLOSED", "message": "this round is already closed",
                   "problems": []}},
    )
    with pytest.raises(ToolError, match="OCCURRENCE_ALREADY_CLOSED"):
        server_module.close_round(schedule_id="s1", closed_on=None)


def test_a_validation_refusal_carries_its_problems(paired) -> None:
    paired.reply(
        "POST", "/v1/groups", 422,
        {"error": {"code": "MEMBER_ALREADY_OPEN", "message": "the group was refused",
                   "problems": ["MemberAlreadyOpen(assetId=a1)"]}},
    )
    with pytest.raises(ToolError, match="MemberAlreadyOpen") as raised:
        server_module.create_group(name="North run", members=[{"assetId": "a1"}])
    assert "MEMBER_ALREADY_OPEN" in str(raised.value)


def test_a_malformed_schedule_read_never_becomes_a_write(paired) -> None:
    """The invariant every overlay tool holds: a response that is not the shape this tool expects
    is a `ToolError`, not a PATCH built from a guess."""
    paired.reply("GET", "/v1/schedules/s1", 200, {"nope": 1})
    with pytest.raises(ToolError, match="schedule"):
        server_module.update_schedule(schedule_id="s1", title="x")
    assert len(paired.requests) == 1, "only the read happened"

    paired.reply("GET", "/v1/groups/g1", 200, {"group": {"name": "x", "description": "", "members": 7}})
    with pytest.raises(ToolError, match="members"):
        server_module.update_group(group_id="g1", name="y")

    paired.reply(
        "GET", "/v1/groups/g2", 200,
        {"group": {"name": "x", "description": "", "members": ["not an object"]}},
    )
    with pytest.raises(ToolError, match="not an object"):
        server_module.update_group(group_id="g2", name="y")
