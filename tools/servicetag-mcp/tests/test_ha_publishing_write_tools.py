"""#113/#114's write and discovery tools (C29b, C35b; row 121): A4w's half.

A4w appends two tools to the "#113/#114" block — `set_schedule_role`, the Season Start role over
`POST /v1/schedules/{id}/role`, and `list_ha_entities`, helper discovery over
`GET /v1/ha-connection/entities/{scope}` — and gives `update_schedule` the QUICK election flag. The election is the
caller's alone: each tool sends `seasonStartQuickElected` only when its argument is true, never by default (O-2,
O-7). Discovery checks its scope here, before any request, and takes the long read budget, since the phone reads
Home Assistant inside the call. B5w1 and B5w2 append their halves below. Every name here is fictional.
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
