"""Guard round (owner ruling, 2026-09-21): "MCP tool calls containing any unknown argument must be
rejected before the tool body performs a write." The scenario that forced this: the controller
called `save_definition` with `id=` where the schema says `definition_id=`; `mcp` 2.2.0's argument
model silently dropped `id`, read `definition_id` as absent, and the tool CREATED a new reading
instead of editing the one the caller meant. A typo must never turn one mutation into another.

`_StrictMCPServer.call_tool` and `_forbid_unknown_arguments` (both in `server.py`) close that,
centrally, for all 21 tools at once — see their docstrings for the two-layer why. This file proves
the six hard acceptance conditions the owner named, one section per condition, plus the fix's own
`(a)`-`(e)` test list.
"""

from __future__ import annotations

import asyncio
import subprocess
from types import SimpleNamespace

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import client as client_module
from servicetag_mcp import server as server_module

_DISTINCTIVE_VALUE = "XVALUE_MARKER_7f3a9c_must_never_appear_in_a_message"


def _call_tool(name: str, arguments: dict):
    return asyncio.run(server_module.mcp.call_tool(name, arguments))


def _assert_value_absent(exc: BaseException, value: str) -> None:
    """Condition 3: the supplied value must not survive anywhere a person or a log would see it —
    `str`, `repr`, or either link of the exception chain, walked in both directions."""
    seen: list[str] = [str(exc), repr(exc)]
    current: BaseException | None = exc
    while current is not None:
        seen.append(str(current))
        seen.append(repr(current))
        current = current.__cause__ or current.__context__
    assert not any(value in text for text in seen), seen


# --- condition 1: every one of the 21 tools publishes additionalProperties: false -----------------


def test_every_tool_publishes_additional_properties_false() -> None:
    tools = server_module.mcp._tool_manager.list_tools()
    assert len(tools) == 21
    for tool in tools:
        assert tool.parameters.get("additionalProperties") is False, tool.name


# --- condition 2: a bogus key is refused before ANY HTTP, adb or API activity ---------------------


def test_save_definition_with_id_instead_of_definition_id_is_refused_with_zero_requests(
    paired,
) -> None:
    """The exact scenario that prompted this round: `id=` silently dropped would make this CREATE
    instead of editing the row named by `definition_id`."""
    with pytest.raises(ToolError, match="does not accept") as raised:
        _call_tool("save_definition", {"asset_id": "a1", "id": "d1", "label": "x"})
    text = str(raised.value)
    assert "id" in text
    assert "definition_id" in text
    assert paired.requests == []


def test_update_asset_with_a_misspelt_field_is_refused_with_zero_requests(paired) -> None:
    with pytest.raises(ToolError, match="does not accept"):
        _call_tool("update_asset", {"asset_id": "a1", "nmae": "Typo"})
    assert paired.requests == []


def test_a_read_tool_with_a_bogus_key_is_refused_with_zero_requests(paired) -> None:
    with pytest.raises(ToolError, match="does not accept"):
        _call_tool("list_assets", {"bogus": "x"})
    assert paired.requests == []


def test_a_bogus_key_is_refused_before_any_adb_forward_is_attempted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Run without `SERVICETAG_API_BASE_URL` — the adb-forward path — so a call that reached the
    tool body would have shelled out to `adb forward` before ever reaching HTTP. The guard must
    refuse before that too, not just before the socket."""
    monkeypatch.delenv("SERVICETAG_API_BASE_URL", raising=False)
    monkeypatch.setenv("SERVICETAG_ADB_SERIAL", "R58N90FAKE1")
    server_module.device = client_module.Device.from_env()
    server_module.pair("ABCD2345")

    calls: list[list[str]] = []
    monkeypatch.setattr(subprocess, "run", lambda argv, **kwargs: calls.append(argv))

    with pytest.raises(ToolError, match="does not accept"):
        _call_tool("status", {"bogus": "x"})
    assert calls == [], "adb forward must never be attempted for a refused call"


# --- condition 3: the error names the unknown key but never its supplied value --------------------


def test_the_refusal_never_contains_the_supplied_value(paired) -> None:
    with pytest.raises(ToolError) as raised:
        _call_tool("save_definition", {"asset_id": "a1", "id": _DISTINCTIVE_VALUE, "label": "x"})
    _assert_value_absent(raised.value, _DISTINCTIVE_VALUE)


# --- condition 4: the error lists the tool's allowed argument names -------------------------------


def test_the_refusal_lists_the_tools_allowed_argument_names(paired) -> None:
    with pytest.raises(ToolError) as raised:
        _call_tool("update_asset", {"asset_id": "a1", "nmae": "Typo"})
    text = str(raised.value)
    for name in ("asset_id", "name", "clear_fields", "category"):
        assert name in text, text


# --- fix spec 4(c): parametrised over every one of the 21 registered tools ------------------------


@pytest.mark.parametrize("tool_name", server_module.TOOL_NAMES)
def test_every_tool_refuses_one_bogus_key_with_zero_requests(tool_name, paired) -> None:
    tool = server_module.mcp._tool_manager.get_tool(tool_name)
    assert tool is not None, tool_name
    assert tool.parameters.get("additionalProperties") is False, tool_name
    with pytest.raises(ToolError, match="does not accept"):
        _call_tool(tool_name, {"__bogus_extra_key_xyz__": "nope"})
    assert paired.requests == []


# --- condition 5 / fix spec 4(d)-(e): a correct call still works; the tool count is unchanged -----


def test_a_correct_call_still_works(paired) -> None:
    paired.reply("GET", "/v1/status", 200, {"appVersion": "1.1.0"})
    result = _call_tool("status", {})
    assert result.is_error is False


def test_the_tool_count_is_still_21() -> None:
    assert len(server_module.TOOL_NAMES) == 21
    assert len(server_module.mcp._tool_manager.list_tools()) == 21


# --- condition 6: the guard fails loudly, never silently, on an internals shape it does not -------
# recognise -----------------------------------------------------------------------------------


def test_the_guard_raises_on_a_differently_shaped_tool_instead_of_skipping_it() -> None:
    """Simulates a future `mcp` release whose `Tool`/`FuncMetadata` shape has changed underneath
    this guard: it must raise, not quietly leave that tool unguarded."""

    class FakeArgModel:  # no `model_config`, no `model_rebuild` — not a pydantic model at all
        pass

    fake_tool = SimpleNamespace(
        name="fake_tool",
        fn_metadata=SimpleNamespace(arg_model=FakeArgModel),
        parameters={},
    )
    with pytest.raises(RuntimeError, match="fake_tool"):
        server_module._forbid_unknown_arguments([fake_tool])


def test_the_guard_refuses_to_run_over_an_empty_tool_list() -> None:
    """A silent no-op over zero tools would be indistinguishable from "already guarded"; treated as
    a shape the guard does not recognise either, rather than a vacuous success."""
    with pytest.raises(RuntimeError):
        server_module._forbid_unknown_arguments([])
