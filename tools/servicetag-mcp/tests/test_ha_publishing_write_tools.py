"""#113/#114's write and discovery tools (C29b, C35b; row 121): A4w's half.

A4w appends two tools to the "#113/#114" block — `set_schedule_role`, the Season Start role over
`POST /v1/schedules/{id}/role`, and `list_ha_entities`, helper discovery over
`GET /v1/ha-connection/entities/{scope}` — and gives `update_schedule` the QUICK election flag. The election is the
caller's alone: each tool sends `seasonStartQuickElected` only when its argument is true, never by default (O-2,
O-7). Discovery checks its scope here, before any request, and takes the long read budget, since the phone reads
Home Assistant inside the call. B5w1 appends `enable_ha_publishing` and `change_ha_publishing_target` (C35b): each
POSTs only the plan without `plan_id` and `confirm_destination`, sends exactly `{kind, action, planId,
confirmDestination}` with both, sends nothing with one of them or a destination unequal to the plan's helper, and never
fills the confirmation itself. B5w2 appends its half below. Every name here is fictional.
"""

from __future__ import annotations

import asyncio
import inspect
import json

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import command_shapes
from servicetag_mcp import server as server_module

from conftest import schedule_row

STATUS_22: dict = {
    "appVersion": "1.9.0", "apiVersion": 1, "schemaVersion": 22, "backupFormatVersion": 21, "counts": {},
}
STATUS_21: dict = dict(STATUS_22, schemaVersion=21, backupFormatVersion=20)

A4W_TOOLS = ("set_schedule_role", "list_ha_entities")
"""A4w's two tools, in C29b's order, right after A4's readiness read."""

SCOPES = ("input_boolean", "input_select", "input_datetime")

NEVER_SAID = ("release", "authoriz", "permission to start", "safe")
"""Words never used for what ServiceTag publishes or reads (C1): Home Assistant decides, ServiceTag reports."""


def role_row(**overrides) -> dict:
    """A schedule row carrying the Season Start role (C29's two response keys)."""
    return schedule_row(**{"role": "SEASON_START", "roleSinceOn": "2026-01-15", **overrides})["schedule"]


def role_detail(**overrides) -> dict:
    """`GET /v1/schedules/{id}` for that row, inside the detail wrapper."""
    return schedule_row(**{"role": "SEASON_START", "roleSinceOn": "2026-01-15", **overrides})


def listed(scope: str = "input_select") -> dict:
    return {
        "scope": scope,
        "entities": [
            {
                "entityId": "input_select.example_heater_equipment_status", "friendlyName": "Example Heater status",
                "options": ["UP", "DEGRADED", "DOWN"], "hasDate": None, "hasTime": None,
            },
        ],
        "truncated": False,
    }


def paths(api) -> list[tuple[str, str]]:
    return [(r.method, r.path) for r in api.requests]


def body_of(recorded) -> dict:
    return json.loads(recorded.body)


def flat(text: str | None) -> str:
    return " ".join((text or "").split())


@pytest.fixture
def phone22(paired):
    paired.reply("GET", "/v1/status", 200, STATUS_22)
    return paired


def spy_requests(monkeypatch) -> list[tuple[str, str, dict]]:
    """Every `device.request` the tools make, with its keyword arguments, still sent for real."""
    calls: list[tuple[str, str, dict]] = []
    real = server_module.device.request

    def spy(method, path, **kwargs):
        calls.append((method, path, kwargs))
        return real(method, path, **kwargs)

    monkeypatch.setattr(server_module.device, "request", spy)
    return calls


# --- registration and order (C29b; D1) ---------------------------------------------------------------


def test_the_two_tools_follow_the_readiness_read_in_c29bs_order() -> None:
    names = server_module.TOOL_NAMES
    at = names.index("get_season_readiness")
    assert names[at + 1:at + 3] == A4W_TOOLS
    for name in A4W_TOOLS:
        tool = server_module.mcp._tool_manager.get_tool(name)
        assert tool is not None and tool.parameters.get("additionalProperties") is False, name


def test_the_signatures_take_no_value_date_to_publish_or_ready() -> None:
    assert list(inspect.signature(server_module.set_schedule_role).parameters) == [
        "schedule_id", "role", "since_on", "season_start_quick_elected",
    ]
    assert list(inspect.signature(server_module.list_ha_entities).parameters) == ["scope"]
    assert inspect.signature(server_module.set_schedule_role).parameters["season_start_quick_elected"].default is None
    assert "season_start_quick_elected" in inspect.signature(server_module.update_schedule).parameters
    assert inspect.signature(server_module.update_schedule).parameters["season_start_quick_elected"].default is None


# --- set_schedule_role (C10, C10a, C29b; O-7) ---------------------------------------------------------


def test_set_schedule_role_posts_the_role_alone_by_default(phone22) -> None:
    phone22.reply("POST", "/v1/schedules/s1/role", 200, {"schedule": role_row()})
    answer = server_module.set_schedule_role(schedule_id="s1", role="SEASON_START")
    assert answer == {"schedule": role_row()}
    assert paths(phone22) == [("GET", "/v1/status"), ("POST", "/v1/schedules/s1/role")]
    assert body_of(phone22.last()) == {"role": "SEASON_START"}
    assert {k.lower(): v for k, v in phone22.last().headers.items()}.get("content-type") == "application/json"


def test_set_schedule_role_sends_the_boundary_and_the_election_only_when_given(phone22) -> None:
    server_module.set_schedule_role(schedule_id="s1", role="SEASON_START", since_on="2026-01-15")
    assert body_of(phone22.last()) == {"role": "SEASON_START", "sinceOn": "2026-01-15"}
    server_module.set_schedule_role(
        schedule_id="s1", role="SEASON_START", since_on="2026-01-15", season_start_quick_elected=True,
    )
    assert body_of(phone22.last()) == {"role": "SEASON_START", "sinceOn": "2026-01-15", "seasonStartQuickElected": True}
    for not_elected in (False, None):
        server_module.set_schedule_role(schedule_id="s1", role="SEASON_START", season_start_quick_elected=not_elected)
        assert body_of(phone22.last()) == {"role": "SEASON_START"}, not_elected
    server_module.set_schedule_role(schedule_id="s1", role="NONE")
    assert body_of(phone22.last()) == {"role": "NONE"}


def test_set_schedule_role_is_never_retried_on_connect(phone22, monkeypatch) -> None:
    calls = spy_requests(monkeypatch)
    server_module.set_schedule_role(schedule_id="s1", role="NONE")
    writes = [kwargs for method, path, kwargs in calls if path == "/v1/schedules/s1/role"]
    assert len(writes) == 1 and writes[0].get("retry_on_connect") is False


def test_a_refusal_reaches_the_caller_by_its_code(phone22) -> None:
    phone22.reply(
        "POST", "/v1/schedules/s1/role", 409,
        {"error": {"code": "SEASON_START_REFUSED", "message": "elect one-tap completions",
                   "problems": ["SeasonStartRefused(reason=QUICK_NOT_ELECTED)"], "field": None}},
    )
    with pytest.raises(ToolError, match="SEASON_START_REFUSED.*QUICK_NOT_ELECTED"):
        server_module.set_schedule_role(schedule_id="s1", role="SEASON_START")


def test_set_schedule_role_refuses_a_schema_21_phone_with_nothing_sent(paired) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_21)
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as refused:
        server_module.set_schedule_role(schedule_id="s1", role="SEASON_START")
    assert "set_schedule_role needs schema 22" in str(refused.value)
    assert paths(paired) == [("GET", "/v1/status")]


def test_set_schedule_role_refuses_an_empty_id_or_an_unknown_argument_before_any_request(paired) -> None:
    with pytest.raises(ToolError, match="must not be empty"):
        server_module.set_schedule_role(schedule_id="", role="NONE")
    for extra in ({"ready_for_season": True}, {"season_service_done_on": "2026-02-10"}, {"value": "2026-02-10"}):
        with pytest.raises(ToolError, match="does not accept"):
            asyncio.run(server_module.mcp.call_tool("set_schedule_role", {"schedule_id": "s1", "role": "NONE", **extra}))
    assert paired.requests == []


def test_set_schedule_role_documents_the_codes_and_the_election() -> None:
    doc = flat(server_module.set_schedule_role.__doc__)
    for code in ("SEASON_START_REFUSED", "QUICK_NOT_ELECTED", "SEASON_START_DATE_INVALID", "NO_SUCH_SCHEDULE"):
        assert f"`{code}`" in doc, code
    assert "season_start_quick_elected" in doc and "never" in doc
    assert "a boundary move included" in doc
    assert "APP_SCHEMA_TOO_OLD" in doc
    lowered = doc.lower()
    assert not any(word in lowered for word in NEVER_SAID), doc


# --- update_schedule's flag (C11, C29b) ----------------------------------------------------------------


def test_update_schedule_sends_the_election_only_when_true(phone22) -> None:
    phone22.reply("GET", "/v1/schedules/s1", 200, role_detail(completionMode="FORM", profileId="p1"))
    server_module.update_schedule(schedule_id="s1", completion_mode="QUICK", season_start_quick_elected=True)
    body = body_of(phone22.last())
    assert phone22.last().method == "PATCH"
    assert body["seasonStartQuickElected"] is True and body["completionMode"] == "QUICK"
    assert "role" not in body and "roleSinceOn" not in body
    for not_elected in (False, None):
        server_module.update_schedule(schedule_id="s1", completion_mode="QUICK", season_start_quick_elected=not_elected)
        assert "seasonStartQuickElected" not in body_of(phone22.last()), not_elected


def test_update_schedule_refuses_the_election_on_a_schema_21_phone_with_nothing_sent(paired) -> None:
    # A4w review M4: the flag is #113/#114's, so a True election is gated at schema 22 before the read; without it
    # the tool reaches a schema-21 phone exactly as it always did.
    paired.reply("GET", "/v1/status", 200, STATUS_21)
    paired.reply("GET", "/v1/schedules/s1", 200, role_detail(completionMode="FORM", profileId="p1"))
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as refused:
        server_module.update_schedule(schedule_id="s1", completion_mode="QUICK", season_start_quick_elected=True)
    assert "update_schedule needs schema 22" in str(refused.value)
    assert paths(paired) == [("GET", "/v1/status")]
    for not_elected in (False, None):
        paired.requests.clear()
        server_module.update_schedule(schedule_id="s1", title="Example annual service", season_start_quick_elected=not_elected)
        assert ("PATCH", "/v1/schedules/s1") in paths(paired), not_elected


def test_update_schedule_names_the_flag_its_strand_asks_for() -> None:
    doc = flat(server_module.update_schedule.__doc__)
    assert "season_start_quick_elected=True" in doc
    assert "`seasonStartQuickElected: true`" in doc
    assert "`QUICK_NEEDS_ELECTION`" in doc
    assert "schema 22 or later" in doc


def test_the_flag_is_a_vendored_schedule_action_flag() -> None:
    assert server_module._SEASON_START_QUICK_ELECTED in command_shapes.SCHEDULE_ACTION_FLAGS
    assert command_shapes.SCHEDULE_ROLE_KEYS == ("role", "sinceOn", "seasonStartQuickElected")


# --- list_ha_entities (C29b; I33) -----------------------------------------------------------------------


@pytest.mark.parametrize("scope", SCOPES)
def test_list_ha_entities_reads_its_scope_once_and_answers_as_sent(phone22, scope) -> None:
    phone22.reply("GET", f"/v1/ha-connection/entities/{scope}", 200, listed(scope))
    assert server_module.list_ha_entities(scope=scope) == listed(scope)
    assert paths(phone22) == [("GET", "/v1/status"), ("GET", f"/v1/ha-connection/entities/{scope}")]
    assert phone22.last().body == b""


@pytest.mark.parametrize("scope", ["switch", "input_text", "INPUT_SELECT", "input_select/x", "", "../status"])
def test_list_ha_entities_refuses_any_other_scope_before_any_request(paired, scope) -> None:
    with pytest.raises(ToolError, match="input_boolean, input_select or input_datetime"):
        server_module.list_ha_entities(scope=scope)
    assert paired.requests == []


def test_list_ha_entities_takes_the_long_read_budget(phone22, monkeypatch) -> None:
    calls = spy_requests(monkeypatch)
    server_module.list_ha_entities(scope="input_datetime")
    reads = [kwargs for method, path, kwargs in calls if path == "/v1/ha-connection/entities/input_datetime"]
    assert len(reads) == 1
    assert reads[0].get("timeout") == httpx.Timeout(30.0, read=120.0)
    assert server_module._HA_READ_TIMEOUT == httpx.Timeout(30.0, read=120.0)


def test_list_ha_entities_refuses_a_schema_21_phone_with_nothing_sent(paired) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_21)
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as refused:
        server_module.list_ha_entities(scope="input_select")
    assert "list_ha_entities needs schema 22" in str(refused.value)
    assert paths(paired) == [("GET", "/v1/status")]


def test_a_failed_read_reaches_the_caller_by_its_code(phone22) -> None:
    phone22.reply(
        "GET", "/v1/ha-connection/entities/input_select", 409,
        {"error": {"code": "HA_HELPER_READ_FAILED", "message": "Home Assistant could not be read: NEEDS_TOKEN",
                   "problems": ["HelperReadFailed(errorKind=NEEDS_TOKEN)"], "field": None}},
    )
    with pytest.raises(ToolError, match="HA_HELPER_READ_FAILED.*NEEDS_TOKEN"):
        server_module.list_ha_entities(scope="input_select")


def test_list_ha_entities_documents_its_shape_and_codes() -> None:
    doc = flat(server_module.list_ha_entities.__doc__)
    for word in ("`options`", "`hasDate`", "`hasTime`", "`truncated`", "`HA_PUBLISH_REFUSED`", "`NO_CONNECTION`",
                 "`HA_HELPER_READ_FAILED`", "`APP_SCHEMA_TOO_OLD`"):
        assert word in doc, word
    assert "never cached" in doc.lower() or "nothing is kept" in doc.lower()
    lowered = doc.lower()
    assert not any(word in lowered for word in NEVER_SAID), doc


# --- the two restore causes (C11 erratum; carried to A4w) ------------------------------------------------


@pytest.mark.parametrize("name", ["archive_schedule", "archive_group"])
def test_a_restore_names_the_strand_and_its_two_causes(name) -> None:
    doc = flat(getattr(server_module, name).__doc__)
    assert "`SEASON_START_STRANDED` (409)" in doc, name
    assert "`RESTORE_NOT_ELIGIBLE`" in doc and "`RESTORE_ALREADY_COVERED`" in doc, name


# --- B5w1: enable_ha_publishing and change_ha_publishing_target (C35b, C25a; O-21; row 121) --------------------------

B5W1_TOOLS = (("enable_ha_publishing", "ENABLE"), ("change_ha_publishing_target", "CHANGE_TARGET"))
"""B5w1's two tools, in C29a's order, each with the action it plans and commits."""

STATUS_HELPER = "input_select.example_heater_equipment_status"
DATE_HELPER = "input_datetime.example_heater_service_done"
ENDED_HELPER = "input_datetime.example_heater_season_ended"
PLAN_ID = "a" * 64
PLAN_PATH = "/v1/assets/a1/ha-publishing-plan"
ACTION_PATH = "/v1/assets/a1/ha-publishing"


def a_plan(action: str = "ENABLE") -> dict:
    return {
        "planId": PLAN_ID, "assetId": "a1", "kind": "EQUIPMENT_STATUS", "action": action, "entityId": STATUS_HELPER,
        "boundaryEntityId": None, "boundaryRequired": False,
        "remote": {"domain": "input_select", "options": ["UP", "DEGRADED", "DOWN"], "hasDate": None, "hasTime": None},
        "boundary": None, "mapping": {"OPERATIONAL": "UP", "DEGRADED": "DEGRADED", "DOWN": "DOWN"}, "refusal": None,
        "readAt": 1000, "expiresAt": 601000,
    }


def tool(name: str):
    return getattr(server_module, name)


def test_b5w1s_two_tools_follow_the_collection_read_in_order() -> None:
    names = server_module.TOOL_NAMES
    at = names.index("list_ha_publishing")
    assert names[at + 1:at + 3] == tuple(name for name, _ in B5W1_TOOLS)
    for name, _ in B5W1_TOOLS:
        registered = server_module.mcp._tool_manager.get_tool(name)
        assert registered is not None and registered.parameters.get("additionalProperties") is False, name
        assert list(inspect.signature(tool(name)).parameters) == [
            "asset_id", "kind", "entity_id", "boundary_entity_id", "plan_id", "confirm_destination",
        ], name
        for optional in ("boundary_entity_id", "plan_id", "confirm_destination"):
            assert inspect.signature(tool(name)).parameters[optional].default is None, (name, optional)


@pytest.mark.parametrize(("name", "action"), B5W1_TOOLS)
def test_without_the_plan_id_and_confirmation_it_posts_only_the_plan(phone22, name, action) -> None:
    phone22.reply("POST", PLAN_PATH, 200, a_plan(action))
    answer = tool(name)(asset_id="a1", kind="EQUIPMENT_STATUS", entity_id=STATUS_HELPER)
    assert answer == a_plan(action)
    assert paths(phone22) == [("GET", "/v1/status"), ("POST", PLAN_PATH)]
    assert body_of(phone22.last()) == {
        "kind": "EQUIPMENT_STATUS", "action": action, "entityId": STATUS_HELPER, "boundaryEntityId": None,
    }
    assert list(body_of(phone22.last())) == list(command_shapes.HA_PUBLISHING_PLAN_KEYS)
    tool(name)(asset_id="a1", kind="SEASON_SERVICE_DATE", entity_id=DATE_HELPER, boundary_entity_id=ENDED_HELPER)
    assert body_of(phone22.last()) == {
        "kind": "SEASON_SERVICE_DATE", "action": action, "entityId": DATE_HELPER, "boundaryEntityId": ENDED_HELPER,
    }


@pytest.mark.parametrize(("name", "action"), B5W1_TOOLS)
def test_with_both_it_sends_exactly_the_action(phone22, name, action) -> None:
    phone22.reply("POST", ACTION_PATH, 200, {"binding": {"kind": "EQUIPMENT_STATUS", "entityId": STATUS_HELPER}})
    answer = tool(name)(
        asset_id="a1", kind="EQUIPMENT_STATUS", entity_id=STATUS_HELPER, plan_id=PLAN_ID, confirm_destination=STATUS_HELPER,
    )
    assert answer == {"binding": {"kind": "EQUIPMENT_STATUS", "entityId": STATUS_HELPER}}
    assert paths(phone22) == [("GET", "/v1/status"), ("POST", ACTION_PATH)]
    assert body_of(phone22.last()) == {
        "kind": "EQUIPMENT_STATUS", "action": action, "planId": PLAN_ID, "confirmDestination": STATUS_HELPER,
    }
    assert list(body_of(phone22.last())) == list(command_shapes.HA_PUBLISHING_ACTION_KEYS)


@pytest.mark.parametrize(("name", "action"), B5W1_TOOLS)
def test_with_one_of_them_or_another_destination_it_sends_nothing(paired, name, action) -> None:
    for partial in (
        {"plan_id": PLAN_ID},
        {"confirm_destination": STATUS_HELPER},
        {"plan_id": PLAN_ID, "confirm_destination": DATE_HELPER},
        {"plan_id": PLAN_ID, "confirm_destination": STATUS_HELPER.upper()},
        {"plan_id": PLAN_ID, "confirm_destination": ""},
    ):
        with pytest.raises(ToolError, match="nothing was sent"):
            tool(name)(asset_id="a1", kind="EQUIPMENT_STATUS", entity_id=STATUS_HELPER, **partial)
    assert paired.requests == []


@pytest.mark.parametrize(("name", "action"), B5W1_TOOLS)
def test_the_action_is_never_retried_and_only_the_plan_takes_the_long_read_budget(phone22, monkeypatch, name, action) -> None:
    calls = spy_requests(monkeypatch)
    tool(name)(asset_id="a1", kind="EQUIPMENT_STATUS", entity_id=STATUS_HELPER)
    tool(name)(
        asset_id="a1", kind="EQUIPMENT_STATUS", entity_id=STATUS_HELPER, plan_id=PLAN_ID, confirm_destination=STATUS_HELPER,
    )
    plans = [kwargs for method, path, kwargs in calls if path == PLAN_PATH]
    actions = [kwargs for method, path, kwargs in calls if path == ACTION_PATH]
    assert len(plans) == 1 and plans[0].get("timeout") == httpx.Timeout(30.0, read=120.0)
    assert len(actions) == 1 and actions[0].get("retry_on_connect") is False
    assert actions[0].get("timeout") is None


@pytest.mark.parametrize(("name", "action"), B5W1_TOOLS)
def test_a_schema_21_phone_is_refused_with_nothing_sent(paired, name, action) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_21)
    for confirmation in ({}, {"plan_id": PLAN_ID, "confirm_destination": STATUS_HELPER}):
        paired.requests.clear()
        with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as refused:
            tool(name)(asset_id="a1", kind="EQUIPMENT_STATUS", entity_id=STATUS_HELPER, **confirmation)
        assert f"{name} needs schema 22" in str(refused.value)
        assert set(paths(paired)) <= {("GET", "/v1/status")}, "the status read at most, and no POST"


@pytest.mark.parametrize(("name", "action"), B5W1_TOOLS)
def test_the_refusals_reach_the_caller_by_their_codes(phone22, name, action) -> None:
    for status, code, problem in (
        (409, "STALE_PLAN", "PublishRefused(reason=STALE_PLAN)"),
        (422, "HA_PUBLISH_CONFIRMATION_REQUIRED", "PublishConfirmationRequired(field=confirmDestination)"),
        (409, "HA_PUBLISH_REFUSED", "PublishRefused(reason=TARGET_IN_USE, holderAssetId=a2)"),
    ):
        phone22.reply("POST", ACTION_PATH, status, {"error": {"code": code, "message": "m", "problems": [problem], "field": None}})
        with pytest.raises(ToolError, match=code):
            tool(name)(
                asset_id="a1", kind="EQUIPMENT_STATUS", entity_id=STATUS_HELPER, plan_id=PLAN_ID,
                confirm_destination=STATUS_HELPER,
            )


@pytest.mark.parametrize(("name", "action"), B5W1_TOOLS)
def test_no_argument_takes_a_value_and_an_empty_id_is_refused(paired, name, action) -> None:
    with pytest.raises(ToolError, match="must not be empty"):
        tool(name)(asset_id="", kind="EQUIPMENT_STATUS", entity_id=STATUS_HELPER)
    for extra in ({"value": "UP"}, {"date": "2026-02-10"}, {"ready_for_season": True}):
        with pytest.raises(ToolError, match="does not accept"):
            asyncio.run(server_module.mcp.call_tool(
                name, {"asset_id": "a1", "kind": "EQUIPMENT_STATUS", "entity_id": STATUS_HELPER, **extra},
            ))
    assert paired.requests == []


@pytest.mark.parametrize(("name", "action"), B5W1_TOOLS)
def test_the_docstring_names_the_two_steps_and_the_codes(name, action) -> None:
    doc = flat(tool(name).__doc__)
    for word in ("`POST /v1/assets/{id}/ha-publishing-plan`", "`POST /v1/assets/{id}/ha-publishing`", "`planId`",
                 "`STALE_PLAN`", "`HA_PUBLISH_CONFIRMATION_REQUIRED`", "`HA_PUBLISH_REFUSED`", "`APP_SCHEMA_TOO_OLD`",
                 "`no_such_asset`", f"`{action}`", "confirm_destination", "never fills"):
        assert word in doc, (name, word)
    assert "the phone may set" in doc, name
    lowered = doc.lower()
    assert not any(word in lowered for word in NEVER_SAID), doc


def test_writes_nothing_matches_the_plan_shape_and_the_two_shipped_literals() -> None:
    assert "/v1/assets/{id}/ha-publishing-plan" in server_module._POSTS_THAT_WRITE_NOTHING
    for path in ("/v1/import-merge/plan", "/v1/repairs/schedule-providers/plan", "/v1/assets/a1/ha-publishing-plan",
                 "/v1/assets/example%2Fheater/ha-publishing-plan"):
        assert server_module._writes_nothing(path), path
    for path in ("/v1/assets/a1/ha-publishing", "/v1/assets//ha-publishing-plan", "/v1/assets/a/b/ha-publishing-plan",
                 "/v1/import-merge/apply", "/v1/repairs/schedule-providers/apply", "/v1/assets/{id}/ha-publishing"):
        assert not server_module._writes_nothing(path), path


def test_the_two_shapes_are_vendored() -> None:
    assert command_shapes.HA_PUBLISHING_PLAN_KEYS == ("kind", "action", "entityId", "boundaryEntityId")
    assert command_shapes.HA_PUBLISHING_ACTION_KEYS == ("kind", "action", "planId", "confirmDestination")


# --- B5w2: Disable, Check helper, Publish now and the acknowledgement (C35b, O-21(b); row 121) -------------------------

B5W2_TOOLS = ("disable_ha_publishing", "check_ha_publishing_helper", "publish_ha_now", "acknowledge_ha_decommission")
"""B5w2's four tools, in C29a's order, after B5w1's two."""

STORED_CONTROLS = (("disable_ha_publishing", "DISABLE"), ("check_ha_publishing_helper", "CHECK_HELPER"))
PUBLISH_NOW_PATH = "/v1/assets/a1/ha-publishing/publish-now"
ACK_PATH = "/v1/ha-publishing/decommissions/t9/acknowledge"


def accepted() -> dict:
    return {"requestId": "r1", "acceptedAt": 1000, "bindings": [{"kind": "SEASON_SERVICE_DATE", "state": "PENDING"}]}


def test_b5w2s_four_tools_follow_b5w1s_in_order() -> None:
    names = server_module.TOOL_NAMES
    at = names.index("change_ha_publishing_target")
    assert names[at + 1:at + 5] == B5W2_TOOLS
    assert names[-13:-9] == B5W2_TOOLS, "#125's three schedule season tools and #129's six season link tools follow"
    assert len(names) == 110
    for name in B5W2_TOOLS:
        registered = server_module.mcp._tool_manager.get_tool(name)
        assert registered is not None and registered.parameters.get("additionalProperties") is False, name
    assert list(inspect.signature(tool("disable_ha_publishing")).parameters) == ["asset_id", "kind"]
    assert list(inspect.signature(tool("check_ha_publishing_helper")).parameters) == ["asset_id", "kind"]
    assert list(inspect.signature(tool("publish_ha_now")).parameters) == ["asset_id"]
    acknowledge = inspect.signature(tool("acknowledge_ha_decommission")).parameters
    assert list(acknowledge) == ["decommission_id", "acknowledged_cleared_in_ha"]
    assert all(p.default is inspect.Parameter.empty for p in acknowledge.values()), "neither is defaulted"
    schema = server_module.mcp._tool_manager.get_tool("acknowledge_ha_decommission").parameters
    assert schema["required"] == ["decommission_id", "acknowledged_cleared_in_ha"]
    assert schema["properties"]["acknowledged_cleared_in_ha"]["type"] == "boolean"


@pytest.mark.parametrize(("name", "action"), STORED_CONTROLS)
def test_a_stored_control_sends_exactly_kind_and_action(phone22, name, action) -> None:
    phone22.reply("POST", ACTION_PATH, 200, {"binding": {"kind": "EQUIPMENT_STATUS", "entityId": STATUS_HELPER}})
    answer = tool(name)(asset_id="a1", kind="EQUIPMENT_STATUS")
    assert answer == {"binding": {"kind": "EQUIPMENT_STATUS", "entityId": STATUS_HELPER}}
    assert paths(phone22) == [("GET", "/v1/status"), ("POST", ACTION_PATH)]
    assert body_of(phone22.last()) == {"kind": "EQUIPMENT_STATUS", "action": action}
    assert set(body_of(phone22.last())) <= set(command_shapes.HA_PUBLISHING_ACTION_KEYS)


def test_disable_of_nothing_answers_the_null_binding(phone22) -> None:
    phone22.reply("POST", ACTION_PATH, 200, {"binding": None})
    assert tool("disable_ha_publishing")(asset_id="a1", kind="SEASON_SERVICE_DATE") == {"binding": None}


def test_the_writes_are_never_retried_and_only_check_helper_takes_the_long_read_budget(phone22, monkeypatch) -> None:
    calls = spy_requests(monkeypatch)
    phone22.reply("POST", PUBLISH_NOW_PATH, 202, accepted())
    phone22.reply("POST", ACK_PATH, 204, b"")
    tool("disable_ha_publishing")(asset_id="a1", kind="SEASON_SERVICE_DATE")
    tool("check_ha_publishing_helper")(asset_id="a1", kind="SEASON_SERVICE_DATE")
    tool("publish_ha_now")(asset_id="a1")
    tool("acknowledge_ha_decommission")(decommission_id="t9", acknowledged_cleared_in_ha=True)
    writes = [(path, kwargs) for method, path, kwargs in calls if method == "POST"]
    assert [path for path, _ in writes] == [ACTION_PATH, ACTION_PATH, PUBLISH_NOW_PATH, ACK_PATH]
    for path, kwargs in writes:
        assert kwargs.get("retry_on_connect") is False, path
    assert [kwargs.get("timeout") for _, kwargs in writes] == [None, httpx.Timeout(30.0, read=120.0), None, None]


def test_publish_ha_now_sends_an_empty_object_and_returns_the_202_body(phone22) -> None:
    phone22.reply("POST", PUBLISH_NOW_PATH, 202, accepted())
    assert tool("publish_ha_now")(asset_id="a1") == accepted()
    assert paths(phone22) == [("GET", "/v1/status"), ("POST", PUBLISH_NOW_PATH)]
    assert body_of(phone22.last()) == {}


def test_the_acknowledgement_sends_exactly_the_callers_attestation(phone22) -> None:
    phone22.reply("POST", ACK_PATH, 204, b"")
    assert tool("acknowledge_ha_decommission")(decommission_id="t9", acknowledged_cleared_in_ha=True) == {}
    assert paths(phone22) == [("GET", "/v1/status"), ("POST", ACK_PATH)]
    assert body_of(phone22.last()) == {"decommissionId": "t9", "acknowledgedClearedInHa": True}
    assert list(body_of(phone22.last())) == list(command_shapes.HA_PUBLISHING_ACKNOWLEDGEMENT_KEYS)


def test_the_acknowledgement_sends_nothing_unless_its_argument_is_true(paired) -> None:
    for value in (False, None, "true", 1, "yes"):
        with pytest.raises(ToolError, match="nothing was sent"):
            tool("acknowledge_ha_decommission")(decommission_id="t9", acknowledged_cleared_in_ha=value)
    # Through the server, as a client calls it: a string or a number is not the boolean `true`, and a missing
    # argument is refused before the tool runs — nothing infers or defaults it.
    for arguments in (
        {"decommission_id": "t9"},
        {"decommission_id": "t9", "acknowledged_cleared_in_ha": "true"},
        {"decommission_id": "t9", "acknowledged_cleared_in_ha": 1},
        {"decommission_id": "t9", "acknowledged_cleared_in_ha": False},
    ):
        with pytest.raises(Exception):
            asyncio.run(server_module.mcp.call_tool("acknowledge_ha_decommission", arguments))
    assert paired.requests == []


def test_a_refusal_reaches_the_caller_by_its_code_and_is_not_re_sent(phone22) -> None:
    for path, status, code, call in (
        (ACTION_PATH, 409, "HA_HELPER_READ_FAILED",
         lambda: tool("check_ha_publishing_helper")(asset_id="a1", kind="EQUIPMENT_STATUS")),
        (ACTION_PATH, 409, "HA_PUBLISH_REFUSED", lambda: tool("disable_ha_publishing")(asset_id="a1", kind="X")),
        (PUBLISH_NOW_PATH, 404, "no_such_asset", lambda: tool("publish_ha_now")(asset_id="a1")),
        (ACK_PATH, 404, "NO_SUCH_DECOMMISSION",
         lambda: tool("acknowledge_ha_decommission")(decommission_id="t9", acknowledged_cleared_in_ha=True)),
        (ACK_PATH, 409, "DECOMMISSION_STILL_PENDING",
         lambda: tool("acknowledge_ha_decommission")(decommission_id="t9", acknowledged_cleared_in_ha=True)),
    ):
        phone22.reply("POST", path, status, {"error": {"code": code, "message": "m", "problems": [], "field": None}})
        before = len([r for r in phone22.requests if r.method == "POST"])
        with pytest.raises(ToolError, match=code):
            call()
        assert len([r for r in phone22.requests if r.method == "POST"]) == before + 1, code


@pytest.mark.parametrize("name", B5W2_TOOLS)
def test_a_schema_21_phone_is_refused_with_nothing_posted(paired, name) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_21)
    arguments = {
        "disable_ha_publishing": {"asset_id": "a1", "kind": "EQUIPMENT_STATUS"},
        "check_ha_publishing_helper": {"asset_id": "a1", "kind": "EQUIPMENT_STATUS"},
        "publish_ha_now": {"asset_id": "a1"},
        "acknowledge_ha_decommission": {"decommission_id": "t9", "acknowledged_cleared_in_ha": True},
    }[name]
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as refused:
        tool(name)(**arguments)
    assert f"{name} needs schema 22" in str(refused.value)
    assert set(paths(paired)) <= {("GET", "/v1/status")}, "the status read at most, and no POST"


@pytest.mark.parametrize("name", B5W2_TOOLS)
def test_no_argument_takes_a_value_and_an_empty_id_is_refused(paired, name) -> None:
    arguments = {
        "disable_ha_publishing": {"asset_id": "a1", "kind": "EQUIPMENT_STATUS"},
        "check_ha_publishing_helper": {"asset_id": "a1", "kind": "EQUIPMENT_STATUS"},
        "publish_ha_now": {"asset_id": "a1"},
        "acknowledge_ha_decommission": {"decommission_id": "t9", "acknowledged_cleared_in_ha": True},
    }[name]
    empty = {key: ("" if key.endswith("_id") else value) for key, value in arguments.items()}
    with pytest.raises(ToolError, match="must not be empty"):
        tool(name)(**empty)
    for extra in ({"value": "UP"}, {"date": "2026-02-10"}, {"ready_for_season": True}, {"plan_id": PLAN_ID}):
        with pytest.raises(ToolError, match="does not accept"):
            asyncio.run(server_module.mcp.call_tool(name, {**arguments, **extra}))
    assert paired.requests == []


def test_the_docstrings_name_their_routes_codes_and_the_poll() -> None:
    expectations = {
        "disable_ha_publishing": ("`POST /v1/assets/{id}/ha-publishing`", 'action: "DISABLE"', "1970-01-01",
                                  "`HA_PUBLISH_CONFIRMATION_REQUIRED`", "`no_such_asset`", "reads nothing"),
        "check_ha_publishing_helper": ('action: "CHECK_HELPER"', "**stored**", "`HA_HELPER_READ_FAILED`", "`BAD_ENTITY_ID`",
                                       "`field` `kind`", "never switches a publication on", "the phone may set"),
        "publish_ha_now": ("`POST /v1/assets/{id}/ha-publishing/publish-now`", "**202**", "`get_ha_publishing`",
                           "`ACCEPTED`", "`PUBLISHED`", "`VERIFIED`", "`FAILED`", "ten minutes", "`requestId`"),
        "acknowledge_ha_decommission": ("`POST /v1/ha-publishing/decommissions/{id}/acknowledge`", "`UNDELIVERABLE`",
                                        "`NO_SUCH_DECOMMISSION`", "`DECOMMISSION_STILL_PENDING`", "never default it",
                                        "never set it on a retry", "Never infer it from an error",
                                        "an orphaned date helper may still permit a start", "never fills it"),
    }
    for name, words in expectations.items():
        doc = flat(tool(name).__doc__)
        for word in words + ("`APP_SCHEMA_TOO_OLD`",):
            assert word in doc, (name, word)
        lowered = doc.lower()
        assert not any(word in lowered for word in NEVER_SAID), (name, doc)


def test_the_acknowledgement_shape_is_vendored() -> None:
    assert command_shapes.HA_PUBLISHING_ACKNOWLEDGEMENT_KEYS == ("decommissionId", "acknowledgedClearedInHa")
    assert not server_module._writes_nothing("/v1/assets/a1/ha-publishing/publish-now")
    assert not server_module._writes_nothing(ACK_PATH)
