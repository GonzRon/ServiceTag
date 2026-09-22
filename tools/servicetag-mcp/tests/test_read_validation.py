"""#53 task 2, the Invariant: `save_profile`'s edit path reads `fields`/`consumables` off the
current row before it overlays and writes a full replacement back. A malformed read must never
become a write — every case below drives `save_profile` through `MCPServer.call_tool` (the real
SDK boundary, same as `test_sdk_boundary.py`), asserts the raised `ToolError` names the path, and
asserts the fake server recorded zero `POST /v1/profiles` requests.
"""

from __future__ import annotations

import asyncio
import json

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import server as server_module


def _call_tool(name: str, arguments: dict):
    return asyncio.run(server_module.mcp.call_tool(name, arguments))


def _body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def _profile_row(**overrides) -> dict:
    row = {
        "id": "p1",
        "assetId": "a1",
        "name": "Water test",
        "eventKind": "MEASUREMENT",
        "defaultTitle": "",
        "templateKey": None,
        "sortOrder": 0,
        "archivedAt": None,
        "createdAt": 1,
        "updatedAt": 1,
        "fields": [{"id": "pf1", "definitionId": "d1", "required": True, "sortOrder": 0}],
        "consumables": [
            {"id": "c1", "name": "Test strips", "defaultQuantity": 1.0, "unit": "ea", "sortOrder": 0},
        ],
    }
    row.update(overrides)
    return row


def _no_profile_write(paired) -> bool:
    return not any(r.method == "POST" and r.path == "/v1/profiles" for r in paired.requests)


# --- the malformed-read matrix ---------------------------------------------------------------


@pytest.mark.parametrize(
    "row_overrides, expected_fragment",
    [
        ({"fields": None}, "the quick action.fields"),
        ({"fields": {}}, "the quick action.fields"),
        ({"fields": ""}, "the quick action.fields"),
        ({"fields": ["x"]}, "the quick action's fields[0]"),
        ({"fields": [{}]}, "the quick action's fields[0]"),
        (
            {"fields": [{"definitionId": "d1", "required": "yes"}]},
            "the quick action's fields[0].required",
        ),
        ({"consumables": None}, "the quick action.consumables"),
        ({"consumables": {}}, "the quick action.consumables"),
        ({"consumables": ""}, "the quick action.consumables"),
        ({"consumables": ["x"]}, "the quick action's consumables[0]"),
        ({"consumables": [{}]}, "the quick action's consumables[0]"),
        (
            {
                "consumables": [
                    {"id": "c1", "name": "Test strips", "unit": "ea", "defaultQuantity": "1"}
                ]
            },
            "the quick action's consumables[0].defaultQuantity",
        ),
    ],
    ids=[
        "fields-null",
        "fields-object",
        "fields-string",
        "fields-entry-not-object",
        "fields-entry-missing-definitionId",
        "fields-required-wrong-type",
        "consumables-null",
        "consumables-object",
        "consumables-string",
        "consumables-entry-not-object",
        "consumables-entry-missing-id",
        "consumables-defaultQuantity-wrong-type",
    ],
)
def test_save_profile_edit_refuses_a_malformed_read_before_writing(
    paired, row_overrides: dict, expected_fragment: str
) -> None:
    current = _profile_row(**row_overrides)
    paired.reply("GET", "/v1/assets/a1/profiles", 200, {"profiles": [current]})
    with pytest.raises(ToolError) as raised:
        _call_tool(
            "save_profile", {"asset_id": "a1", "profile_id": "p1", "name": "Water test (weekly)"}
        )
    # A malformed read must never raise anything but a deliberate ToolError — never the SDK's own
    # bare, message-free UnexpectedToolError (a ToolError subclass) for an exception that escaped
    # past this seam unconverted.
    assert type(raised.value) is ToolError
    assert expected_fragment in str(raised.value)
    assert _no_profile_write(paired)


# --- the two "this read was fine" edges -------------------------------------------------------


def test_save_profile_edit_accepts_extra_unknown_keys_and_sends_the_kept_values(paired) -> None:
    current = _profile_row(
        extraProfileKey="ignored",
        fields=[
            {
                "id": "pf1",
                "definitionId": "d1",
                "required": True,
                "sortOrder": 0,
                "extraFieldKey": "x",
            }
        ],
        consumables=[
            {
                "id": "c1",
                "name": "Test strips",
                "defaultQuantity": 1.0,
                "unit": "ea",
                "sortOrder": 0,
                "extraConsumableKey": "y",
            }
        ],
    )
    paired.reply("GET", "/v1/assets/a1/profiles", 200, {"profiles": [current]})
    _call_tool(
        "save_profile", {"asset_id": "a1", "profile_id": "p1", "name": "Water test (weekly)"}
    )
    body = _body_of(paired.last())
    assert body["fields"] == [{"definitionId": "d1", "required": True}]
    assert body["consumables"] == [
        {"id": "c1", "name": "Test strips", "defaultQuantity": 1.0, "unit": "ea"}
    ]


def test_save_profile_edit_treats_empty_fields_and_consumables_as_valid(paired) -> None:
    current = _profile_row(fields=[], consumables=[])
    paired.reply("GET", "/v1/assets/a1/profiles", 200, {"profiles": [current]})
    _call_tool(
        "save_profile", {"asset_id": "a1", "profile_id": "p1", "name": "Water test (weekly)"}
    )
    body = _body_of(paired.last())
    assert body["fields"] == []
    assert body["consumables"] == []
    assert paired.last().method == "POST"
    assert paired.last().path == "/v1/profiles"


def test_save_profile_edit_accepts_a_null_defaultQuantity(paired) -> None:
    """`defaultQuantity` is the one field the contract allows to be `null` on top of its real
    type — nothing else in this row has a nullable slot."""
    current = _profile_row(
        consumables=[{"id": "c1", "name": "Test strips", "defaultQuantity": None, "unit": "ea"}]
    )
    paired.reply("GET", "/v1/assets/a1/profiles", 200, {"profiles": [current]})
    _call_tool(
        "save_profile", {"asset_id": "a1", "profile_id": "p1", "name": "Water test (weekly)"}
    )
    body = _body_of(paired.last())
    assert body["consumables"] == [
        {"id": "c1", "name": "Test strips", "defaultQuantity": None, "unit": "ea"}
    ]
