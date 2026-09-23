"""The three 1.3 tools: the method, the path and the body each one sends — and the two conventions
a reference tool is most likely to get wrong.

One case per hazard, not per permutation. The two hazards carrying the most risk here are both
about a field that is **not** an argument. `kind` is derived from the URI's scheme and is an unknown
field on both API commands, so neither tool may offer one — a tool that did would send a field the
phone answers 400 for, every time. And there is **no `clear_fields`** on either write tool:
`display_name` cannot be cleared at all (the app requires it non-blank) and `description` is
cleared **by value**, `description=""`, since it is a non-null column with an empty default. So
`None` here means only "leave it alone", and the case below proves the wire carries nothing at all
for it.
"""

from __future__ import annotations

import asyncio
import json

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import server as server_module

REFERENCE_TOOLS = ("list_references", "add_reference", "update_reference")
"""Master plan §7's three, named exactly as it names them. The registered total is 38 + 3 = **41**,
which `test_argument_guard.py` pins."""

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


def test_the_registered_tool_count_is_41() -> None:
    """A tool added without updating `TOOL_NAMES` makes `_forbid_unknown_arguments` raise at
    *import* time, which is the intended loud failure — so this file importing at all is half the
    proof, and the counts below are the other half."""
    assert len(server_module.TOOL_NAMES) == 41
    assert len(server_module.mcp._tool_manager.list_tools()) == 41


def test_nothing_deletes_a_reference_and_no_tool_takes_a_kind() -> None:
    """The API adds and amends a reference; the phone removes one. And `kind` is derived from the
    scheme, so a tool offering one would let a caller state a fact it cannot set."""
    for forbidden in ("delete_reference", "remove_reference", "add_attachment", "share"):
        assert not hasattr(server_module, forbidden), forbidden
        assert forbidden not in server_module.TOOL_NAMES, forbidden
    for name in ("add_reference", "update_reference"):
        parameters = server_module.mcp._tool_manager.get_tool(name).parameters["properties"]
        assert "kind" not in parameters, name
        assert "clear_fields" not in parameters, name


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
