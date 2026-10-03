"""#47's five installed-component tools (C23, C24; rows 47, 48): the method, the path and the body each one sends,
and the schema-19 gate on every one.

An **installed component** is one fitted instance inside an asset, or inside another installed component — a battery
tray, the pack in it, a membrane in its housing — with an optional direct supply item (this unit is one of these)
and an optional ordered composition of supply items (this unit is made of these). A composition entry's quantity is
how many of that SupplyItem one unit is made of. It is not a child asset: `create_component` makes one of those, and
none of the five touches it.

`list_installed_components` reads `GET /v1/assets/{id}/installed-components`; `add_installed_component` sends `POST
/v1/installed-components` with only what it was given; `update_installed_component` sends `PATCH
/v1/installed-components/{id}` with only the keys it was given, because the phone's PATCH is itself the overlay —
`""` clears the link, the install date, the serial or lot and the notes, a given composition is the whole list,
`[]` empties it, and there is no `clear_fields`; `remove_installed_component` sends `POST …/remove`; and
`replace_installed_component` sends `POST …/replace` with only what it was given, never reading or copying the
replaced row's link or composition (R47-17b). Nothing deletes an installed component. All five speak routes a phone
below schema 19 does not have, so each refuses such a phone — the read too — by name, with nothing sent beyond the
pairing's one `/v1/status` read.
"""

from __future__ import annotations

import asyncio
import inspect
import json

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import server as server_module

STATUS_19: dict = {
    "appVersion": "1.6.0", "apiVersion": 1, "schemaVersion": 19, "backupFormatVersion": 19, "counts": {},
}
STATUS_18: dict = dict(STATUS_19, schemaVersion=18, backupFormatVersion=18)

INSTALLED_COMPONENT_TOOLS = (
    "list_installed_components",
    "add_installed_component",
    "update_installed_component",
    "remove_installed_component",
    "replace_installed_component",
)
"""C23's five, named exactly as it names them (C-4: the add verb is `add_installed_component`, beside the shipped
`create_component`). The registered total was **89** after #47 and is **90** since #16's season sync read, which
`test_argument_guard.py` pins."""

SIGNATURES = {
    "list_installed_components": ["asset_id"],
    "add_installed_component": [
        "asset_id", "name", "parent_id", "supply_id", "composition", "serial_or_lot", "installed_on", "notes",
        "sort_order",
    ],
    "update_installed_component": [
        "installed_component_id", "name", "supply_id", "composition", "serial_or_lot", "installed_on", "notes",
        "sort_order",
    ],
    "remove_installed_component": ["installed_component_id", "removed_on"],
    "replace_installed_component": [
        "installed_component_id", "replaced_on", "name", "supply_id", "composition", "serial_or_lot", "notes",
    ],
}
"""Each tool's whole argument list, in C23's order: no `clear_fields`, no removal date, parent or asset on an edit,
no parent or asset on a replace, and nothing else at all."""

C2_CODES = (
    "NO_SUCH_INSTALLED_COMPONENT",
    "INSTALLED_COMPONENT_NAME_REQUIRED",
    "INSTALLED_COMPONENT_DATE_INVALID",
    "INSTALLED_COMPONENT_DATE_AFTER_TODAY",
    "INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED",
    "INSTALLED_COMPONENT_PARENT_ON_ANOTHER_ASSET",
    "INSTALLED_COMPONENT_PARENT_REMOVED",
    "INSTALLED_COMPONENT_REMOVED",
    "COMPOSITION_QUANTITY_INVALID",
    "INSTALLED_COMPONENT_INVALID",
)
"""The plan's C2 table: the ten codes the phone answers for the installed-component routes, beside the reused
`no_such_asset`, `NO_SUCH_SUPPLY_ITEM`, `SUPPLY_ITEM_ARCHIVED` and `asset_transferred_out`."""

QUANTITY = "how many of that SupplyItem one unit is made of"
"""C23's sentence for what a composition quantity is, carried by every tool that sends a composition."""

SUBTREE = "on the same date, in the same write"
"""The words the remove and the replace use for R47-6: every current row inside the closed one closes with it."""


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def paths(api) -> list[tuple[str, str]]:
    return [(r.method, r.path) for r in api.requests]


def refusal(code: str, field: str | None = None, problems: list[str] | None = None) -> dict:
    return {"error": {"code": code, "message": "no", "field": field, "problems": problems or []}}


def entry_row(**overrides) -> dict:
    """A `CompositionEntryDto` as a schema-19 phone answers it: four of a fictional 12 V battery."""
    row = {"id": "ce-1", "supplyId": "si-1", "quantity": 4.0, "unit": "ea", "sortOrder": 0}
    row.update(overrides)
    return row


def component_row(**overrides) -> dict:
    """An `InstalledComponentDto` from a schema-19 phone: a fictional battery pack in a fictional UPS's tray, linked
    to its pack SKU and made of four 12 V batteries."""
    row = {
        "id": "ic-2", "assetId": "a1", "parentId": "ic-1", "name": "Example Battery Pack", "supplyId": "si-9",
        "composition": [entry_row()], "serialOrLot": "LOT-0001", "installedOn": "2026-03-01", "removedOn": None,
        "replacesId": None, "sortOrder": 0, "notes": "", "createdAt": 1, "updatedAt": 1,
    }
    row.update(overrides)
    return row


@pytest.fixture
def phone19(paired):
    paired.reply("GET", "/v1/status", 200, STATUS_19)
    return paired


# --- registration -------------------------------------------------------------------------------


def test_the_five_tools_are_registered_guarded_and_listed_last() -> None:
    for name in INSTALLED_COMPONENT_TOOLS:
        assert callable(getattr(server_module, name)), f"{name} is missing"
        assert name in server_module.TOOL_NAMES, f"{name} is not in TOOL_NAMES"
        tool = server_module.mcp._tool_manager.get_tool(name)
        assert tool is not None, name
        assert tool.parameters.get("additionalProperties") is False, name
    # #16's season sync read follows them, last.
    assert server_module.TOOL_NAMES[-len(INSTALLED_COMPONENT_TOOLS) - 1:-1] == INSTALLED_COMPONENT_TOOLS


def test_each_tool_takes_exactly_c23s_arguments() -> None:
    """The signatures as C23 writes them. The update is the phone's overlay and clears by value, and the replace
    gives the new unit only what it is sent, so no tool publishes a `clear_fields`."""
    for name, expected in SIGNATURES.items():
        assert list(inspect.signature(getattr(server_module, name)).parameters) == expected, name
        properties = server_module.mcp._tool_manager.get_tool(name).parameters.get("properties", {})
        assert set(properties) == set(expected), name
        assert "clear_fields" not in properties, name


def test_nothing_deletes_an_installed_component_and_create_component_stays_the_child_asset_tool() -> None:
    """A remove closes a row and keeps it as history, so no tool deletes one. The only tools whose names say
    component are the five and the shipped `create_component`, which still makes a child asset (R47-1)."""
    named = {name for name in server_module.TOOL_NAMES if "component" in name}
    assert named == {"create_component", *INSTALLED_COMPONENT_TOOLS}
    assert not any(name.startswith("delete_") for name in named)


def test_the_installed_component_minimum_is_19_beside_the_others() -> None:
    assert server_module._MIN_INSTALLED_COMPONENT_SCHEMA_VERSION == 19
    assert server_module._MIN_SUPPLY_SCHEMA_VERSION == 18
    assert server_module._MIN_SCHEMA_VERSION == 8


# --- row 47: each tool's method, path and body -----------------------------------------------------


def test_list_installed_components_reads_the_sub_resource_and_answers_as_sent(phone19) -> None:
    listing = {
        "installedComponents": [component_row(), component_row(id="ic-3", removedOn="2026-09-01", composition=[])],
        "supplyItems": [{"id": "si-1", "name": "Example 12 V Battery"}],
    }
    phone19.reply("GET", "/v1/assets/a1/installed-components", 200, listing)
    assert server_module.list_installed_components(asset_id="a1") == listing
    assert paths(phone19) == [("GET", "/v1/status"), ("GET", "/v1/assets/a1/installed-components")]


def test_an_empty_id_refuses_before_any_request(paired) -> None:
    for call in (
        lambda: server_module.list_installed_components(asset_id=""),
        lambda: server_module.update_installed_component(installed_component_id="", notes="x"),
        lambda: server_module.remove_installed_component(installed_component_id="", removed_on="2026-09-30"),
        lambda: server_module.replace_installed_component(installed_component_id="", replaced_on="2026-09-30"),
    ):
        with pytest.raises(ToolError, match="must not be empty"):
            call()
    assert paired.requests == []


def test_add_installed_component_sends_only_what_it_was_given(phone19) -> None:
    phone19.reply("POST", "/v1/installed-components", 201, {"installedComponent": component_row()})
    answer = server_module.add_installed_component(asset_id="a1", name="Example Battery Tray")
    assert answer == {"installedComponent": component_row()}
    assert paths(phone19) == [("GET", "/v1/status"), ("POST", "/v1/installed-components")]
    assert body_of(phone19.last()) == {"assetId": "a1", "name": "Example Battery Tray"}

    server_module.add_installed_component(
        asset_id="a1", name="Example Battery Pack", parent_id="ic-1", supply_id="si-9",
        composition=[{"supplyId": "si-1", "quantity": 4, "unit": "ea"}], serial_or_lot="LOT-0001",
        installed_on="2026-03-01", notes="Front bay", sort_order=0,
    )
    sent = body_of(phone19.last())
    assert sent == {
        "assetId": "a1", "parentId": "ic-1", "name": "Example Battery Pack", "supplyId": "si-9",
        "composition": [{"supplyId": "si-1", "quantity": 4, "unit": "ea"}], "serialOrLot": "LOT-0001",
        "installedOn": "2026-03-01", "notes": "Front bay", "sortOrder": 0,
    }
    assert type(sent["composition"][0]["quantity"]) is int


def test_add_installed_component_sends_an_empty_parent_as_given(phone19) -> None:
    """The tool re-checks nothing the phone checks: a `parent_id` of `""` is sent as `""`, and the phone's 404 naming
    `parentId` comes back — it is not read as "fitted straight into the asset"."""
    phone19.reply("POST", "/v1/installed-components", 404, refusal("NO_SUCH_INSTALLED_COMPONENT", "parentId"))
    with pytest.raises(ToolError) as raised:
        server_module.add_installed_component(asset_id="a1", name="Example Battery Pack", parent_id="")
    assert "404 NO_SUCH_INSTALLED_COMPONENT" in str(raised.value)
    assert "[field=parentId]" in str(raised.value)
    assert body_of(phone19.last()) == {"assetId": "a1", "name": "Example Battery Pack", "parentId": ""}


def test_update_sends_only_given_keys(phone19) -> None:
    """Row 47: the PATCH is the phone's overlay, so an absent key is "unchanged" there — the tool reads nothing and
    sends only what it was given. An explicit `None` is the same as omitting it (the shipped convention)."""
    server_module.update_installed_component(installed_component_id="ic-2", notes="Front strap swapped")
    assert paths(phone19) == [("GET", "/v1/status"), ("PATCH", "/v1/installed-components/ic-2")]
    assert body_of(phone19.last()) == {"notes": "Front strap swapped"}

    server_module.update_installed_component(
        installed_component_id="ic-2", name="Example Battery Pack (front)", supply_id=None, composition=None,
        serial_or_lot=None, installed_on=None, notes=None, sort_order=None,
    )
    assert body_of(phone19.last()) == {"name": "Example Battery Pack (front)"}

    server_module.update_installed_component(installed_component_id="ic-2", sort_order=0)
    assert body_of(phone19.last()) == {"sortOrder": 0}
    assert [method for method, _ in paths(phone19)] == ["GET", "PATCH", "PATCH", "PATCH"]


def test_update_sends_an_empty_string_to_clear(phone19) -> None:
    """A given `""` clears the direct link, the install date, the serial or lot and the notes on the phone, so it
    reaches the wire as `""`."""
    server_module.update_installed_component(
        installed_component_id="ic-2", supply_id="", installed_on="", serial_or_lot="", notes="",
    )
    assert body_of(phone19.last()) == {"supplyId": "", "installedOn": "", "serialOrLot": "", "notes": ""}


def test_composition_is_sent_whole_and_an_empty_list_empties(phone19) -> None:
    """A given composition is the whole ordered list: a kept entry travels with its `id`, a new one without, each
    entry with only the keys it was given and its quantity a JSON number; `[]` empties it."""
    entries = [
        {"id": "ce-1", "supplyId": "si-1", "quantity": 4, "unit": "ea"},
        {"supplyId": "si-2", "quantity": 0.5, "unit": "m"},
        {"supplyId": "si-3", "quantity": 2},
    ]
    server_module.update_installed_component(installed_component_id="ic-2", composition=entries)
    sent = body_of(phone19.last())
    assert sent == {"composition": entries}
    assert [type(entry["quantity"]) for entry in sent["composition"]] == [int, float, int]

    server_module.update_installed_component(installed_component_id="ic-2", composition=[])
    assert body_of(phone19.last()) == {"composition": []}


def test_a_composition_read_back_travels_without_its_sort_order(phone19) -> None:
    """`list_installed_components` answers each entry with its `sortOrder`, which the phone sets from the entry's
    place in the list and refuses in a body. So a composition passed back as it was read is sent in the order given,
    every other key exactly as read and no `sortOrder` — on the add, the update and the replace alike — and the
    caller's own list is left as it was."""
    read = [entry_row(), entry_row(id="ce-2", supplyId="si-2", quantity=1.0, unit="", sortOrder=1)]
    expected = [
        {"id": "ce-1", "supplyId": "si-1", "quantity": 4.0, "unit": "ea"},
        {"id": "ce-2", "supplyId": "si-2", "quantity": 1.0, "unit": ""},
    ]
    server_module.add_installed_component(asset_id="a1", name="Example Battery Pack", composition=read)
    assert body_of(phone19.last()) == {"assetId": "a1", "name": "Example Battery Pack", "composition": expected}
    server_module.update_installed_component(installed_component_id="ic-2", composition=read)
    assert body_of(phone19.last()) == {"composition": expected}
    server_module.replace_installed_component(
        installed_component_id="ic-2", replaced_on="2026-09-30", composition=read,
    )
    assert body_of(phone19.last()) == {"replacedOn": "2026-09-30", "composition": expected}
    assert read == [entry_row(), entry_row(id="ce-2", supplyId="si-2", quantity=1.0, unit="", sortOrder=1)]


def test_update_leaves_a_blank_name_to_the_phone(phone19) -> None:
    """The tool re-checks nothing the phone checks: a blank name is sent and the phone's 422 comes back."""
    phone19.reply(
        "PATCH", "/v1/installed-components/ic-2", 422, refusal("INSTALLED_COMPONENT_NAME_REQUIRED", "name"),
    )
    with pytest.raises(ToolError) as raised:
        server_module.update_installed_component(installed_component_id="ic-2", name="")
    assert "422 INSTALLED_COMPONENT_NAME_REQUIRED" in str(raised.value)
    assert "[field=name]" in str(raised.value)
    assert body_of(phone19.last()) == {"name": ""}


def test_remove_sends_its_date_and_answers_the_closed_rows(phone19) -> None:
    answer = {
        "installedComponent": component_row(id="ic-1", parentId=None, removedOn="2026-09-30"),
        "closed": [component_row(removedOn="2026-09-30")],
    }
    phone19.reply("POST", "/v1/installed-components/ic-1/remove", 200, answer)
    assert server_module.remove_installed_component(installed_component_id="ic-1", removed_on="2026-09-30") == answer
    assert paths(phone19) == [("GET", "/v1/status"), ("POST", "/v1/installed-components/ic-1/remove")]
    assert body_of(phone19.last()) == {"removedOn": "2026-09-30"}


def test_replace_sends_no_composition_or_link_unless_given(phone19) -> None:
    """Row 47, R47-17b: an omitted `supply_id` or `composition` gives the new unit none. The tool never reads the
    replaced row and never copies its link or its composition: a caller keeps them by passing them."""
    answer = {
        "installedComponent": component_row(
            id="ic-4", supplyId=None, composition=[], serialOrLot="", installedOn="2026-09-30", replacesId="ic-2",
        ),
        "replaced": component_row(removedOn="2026-09-30"),
        "closed": [],
    }
    phone19.reply("POST", "/v1/installed-components/ic-2/replace", 201, answer)
    assert server_module.replace_installed_component(
        installed_component_id="ic-2", replaced_on="2026-09-30",
    ) == answer
    assert paths(phone19) == [("GET", "/v1/status"), ("POST", "/v1/installed-components/ic-2/replace")]
    assert body_of(phone19.last()) == {"replacedOn": "2026-09-30"}

    server_module.replace_installed_component(
        installed_component_id="ic-2", replaced_on="2026-09-30", name="Example Battery Pack (2026)",
        supply_id="si-9", composition=[{"supplyId": "si-1", "quantity": 4, "unit": "ea"}],
        serial_or_lot="LOT-0002", notes="Replaced whole",
    )
    assert body_of(phone19.last()) == {
        "replacedOn": "2026-09-30", "name": "Example Battery Pack (2026)", "supplyId": "si-9",
        "composition": [{"supplyId": "si-1", "quantity": 4, "unit": "ea"}], "serialOrLot": "LOT-0002",
        "notes": "Replaced whole",
    }

    server_module.replace_installed_component(
        installed_component_id="ic-2", replaced_on="2026-09-30", supply_id="", composition=[],
    )
    assert body_of(phone19.last()) == {"replacedOn": "2026-09-30", "supplyId": "", "composition": []}
    assert [method for method, _ in paths(phone19)] == ["GET", "POST", "POST", "POST"]


@pytest.mark.parametrize(
    ("call", "method", "path", "status", "code", "field", "problems"),
    [
        pytest.param(
            lambda: server_module.list_installed_components(asset_id="a9"),
            "GET", "/v1/assets/a9/installed-components", 404, "no_such_asset", None, [], id="no-such-asset",
        ),
        pytest.param(
            lambda: server_module.update_installed_component(installed_component_id="ic-9", notes="x"),
            "PATCH", "/v1/installed-components/ic-9", 404, "NO_SUCH_INSTALLED_COMPONENT", None, [], id="no-such-row",
        ),
        pytest.param(
            lambda: server_module.add_installed_component(
                asset_id="a1", name="Example Fan", installed_on="2099-01-01",
            ),
            "POST", "/v1/installed-components", 422, "INSTALLED_COMPONENT_DATE_AFTER_TODAY", "installedOn",
            ["AfterToday(field=installedOn)"], id="after-today",
        ),
        pytest.param(
            lambda: server_module.add_installed_component(asset_id="a1", name="Example Fan", parent_id="ic-8"),
            "POST", "/v1/installed-components", 409, "INSTALLED_COMPONENT_PARENT_REMOVED", "parentId", [],
            id="parent-removed",
        ),
        pytest.param(
            lambda: server_module.add_installed_component(
                asset_id="a1", name="Example Battery Pack", composition=[{"supplyId": "si-1", "quantity": 0}],
            ),
            "POST", "/v1/installed-components", 422, "COMPOSITION_QUANTITY_INVALID", "composition",
            ["QuantityInvalid(index=0)"], id="quantity",
        ),
        pytest.param(
            lambda: server_module.add_installed_component(
                asset_id="a1", name="Example Battery Pack", composition=[{"supplyId": "si-1", "quantity": "4"}],
            ),
            "POST", "/v1/installed-components", 400, "bad_request", None, [], id="quoted-quantity",
        ),
        pytest.param(
            lambda: server_module.remove_installed_component(installed_component_id="ic-3", removed_on="2026-09-30"),
            "POST", "/v1/installed-components/ic-3/remove", 409, "INSTALLED_COMPONENT_REMOVED", None, [],
            id="already-removed",
        ),
        pytest.param(
            lambda: server_module.remove_installed_component(installed_component_id="ic-2", removed_on="2026-01-01"),
            "POST", "/v1/installed-components/ic-2/remove", 422, "INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED",
            "removedOn", ["RemovedBeforeInstalled(field=removedOn)"], id="before-installed",
        ),
        pytest.param(
            lambda: server_module.replace_installed_component(
                installed_component_id="ic-2", replaced_on="2026-09-30",
                composition=[{"supplyId": "si-7", "quantity": 4}],
            ),
            "POST", "/v1/installed-components/ic-2/replace", 409, "SUPPLY_ITEM_ARCHIVED", "composition",
            ["EntrySupplyItemArchived(index=0)"], id="archived-entry",
        ),
        pytest.param(
            lambda: server_module.replace_installed_component(
                installed_component_id="ic-2", replaced_on="2026-09-30", supply_id="si-8",
            ),
            "POST", "/v1/installed-components/ic-2/replace", 404, "NO_SUCH_SUPPLY_ITEM", "supplyId", [],
            id="no-such-supply-item",
        ),
        pytest.param(
            lambda: server_module.update_installed_component(installed_component_id="ic-5", notes="x"),
            "PATCH", "/v1/installed-components/ic-5", 409, "asset_transferred_out", None, [], id="held",
        ),
    ],
)
def test_each_refusal_arrives_as_a_tool_error_with_the_phones_code(
    phone19, call, method, path, status, code, field, problems,
) -> None:
    """Every refusal is the phone's, carried through `_call` as a real `ToolError`: the status, the code, the field
    it names and every problem."""
    phone19.reply(method, path, status, refusal(code, field, problems))
    with pytest.raises(ToolError) as raised:
        call()
    text = str(raised.value)
    assert f"{status} {code}" in text, text
    if field is not None:
        assert f"[field={field}]" in text, text
    for problem in problems:
        assert problem in text, text
    assert (phone19.last().method, phone19.last().path) == (method, path)


def test_a_refusal_and_an_omitted_argument_survive_the_sdk_boundary(phone19) -> None:
    """Through `MCPServer.call_tool`, the path a real client takes (the #46/#53 lesson): a `null` argument is the
    same as an omitted one, so a strict-function-calling client's edit of the notes sends the notes alone; and the
    phone's refusal reaches the client with its code and field, not the SDK's bare "Error executing tool"."""
    asyncio.run(server_module.mcp.call_tool("update_installed_component", {
        "installed_component_id": "ic-2", "notes": "Front strap swapped", "name": None, "supply_id": None,
        "composition": None, "serial_or_lot": None, "installed_on": None, "sort_order": None,
    }))
    assert body_of(phone19.last()) == {"notes": "Front strap swapped"}

    phone19.reply(
        "POST", "/v1/installed-components/ic-2/remove", 422,
        refusal("INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED", "removedOn"),
    )
    with pytest.raises(ToolError) as raised:
        asyncio.run(server_module.mcp.call_tool(
            "remove_installed_component", {"installed_component_id": "ic-2", "removed_on": "2026-01-01"},
        ))
    assert "422 INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED" in str(raised.value)
    assert "[field=removedOn]" in str(raised.value)


def test_the_docstrings_name_their_routes_their_codes_and_their_rules() -> None:
    docs = {
        name: " ".join((getattr(server_module, name).__doc__ or "").split()) for name in INSTALLED_COMPONENT_TOOLS
    }
    for name, route in (
        ("list_installed_components", "GET /v1/assets/{id}/installed-components"),
        ("add_installed_component", "POST /v1/installed-components"),
        ("update_installed_component", "PATCH /v1/installed-components/{id}"),
        ("remove_installed_component", "POST /v1/installed-components/{id}/remove"),
        ("replace_installed_component", "POST /v1/installed-components/{id}/replace"),
    ):
        assert route in docs[name], (name, route)
    every = " ".join(docs.values())
    for code in C2_CODES + (
        "no_such_asset", "NO_SUCH_SUPPLY_ITEM", "SUPPLY_ITEM_ARCHIVED", "asset_transferred_out", "APP_SCHEMA_TOO_OLD",
    ):
        assert f"`{code}`" in every, code
    for name, doc in docs.items():
        assert "not `create_component`" in doc, name
        assert "schema 19" in doc, name
    for name in ("add_installed_component", "update_installed_component", "replace_installed_component"):
        assert QUANTITY in docs[name], name
        assert "`sortOrder`" in docs[name], name
    for name in ("remove_installed_component", "replace_installed_component"):
        assert SUBTREE in docs[name], name
    update = docs["update_installed_component"]
    for phrase in ('`""`', "`[]`", "`id`", "once", "no `clear_fields`"):
        assert phrase in update, phrase
    replace = docs["replace_installed_component"]
    for phrase in ("**none**", "`list_installed_components`", "never copies"):
        assert phrase in replace, phrase


# --- row 48: the gate, on every installed-component tool -------------------------------------------


@pytest.mark.parametrize(
    ("tool", "call"),
    [
        pytest.param(
            "list_installed_components", lambda: server_module.list_installed_components(asset_id="a1"), id="list",
        ),
        pytest.param(
            "add_installed_component",
            lambda: server_module.add_installed_component(asset_id="a1", name="Example Battery Tray"),
            id="add",
        ),
        pytest.param(
            "update_installed_component",
            lambda: server_module.update_installed_component(installed_component_id="ic-2", notes=""),
            id="update",
        ),
        pytest.param(
            "remove_installed_component",
            lambda: server_module.remove_installed_component(installed_component_id="ic-2", removed_on="2026-09-30"),
            id="remove",
        ),
        pytest.param(
            "replace_installed_component",
            lambda: server_module.replace_installed_component(installed_component_id="ic-2", replaced_on="2026-09-30"),
            id="replace",
        ),
    ],
)
def test_each_installed_component_tool_refuses_schema_18_with_nothing_sent(paired, tool, call) -> None:
    """Row 48 (C24): the routes are schema 19's, so a schema-18 phone is refused by name — the read too — with
    nothing sent but the pairing's one `/v1/status` read, and the refusal names the feature (G3)."""
    paired.reply("GET", "/v1/status", 200, STATUS_18)
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as raised:
        call()
    text = str(raised.value)
    assert f"reports schema 18; {tool} needs schema 19" in text, text
    assert "(installed components)" in text, text
    assert paths(paired) == [("GET", "/v1/status")]


def test_an_unknown_argument_is_refused_before_the_gate(paired) -> None:
    """No `clear_fields` on the overlay, no removal date or parent on an edit, no parent on a replace: each is an
    unknown argument, refused before anything is sent."""
    for name, arguments in (
        ("update_installed_component", {"installed_component_id": "ic-2", "clear_fields": ["notes"]}),
        ("update_installed_component", {"installed_component_id": "ic-2", "removed_on": "2026-09-30"}),
        ("update_installed_component", {"installed_component_id": "ic-2", "parent_id": "ic-1"}),
        (
            "replace_installed_component",
            {"installed_component_id": "ic-2", "replaced_on": "2026-09-30", "parent_id": "ic-1"},
        ),
        ("add_installed_component", {"asset_id": "a1", "name": "Example Fan", "quantity": 2}),
    ):
        with pytest.raises(ToolError, match="does not accept"):
            asyncio.run(server_module.mcp.call_tool(name, arguments))
    assert paired.requests == []
