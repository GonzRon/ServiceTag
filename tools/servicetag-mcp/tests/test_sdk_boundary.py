"""Finding 2, plus the owner's proofs for fix round 2: every refusal must survive `mcp` 2.2.0's own
tool-invocation machinery, not just a direct Python call — and the omit-vs-`null` clearing
convention has to be proven through the *real* argument path, not asserted about it. The SDK
converts anything that is not itself a `ToolError` (or `ResourceError`, or `MCPError`) into the bare
string `Error executing tool <name>` and discards the rest
(`mcp/server/mcpserver/tools/base.py:208`-`210`) — the one thing this file exists to disprove is now
false. Every test here drives a tool through `MCPServer.call_tool`, which is what actually applies
that conversion (`Tool.run`, underneath) and what actually turns an omitted Python argument and an
explicit JSON `null` into the same `None` a strict-function-calling MCP client would send — rather
than calling the tool function directly the way the rest of the suite does.
"""

from __future__ import annotations

import asyncio
import json
import subprocess

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import client as client_module
from servicetag_mcp import server as server_module

_FAKE_SERIAL_FOR_TRANSPORT_TESTS = "FAKE_SERIAL_TRANSPORT_TEST_9q2z"


def _adb_forward_device(monkeypatch: pytest.MonkeyPatch) -> None:
    """Wires `server_module.device` onto the adb-forward path (no `SERVICETAG_API_BASE_URL`) with a
    distinctive fake serial, and makes `subprocess.run` succeed silently — so `ensure_forward()`
    (including the retry inside `Device.request`) never actually shells out to a real `adb`."""
    monkeypatch.delenv("SERVICETAG_API_BASE_URL", raising=False)
    monkeypatch.setenv("SERVICETAG_ADB_SERIAL", _FAKE_SERIAL_FOR_TRANSPORT_TESTS)
    monkeypatch.setattr(
        subprocess,
        "run",
        lambda argv, **kwargs: subprocess.CompletedProcess(argv, 0, stdout="", stderr=""),
    )
    server_module.device = client_module.Device.from_env()
    server_module.pair("ABCD2345")


def _call_tool(name: str, arguments: dict):
    return asyncio.run(server_module.mcp.call_tool(name, arguments))


def _body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def _asset_row(**overrides) -> dict:
    row = {
        "id": "a1",
        "name": "Hot tub",
        "description": "the deck one",
        "category": "Water",
        "notes": "needs a new cover",
        "status": "ACTIVE",
        "createdAt": 1,
        "updatedAt": 1,
        "templateKey": "hot_tub",
        "manufacturer": "Acme",
        "model": "HT-9",
        "serialNumber": "SN-1",
        "purchaseOn": "2024-05-01",
        "inServiceOn": "2024-05-15",
        "purchasePriceMinor": 450000,
        "currency": "USD",
        "vendor": "Pool Supply Co",
        "location": "Deck",
        "warrantyExpiresOn": "2026-05-01",
        "warrantyNotes": "parts only",
        "retiredOn": None,
        "parentAssetId": "sys1",
        "seasonStartMmdd": "05-01",
        "seasonEndMmdd": "09-30",
    }
    row.update(overrides)
    return row


# --- proof 4: four distinct, useful refusals, each through the real SDK boundary -----------------


def test_a_401_survives_the_sdk_boundary_with_its_own_wording(paired) -> None:
    paired.reply("GET", "/v1/status", 401, b"")
    with pytest.raises(ToolError) as raised:
        _call_tool("status", {})
    assert "every time the screen opens" in str(raised.value)


def test_a_422_survives_the_sdk_boundary_with_code_and_problems(paired) -> None:
    paired.reply(
        "POST", "/v1/assets", 422,
        {"error": {"code": "asset_validation", "message": "the asset was refused",
                   "problems": ["NameRequired"]}},
    )
    with pytest.raises(ToolError) as raised:
        _call_tool("create_asset", {"name": "  "})
    text = str(raised.value)
    assert "asset_validation" in text
    assert "NameRequired" in text


def test_a_connection_refusal_survives_the_sdk_boundary_with_its_own_wording(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The screen-closed case: a real refused socket on loopback, no device, no `adb`."""
    monkeypatch.setenv("SERVICETAG_API_BASE_URL", "http://127.0.0.1:1")
    monkeypatch.delenv("SERVICETAG_ADB_SERIAL", raising=False)
    server_module.device = client_module.Device.from_env()
    server_module.pair("ABCD2345")
    with pytest.raises(ToolError) as raised:
        _call_tool("status", {})
    assert "Developer API" in str(raised.value)


def test_a_remote_protocol_error_survives_the_sdk_boundary_with_its_own_wording(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """F1, live observation: `adb forward` stays installed after the Developer API screen closes,
    so the port still accepts a connection and the phone's own listener closes it mid-request —
    `httpx.RemoteProtocolError`, not `ConnectError`. This is the single most common real-world
    failure, and the old message ("could not reach the phone: RemoteProtocolError") said nothing
    actionable."""
    _adb_forward_device(monkeypatch)

    def fake_request(method, url, **kwargs):
        raise httpx.RemoteProtocolError("Server disconnected", request=httpx.Request(method, url))

    monkeypatch.setattr(httpx, "request", fake_request)
    with pytest.raises(ToolError) as raised:
        _call_tool("status", {})
    text = str(raised.value)
    assert "Developer API" in text
    assert "pair" in text
    assert _FAKE_SERIAL_FOR_TRANSPORT_TESTS not in text


def test_a_connect_error_survives_the_sdk_boundary_with_its_own_wording(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Same unified message, this time through the forward-invalidate-and-retry path (fix 5): the
    fake transport keeps refusing on the retry too, so this also proves the serial stays out of the
    final message even after `ensure_forward()` ran a second time."""
    _adb_forward_device(monkeypatch)

    def fake_request(method, url, **kwargs):
        raise httpx.ConnectError("refused", request=httpx.Request(method, url))

    monkeypatch.setattr(httpx, "request", fake_request)
    with pytest.raises(ToolError) as raised:
        _call_tool("status", {})
    text = str(raised.value)
    assert "Developer API" in text
    assert "pair" in text
    assert _FAKE_SERIAL_FOR_TRANSPORT_TESTS not in text


def test_a_missing_serial_survives_the_sdk_boundary_with_no_serial_in_it(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("SERVICETAG_API_BASE_URL", raising=False)
    monkeypatch.delenv("SERVICETAG_ADB_SERIAL", raising=False)
    server_module.device = client_module.Device.from_env()
    server_module.pair("ABCD2345")
    with pytest.raises(ToolError) as raised:
        _call_tool("status", {})
    text = str(raised.value)
    assert "SERVICETAG_ADB_SERIAL" in text


# --- R1: the three exception classes the re-review found still escaping `_call`'s conversion -----
# seam as the bare, message-free `UnexpectedToolError` -------------------------------------------


def test_a_non_json_200_survives_the_sdk_boundary_instead_of_crashing(paired) -> None:
    """`SERVICETAG_API_BASE_URL` pointed at anything else that answers 200 with a non-JSON body —
    no app bug needed to reach this, since it is a documented, user-set variable."""
    paired.reply("GET", "/v1/status", 200, b"<html>not the app</html>")
    with pytest.raises(ToolError, match="not valid JSON") as raised:
        _call_tool("status", {})
    assert type(raised.value) is ToolError, "must be a deliberate ToolError, not UnexpectedToolError"
    assert len(paired.requests) == 1


def test_update_asset_with_an_unexpected_response_shape_survives_the_sdk_boundary(paired) -> None:
    """A GET that does not answer `{"asset": {...}}` — a version skew, or the same misdirected
    base URL — must not crash past `_call` on the first `["asset"]`/`["<field>"]` subscript."""
    paired.reply("GET", "/v1/assets/a1", 200, {"nope": {}})
    with pytest.raises(ToolError, match="no 'asset' field") as raised:
        _call_tool("update_asset", {"asset_id": "a1", "name": "New Name"})
    assert type(raised.value) is ToolError
    assert not any(r.method == "PATCH" for r in paired.requests)


def test_save_definition_edit_with_an_unexpected_response_shape_survives_the_sdk_boundary(
    paired,
) -> None:
    paired.reply("GET", "/v1/assets/a1/definitions", 200, {"nope": []})
    with pytest.raises(ToolError, match="no 'definitions' field") as raised:
        _call_tool(
            "save_definition", {"asset_id": "a1", "definition_id": "d1", "label": "New label"}
        )
    assert type(raised.value) is ToolError
    assert not any(r.method == "POST" for r in paired.requests)


# --- proof 2: clear versus omit, through the real argument path ----------------------------------


def test_omitted_and_explicit_null_both_preserve_through_the_strict_client_scenario(paired) -> None:
    """A strict-function-calling MCP client sends `null` for every optional argument its caller did
    not set. This drives `update_asset` through `MCPServer.call_tool` with every optional field
    explicit `null` but one, and asserts the PATCH body still preserves the other seventeen —
    proving the SDK's own argument marshalling treats "omitted" and "explicit null" alike, not just
    that `server.py`'s Python code does."""
    current = _asset_row()
    paired.reply("GET", "/v1/assets/a1", 200, {"asset": current})
    arguments = {
        "asset_id": "a1",
        "name": "Hot Tub Deluxe",
        "category": None,
        "description": None,
        "notes": None,
        "manufacturer": None,
        "model": None,
        "serial_number": None,
        "purchase_on": None,
        "in_service_on": None,
        "purchase_price_minor": None,
        "currency": None,
        "vendor": None,
        "location": None,
        "warranty_expires_on": None,
        "warranty_notes": None,
        "parent_asset_id": None,
        "season_start_mmdd": None,
        "season_end_mmdd": None,
        "clear_fields": None,
    }
    _call_tool("update_asset", arguments)
    body = _body_of(paired.last())
    assert body["name"] == "Hot Tub Deluxe"
    for key in (
        "category", "description", "notes", "manufacturer", "model", "serialNumber",
        "purchaseOn", "inServiceOn", "purchasePriceMinor", "currency", "vendor", "location",
        "warrantyExpiresOn", "warrantyNotes", "parentAssetId", "seasonStartMmdd", "seasonEndMmdd",
    ):
        assert body[key] == current[key], key


def test_clear_fields_clears_exactly_one_field_through_the_sdk(paired) -> None:
    """`vendor` is a text field, so "cleared" is `""` — the app has no `null` state for it."""
    current = _asset_row()
    paired.reply("GET", "/v1/assets/a1", 200, {"asset": current})
    _call_tool("update_asset", {"asset_id": "a1", "clear_fields": ["vendor"]})
    body = _body_of(paired.last())
    assert body["vendor"] == ""
    assert body["name"] == current["name"]
    assert body["parentAssetId"] == current["parentAssetId"]


def test_clear_fields_validation_refuses_before_any_request_through_the_sdk(paired) -> None:
    # `match=` is load-bearing here, not decoration: `UnexpectedToolError` subclasses `ToolError`,
    # so `pytest.raises(ToolError)` alone would also pass on the bare, message-free SDK crash text —
    # asserting the wording is what actually proves the refusal's own message survived.
    with pytest.raises(ToolError, match="cannot be cleared"):
        _call_tool("update_asset", {"asset_id": "a1", "clear_fields": ["name"]})
    assert paired.requests == []

    with pytest.raises(ToolError, match="cannot be cleared"):
        _call_tool("update_asset", {"asset_id": "a1", "clear_fields": ["not_a_field"]})
    assert paired.requests == []

    with pytest.raises(ToolError, match="also given a value"):
        _call_tool(
            "update_asset", {"asset_id": "a1", "vendor": "Acme", "clear_fields": ["vendor"]}
        )
    assert paired.requests == []


# --- proof 6: retire_asset is monotonic, proven through the SDK too ------------------------------


def test_retire_asset_with_null_is_refused_before_any_request_through_the_sdk(paired) -> None:
    with pytest.raises(ToolError, match="retired_on"):
        _call_tool("retire_asset", {"asset_id": "a1", "retired_on": None})
    assert paired.requests == []
