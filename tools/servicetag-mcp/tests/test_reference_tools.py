"""The three 1.3 tools: the method, the path and the body each one sends — and the conventions a
reference tool is most likely to get wrong.

One case per hazard, not per permutation. Two hazards carrying the most risk here are about a field
that is **not** an argument. `kind` is derived from the URI's scheme and is an unknown field on both
API commands, so neither tool may offer one — a tool that did would send a field the phone answers
400 for, every time. And **neither the name nor the description has a `clear_fields` name**:
`display_name` cannot be cleared at all (the app requires it non-blank) and `description` is
cleared **by value**, `description=""`, since it is a non-null column with an empty default. So
`None` there means only "leave it alone", and the case below proves the wire carries nothing at all
for it.

#91 adds a reference's one nullable field, its document role. `add_reference` has no `clear_fields`,
since a create has nothing to clear; `update_reference` clears the role, and only the role, by name —
`clear_fields=["role"]`, sent as `"role": null`, which the phone's PATCH reads as a clear where an
absent `role` is "unchanged". A role given or cleared needs a phone at schema 17 and is refused below
it with nothing sent; without one, both tools behave exactly as before against any phone (R91-10).
"""

from __future__ import annotations

import asyncio
import json

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import server as server_module

REFERENCE_TOOLS = ("list_references", "add_reference", "update_reference")
"""Master plan §7's three, named exactly as it names them. The registered total was 38 + 3 = **41**
at 1.3, **55** after 1.4's fourteen, **56** after 1.4.1's provider repair, **58** after #79's
two warranty tools, **63** after #79's five service-case tools, **68** after #72's five loan tools, **69**
after #86's succession read, **74** after #92's five attachment tools, **76** after #92's two replace tools,
**84** after #15's eight supply tools, and is **89** since #47's five installed-component tools, which
`test_argument_guard.py` pins."""

_DISTINCTIVE_VALUE = "XVALUE_MARKER_1c4e7b_must_never_appear_in_a_message"


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def _call_tool(name: str, arguments: dict):
    return asyncio.run(server_module.mcp.call_tool(name, arguments))


# --- registration -------------------------------------------------------------------------------


def test_every_reference_tool_is_registered_and_guarded() -> None:
    for name in REFERENCE_TOOLS:
        assert callable(getattr(server_module, name)), f"{name} is missing"
        assert name in server_module.TOOL_NAMES, f"{name} is not in TOOL_NAMES"
        tool = server_module.mcp._tool_manager.get_tool(name)
        assert tool is not None, name
        assert tool.parameters.get("additionalProperties") is False, name


def test_the_registered_tool_count_is_89() -> None:
    """A tool added without updating `TOOL_NAMES` makes `_forbid_unknown_arguments` raise at
    *import* time, which is the intended loud failure — so this file importing at all is half the
    proof, and the counts below are the other half."""
    assert len(server_module.TOOL_NAMES) == 89
    assert len(server_module.mcp._tool_manager.list_tools()) == 89


def test_nothing_deletes_a_reference_and_no_tool_takes_a_kind() -> None:
    """The API adds and amends a reference; the phone removes one. And `kind` is derived from the
    scheme, so a tool offering one would let a caller state a fact it cannot set. Since #92 a file is
    added by `add_attachment` or saved from a reference by `materialize_reference`, and nothing deletes
    an attachment either."""
    for forbidden in ("delete_reference", "remove_reference", "delete_attachment", "remove_attachment", "share"):
        assert not hasattr(server_module, forbidden), forbidden
        assert forbidden not in server_module.TOOL_NAMES, forbidden
    for name in ("add_reference", "update_reference"):
        parameters = server_module.mcp._tool_manager.get_tool(name).parameters["properties"]
        assert "kind" not in parameters, name
    # #91 (R91-3): a create has nothing to clear; the edit clears the role, and only the role, by name.
    assert "clear_fields" not in server_module.mcp._tool_manager.get_tool("add_reference").parameters["properties"]
    assert "clear_fields" in server_module.mcp._tool_manager.get_tool("update_reference").parameters["properties"]
    assert server_module._REFERENCE_CLEARABLE_FIELDS == frozenset({"role"})


# --- the paths and the bodies ---------------------------------------------------------------------


def test_list_references_gets_the_asset_sub_resource(paired) -> None:
    paired.reply("GET", "/v1/assets/a1/references", 200, {"references": []})
    server_module.list_references(asset_id="a1")
    assert paired.last().method == "GET"
    assert paired.last().path == "/v1/assets/a1/references"


def test_an_empty_id_refuses_before_any_request(paired) -> None:
    for call in (
        lambda: server_module.list_references(asset_id=""),
        lambda: server_module.update_reference(reference_id="", display_name="x"),
    ):
        with pytest.raises(ToolError, match="must not be empty"):
            call()
    assert paired.requests == []


def test_add_reference_sends_the_four_fields_and_never_a_kind(paired) -> None:
    server_module.add_reference(
        asset_id="a1",
        uri="https://example.invalid/mower?page=3#oil",
        display_name="Mower manual",
        description="the PDF",
    )
    recorded = paired.last()
    assert recorded.method == "POST"
    assert recorded.path == "/v1/references"
    assert body_of(recorded) == {
        "assetId": "a1",
        "uri": "https://example.invalid/mower?page=3#oil",
        "displayName": "Mower manual",
        "description": "the PDF",
    }


def test_add_reference_omits_a_description_it_was_not_given_and_still_succeeds(paired) -> None:
    """A create has no existing row for an omission to overwrite, so an omitted field is correctly
    the API's own default — an empty description.

    The reply is asserted too, not just the body: this three-field body is the one this tool sends
    on every call that names no description, and `ReferenceRoutesTest`'s
    `aCreateWithNoDescriptionAtAllIs201AndTheRowCarriesAnEmptyOne` is the other half of the same
    proof, on the phone's side of the wire.
    """
    created = {
        "reference": {
            "id": "r1",
            "assetId": "a1",
            "kind": "WEB_URL",
            "uri": "https://example.invalid/mower",
            "displayName": "Mower manual",
            "description": "",
            "scheme": "https",
            "createdAt": 1,
            "updatedAt": 1,
        }
    }
    paired.reply("POST", "/v1/references", 201, created)

    answer = server_module.add_reference(
        asset_id="a1", uri="https://example.invalid/mower", display_name="Mower manual"
    )
    assert "description" not in body_of(paired.last())
    assert answer == created


def test_update_reference_sends_only_the_fields_it_was_given(paired) -> None:
    """`None` is "leave it alone" and reaches the wire as nothing at all; `""` is a value, and
    blanking the description is exactly that value."""
    server_module.update_reference(reference_id="r1", display_name="Manual")
    recorded = paired.last()
    assert recorded.method == "PATCH"
    assert recorded.path == "/v1/references/r1"
    assert body_of(recorded) == {"displayName": "Manual"}

    server_module.update_reference(reference_id="r1", description=None)
    assert body_of(paired.last()) == {}

    server_module.update_reference(reference_id="r1", description="")
    assert body_of(paired.last()) == {"description": ""}


# --- the refusals ---------------------------------------------------------------------------------


def test_each_reference_tool_surfaces_the_code_as_a_tool_error(paired) -> None:
    """An SDK exception that is not a `ToolError` is discarded and wrapped as "Error executing
    tool", and the model learns nothing — so every error path has to come back through `_call`."""
    for call, method, path, status, code in (
        (
            lambda: server_module.list_references(asset_id="a1"),
            "GET",
            "/v1/assets/a1/references",
            404,
            "no_such_asset",
        ),
        (
            lambda: server_module.add_reference(
                asset_id="a1", uri="zotero://select/items/0", display_name="A citation"
            ),
            "POST",
            "/v1/references",
            422,
            "REFERENCE_SCHEME_BLOCKED",
        ),
        (
            lambda: server_module.update_reference(reference_id="r1", display_name="  "),
            "PATCH",
            "/v1/references/r1",
            422,
            "REFERENCE_NAME_REQUIRED",
        ),
    ):
        paired.reply(
            method, path, status, {"error": {"code": code, "message": "no", "problems": []}}
        )
        with pytest.raises(ToolError) as raised:
            call()
        assert code in str(raised.value)


def test_an_unknown_argument_is_refused_naming_it_and_never_its_value(paired) -> None:
    """The guard covers a new tool only if the tool is registered on the strict server; a hole here
    is silent, which is why each of the three is called with one bogus key of its own."""
    for name, arguments in (
        (
            "add_reference",
            {
                "asset_id": "a1",
                "uri": "https://example.invalid/a",
                "display_name": "A",
                "provenance": _DISTINCTIVE_VALUE,
            },
        ),
        ("update_reference", {"reference_id": "r1", "uri": _DISTINCTIVE_VALUE}),
        ("list_references", {"asset_id": "a1", "kind": _DISTINCTIVE_VALUE}),
    ):
        with pytest.raises(ToolError) as raised:
            _call_tool(name, arguments)
        text = str(raised.value)
        assert "does not accept" in text
        assert _DISTINCTIVE_VALUE not in text
    assert paired.requests == []


def test_list_references_refuses_an_answer_it_cannot_read(paired) -> None:
    """A read that hands the model a `KeyError` traceback has told it nothing. Two shapes: the
    wrapper that is not a list, and a row missing one of the nine fields."""
    paired.reply("GET", "/v1/assets/a1/references", 200, {"references": "nope"})
    with pytest.raises(ToolError, match="references"):
        server_module.list_references(asset_id="a1")

    paired.reply(
        "GET",
        "/v1/assets/a1/references",
        200,
        {
            "references": [
                {
                    "id": "r1",
                    "assetId": "a1",
                    "kind": "WEB_URL",
                    "uri": "https://example.invalid/a",
                }
            ]
        },
    )
    with pytest.raises(ToolError, match="displayName"):
        server_module.list_references(asset_id="a1")

    paired.reply("GET", "/v1/assets/a1/references", 200, {"references": ["nope"]})
    with pytest.raises(ToolError, match="was not an object"):
        server_module.list_references(asset_id="a1")


# --- #91: the document role on a web reference (rows 34–36, 38) -----------------------------------

LINK = "https://manuals.example.invalid/water-heater/manual.pdf"

STATUS_16: dict = {
    "appVersion": "1.5.0", "apiVersion": 1, "schemaVersion": 16, "backupFormatVersion": 16, "counts": {},
}
STATUS_17: dict = dict(STATUS_16, schemaVersion=17, backupFormatVersion=17)


def reference_row(**overrides) -> dict:
    """A schema-16 row — the nine fields and no `role` — unless an override adds one."""
    row = {
        "id": "r1", "assetId": "a1", "kind": "WEB_URL", "uri": LINK, "displayName": "Example Water Heater manual",
        "description": "", "scheme": "https", "createdAt": 1, "updatedAt": 1,
    }
    row.update(overrides)
    return row


def paths(api) -> list[tuple[str, str]]:
    return [(r.method, r.path) for r in api.requests]


def test_add_reference_sends_a_role_it_was_given(paired) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_17)
    server_module.add_reference(
        asset_id="a1", uri=LINK, display_name="Example Water Heater manual", role="USER_MANUAL"
    )
    assert paths(paired) == [("GET", "/v1/status"), ("POST", "/v1/references")]
    assert body_of(paired.last()) == {
        "assetId": "a1", "uri": LINK, "displayName": "Example Water Heater manual", "role": "USER_MANUAL",
    }


def test_without_a_role_both_writes_reach_a_schema_16_phone_with_no_role_key(paired) -> None:
    """R91-10: the gate is the role's, not the tools'. With no role given or cleared, a schema-16 phone still
    takes the create and the edit, and neither body names `role` — absent is no role on a create and
    "unchanged" on an edit, and a schema-16 phone's strict decoder would answer 400 for the key."""
    paired.reply("GET", "/v1/status", 200, STATUS_16)
    server_module.add_reference(asset_id="a1", uri=LINK, display_name="Example Water Heater manual")
    assert "role" not in body_of(paired.last())
    server_module.update_reference(reference_id="r1", display_name="Heater manual", role=None, clear_fields=None)
    assert body_of(paired.last()) == {"displayName": "Heater manual"}
    assert paths(paired) == [
        ("GET", "/v1/status"), ("POST", "/v1/references"), ("PATCH", "/v1/references/r1"),
    ]


@pytest.mark.parametrize(
    ("tool", "call"),
    [
        pytest.param(
            "add_reference",
            lambda: server_module.add_reference(
                asset_id="a1", uri=LINK, display_name="Example Water Heater manual", role="USER_MANUAL"
            ),
            id="add_reference-role",
        ),
        pytest.param(
            "update_reference",
            lambda: server_module.update_reference(reference_id="r1", role="SERVICE_MANUAL"),
            id="update_reference-role",
        ),
        pytest.param(
            "update_reference",
            lambda: server_module.update_reference(reference_id="r1", clear_fields=["role"]),
            id="update_reference-clear",
        ),
    ],
)
def test_a_role_refuses_schema_16_with_nothing_sent(paired, tool, call) -> None:
    """C19: a role given or cleared is a key a schema-16 phone answers 400 for, so it is refused here by name,
    with nothing sent but the pairing's one `/v1/status` read."""
    paired.reply("GET", "/v1/status", 200, STATUS_16)
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as raised:
        call()
    text = str(raised.value)
    assert f"reports schema 16; {tool} needs schema 17" in text, text
    assert "the reference document role" in text, text
    assert paths(paired) == [("GET", "/v1/status")]


def test_update_reference_sends_a_role_it_was_given(paired) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_17)
    server_module.update_reference(reference_id="r1", role="SERVICE_MANUAL")
    recorded = paired.last()
    assert (recorded.method, recorded.path) == ("PATCH", "/v1/references/r1")
    assert body_of(recorded) == {"role": "SERVICE_MANUAL"}


def test_update_reference_clears_the_role_by_name_as_an_explicit_null(paired) -> None:
    """R91-3: the phone's PATCH reads an absent `role` as "unchanged" and `null` as a clear, so the clear has to
    reach the wire as the key with a `null` — the one place in this tool a `null` is sent at all."""
    paired.reply("GET", "/v1/status", 200, STATUS_17)
    server_module.update_reference(reference_id="r1", clear_fields=["role"])
    recorded = paired.last()
    assert (recorded.method, recorded.path) == ("PATCH", "/v1/references/r1")
    assert body_of(recorded) == {"role": None}

    server_module.update_reference(reference_id="r1", display_name="Heater manual", clear_fields=["role"])
    assert body_of(paired.last()) == {"displayName": "Heater manual", "role": None}


def test_update_reference_clears_only_the_role_and_never_a_role_it_was_also_given(paired) -> None:
    """The shipped `clear_fields` rules: a name outside the clearable set, and a field both given and cleared,
    are each refused before any request — the name and the description stay uncleared by name."""
    for clear, given in (
        (["description"], {}),
        (["display_name"], {}),
        (["kind"], {}),
        (["role"], {"role": "USER_MANUAL"}),
    ):
        with pytest.raises(ToolError, match="clear_fields"):
            server_module.update_reference(reference_id="r1", clear_fields=clear, **given)
    assert paired.requests == []


def test_list_references_reads_rows_with_and_without_a_role(paired) -> None:
    """A schema-16 phone's rows carry no `role`; a schema-17 phone's carry one, a name or `null`. `role` is never
    among the fields the read requires, so all three pass and the answer comes back exactly as sent."""
    rows = [reference_row(id="r1"), reference_row(id="r2", role="USER_MANUAL"), reference_row(id="r3", role=None)]
    paired.reply("GET", "/v1/assets/a1/references", 200, {"references": rows})
    assert server_module.list_references(asset_id="a1") == {"references": rows}
    assert len(server_module._REFERENCE_FIELDS) == 9 and "role" not in server_module._REFERENCE_FIELDS


def test_the_reference_docstrings_name_the_role_and_how_to_clear_it() -> None:
    """C20: what a caller reads before the call — the role is given, never guessed; it is cleared by name on the
    edit; it needs schema 17; and the read's rows carry it from schema 17."""
    add = " ".join((server_module.add_reference.__doc__ or "").split())
    update = " ".join((server_module.update_reference.__doc__ or "").split())
    listing = " ".join((server_module.list_references.__doc__ or "").split())
    for doc in (add, update):
        assert "`role`" in doc and "never guessed" in doc and "schema 17" in doc
        assert "`REFERENCE_ROLE_NOT_ALLOWED`" in doc
    assert 'clear_fields=["role"]' in update and '"role": null' in update
    assert "`role`" in listing and "schema 17" in listing


# --- #69: a link on a supply item or an installed component (rows 46–48) ---------------------------

SUPPLY_ITEM = "3b9d2f70-6a1e-4c8b-9f25-7e0a1c4d8b36"
INSTALLED_COMPONENT = "8e1f4a27-0c6b-4d93-a5e2-1b7c9d0f3e58"
STATUS_20: dict = dict(STATUS_16, schemaVersion=20, backupFormatVersion=20)
OWNER_KEYS = ("assetId", "supplyItemId", "installedComponentId")

OWNERS = [
    pytest.param({"asset_id": "a1"}, "/v1/assets/a1", "assetId", id="asset"),
    pytest.param(
        {"supply_item_id": SUPPLY_ITEM}, f"/v1/supply-items/{SUPPLY_ITEM}", "supplyItemId", id="supply-item"
    ),
    pytest.param(
        {"installed_component_id": INSTALLED_COMPONENT},
        f"/v1/installed-components/{INSTALLED_COMPONENT}",
        "installedComponentId",
        id="installed-component",
    ),
]
"""Each owner as a caller names it, the owner's own route and the one wire key its rows and its create carry."""


def owned_row(key: str, owner_id: str, **overrides) -> dict:
    """A schema-20 reference row: its one owner key set, the other two `null`."""
    row = reference_row(**{name: None for name in OWNER_KEYS})
    row[key] = owner_id
    row.update(overrides)
    return row


@pytest.mark.parametrize(("owner", "base", "key"), OWNERS)
def test_list_references_reads_the_owners_own_route(paired, owner, base, key) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_20)
    rows = [owned_row(key, next(iter(owner.values())))]
    paired.reply("GET", f"{base}/references", 200, {"references": rows})
    assert server_module.list_references(**owner) == {"references": rows}
    assert paired.last().method == "GET"
    assert paired.last().path == f"{base}/references"
    assert all(r.method == "GET" for r in paired.requests)


@pytest.mark.parametrize(("owner", "base", "key"), OWNERS)
def test_add_reference_names_exactly_the_given_owner_in_the_body(paired, owner, base, key) -> None:
    """C19's create: one `POST /v1/references` for every owner, its body carrying the one key the caller named and
    neither of the other two — never `assetId` for a supply item's or an installed component's link."""
    paired.reply("GET", "/v1/status", 200, STATUS_20)
    server_module.add_reference(
        **owner, uri=LINK, display_name="Example Water Heater manual", description="the PDF", role="USER_MANUAL"
    )
    recorded = paired.last()
    assert (recorded.method, recorded.path) == ("POST", "/v1/references")
    assert body_of(recorded) == {
        key: next(iter(owner.values())),
        "uri": LINK,
        "displayName": "Example Water Heater manual",
        "description": "the PDF",
        "role": "USER_MANUAL",
    }


@pytest.mark.parametrize(
    "owners",
    [
        pytest.param({}, id="none"),
        pytest.param({"asset_id": "a1", "supply_item_id": SUPPLY_ITEM}, id="asset-and-supply-item"),
        pytest.param(
            {"supply_item_id": SUPPLY_ITEM, "installed_component_id": INSTALLED_COMPONENT},
            id="supply-item-and-installed-component",
        ),
        pytest.param(
            {"asset_id": "a1", "supply_item_id": SUPPLY_ITEM, "installed_component_id": INSTALLED_COMPONENT},
            id="all-three",
        ),
    ],
)
def test_a_reference_tool_given_no_owner_or_several_refuses_with_nothing_sent(paired, owners) -> None:
    """C23: exactly one of the three, checked here before the pairing's status read or any other request."""
    for call in (
        lambda: server_module.list_references(**owners),
        lambda: server_module.add_reference(**owners, uri=LINK, display_name="Example Water Heater manual"),
    ):
        with pytest.raises(ToolError, match="exactly one owner") as raised:
            call()
        text = str(raised.value)
        assert "asset_id, supply_item_id or installed_component_id" in text, text
        assert SUPPLY_ITEM not in text and INSTALLED_COMPONENT not in text, "names the arguments, never a value"
    assert paired.requests == []


def test_a_new_owners_read_refuses_an_answer_it_cannot_read(paired) -> None:
    """The read's defensive checks hold on every owner's route, naming that owner."""
    paired.reply("GET", "/v1/status", 200, STATUS_20)
    paired.reply("GET", f"/v1/supply-items/{SUPPLY_ITEM}/references", 200, {"references": "nope"})
    with pytest.raises(ToolError, match="that supply item's references"):
        server_module.list_references(supply_item_id=SUPPLY_ITEM)

    paired.reply(
        "GET", f"/v1/installed-components/{INSTALLED_COMPONENT}/references", 200,
        {"references": [{"id": "r1", "installedComponentId": INSTALLED_COMPONENT}]},
    )
    with pytest.raises(ToolError, match="assetId"):
        server_module.list_references(installed_component_id=INSTALLED_COMPONENT)


def test_a_new_owners_refusals_pass_through_as_the_phone_said_them(paired) -> None:
    """The owner 404s and the held 409 are the phone's, carried with their code and `field`."""
    paired.reply("GET", "/v1/status", 200, STATUS_20)
    paired.reply("GET", f"/v1/supply-items/{SUPPLY_ITEM}/references", 404, {"error": {
        "code": "NO_SUCH_SUPPLY_ITEM", "message": "no such supply item", "field": None,
    }})
    with pytest.raises(ToolError, match="404 NO_SUCH_SUPPLY_ITEM"):
        server_module.list_references(supply_item_id=SUPPLY_ITEM)

    paired.reply("POST", "/v1/references", 409, {"error": {
        "code": "asset_transferred_out", "message": "that asset is transferred out",
        "problems": ["AssetTransferredOut(assetId=a1)"],
    }})
    with pytest.raises(ToolError, match="409 asset_transferred_out") as raised:
        server_module.add_reference(installed_component_id=INSTALLED_COMPONENT, uri=LINK, display_name="Label")
    assert "AssetTransferredOut(assetId=a1)" in str(raised.value)

    paired.reply("POST", "/v1/references", 404, {"error": {
        "code": "NO_SUCH_INSTALLED_COMPONENT", "message": "no such installed component",
        "field": "installedComponentId", "problems": ["NoSuchInstalledComponent"],
    }})
    with pytest.raises(ToolError, match=r"404 NO_SUCH_INSTALLED_COMPONENT.*\[field=installedComponentId\]"):
        server_module.add_reference(installed_component_id=INSTALLED_COMPONENT, uri=LINK, display_name="Label")


def test_the_reference_docstrings_name_the_three_owners() -> None:
    """C23: what a caller reads before the call — the three owners, exactly one of them, and the schema-20 gate for
    the two new ones; never the child-asset tool."""
    for tool in (server_module.list_references, server_module.add_reference):
        doc = " ".join((tool.__doc__ or "").split())
        for words in ("`asset_id`", "`supply_item_id`", "`installed_component_id`", "exactly one", "schema 20"):
            assert words in doc, (tool.__name__, words)
        assert "create_component" not in doc, tool.__name__


def test_an_owner_passed_as_null_is_not_given(paired) -> None:
    """The API's own rule (exactly one owner key non-null): an owner argument sent as `null` counts as not given,
    so a client that sends every optional argument names one owner with the other two `null`, and the body carries
    only the one."""
    paired.reply("GET", "/v1/status", 200, STATUS_20)
    server_module.add_reference(
        asset_id=None, supply_item_id=SUPPLY_ITEM, installed_component_id=None, uri=LINK, display_name="Data sheet"
    )
    assert body_of(paired.last()) == {"supplyItemId": SUPPLY_ITEM, "uri": LINK, "displayName": "Data sheet"}
    paired.reply("GET", f"/v1/installed-components/{INSTALLED_COMPONENT}/references", 200, {"references": []})
    result = _call_tool(
        "list_references", {"asset_id": None, "supply_item_id": None, "installed_component_id": INSTALLED_COMPONENT}
    )
    assert result.is_error is False
    assert paired.last().path == f"/v1/installed-components/{INSTALLED_COMPONENT}/references"
