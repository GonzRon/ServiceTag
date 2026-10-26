"""#125's three tools (U-C7; row h17): a schedule's own Home Assistant season over the "#125" block.

`get_schedule_season` reads `GET /v1/schedules/{id}/season-sync`; `link_schedule_season` and `unlink_schedule_season`
POST the same path. The confirmations are the caller's alone: the link sends nothing unless `confirm_entity_id` equals
`entity_id`, the unlink nothing unless `confirm_unlink` is the boolean `true`, and neither tool fills either. Each tool
refuses a phone below schema 22 with nothing sent, and neither write is re-sent on a dropped connection. No tool name
carries `sync` or `home_assistant` (#16's pin, `test_season_sync_tools.py`). Every name here is fictional.
"""

from __future__ import annotations

import asyncio
import inspect
import json

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import command_shapes
from servicetag_mcp import server as server_module

STATUS_22: dict = {
    "appVersion": "1.9.0", "apiVersion": 1, "schemaVersion": 22, "backupFormatVersion": 21, "counts": {},
}
STATUS_21: dict = dict(STATUS_22, schemaVersion=21, backupFormatVersion=20)

SCHEDULE_SEASON_TOOLS = ("get_schedule_season", "link_schedule_season", "unlink_schedule_season")
"""U-C7's three tools, the "#125" block, appended after the "#113/#114" block: the registered total is **104**."""

PATH = "/v1/schedules/s1/season-sync"
SWITCH = "input_boolean.example_heater_winterize_season"
NEVER_SAID = ("release", "authoriz", "permission to start", "safe")


def read(**overrides) -> dict:
    """The route's body for a fictional "Example season start" following its own switch, one START applied."""
    body = {
        "scheduleId": "s1",
        "seasonSource": "OWN",
        "seasonPhase": "IN_SEASON",
        "connection": {
            "configured": True, "needsToken": False, "cadence": "DAILY", "networkEligibility": "HOME_NETWORK_ONLY",
            "backgroundChecks": "OFF", "backgroundAllowed": False, "homeNetworkSet": True,
        },
        "binding": {
            "entityId": SWITCH, "mode": "FOLLOW", "enabled": True, "state": "ACTIVE",
            "observation": {"state": "ON", "haLastChanged": "2026-10-19T21:00:00+00:00"},
            "lastSuccessAt": 1792454400000, "lastAttemptAt": 1792454400000, "lastError": None,
            "lastApplied": {"action": "START", "occurredOn": "2026-10-20", "at": 1792454400000, "source": "HOME_ASSISTANT"},
        },
        "activations": [{"id": "r1", "action": "START", "occurredOn": "2026-10-20", "createdAt": 1792454400000}],
    }
    body.update(overrides)
    return body


def tool(name: str):
    return getattr(server_module, name)


def paths(api) -> list[tuple[str, str]]:
    return [(r.method, r.path) for r in api.requests]


def body_of(request) -> dict:
    return json.loads(request.body)


def flat(doc: str | None) -> str:
    return " ".join((doc or "").split())


def spy_requests(monkeypatch) -> list[tuple[str, str, dict]]:
    """Every `_call` the tools make, with its keyword arguments, still answered by the paired phone."""
    calls: list[tuple[str, str, dict]] = []
    real = server_module._call

    def spy(method, path, **kwargs):
        calls.append((method, path, kwargs))
        return real(method, path, **kwargs)

    monkeypatch.setattr(server_module, "_call", spy)
    return calls


@pytest.fixture
def phone22(paired):
    paired.reply("GET", "/v1/status", 200, STATUS_22)
    return paired


def test_the_three_tools_close_the_list_in_order_and_take_only_their_arguments() -> None:
    names = server_module.TOOL_NAMES
    assert names[-3:] == SCHEDULE_SEASON_TOOLS
    assert names.index("get_schedule_season") == names.index("acknowledge_ha_decommission") + 1
    assert len(names) == 104
    assert len(server_module.mcp._tool_manager.list_tools()) == 104
    for name in SCHEDULE_SEASON_TOOLS:
        registered = server_module.mcp._tool_manager.get_tool(name)
        assert registered is not None and registered.parameters.get("additionalProperties") is False, name
    assert list(inspect.signature(tool("get_schedule_season")).parameters) == ["schedule_id"]
    link = inspect.signature(tool("link_schedule_season")).parameters
    assert list(link) == ["schedule_id", "entity_id", "confirm_entity_id"]
    assert link["confirm_entity_id"].default is None, "the confirmation is the caller's, never filled"
    unlink = inspect.signature(tool("unlink_schedule_season")).parameters
    assert list(unlink) == ["schedule_id", "confirm_unlink"]
    assert unlink["confirm_unlink"].default is inspect.Parameter.empty, "never defaulted"
    schema = server_module.mcp._tool_manager.get_tool("unlink_schedule_season").parameters
    assert schema["required"] == ["schedule_id", "confirm_unlink"]
    assert schema["properties"]["confirm_unlink"]["type"] == "boolean"


def test_no_tool_name_carries_sync_or_home_assistant() -> None:
    for name in SCHEDULE_SEASON_TOOLS:
        assert "sync" not in name and "home_assistant" not in name, name
    named = {name for name in server_module.TOOL_NAMES if "sync" in name or "home_assistant" in name}
    assert named == {"get_season_sync"}, "#16's pin: one season sync tool, read only"


def test_the_read_reads_the_route_once_and_answers_as_sent(phone22) -> None:
    phone22.reply("GET", PATH, 200, read())
    assert tool("get_schedule_season")(schedule_id="s1") == read()
    assert paths(phone22) == [("GET", "/v1/status"), ("GET", PATH)]
    assert phone22.last().body == b""


def test_the_link_sends_exactly_the_callers_confirmation(phone22) -> None:
    phone22.reply("POST", PATH, 200, read(binding=None, activations=[], seasonPhase="OUT_OF_SEASON"))
    tool("link_schedule_season")(schedule_id="s1", entity_id=SWITCH, confirm_entity_id=SWITCH)
    assert paths(phone22) == [("GET", "/v1/status"), ("POST", PATH)]
    assert body_of(phone22.last()) == {"action": "LINK", "entityId": SWITCH, "confirmEntityId": SWITCH}
    assert list(body_of(phone22.last())) == ["action", "entityId", "confirmEntityId"]


def test_the_link_sends_nothing_unless_the_confirmation_equals_the_entity(paired) -> None:
    for confirmation in (None, "input_boolean.example_other_switch", ""):
        with pytest.raises(ToolError, match="nothing was sent"):
            tool("link_schedule_season")(schedule_id="s1", entity_id=SWITCH, confirm_entity_id=confirmation)
    # Through the server, as a client calls it: a missing confirmation is never filled from the entity.
    with pytest.raises(Exception):
        asyncio.run(server_module.mcp.call_tool("link_schedule_season", {"schedule_id": "s1", "entity_id": SWITCH}))
    assert paired.requests == []


def test_the_unlink_sends_exactly_the_callers_confirmation(phone22) -> None:
    phone22.reply("POST", PATH, 200, read(seasonSource="ASSET", binding=None))
    tool("unlink_schedule_season")(schedule_id="s1", confirm_unlink=True)
    assert body_of(phone22.last()) == {"action": "UNLINK", "confirmUnlink": True}
    assert list(body_of(phone22.last())) == ["action", "confirmUnlink"]


def test_the_unlink_sends_nothing_unless_its_argument_is_true(paired) -> None:
    for value in (False, None, "true", 1, "yes"):
        with pytest.raises(ToolError, match="nothing was sent"):
            tool("unlink_schedule_season")(schedule_id="s1", confirm_unlink=value)
    for arguments in (
        {"schedule_id": "s1"},
        {"schedule_id": "s1", "confirm_unlink": "true"},
        {"schedule_id": "s1", "confirm_unlink": 1},
        {"schedule_id": "s1", "confirm_unlink": False},
    ):
        with pytest.raises(Exception):
            asyncio.run(server_module.mcp.call_tool("unlink_schedule_season", arguments))
    assert paired.requests == []


def test_the_writes_are_never_retried_on_connect(phone22, monkeypatch) -> None:
    calls = spy_requests(monkeypatch)
    phone22.reply("POST", PATH, 200, read())
    tool("link_schedule_season")(schedule_id="s1", entity_id=SWITCH, confirm_entity_id=SWITCH)
    tool("unlink_schedule_season")(schedule_id="s1", confirm_unlink=True)
    writes = [kwargs for method, _, kwargs in calls if method == "POST"]
    assert len(writes) == 2
    assert all(kwargs.get("retry_on_connect") is False for kwargs in writes)


def test_a_refusal_reaches_the_caller_by_its_code_and_is_not_re_sent(phone22) -> None:
    phone22.reply(
        "POST", PATH, 409,
        {"error": {"code": "SCHEDULE_SEASON_REFUSED", "message": "a group's schedule follows no season of its own",
                   "problems": ["ScheduleSeasonRefused(reason=GROUP_TARGET)"], "field": None}},
    )
    with pytest.raises(ToolError, match="SCHEDULE_SEASON_REFUSED"):
        tool("link_schedule_season")(schedule_id="s1", entity_id=SWITCH, confirm_entity_id=SWITCH)
    assert paths(phone22).count(("POST", PATH)) == 1


@pytest.mark.parametrize("name", SCHEDULE_SEASON_TOOLS)
def test_a_schema_21_phone_is_refused_with_nothing_sent(paired, name) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_21)
    arguments = {
        "get_schedule_season": {"schedule_id": "s1"},
        "link_schedule_season": {"schedule_id": "s1", "entity_id": SWITCH, "confirm_entity_id": SWITCH},
        "unlink_schedule_season": {"schedule_id": "s1", "confirm_unlink": True},
    }[name]
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as refused:
        tool(name)(**arguments)
    assert f"{name} needs schema 22" in str(refused.value)
    assert set(paths(paired)) <= {("GET", "/v1/status")}, "the status read at most, nothing on the route"


@pytest.mark.parametrize("name", SCHEDULE_SEASON_TOOLS)
def test_an_empty_id_or_an_unknown_argument_is_refused_before_any_request(paired, name) -> None:
    arguments = {
        "get_schedule_season": {"schedule_id": "s1"},
        "link_schedule_season": {"schedule_id": "s1", "entity_id": SWITCH, "confirm_entity_id": SWITCH},
        "unlink_schedule_season": {"schedule_id": "s1", "confirm_unlink": True},
    }[name]
    with pytest.raises(ToolError, match="must not be empty"):
        tool(name)(**{**arguments, "schedule_id": ""})
    for extra in ({"mode": "FORCE_IN"}, {"token": "x"}, {"base_url": "http://192.168.0.10:8123"}):
        with pytest.raises(ToolError, match="does not accept"):
            asyncio.run(server_module.mcp.call_tool(name, {**arguments, **extra}))
    assert paired.requests == []


def test_the_docstrings_name_their_route_confirmation_and_codes() -> None:
    expectations = {
        "get_schedule_season": ("`GET /v1/schedules/{id}/season-sync`", "`seasonSource`", "`NO_SUCH_SCHEDULE`",
                                "Never the address", "frozen"),
        "link_schedule_season": ("`POST /v1/schedules/{id}/season-sync`", "Follow only", "never fills it",
                                 "`SCHEDULE_SEASON_CONFIRMATION_REQUIRED`", "`SCHEDULE_SEASON_REFUSED`", "`GROUP_TARGET`",
                                 "`STRANDS_PRE_SERVICE`", "never re-sent"),
        "unlink_schedule_season": ("`POST /v1/schedules/{id}/season-sync`", "kept as history", "never fills it",
                                   "`SCHEDULE_SEASON_CONFIRMATION_REQUIRED`", "never re-sent"),
    }
    for name, words in expectations.items():
        doc = flat(tool(name).__doc__)
        for word in words + ("`APP_SCHEMA_TOO_OLD`",):
            assert word in doc, (name, word)
        assert not any(word in doc.lower() for word in NEVER_SAID), (name, doc)


def test_the_shape_is_vendored() -> None:
    assert command_shapes.SCHEDULE_SEASON_KEYS == ("action", "entityId", "confirmEntityId", "confirmUnlink")
    assert not server_module._writes_nothing(PATH)
