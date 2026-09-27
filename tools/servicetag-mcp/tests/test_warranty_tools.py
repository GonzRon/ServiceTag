"""#79's two warranty tools (C12; R79-18) and the lead's absence from every asset command.

`get_warranty` reads `GET /v1/assets/{id}/warranty` — the status derived on the phone for today, in a
response of its own — and `set_warranty_reminder` sends `POST /v1/assets/{id}/warranty-reminder` with
exactly its golden key. Both speak routes a phone below schema 11 does not have, so each refuses such a
phone — read and write alike — by name, with nothing sent beyond the pairing's one `/v1/status` read. The
global write minimum stays 8.

`update_asset` never sends the lead: it is not a key of the vendored asset command, so an edit keeps it
while the date stays, and the app clears it with the date.
"""

from __future__ import annotations

import inspect
import json
from pathlib import Path

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import command_shapes
from servicetag_mcp import server as server_module

GOLDEN = Path(__file__).resolve().parents[3] / "docs" / "api" / "command-shapes.json"

STATUS_11 = {"appVersion": "1.4.1", "apiVersion": 1, "schemaVersion": 11, "backupFormatVersion": 11,
             "counts": {}}
STATUS_10 = {"appVersion": "1.4.1", "apiVersion": 1, "schemaVersion": 10, "backupFormatVersion": 10,
             "counts": {}}

WARRANTY = {"warranty": {"status": "IN_WARRANTY", "expiresOn": "2026-06-30", "leadDays": 30}}


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def asset_row(**overrides) -> dict:
    """`GET /v1/assets/{id}` from a schema-11 app: the format-11 row, lead included."""
    row = {
        "id": "a1", "name": "Example Heater", "description": "", "category": "Appliance", "notes": "",
        "status": "ACTIVE", "createdAt": 1, "updatedAt": 1, "templateKey": None,
        "manufacturer": "", "model": "", "serialNumber": "", "purchaseOn": None,
        "inServiceOn": None, "purchasePriceMinor": None, "currency": None, "vendor": "",
        "location": "", "warrantyExpiresOn": "2026-06-30", "warrantyNotes": "", "retiredOn": None,
        "parentAssetId": None, "seasonStartMmdd": None, "seasonEndMmdd": None,
        "seasonMode": "YEAR_ROUND", "blackoutStartMmdd": None, "blackoutEndMmdd": None,
        "healthAggregation": "WORST", "healthPrimarySubjectId": None, "warrantyReminderLeadDays": 30,
    }
    row.update(overrides)
    return {"asset": row}


def test_the_warranty_tools_refuse_a_schema_10_phone_with_nothing_sent(paired) -> None:
    """A per-tool minimum beside the global one: a schema-10 phone takes every other write, and these
    two refuse by name — the read too, because its route is not there — sending nothing but the one
    `/v1/status` read the pairing makes."""
    paired.reply("GET", "/v1/status", 200, STATUS_10)
    for call in (
        lambda: server_module.get_warranty(asset_id="a1"),
        lambda: server_module.set_warranty_reminder(asset_id="a1", lead_days=30),
        lambda: server_module.set_warranty_reminder(asset_id="a1", lead_days=None),
    ):
        with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as raised:
            call()
        assert "schema 10" in str(raised.value)
        assert "11" in str(raised.value)
    assert [(r.method, r.path) for r in paired.requests] == [("GET", "/v1/status")]

    # The global minimum is untouched: the same phone still takes a 1.4 write.
    server_module.archive_asset(asset_id="a1")
    assert [(r.method, r.path) for r in paired.requests][-1] == ("POST", "/v1/assets/a1/archive")
    assert server_module._MIN_SCHEMA_VERSION == 8


def test_a_status_without_a_schema_version_refuses_the_warranty_tools_too(paired) -> None:
    paired.reply("GET", "/v1/status", 200, {"appVersion": "1.0.0"})
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD"):
        server_module.get_warranty(asset_id="a1")
    assert [(r.method, r.path) for r in paired.requests] == [("GET", "/v1/status")]


def test_get_warranty_reads_its_own_route(paired) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_11)
    paired.reply("GET", "/v1/assets/a1/warranty", 200, WARRANTY)
    assert server_module.get_warranty(asset_id="a1") == WARRANTY
    assert [(r.method, r.path) for r in paired.requests] == [
        ("GET", "/v1/status"), ("GET", "/v1/assets/a1/warranty"),
    ]
    with pytest.raises(ToolError, match="asset_id"):
        server_module.get_warranty(asset_id="")


def test_set_warranty_reminder_sends_exactly_the_golden_key(paired) -> None:
    """Not an overlay — a command sent as given, nothing read first — so its body is tied to the golden
    `warrantyReminder` entry directly. `None` is sent as `null`, which turns the reminder off."""
    paired.reply("GET", "/v1/status", 200, STATUS_11)
    golden = json.loads(GOLDEN.read_text(encoding="utf-8"))["warrantyReminder"]["keys"]

    server_module.set_warranty_reminder(asset_id="a1", lead_days=30)
    last = paired.last()
    assert (last.method, last.path) == ("POST", "/v1/assets/a1/warranty-reminder")
    assert body_of(last) == {"leadDays": 30}
    assert sorted(body_of(last)) == sorted(golden)

    server_module.set_warranty_reminder(asset_id="a1", lead_days=None)
    assert body_of(paired.last()) == {"leadDays": None}
    assert [r.method for r in paired.requests] == ["GET", "POST", "POST"], "one status read, nothing read first"

    lead = inspect.signature(server_module.set_warranty_reminder).parameters["lead_days"]
    assert lead.default is inspect.Parameter.empty, "required: leaving it out never turns a reminder off"


def test_a_refusal_arrives_carrying_the_family_code_and_field(paired) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_11)
    paired.reply("POST", "/v1/assets/a1/warranty-reminder", 422, {"error": {
        "code": "warranty_reminder_validation",
        "message": "a warranty reminder needs the asset's warrantyExpiresOn; set the date first",
        "problems": ["LeadWithoutDate"], "field": "leadDays",
    }})
    with pytest.raises(ToolError) as raised:
        server_module.set_warranty_reminder(asset_id="a1", lead_days=14)
    text = str(raised.value)
    assert "422 warranty_reminder_validation" in text
    assert "[field=leadDays]" in text
    assert "(LeadWithoutDate)" in text


def test_update_asset_never_sends_the_lead(paired) -> None:
    """K4 on this side: the lead is in no asset command, so the overlay that sends every vendored key
    never sends it — the phone keeps it while the date stays — and no asset tool takes it."""
    paired.reply("GET", "/v1/status", 200, STATUS_11)
    paired.reply("GET", "/v1/assets/a1", 200, asset_row())

    server_module.update_asset(asset_id="a1", name="Example Heater, loft")
    body = body_of(paired.last())
    assert paired.last().method == "PATCH"
    assert "warrantyReminderLeadDays" not in body
    assert body["warrantyExpiresOn"] == "2026-06-30"
    assert "warrantyReminderLeadDays" not in command_shapes.ASSET_KEYS

    with pytest.raises(ToolError, match="warranty_reminder_lead_days"):
        server_module.update_asset(asset_id="a1", clear_fields=["warranty_reminder_lead_days"])
    for tool in (server_module.create_asset, server_module.update_asset, server_module.create_component):
        assert "warranty_reminder_lead_days" not in inspect.signature(tool).parameters, tool.__name__
