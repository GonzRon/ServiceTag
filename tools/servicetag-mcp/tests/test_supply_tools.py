"""#15's eight supply tools (C26, C27; rows 54, 55): the method, the path and the body each one sends.

A **supply item** is one canonical product — a cartridge, a battery pack, a belt — with its identity fields and
an ordered list of generic specifications; an **applicability row** says which supply item an asset takes and in
what role. Nothing else: the tools carry no quantity, no fitted position or date and no file (the HARD SCOPE).

`list_supply_items`, `get_supply_item` and `list_asset_supplies` read `GET /v1/supply-items`, `GET
/v1/supply-items/{id}` and `GET /v1/assets/{id}/supply-items`; `create_supply_item` sends `POST
/v1/supply-items` with only what it was given; `update_supply_item` sends `PATCH /v1/supply-items/{id}` with only
the keys it was given, because the phone's PATCH is itself the overlay — `""` clears a text field, `[]` removes
every specification, and there is no `clear_fields`; `archive_supply_item` sends `POST …/archive`;
`set_asset_supply` creates (`POST /v1/asset-supplies`) without an `asset_supply_id` and re-roles (`PATCH
/v1/asset-supplies/{id}`) with one; `remove_asset_supply` sends `DELETE /v1/asset-supplies/{id}`. Nothing deletes
a supply item. All eight speak routes a phone below schema 18 does not have, so each refuses such a phone —
reads too — by name, with nothing sent beyond the pairing's one `/v1/status` read.
"""

from __future__ import annotations

import asyncio
import json
import re

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import server as server_module

STATUS_18: dict = {
    "appVersion": "1.5.0", "apiVersion": 1, "schemaVersion": 18, "backupFormatVersion": 18, "counts": {},
}
STATUS_17: dict = dict(STATUS_18, schemaVersion=17, backupFormatVersion=17)

SUPPLY_TOOLS = (
    "list_supply_items",
    "get_supply_item",
    "create_supply_item",
    "update_supply_item",
    "archive_supply_item",
    "list_asset_supplies",
    "set_asset_supply",
    "remove_asset_supply",
)
"""C26's eight, named exactly as it names them (R15-5: the removal tool stays). The registered total was **84**
after #15 and is **89** since #47's five installed-component tools, which `test_argument_guard.py` pins."""

C2_CODES = (
    "SUPPLY_ITEM_NAME_REQUIRED",
    "SPECIFICATION_LABEL_REQUIRED",
    "SPECIFICATION_VALUE_REQUIRED",
    "SPECIFICATION_KEY_INVALID",
    "SPECIFICATION_KEY_TAKEN",
    "SUPPLY_ITEM_INVALID",
    "NO_SUCH_SUPPLY_ITEM",
    "SUPPLY_ITEM_ARCHIVED",
    "ASSET_SUPPLY_ROLE_REQUIRED",
    "ASSET_SUPPLY_TAKEN",
    "NO_SUCH_ASSET_SUPPLY",
)
"""The plan's C2 table, the eleven codes the phone answers for the supply routes."""

_IDENTITY = {"name", "category", "manufacturer", "model", "part_number", "preferred_unit", "notes", "specifications"}
ARGUMENTS = {
    "list_supply_items": set(),
    "get_supply_item": {"supply_id"},
    "create_supply_item": _IDENTITY,
    "update_supply_item": {"supply_id"} | _IDENTITY,
    "archive_supply_item": {"supply_id", "archived"},
    "list_asset_supplies": {"asset_id"},
    "set_asset_supply": {"role", "asset_id", "supply_id", "asset_supply_id"},
    "remove_asset_supply": {"asset_supply_id"},
}
"""Each tool's whole argument list (the HARD SCOPE, structurally): identity, specifications, applicability and the
role — and no other argument at all, so nothing about quantity, fitting, files or clearing by name."""

SPEC = {"id": "sp-1", "key": "micron_rating", "label": "Micron rating", "value": "5", "unit": "um"}


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def paths(api) -> list[tuple[str, str]]:
    return [(r.method, r.path) for r in api.requests]


def refusal(code: str, field: str | None = None, problems: list[str] | None = None) -> dict:
    return {"error": {"code": code, "message": "no", "field": field, "problems": problems or []}}


def supply_row(**overrides) -> dict:
    """A `SupplyItemDto` from a schema-18 phone: a fictional prefilter cartridge with one specification."""
    row = {
        "id": "si-1", "name": "Example Prefilter Cartridge", "category": "Filters",
        "manufacturer": "Example Filters Co.", "model": "PF-10", "partNumber": "EX-PF-0010",
        "preferredUnit": "ea", "notes": "", "archivedAt": None, "createdAt": 1, "updatedAt": 1,
        "specifications": [dict(SPEC, sortOrder=0)],
    }
    row.update(overrides)
    return row


def asset_supply_row(**overrides) -> dict:
    row = {"id": "as-1", "assetId": "a1", "supplyId": "si-1", "role": "Prefilter", "createdAt": 1, "updatedAt": 1}
    row.update(overrides)
    return row


@pytest.fixture
def phone18(paired):
    paired.reply("GET", "/v1/status", 200, STATUS_18)
    return paired


# --- registration -------------------------------------------------------------------------------


def test_the_eight_supply_tools_are_registered_and_guarded() -> None:
    for name in SUPPLY_TOOLS:
        assert callable(getattr(server_module, name)), f"{name} is missing"
        assert name in server_module.TOOL_NAMES, f"{name} is not in TOOL_NAMES"
        tool = server_module.mcp._tool_manager.get_tool(name)
        assert tool is not None, name
        assert tool.parameters.get("additionalProperties") is False, name
    # #47's five installed-component tools follow them, last.
    assert server_module.TOOL_NAMES[-len(SUPPLY_TOOLS) - 5:-5] == SUPPLY_TOOLS


def test_nothing_deletes_a_supply_item_and_the_overlay_has_no_clear_fields() -> None:
    """R15-5: a supply item is archived, never removed; only an applicability row is removable. And the phone's
    PATCH is the overlay, clearing by value (`""`, `[]`), so `update_supply_item` publishes no `clear_fields`."""
    for forbidden in ("delete_supply_item", "remove_supply_item", "delete_specification", "delete_asset_supply"):
        assert not hasattr(server_module, forbidden), forbidden
        assert forbidden not in server_module.TOOL_NAMES, forbidden
    for name in SUPPLY_TOOLS:
        properties = server_module.mcp._tool_manager.get_tool(name).parameters.get("properties", {})
        assert "clear_fields" not in properties, name
        assert set(properties) == ARGUMENTS[name], name


def test_the_supply_minimum_is_18_beside_the_others() -> None:
    assert server_module._MIN_SUPPLY_SCHEMA_VERSION == 18
    assert server_module._MIN_REFERENCE_ROLE_SCHEMA_VERSION == 17
    assert server_module._MIN_SCHEMA_VERSION == 8


# --- row 54: each tool's method, path and body ----------------------------------------------------


def test_the_supply_reads_get_their_paths_and_answer_as_sent(phone18) -> None:
    listing = {"supplyItems": [supply_row(), supply_row(id="si-2", archivedAt=5)]}
    detail = {"supplyItem": supply_row(), "assetSupplies": [asset_supply_row()]}
    sub = {"assetSupplies": [asset_supply_row()], "supplyItems": [supply_row()]}
    phone18.reply("GET", "/v1/supply-items", 200, listing)
    phone18.reply("GET", "/v1/supply-items/si-1", 200, detail)
    phone18.reply("GET", "/v1/assets/a1/supply-items", 200, sub)

    assert server_module.list_supply_items() == listing
    assert server_module.get_supply_item(supply_id="si-1") == detail
    assert server_module.list_asset_supplies(asset_id="a1") == sub
    assert paths(phone18) == [
        ("GET", "/v1/status"), ("GET", "/v1/supply-items"), ("GET", "/v1/supply-items/si-1"),
        ("GET", "/v1/assets/a1/supply-items"),
    ]


def test_an_empty_id_refuses_before_any_request(paired) -> None:
    for call in (
        lambda: server_module.get_supply_item(supply_id=""),
        lambda: server_module.update_supply_item(supply_id="", notes="x"),
        lambda: server_module.archive_supply_item(supply_id=""),
        lambda: server_module.list_asset_supplies(asset_id=""),
        lambda: server_module.set_asset_supply(role="Prefilter", asset_supply_id=""),
        lambda: server_module.remove_asset_supply(asset_supply_id=""),
    ):
        with pytest.raises(ToolError, match="must not be empty"):
            call()
    assert paired.requests == []


def test_create_supply_item_sends_only_what_it_was_given(phone18) -> None:
    server_module.create_supply_item(
        name="Example Prefilter Cartridge",
        manufacturer="Example Filters Co.",
        part_number="EX-PF-0010",
        specifications=[{"label": "Micron rating", "value": "5", "unit": "um"}],
    )
    assert paths(phone18) == [("GET", "/v1/status"), ("POST", "/v1/supply-items")]
    assert body_of(phone18.last()) == {
        "name": "Example Prefilter Cartridge",
        "manufacturer": "Example Filters Co.",
        "partNumber": "EX-PF-0010",
        "specifications": [{"label": "Micron rating", "value": "5", "unit": "um"}],
    }

    server_module.create_supply_item(
        name="Example Battery Pack", category="Batteries", manufacturer="Example Cells Ltd", model="BP-4",
        part_number="EX-BP-4", preferred_unit="ea", notes="The whole pack", specifications=[],
    )
    assert body_of(phone18.last()) == {
        "name": "Example Battery Pack", "category": "Batteries", "manufacturer": "Example Cells Ltd",
        "model": "BP-4", "partNumber": "EX-BP-4", "preferredUnit": "ea", "notes": "The whole pack",
        "specifications": [],
    }


def test_update_supply_item_sends_only_the_keys_it_was_given(phone18) -> None:
    """Row 54: the PATCH is the phone's overlay, so an absent key is "unchanged" there — the tool reads nothing
    and sends only what it was given. An explicit `None` is the same as omitting it (the shipped convention)."""
    server_module.update_supply_item(supply_id="si-1", notes="Change every six months")
    assert paths(phone18) == [("GET", "/v1/status"), ("PATCH", "/v1/supply-items/si-1")]
    assert body_of(phone18.last()) == {"notes": "Change every six months"}

    server_module.update_supply_item(
        supply_id="si-1", name="Example Prefilter Cartridge (5 um)", category=None, manufacturer=None, model=None,
        part_number=None, preferred_unit=None, notes=None, specifications=None,
    )
    assert body_of(phone18.last()) == {"name": "Example Prefilter Cartridge (5 um)"}


def test_update_supply_item_sends_an_empty_string_to_clear(phone18) -> None:
    """C-7: a given `""` clears an optional text field on the phone, so it reaches the wire as `""`."""
    server_module.update_supply_item(
        supply_id="si-1", category="", manufacturer="", model="", part_number="", preferred_unit="", notes="",
    )
    assert body_of(phone18.last()) == {
        "category": "", "manufacturer": "", "model": "", "partNumber": "", "preferredUnit": "", "notes": "",
    }


def test_update_supply_item_sends_an_empty_list_to_remove_specifications(phone18) -> None:
    server_module.update_supply_item(supply_id="si-1", specifications=[])
    assert body_of(phone18.last()) == {"specifications": []}


def test_update_supply_item_sends_kept_specification_rows_exactly_as_given(phone18) -> None:
    """A kept row travels with its `id`, `key` and `unit`, so the phone keeps its id and key and its content
    is unchanged; a row without an `id` is a new one. The tool neither adds nor drops a key of a row."""
    rows = [dict(SPEC, label="Micron rating (nominal)"), {"label": "Length", "value": "10", "unit": "in"}]
    server_module.update_supply_item(supply_id="si-1", specifications=rows)
    assert body_of(phone18.last()) == {"specifications": rows}


def test_update_supply_item_leaves_a_blank_name_to_the_phone(phone18) -> None:
    """The tool re-checks nothing the phone checks: a blank name is sent and the phone's 422 comes back."""
    phone18.reply("PATCH", "/v1/supply-items/si-1", 422, refusal("SUPPLY_ITEM_NAME_REQUIRED", "name"))
    with pytest.raises(ToolError) as raised:
        server_module.update_supply_item(supply_id="si-1", name="")
    assert "422 SUPPLY_ITEM_NAME_REQUIRED" in str(raised.value)
    assert "[field=name]" in str(raised.value)
    assert body_of(phone18.last()) == {"name": ""}


def test_archive_supply_item_sends_the_flag(phone18) -> None:
    server_module.archive_supply_item(supply_id="si-1")
    assert (phone18.last().method, phone18.last().path) == ("POST", "/v1/supply-items/si-1/archive")
    assert body_of(phone18.last()) == {"archived": True}

    server_module.archive_supply_item(supply_id="si-1", archived=False)
    assert body_of(phone18.last()) == {"archived": False}


def test_set_asset_supply_creates_without_an_id_and_re_roles_with_one(phone18) -> None:
    phone18.reply("POST", "/v1/asset-supplies", 201, {"assetSupply": asset_supply_row()})
    created = server_module.set_asset_supply(role="Prefilter", asset_id="a1", supply_id="si-1")
    assert created == {"assetSupply": asset_supply_row()}
    assert (phone18.last().method, phone18.last().path) == ("POST", "/v1/asset-supplies")
    assert body_of(phone18.last()) == {"assetId": "a1", "supplyId": "si-1", "role": "Prefilter"}

    server_module.set_asset_supply(role="Stage 1", asset_supply_id="as-1")
    assert (phone18.last().method, phone18.last().path) == ("PATCH", "/v1/asset-supplies/as-1")
    assert body_of(phone18.last()) == {"role": "Stage 1"}
    assert paths(phone18) == [
        ("GET", "/v1/status"), ("POST", "/v1/asset-supplies"), ("PATCH", "/v1/asset-supplies/as-1"),
    ]


def test_set_asset_supply_refuses_a_create_missing_its_asset_or_item_and_a_re_role_naming_either(paired) -> None:
    """A create names both ends; a re-role changes the role only, so an asset or a supply item given with an
    `asset_supply_id` would vanish on the way to the phone — refused instead, before any request."""
    for arguments in ({"asset_id": "a1"}, {"supply_id": "si-1"}, {}):
        with pytest.raises(ToolError, match="asset_id and supply_id"):
            server_module.set_asset_supply(role="Prefilter", **arguments)
    for arguments in ({"asset_id": "a1"}, {"supply_id": "si-1"}):
        with pytest.raises(ToolError, match="asset_supply_id"):
            server_module.set_asset_supply(role="Prefilter", asset_supply_id="as-1", **arguments)
    assert paired.requests == []


def test_remove_asset_supply_deletes_the_row(phone18) -> None:
    phone18.reply("DELETE", "/v1/asset-supplies/as-1", 204, b"")
    server_module.remove_asset_supply(asset_supply_id="as-1")
    assert paths(phone18) == [("GET", "/v1/status"), ("DELETE", "/v1/asset-supplies/as-1")]
    assert phone18.last().body == b""


@pytest.mark.parametrize(
    ("call", "method", "path", "status", "code", "field"),
    [
        pytest.param(
            lambda: server_module.create_supply_item(
                name="Example Prefilter Cartridge",
                specifications=[{"key": "length", "label": "Length", "value": "10"},
                                {"key": "length", "label": "Width", "value": "3"}],
            ),
            "POST", "/v1/supply-items", 422, "SPECIFICATION_KEY_TAKEN", "specifications", id="key-taken",
        ),
        pytest.param(
            lambda: server_module.update_supply_item(supply_id="si-9", notes="x"),
            "PATCH", "/v1/supply-items/si-9", 404, "NO_SUCH_SUPPLY_ITEM", None, id="no-such-item",
        ),
        pytest.param(
            lambda: server_module.set_asset_supply(role="Prefilter", asset_id="a1", supply_id=""),
            "POST", "/v1/asset-supplies", 404, "NO_SUCH_SUPPLY_ITEM", None, id="empty-supply-id",
        ),
        pytest.param(
            lambda: server_module.set_asset_supply(role="Spare", asset_id="a1", supply_id="si-2"),
            "POST", "/v1/asset-supplies", 409, "SUPPLY_ITEM_ARCHIVED", "supplyId", id="archived",
        ),
        pytest.param(
            lambda: server_module.set_asset_supply(role="Prefilter", asset_supply_id="as-2"),
            "PATCH", "/v1/asset-supplies/as-2", 409, "ASSET_SUPPLY_TAKEN", "role", id="taken",
        ),
        pytest.param(
            lambda: server_module.remove_asset_supply(asset_supply_id="as-9"),
            "DELETE", "/v1/asset-supplies/as-9", 404, "NO_SUCH_ASSET_SUPPLY", None, id="no-such-row",
        ),
    ],
)
def test_each_refusal_arrives_with_the_phones_code(phone18, call, method, path, status, code, field) -> None:
    """Every refusal is the phone's, carried through `_call`: the status, the code and the field it names."""
    phone18.reply(method, path, status, refusal(code, field))
    with pytest.raises(ToolError) as raised:
        call()
    text = str(raised.value)
    assert f"{status} {code}" in text, text
    if field is not None:
        assert f"[field={field}]" in text, text
    assert (phone18.last().method, phone18.last().path) == (method, path)


def test_the_supply_docstrings_name_their_routes_their_codes_and_none_of_the_line_words() -> None:
    docs = {name: " ".join((getattr(server_module, name).__doc__ or "").split()) for name in SUPPLY_TOOLS}
    for name, route in (
        ("list_supply_items", "GET /v1/supply-items"),
        ("get_supply_item", "GET /v1/supply-items/{id}"),
        ("create_supply_item", "POST /v1/supply-items"),
        ("update_supply_item", "PATCH /v1/supply-items/{id}"),
        ("archive_supply_item", "POST /v1/supply-items/{id}/archive"),
        ("list_asset_supplies", "GET /v1/assets/{id}/supply-items"),
        ("set_asset_supply", "POST /v1/asset-supplies"),
        ("set_asset_supply", "PATCH /v1/asset-supplies/{id}"),
        ("remove_asset_supply", "DELETE /v1/asset-supplies/{id}"),
    ):
        assert route in docs[name], (name, route)
    every = " ".join(docs.values())
    for code in C2_CODES + ("no_such_asset", "asset_transferred_out", "APP_SCHEMA_TOO_OLD"):
        assert f"`{code}`" in every, code
    update = docs["update_supply_item"]
    for phrase in ('`""`', "`[]`", "`id`", "`key`", "`unit`", "`SPECIFICATION_KEY_TAKEN`", "CONFLICT"):
        assert phrase in update, phrase
    assert "archived" in docs["list_supply_items"]
    assert '`""`' in docs["set_asset_supply"]
    for name, doc in docs.items():
        assert "schema 18" in doc, name
        assert "component" not in doc.lower(), name
        assert "consumable" not in doc.lower(), name
        assert not re.search(r"\bparts?\b(?! number)", doc, re.IGNORECASE), name


# --- row 55: the gate, on every supply tool --------------------------------------------------------


@pytest.mark.parametrize(
    ("tool", "call"),
    [
        pytest.param("list_supply_items", lambda: server_module.list_supply_items(), id="list"),
        pytest.param("get_supply_item", lambda: server_module.get_supply_item(supply_id="si-1"), id="get"),
        pytest.param(
            "create_supply_item", lambda: server_module.create_supply_item(name="Example Belt"), id="create"
        ),
        pytest.param(
            "update_supply_item", lambda: server_module.update_supply_item(supply_id="si-1", notes=""), id="update"
        ),
        pytest.param(
            "archive_supply_item", lambda: server_module.archive_supply_item(supply_id="si-1"), id="archive"
        ),
        pytest.param(
            "list_asset_supplies", lambda: server_module.list_asset_supplies(asset_id="a1"), id="asset-list"
        ),
        pytest.param(
            "set_asset_supply",
            lambda: server_module.set_asset_supply(role="Prefilter", asset_id="a1", supply_id="si-1"),
            id="set-create",
        ),
        pytest.param(
            "set_asset_supply",
            lambda: server_module.set_asset_supply(role="Stage 1", asset_supply_id="as-1"),
            id="set-re-role",
        ),
        pytest.param(
            "remove_asset_supply", lambda: server_module.remove_asset_supply(asset_supply_id="as-1"), id="remove"
        ),
    ],
)
def test_each_supply_tool_refuses_schema_17_with_nothing_sent(paired, tool, call) -> None:
    """Row 55 (C27): the routes are schema 18's, so a schema-17 phone is refused by name — reads too — with
    nothing sent but the pairing's one `/v1/status` read."""
    paired.reply("GET", "/v1/status", 200, STATUS_17)
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as raised:
        call()
    text = str(raised.value)
    assert f"reports schema 17; {tool} needs schema 18" in text, text
    assert "(supply items)" in text, text
    assert paths(paired) == [("GET", "/v1/status")]


def test_an_unknown_argument_is_refused_before_the_gate(paired) -> None:
    for name, arguments in (
        ("update_supply_item", {"supply_id": "si-1", "clear_fields": ["notes"]}),
        ("set_asset_supply", {"role": "Prefilter", "asset_id": "a1", "supply_id": "si-1", "quantity": "2"}),
        ("create_supply_item", {"name": "Example Belt", "url": "x"}),
    ):
        with pytest.raises(ToolError, match="does not accept"):
            asyncio.run(server_module.mcp.call_tool(name, arguments))
    assert paired.requests == []
