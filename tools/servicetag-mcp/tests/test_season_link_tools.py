"""#129's six tools (L-C9; rows k21 and k22): an asset's Home Assistant season link driven over the "#129" block.

Each tool POSTs `/v1/assets/{id}/season-sync` with one action — `LINK`, `CHANGE_ENTITY`, `STOP`, `RESUME`, `SET_MODE`
or `UNLINK` — and a body built from the vendored golden keys alone. The confirmations are the caller's: the link and
the change send nothing unless `confirm_entity_id` equals `entity_id`, the unlink nothing unless `confirm_unlink` is the
boolean `true`, and no tool fills either or adds one the route does not check. Each tool refuses a phone below schema
22 with nothing sent, and no write is re-sent on a dropped connection. A 1.9.0 phone, at schema 22 too, answers the
route's 404 `not_found`, surfaced as sent. No tool reaches the connection, the token, Forget or Disconnect. Every name
here is fictional.
"""

from __future__ import annotations

import ast
import asyncio
import inspect
import json
from pathlib import Path

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import command_shapes
from servicetag_mcp import server as server_module

STATUS_22: dict = {
    "appVersion": "1.9.1", "apiVersion": 1, "schemaVersion": 22, "backupFormatVersion": 21, "counts": {},
}
STATUS_21: dict = dict(STATUS_22, appVersion="1.8.1", schemaVersion=21, backupFormatVersion=20)
STATUS_190: dict = dict(STATUS_22, appVersion="1.9.0")

SEASON_LINK_TOOLS = (
    "link_season_sync",
    "change_season_sync_entity",
    "stop_season_sync",
    "resume_season_sync",
    "set_season_sync_mode",
    "unlink_season_sync",
)
"""L-C9's six, the "#129" block, appended after the "#125" block in the issue's order: the registered total is **110**."""

PATH = "/v1/assets/a1/season-sync"
OLD_HELPER = "input_boolean.example_heater_in_season"
HELPER = "input_boolean.example_heater_season"
NEVER_SAID = ("release", "authoriz", "permission to start", "safe")

ARGUMENTS: dict[str, dict] = {
    "link_season_sync": {"asset_id": "a1", "entity_id": HELPER, "confirm_entity_id": HELPER},
    "change_season_sync_entity": {"asset_id": "a1", "entity_id": HELPER, "confirm_entity_id": HELPER},
    "stop_season_sync": {"asset_id": "a1"},
    "resume_season_sync": {"asset_id": "a1"},
    "set_season_sync_mode": {"asset_id": "a1", "mode": "FORCE_IN"},
    "unlink_season_sync": {"asset_id": "a1", "confirm_unlink": True},
}
"""One valid call of each tool."""

BODIES: dict[str, dict] = {
    "link_season_sync": {"action": "LINK", "entityId": HELPER, "confirmEntityId": HELPER},
    "change_season_sync_entity": {"action": "CHANGE_ENTITY", "entityId": HELPER, "confirmEntityId": HELPER},
    "stop_season_sync": {"action": "STOP"},
    "resume_season_sync": {"action": "RESUME"},
    "set_season_sync_mode": {"action": "SET_MODE", "mode": "FORCE_IN"},
    "unlink_season_sync": {"action": "UNLINK", "confirmUnlink": True},
}
"""What each sends, exactly: L-C9's table."""


def answer(**overrides) -> dict:
    """The route's answer for a fictional "example_heater" linked in FOLLOW, one START applied, nothing switched."""
    body = {
        "assetId": "a1",
        "connection": {
            "configured": True, "needsToken": False, "cadence": "DAILY", "networkEligibility": "HOME_NETWORK_ONLY",
            "backgroundChecks": "OFF", "backgroundAllowed": False, "homeNetworkSet": True,
        },
        "binding": {
            "entityId": HELPER, "mode": "FOLLOW", "enabled": True, "state": "ACTIVE", "observation": None,
            "lastSuccessAt": None, "lastAttemptAt": None, "lastError": None,
            "lastApplied": {"action": "START", "occurredOn": "2026-10-20", "at": 1792454400000, "source": "HOME_ASSISTANT"},
        },
        "switchedFrom": None,
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


def refusal(code: str, message: str, problems: list[str], field: str | None = None) -> dict:
    return {"error": {"code": code, "message": message, "problems": problems, "field": field}}


@pytest.fixture
def phone22(paired):
    paired.reply("GET", "/v1/status", 200, STATUS_22)
    return paired


# --- k21: the block, its signatures and schemas -------------------------------------------------------------------


def test_the_six_tools_close_the_list_in_order_after_the_schedule_season_tools() -> None:
    names = server_module.TOOL_NAMES
    assert names[-6:] == SEASON_LINK_TOOLS
    assert names.index("link_season_sync") == names.index("unlink_schedule_season") + 1
    assert len(names) == 110
    assert len(server_module.mcp._tool_manager.list_tools()) == 110
    for name in SEASON_LINK_TOOLS:
        registered = server_module.mcp._tool_manager.get_tool(name)
        assert registered is not None and registered.parameters.get("additionalProperties") is False, name


@pytest.mark.parametrize("name", ["link_season_sync", "change_season_sync_entity"])
def test_the_entity_confirmation_is_an_argument_of_its_own_never_filled(name) -> None:
    parameters = inspect.signature(tool(name)).parameters
    assert list(parameters) == ["asset_id", "entity_id", "confirm_entity_id"]
    assert parameters["confirm_entity_id"].default is None, "the confirmation is the caller's, never filled"
    schema = server_module.mcp._tool_manager.get_tool(name).parameters
    assert schema["required"] == ["asset_id", "entity_id"]


def test_the_other_signatures_take_only_their_arguments() -> None:
    for name in ("stop_season_sync", "resume_season_sync"):
        assert list(inspect.signature(tool(name)).parameters) == ["asset_id"], name
        assert server_module.mcp._tool_manager.get_tool(name).parameters["required"] == ["asset_id"], name
    mode = inspect.signature(tool("set_season_sync_mode")).parameters
    assert list(mode) == ["asset_id", "mode"]
    assert server_module.mcp._tool_manager.get_tool("set_season_sync_mode").parameters["required"] == ["asset_id", "mode"]
    unlink = inspect.signature(tool("unlink_season_sync")).parameters
    assert list(unlink) == ["asset_id", "confirm_unlink"]
    assert unlink["confirm_unlink"].default is inspect.Parameter.empty, "never defaulted"
    schema = server_module.mcp._tool_manager.get_tool("unlink_season_sync").parameters
    assert schema["required"] == ["asset_id", "confirm_unlink"]
    assert schema["properties"]["confirm_unlink"]["type"] == "boolean"


# --- k21: the bodies --------------------------------------------------------------------------------------------


@pytest.mark.parametrize("name", SEASON_LINK_TOOLS)
def test_each_tool_sends_exactly_its_golden_keys_and_answers_as_sent(phone22, name) -> None:
    sent = answer(switchedFrom="CALENDAR") if name == "link_season_sync" else answer()
    phone22.reply("POST", PATH, 200, sent)
    assert tool(name)(**ARGUMENTS[name]) == sent
    assert paths(phone22) == [("GET", "/v1/status"), ("POST", PATH)]
    body = body_of(phone22.last())
    assert body == BODIES[name]
    assert list(body) == [key for key in command_shapes.SEASON_SYNC_KEYS if key in body], "the golden order"
    assert phone22.last().headers.get("Content-Type", "").startswith("application/json")


def test_the_mode_is_passed_as_given_and_the_phones_400_reaches_the_caller(phone22) -> None:
    phone22.reply(
        "POST", PATH, 400, refusal("bad_request", "mode is not one of FOLLOW, FORCE_IN, FORCE_OUT", []),
    )
    with pytest.raises(ToolError, match="400"):
        tool("set_season_sync_mode")(asset_id="a1", mode="follow")
    assert body_of(phone22.last()) == {"action": "SET_MODE", "mode": "follow"}
    assert paths(phone22).count(("POST", PATH)) == 1


# --- k21: nothing is sent without the caller's own confirmation ---------------------------------------------------


@pytest.mark.parametrize("name", ["link_season_sync", "change_season_sync_entity"])
def test_the_entity_tools_send_nothing_unless_the_confirmation_equals_the_entity(paired, name) -> None:
    for confirmation in (None, OLD_HELPER, "", HELPER.upper()):
        with pytest.raises(ToolError, match="nothing was sent"):
            tool(name)(asset_id="a1", entity_id=HELPER, confirm_entity_id=confirmation)
    # Through the server, as a client calls it: a missing confirmation is never filled from the entity.
    with pytest.raises(Exception):
        asyncio.run(server_module.mcp.call_tool(name, {"asset_id": "a1", "entity_id": HELPER}))
    assert paired.requests == []


def test_the_unlink_sends_nothing_unless_its_argument_is_true(paired) -> None:
    for value in (False, None, "true", 1, "yes"):
        with pytest.raises(ToolError, match="nothing was sent"):
            tool("unlink_season_sync")(asset_id="a1", confirm_unlink=value)
    for arguments in (
        {"asset_id": "a1"},
        {"asset_id": "a1", "confirm_unlink": "true"},
        {"asset_id": "a1", "confirm_unlink": 1},
        {"asset_id": "a1", "confirm_unlink": False},
    ):
        with pytest.raises(Exception):
            asyncio.run(server_module.mcp.call_tool("unlink_season_sync", arguments))
    assert paired.requests == []


@pytest.mark.parametrize("name", ["stop_season_sync", "resume_season_sync", "set_season_sync_mode"])
def test_the_tools_without_a_confirmation_take_none(paired, name) -> None:
    """The phone asks none for Stop, Resume or a mode, so no tool adds one the route does not check."""
    for extra in ({"confirm_entity_id": HELPER}, {"confirm_unlink": True}, {"entity_id": HELPER}):
        with pytest.raises(ToolError, match="does not accept"):
            asyncio.run(server_module.mcp.call_tool(name, {**ARGUMENTS[name], **extra}))
    assert paired.requests == []


# --- k21: the gate, the retry and the refusals --------------------------------------------------------------------


@pytest.mark.parametrize("name", SEASON_LINK_TOOLS)
def test_a_schema_21_phone_is_refused_naming_the_feature_with_nothing_sent(paired, name) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_21)
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as refused:
        tool(name)(**ARGUMENTS[name])
    assert f"{name} needs schema 22" in str(refused.value)
    assert "an asset's Home Assistant season link" in str(refused.value)
    assert set(paths(paired)) <= {("GET", "/v1/status")}, "the status read at most, nothing on the route"


def test_the_writes_are_never_retried_on_connect(phone22, monkeypatch) -> None:
    calls = spy_requests(monkeypatch)
    phone22.reply("POST", PATH, 200, answer())
    for name in SEASON_LINK_TOOLS:
        tool(name)(**ARGUMENTS[name])
    writes = [kwargs for method, _, kwargs in calls if method == "POST"]
    assert len(writes) == 6
    assert all(kwargs.get("retry_on_connect") is False for kwargs in writes)


@pytest.mark.parametrize(
    ("name", "status", "code", "message", "problems"),
    [
        ("link_season_sync", 409, "SEASON_SYNC_REFUSED", "this asset already has a season link; change its entity, "
         "resume it or unlink it first", ["SeasonSyncLinkRefused(reason=ALREADY_LINKED)"]),
        ("change_season_sync_entity", 409, "SEASON_SYNC_REFUSED", "ServiceTag writes that helper, so it cannot "
         "follow it", ["SeasonSyncLinkRefused(reason=OUTBOUND_TARGET)"]),
        ("stop_season_sync", 409, "SEASON_SYNC_NOT_LINKED", "this asset has no season link; link it first", []),
        ("resume_season_sync", 409, "SEASON_SYNC_REFUSED", "the Home Assistant token is not on this phone; enter "
         "it on the phone first", ["SeasonSyncLinkRefused(reason=NEEDS_TOKEN)"]),
        ("set_season_sync_mode", 409, "SEASON_SYNC_NOT_LINKED", "this asset has no season link; link it first", []),
        ("unlink_season_sync", 422, "SEASON_SYNC_CONFIRMATION_REQUIRED", "UNLINK needs confirmUnlink: true, the "
         "caller's own confirmation", ["SeasonSyncConfirmationRequired(field=confirmUnlink)"]),
        ("link_season_sync", 404, "no_such_asset", "no asset a1", []),
    ],
)
def test_a_refusal_reaches_the_caller_by_its_code_and_is_not_re_sent(phone22, name, status, code, message, problems):
    phone22.reply("POST", PATH, status, refusal(code, message, problems))
    with pytest.raises(ToolError) as raised:
        tool(name)(**ARGUMENTS[name])
    assert f"{status} {code}: {message}" in str(raised.value)
    assert paths(phone22).count(("POST", PATH)) == 1


@pytest.mark.parametrize("name", SEASON_LINK_TOOLS)
def test_a_1_9_0_phone_answers_its_404_and_the_tool_surfaces_it_once(paired, name) -> None:
    """D2: schema 22 too, so the gate passes; its router falls to the asset sub-resource 404 and writes nothing."""
    paired.reply("GET", "/v1/status", 200, STATUS_190)
    paired.reply("POST", PATH, 404, refusal("not_found", f"no route for POST {PATH}", []))
    with pytest.raises(ToolError, match="404 not_found"):
        tool(name)(**ARGUMENTS[name])
    assert paths(paired) == [("GET", "/v1/status"), ("POST", PATH)]


@pytest.mark.parametrize("name", SEASON_LINK_TOOLS)
def test_an_empty_id_or_an_unknown_argument_is_refused_before_any_request(paired, name) -> None:
    with pytest.raises(ToolError, match="must not be empty"):
        tool(name)(**{**ARGUMENTS[name], "asset_id": ""})
    for extra in (
        {"token": "x"}, {"base_url": "http://192.168.0.10:8123"}, {"home_network_ssid": "ExampleHomeWifi"},
        {"state": "ON"}, {"occurred_on": "2026-10-27"}, {"action": "LINK"},
    ):
        with pytest.raises(ToolError, match="does not accept"):
            asyncio.run(server_module.mcp.call_tool(name, {**ARGUMENTS[name], **extra}))
    assert paired.requests == []


# --- k21: the docstrings ------------------------------------------------------------------------------------------


def test_the_docstrings_name_their_route_body_answer_codes_and_limits() -> None:
    common = (
        "`POST /v1/assets/{id}/season-sync`", "`get_season_sync`", "`switchedFrom`", "`no_such_asset`", "1.9.0",
        "`not_found`", "never re-sent on a dropped connection", "schema 22", "`APP_SCHEMA_TOO_OLD`",
    )
    expectations = {
        "link_season_sync": (
            '`{action: "LINK", entityId, confirmEntityId}`', "never fills it", "`SEASON_SYNC_CONFIRMATION_REQUIRED`",
            "`SEASON_SYNC_REFUSED`", "`NOT_MAINTAINED_HERE`", "`NO_CONNECTION`", "`NEEDS_TOKEN`", "`BAD_ENTITY_ID`",
            "`OUTBOUND_TARGET`", "`ALREADY_LINKED`", "`SEASON_MODE_STRANDS_POLICY`", "`SEASON_START_STRANDED`",
            "`CALENDAR`", "`YEAR_ROUND`", "`MANUAL`",
        ),
        "change_season_sync_entity": (
            '`{action: "CHANGE_ENTITY", entityId, confirmEntityId}`', "never fills it",
            "`SEASON_SYNC_CONFIRMATION_REQUIRED`", "`SEASON_SYNC_REFUSED`", "`BAD_ENTITY_ID`", "`OUTBOUND_TARGET`",
            "`SEASON_SYNC_NOT_LINKED`", "the season history is kept", "reads the new helper at once",
        ),
        "stop_season_sync": ('`{action: "STOP"}`', "`SEASON_SYNC_NOT_LINKED`"),
        "resume_season_sync": (
            '`{action: "RESUME"}`', "`SEASON_SYNC_REFUSED`", "`NOT_MAINTAINED_HERE`", "`NO_CONNECTION`",
            "`NEEDS_TOKEN`", "`SEASON_SYNC_NOT_LINKED`", "`SEASON_MODE_STRANDS_POLICY`", "`SEASON_START_STRANDED`",
            "`CALENDAR`", "`YEAR_ROUND`", "`MANUAL`",
        ),
        "set_season_sync_mode": (
            '`{action: "SET_MODE", mode}`', "`FOLLOW`", "`FORCE_IN`", "`FORCE_OUT`", "`SEASON_SYNC_NOT_LINKED`",
        ),
        "unlink_season_sync": (
            '`{action: "UNLINK", confirmUnlink: true}`', "never fills it", "`SEASON_SYNC_CONFIRMATION_REQUIRED`",
            "keeps its season and its history", "the connection and the other links stay",
        ),
    }
    for name, words in expectations.items():
        doc = flat(tool(name).__doc__)
        for word in words + common:
            assert word in doc, (name, word)
        assert not any(word in doc.lower() for word in NEVER_SAID), (name, doc)
    # Each names only the refusals its command can raise (L-C8).
    for name in ("stop_season_sync", "set_season_sync_mode", "unlink_season_sync"):
        assert "`SEASON_SYNC_REFUSED`" not in flat(tool(name).__doc__), name
    for name in ("link_season_sync", "unlink_season_sync"):
        assert "`SEASON_SYNC_NOT_LINKED`" not in flat(tool(name).__doc__), name
    for name in ("change_season_sync_entity", "resume_season_sync"):
        assert "`ALREADY_LINKED`" not in flat(tool(name).__doc__), name


def test_get_season_sync_still_reads_only_and_names_the_six_and_what_stays_on_the_phone() -> None:
    """I3: the read keeps "read only"; the sentence that linking and the modes are the phone's alone is gone."""
    doc = flat(server_module.get_season_sync.__doc__)
    assert "read only" in doc
    for name in SEASON_LINK_TOOLS:
        assert f"`{name}`" in doc, name
    for words in ("Sync now", "the connection", "the token", "Forget"):
        assert words in doc, words
    assert "Linking, the follow and force modes" not in doc


# --- k22: no connection or token tool, and no confirmation filled in the source -----------------------------------


def test_no_tool_name_carries_the_connection_the_token_forget_or_disconnect() -> None:
    for name in server_module.TOOL_NAMES:
        for word in ("home_assistant", "connection", "token", "forget", "disconnect"):
            assert word not in name, (name, word)


def _module_source() -> str:
    return Path(server_module.__file__).read_text(encoding="utf-8")


def test_no_tool_sends_anything_but_a_get_to_a_ha_connection_path() -> None:
    """A source scan: every `/v1/ha-connection` string outside a docstring sits inside a `_call("GET", …)`."""
    tree = ast.parse(_module_source())
    docstrings = {
        id(node.value) for node in ast.walk(tree) if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant)
    }

    def names_ha_connection(node: ast.AST) -> bool:
        return any(
            isinstance(inner, ast.Constant) and isinstance(inner.value, str) and "/v1/ha-connection" in inner.value
            for inner in ast.walk(node)
        )

    inside_get: set[int] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "_call":
            if names_ha_connection(node):
                method = node.args[0]
                assert isinstance(method, ast.Constant) and method.value == "GET", ast.unparse(node)
                inside_get.update(id(inner) for inner in ast.walk(node))
    strays = [
        node.value for node in ast.walk(tree)
        if isinstance(node, ast.Constant) and isinstance(node.value, str) and "/v1/ha-connection" in node.value
        and id(node) not in docstrings and id(node) not in inside_get
    ]
    assert strays == [], "a /v1/ha-connection path built outside a GET call"
    assert inside_get, "the scan found the one shipped helper read"


def test_no_confirmation_is_filled_in_the_source() -> None:
    source = _module_source()
    assert '"confirmEntityId": entity_id' not in source
    assert '"confirmUnlink": True' not in source


# --- the vendored shape, and the gate's minimum last --------------------------------------------------------------


def test_the_shape_is_vendored_and_the_route_writes() -> None:
    assert command_shapes.SEASON_SYNC_KEYS == ("action", "entityId", "confirmEntityId", "mode", "confirmUnlink")
    assert not server_module._writes_nothing(PATH)
    assert server_module._SEASON_LINK_FEATURE == "an asset's Home Assistant season link"


def test_the_gate_is_schema_22() -> None:
    """A 1.9.0 phone is at 22 too and answers the 404 (D2): the gate is the lowest schema whose app can carry it."""
    assert server_module._MIN_SEASON_LINK_SCHEMA_VERSION == 22
