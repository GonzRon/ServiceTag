"""Finding 2: every refusal must survive `mcp` 2.2.0's own tool-invocation machinery, not just a
direct Python call. The SDK converts anything that is not itself a `ToolError` (or `ResourceError`,
or `MCPError`) into the bare string `Error executing tool <name>` and discards the rest
(`mcp/server/mcpserver/tools/base.py:208`-`210`) — the one thing this file exists to disprove is
now false. These three drive the tool through `MCPServer.call_tool`, which is what actually applies
that conversion (`Tool.run`, underneath), rather than calling the tool function directly the way
the rest of the suite does.
"""

from __future__ import annotations

import asyncio

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import client as client_module
from servicetag_mcp import server as server_module


def _call_tool(name: str, arguments: dict):
    return asyncio.run(server_module.mcp.call_tool(name, arguments))


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
