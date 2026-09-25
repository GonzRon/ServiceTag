"""Guard round (owner ruling, 2026-09-21): "MCP tool calls containing any unknown argument must be
rejected before the tool body performs a write." The scenario that forced this: the controller
called `save_definition` with `id=` where the schema says `definition_id=`; `mcp` 2.2.0's argument
model silently dropped `id`, read `definition_id` as absent, and the tool CREATED a new reading
instead of editing the one the caller meant. A typo must never turn one mutation into another.

`_StrictMCPServer.call_tool` and `_forbid_unknown_arguments` (both in `server.py`) close that,
centrally, for all 55 tools at once — see their docstrings for the two-layer why. This file proves
the six hard acceptance conditions the owner named, one section per condition, plus the fix's own
`(a)`-`(e)` test list.
"""

from __future__ import annotations

import asyncio
import subprocess
from types import SimpleNamespace

import mcp.types
import pytest
from mcp import Client
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


# --- condition 1: every one of the 55 tools publishes additionalProperties: false -----------------


def test_every_tool_publishes_additional_properties_false() -> None:
    tools = server_module.mcp._tool_manager.list_tools()
    assert len(tools) == 55
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


# --- G1: the override's *position* on the real request path, not just the override in isolation ---
#
# Every test above calls `server_module.mcp.call_tool(...)` directly — the override itself, proven
# correct, but never proving that a real client request actually reaches it. The scoped re-review of
# `1813ed3` enumerated every server-side `call_tool` call site in `mcp` 2.2.0 (exactly two:
# `_handle_call_tool` and inside `MCPServer.call_tool` itself) and found no bypass — these two tests
# are what make that finding a standing test rather than a one-time read.


def test_the_real_request_handler_refuses_and_says_nothing_about_the_value(paired) -> None:
    """Drives `_handle_call_tool` — the lowlevel handler the SDK's `tools/call` JSON-RPC method
    calls — with a validated `CallToolRequestParams`, the same shape a real connected client's
    request takes. The `"does not accept"` assertion is load-bearing: it fails if the refusal ever
    came from the model-level backstop instead of this override, since the backstop's raw message
    reads differently and echoes the value."""
    params = mcp.types.CallToolRequestParams.model_validate(
        {"name": "save_definition", "arguments": {"asset_id": "a1", "id": _DISTINCTIVE_VALUE}}
    )
    result = asyncio.run(server_module.mcp._handle_call_tool(None, params))
    assert result.is_error is True
    assert "does not accept" in result.content[0].text
    assert _DISTINCTIVE_VALUE not in result.content[0].text
    assert paired.requests == []


def test_an_in_process_sdk_client_receives_the_refusal_with_no_value_anywhere(paired) -> None:
    """The other end of the same path, from the SDK's own client side: `mcp.Client` talking to this
    `MCPServer` in-process (no transport, no socket — the SDK wires the two together directly), so
    this is what a connected client's `call_tool` actually returns. Checked against the *whole*
    serialized result (`model_dump_json()`), not just the one text block, since condition 3 says
    "the error" a client receives, not "the one field this test happened to check"."""

    async def call() -> mcp.types.CallToolResult:
        async with Client(server_module.mcp) as connected:
            return await connected.call_tool(
                "save_definition", {"asset_id": "a1", "id": _DISTINCTIVE_VALUE}
            )

    result = asyncio.run(call())
    assert result.is_error is True
    serialized = result.model_dump_json()
    assert "does not accept" in serialized
    assert _DISTINCTIVE_VALUE not in serialized
    assert paired.requests == []


# --- condition 4: the error lists the tool's allowed argument names -------------------------------


def test_the_refusal_lists_the_tools_allowed_argument_names(paired) -> None:
    with pytest.raises(ToolError) as raised:
        _call_tool("update_asset", {"asset_id": "a1", "nmae": "Typo"})
    text = str(raised.value)
    for name in ("asset_id", "name", "clear_fields", "category"):
        assert name in text, text


# --- G2: the allowed-name set must agree with what validation actually accepts, aliases included ---


def test_a_parameter_that_shadows_a_basemodel_attribute_is_listed_by_its_real_name() -> None:
    """`func_metadata.py` renames a parameter whose name collides with a callable `BaseModel`
    attribute (`json`, `copy`, `dict`, `schema`, `validate`, ...) to `field_<name>` internally, with
    `alias=<name>` so the wire key stays the original — probed and confirmed: `model_fields` holds
    `field_json` for a parameter literally named `json`. Building the allowed set from field names
    alone would call the real key `json` unknown (a false refusal of a legitimate call) while
    admitting `field_json`, a name no real client would ever send. `_StrictMCPServer.call_tool`
    builds it from `info.alias or name` instead, so accepted and listed always agree.

    Registered on a throwaway `_StrictMCPServer`, never on the real `mcp` — the published tool count
    must stay 55 (asserted at the end, the same way condition 5 pins it elsewhere)."""
    throwaway = server_module._StrictMCPServer("throwaway-guard-probe")

    @throwaway.tool()
    def demo_tool(json: str | None = None, asset_id: str = "") -> dict:
        return {"json": json, "asset_id": asset_id}

    tool = throwaway._tool_manager.get_tool("demo_tool")
    assert "field_json" in tool.fn_metadata.arg_model.model_fields, "the SDK's own renaming rule"

    server_module._forbid_unknown_arguments(throwaway._tool_manager.list_tools())

    accepted = asyncio.run(throwaway.call_tool("demo_tool", {"json": "hello"}))
    assert accepted.is_error is False, "the real wire key must not be refused as unknown"

    with pytest.raises(ToolError) as raised:
        asyncio.run(throwaway.call_tool("demo_tool", {"bogus": "x"}))
    text = str(raised.value)
    assert "json" in text
    assert "field_json" not in text

    assert len(server_module.TOOL_NAMES) == 55
    assert len(server_module.mcp._tool_manager.list_tools()) == 55


# --- fix spec 4(c): parametrised over every one of the 55 registered tools ------------------------


@pytest.mark.parametrize("tool_name", server_module.TOOL_NAMES)
def test_every_tool_refuses_one_bogus_key_with_zero_requests(tool_name, paired) -> None:
    tool = server_module.mcp._tool_manager.get_tool(tool_name)
    assert tool is not None, tool_name
    assert tool.parameters.get("additionalProperties") is False, tool_name
    with pytest.raises(ToolError, match="does not accept"):
        _call_tool(tool_name, {"__bogus_extra_key_xyz__": "nope"})
    assert paired.requests == []


SEASON_HEALTH_TOOLS = (
    "get_season", "start_season", "end_season", "set_season_mode", "set_maintenance_break",
    "list_conditions", "record_condition", "get_health", "set_health_policy",
    "list_health_subjects", "create_health_subject", "update_health_subject",
    "archive_health_subject", "list_attention",
)
"""The fourteen 1.4 tools (spec §9.4), written out here rather than read from `TOOL_NAMES`, so a new
tool dropped from that list — and so from the parametrisation above — still fails by name here."""


@pytest.mark.parametrize("tool_name", SEASON_HEALTH_TOOLS)
def test_every_new_tool_rejects_an_unknown_argument_before_the_body_runs(tool_name, paired) -> None:
    """Each with every required argument present and valid, plus one misspelt field, so nothing but
    the guard can be what refuses it — and it refuses before the schema check's `/v1/status` read,
    before any `adb` forward and before the tool body."""
    assert tool_name in server_module.TOOL_NAMES, tool_name
    tool = server_module.mcp._tool_manager.get_tool(tool_name)
    assert tool is not None, tool_name
    required = tool.parameters.get("required", [])
    arguments = {name: "x" for name in required}
    arguments["reasn"] = "typo"
    with pytest.raises(ToolError, match="does not accept") as raised:
        _call_tool(tool_name, arguments)
    assert "reasn" in str(raised.value)
    assert paired.requests == []


# --- condition 5 / fix spec 4(d)-(e): a correct call still works; the tool count is unchanged -----


def test_a_correct_call_still_works(paired) -> None:
    paired.reply("GET", "/v1/status", 200, {"appVersion": "1.1.0"})
    result = _call_tool("status", {})
    assert result.is_error is False


def test_the_tool_count_is_55() -> None:
    assert len(server_module.TOOL_NAMES) == 55
    assert len(server_module.mcp._tool_manager.list_tools()) == 55


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


def test_the_guard_raises_when_the_published_schema_never_gets_additional_properties_false() -> None:
    """The *schema* assertion specifically (point 2 of `_forbid_unknown_arguments`'s docstring): a
    hand-made fake whose `model_json_schema()` never reports `additionalProperties: false` at all,
    however `model_config`/`model_rebuild` behave. This is the guard's schema check working; it is
    **not** a proof of condition 6 on its own — that check reads `model_config` live and can pass
    even when the validator itself was never rebuilt, which is exactly what
    `test_the_guard_raises_when_a_genuine_arg_models_rebuild_does_not_change_validation`, directly
    below, reproduces for real (round 2 of this guard round)."""

    class DegradedArgModel:
        model_config: dict = {}

        @classmethod
        def model_rebuild(cls, force: bool = False) -> None:
            pass  # succeeds, but a future pydantic here would change nothing about validation

        @classmethod
        def model_json_schema(cls) -> dict:
            return {"type": "object", "properties": {}}  # no "additionalProperties": False

    fake_tool = SimpleNamespace(
        name="degraded_tool",
        fn_metadata=SimpleNamespace(arg_model=DegradedArgModel),
        parameters={},
    )
    with pytest.raises(RuntimeError, match="degraded_tool"):
        server_module._forbid_unknown_arguments([fake_tool])


def test_the_guard_raises_when_a_genuine_arg_models_rebuild_does_not_change_validation() -> None:
    """G3, round 2: the schema-only check the first G3 fix relied on is unsound. Controller
    evidence: `pydantic`'s `model_json_schema()` reads `model_config` live regardless of whether
    `model_rebuild` ever ran, so mutating `model_config["extra"] = "forbid"` on a plain model with
    **no rebuild at all** already makes its schema say `additionalProperties: false` — while
    `model_validate` on that same, never-rebuilt model still silently *accepts* an unknown key. So
    this reproduces the real failure mode instead of a hand-made one: a genuine pydantic arg model,
    built by the SDK itself for a throwaway tool, whose `model_rebuild` is turned into a no-op
    afterwards — patched on this one generated class only, never on the shared `ArgModelBase`, so no
    other test and none of the real server's 55 already-guarded tools are affected. The guard must
    still raise, because the *effect check* (validating a probe payload) catches what the schema
    check cannot."""
    throwaway = server_module._StrictMCPServer("throwaway-guard-probe-g3-round-2")

    @throwaway.tool()
    def demo_tool(a: str = "x") -> dict:
        return {"a": a}

    tool = throwaway._tool_manager.get_tool("demo_tool")
    tool.fn_metadata.arg_model.model_rebuild = classmethod(lambda cls, **kwargs: None)

    with pytest.raises(RuntimeError, match="demo_tool"):
        server_module._forbid_unknown_arguments([tool])

    assert len(server_module.TOOL_NAMES) == 55
    assert len(server_module.mcp._tool_manager.list_tools()) == 55


# --- G5: a tool registered (or dropped) around the guard's call site must not go unnoticed ---------


def test_the_guard_raises_when_the_tool_count_does_not_match_what_was_expected() -> None:
    """`expected_count` is what the real call site (`_forbid_unknown_arguments(mcp._tool_manager
    .list_tools(), expected_count=len(TOOL_NAMES))`) passes — a tool added or removed around that
    call site changes `len(tools)` without changing anything the per-tool loop inspects, so nothing
    else in the guard would notice either way."""
    with pytest.raises(RuntimeError, match=r"1 tools were registered but 2 were expected"):
        server_module._forbid_unknown_arguments([SimpleNamespace(name="only_one")], expected_count=2)


def test_the_real_call_site_pins_the_tool_count_against_tool_names() -> None:
    """Not a simulation: the real module-level call already ran at import — this just asserts the
    two numbers it compared are in fact equal for the server as shipped, so a future edit that adds
    a `@mcp.tool()` without updating `TOOL_NAMES` (or the reverse) is caught the next time this
    suite runs, not only the next time the module is freshly imported."""
    assert len(server_module.mcp._tool_manager.list_tools()) == len(server_module.TOOL_NAMES)
