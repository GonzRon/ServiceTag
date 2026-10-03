"""#16's one season sync tool (C25; rows 64–65): `get_season_sync` reads `GET /v1/assets/{id}/season-sync` and answers
its body as sent, behind a schema-21 gate.

On the phone an asset can follow one Home Assistant on/off entity; the route shows that link's non-secret state —
the connection's settings, the binding, its status and its last change — and never the address, the home Wi-Fi's
name or the token. The tool is read only: nothing here links, edits, stops or resumes a binding, sets the
connection, or asks the phone to check. A phone below schema 21 has no such route, so the tool refuses it by name,
with nothing sent beyond the pairing's one `/v1/status` read (G2's feature name). The three shipped season writes
say what a synced asset answers them.
"""

from __future__ import annotations

import asyncio
import inspect

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import server as server_module

STATUS_21: dict = {
    "appVersion": "1.6.0", "apiVersion": 1, "schemaVersion": 21, "backupFormatVersion": 20, "counts": {},
}
STATUS_20: dict = dict(STATUS_21, schemaVersion=20)

SEASON_SYNC_TOOLS = ("get_season_sync",)
"""C25's one tool, the #16 block, appended last (R16-17). The registered total is **90** since #16, which
`test_argument_guard.py` pins."""

CONNECTION_KEYS = {
    "configured", "needsToken", "cadence", "networkEligibility", "backgroundChecks", "backgroundAllowed",
    "homeNetworkSet",
}
BINDING_KEYS = {
    "entityId", "mode", "enabled", "state", "observation", "lastSuccessAt", "lastAttemptAt", "lastError",
    "lastApplied",
}
"""C3's keys, exactly: the settings are shown, and no key carries the address, the home Wi-Fi's name or the token."""

SEASON_WRITES = ("start_season", "end_season", "set_season_mode")


def linked() -> dict:
    """C3's body for a fictional "Example Heater" following a fictional helper: in season since a START the link
    applied, then one failed check, so the three times differ."""
    return {
        "assetId": "a1",
        "connection": {
            "configured": True, "needsToken": False, "cadence": "DAILY", "networkEligibility": "HOME_NETWORK_ONLY",
            "backgroundChecks": "OFF", "backgroundAllowed": False, "homeNetworkSet": True,
        },
        "binding": {
            "entityId": "input_boolean.example_heater_in_season", "mode": "FOLLOW", "enabled": True,
            "state": "ACTIVE", "observation": {"state": "ON", "haLastChanged": "2026-02-10T06:30:00+00:00"},
            "lastSuccessAt": 1770681600000, "lastAttemptAt": 1770768000000,
            "lastError": {"kind": "UNREACHABLE", "detail": None, "at": 1770768000000},
            "lastApplied": {"action": "START", "occurredOn": "2026-02-10", "at": 1770681600000},
        },
    }


UNCONFIGURED = {"assetId": "a2", "connection": {"configured": False, "needsToken": False}, "binding": None}
"""No connection on the phone: the five settings are absent, not `null`, and the asset has no binding."""


def paths(api) -> list[tuple[str, str]]:
    return [(r.method, r.path) for r in api.requests]


@pytest.fixture
def phone21(paired):
    paired.reply("GET", "/v1/status", 200, STATUS_21)
    return paired


def test_get_season_sync_is_registered_guarded_listed_last_and_takes_only_an_asset_id() -> None:
    assert callable(server_module.get_season_sync)
    tool = server_module.mcp._tool_manager.get_tool("get_season_sync")
    assert tool is not None and tool.parameters.get("additionalProperties") is False
    assert list(inspect.signature(server_module.get_season_sync).parameters) == ["asset_id"]
    assert set(tool.parameters.get("properties", {})) == {"asset_id"}
    assert server_module.TOOL_NAMES[-len(SEASON_SYNC_TOOLS):] == SEASON_SYNC_TOOLS


def test_no_other_tool_speaks_of_season_sync_or_home_assistant() -> None:
    """R16-9: one read and no write — no tool links, stops, resumes or checks a binding, or sets the connection."""
    named = {name for name in server_module.TOOL_NAMES if "sync" in name or "home_assistant" in name}
    assert named == set(SEASON_SYNC_TOOLS)


def test_it_reads_the_route_once_and_answers_the_body_as_sent(phone21) -> None:
    phone21.reply("GET", "/v1/assets/a1/season-sync", 200, linked())
    assert server_module.get_season_sync(asset_id="a1") == linked()
    assert paths(phone21) == [("GET", "/v1/status"), ("GET", "/v1/assets/a1/season-sync")]
    assert phone21.last().body == b""


def test_the_answer_carries_c3s_keys_and_nothing_more(phone21) -> None:
    phone21.reply("GET", "/v1/assets/a1/season-sync", 200, linked())
    answer = server_module.get_season_sync(asset_id="a1")
    assert set(answer) == {"assetId", "connection", "binding"}
    assert set(answer["connection"]) == CONNECTION_KEYS
    assert set(answer["binding"]) == BINDING_KEYS
    assert set(answer["binding"]["observation"]) == {"state", "haLastChanged"}
    assert set(answer["binding"]["lastError"]) == {"kind", "detail", "at"}
    assert set(answer["binding"]["lastApplied"]) == {"action", "occurredOn", "at"}

    phone21.reply("GET", "/v1/assets/a2/season-sync", 200, UNCONFIGURED)
    assert server_module.get_season_sync(asset_id="a2") == UNCONFIGURED


def test_a_schema_20_phone_is_refused_with_nothing_sent(paired) -> None:
    """Row 64 (C25): the route is schema 21's, so a schema-20 phone is refused by name with nothing sent but the
    pairing's one `/v1/status` read, and the refusal names the feature (G2). The behaviour is asserted first and
    the pinned minimum last, so a gate written one lower fails here on the request it lets through."""
    paired.reply("GET", "/v1/status", 200, STATUS_20)
    try:
        answer = server_module.get_season_sync(asset_id="a1")
    except ToolError as refused:
        text = str(refused)
    else:
        text = f"answered {answer!r}"
    assert paths(paired) == [("GET", "/v1/status")], "a schema-20 phone must be sent only the status read"
    assert "APP_SCHEMA_TOO_OLD" in text, text
    assert "reports schema 20; get_season_sync needs schema 21" in text, text
    assert "(Home Assistant season sync)" in text, text
    assert server_module._MIN_SEASON_SYNC_SCHEMA_VERSION == 21


def test_an_empty_id_or_an_unknown_argument_is_refused_before_any_request(paired) -> None:
    with pytest.raises(ToolError, match="must not be empty"):
        server_module.get_season_sync(asset_id="")
    for extra in ({"base_url": "http://192.168.0.10:8123"}, {"mode": "FORCE_IN"}, {"enabled": False}):
        with pytest.raises(ToolError, match="does not accept"):
            asyncio.run(server_module.mcp.call_tool("get_season_sync", {"asset_id": "a1", **extra}))
    assert paired.requests == []


@pytest.mark.parametrize(
    ("status", "code", "message"),
    [
        pytest.param(404, "no_such_asset", "no asset a9", id="no-such-asset"),
        pytest.param(500, "internal", "KeyStoreException", id="key-store"),
    ],
)
def test_each_refusal_arrives_as_a_tool_error_with_the_phones_code(phone21, status, code, message) -> None:
    """Through `MCPServer.call_tool`, the path a real client takes (the #46 rule): the phone's code, never the SDK's
    bare "Error executing tool"."""
    phone21.reply(
        "GET", "/v1/assets/a9/season-sync", status, {"error": {"code": code, "message": message, "problems": []}},
    )
    with pytest.raises(ToolError) as raised:
        asyncio.run(server_module.mcp.call_tool("get_season_sync", {"asset_id": "a9"}))
    assert f"{status} {code}: {message}" in str(raised.value)
    assert paths(phone21)[-1] == ("GET", "/v1/assets/a9/season-sync")


def test_the_docstring_names_the_route_its_keys_its_times_and_what_it_never_returns() -> None:
    doc = " ".join((server_module.get_season_sync.__doc__ or "").split())
    for words in (
        "GET /v1/assets/{id}/season-sync", "`binding`", "`null`", "`no_such_asset`", "`APP_SCHEMA_TOO_OLD`",
        "schema 21", "read only", "**absent**", "never the address, the home Wi-Fi's name or the token",
        "`lastSuccessAt`", "a failure never moves it", "`lastAttemptAt`", "whatever its outcome", "`lastApplied`",
        "`haLastChanged`", "never dates",
    ):
        assert words in doc, words
    for key in CONNECTION_KEYS | BINDING_KEYS:
        assert f"`{key}`" in doc, key


def test_the_three_season_writes_name_season_sync_enabled() -> None:
    for name in SEASON_WRITES:
        doc = " ".join((getattr(server_module, name).__doc__ or "").split())
        assert "`SEASON_SYNC_ENABLED` (409)" in doc, name
        assert "follows Home Assistant" in doc, name
