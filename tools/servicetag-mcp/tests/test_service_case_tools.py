"""#79b's five service-case tools (C24; R79-5, R79-18).

`list_service_cases` and `get_service_case` read `GET /v1/assets/{id}/service-cases` and
`GET /v1/service-cases/{id}`; `open_service_case` sends `POST /v1/service-cases` as given;
`update_service_case` is an overlay on the `update_asset` pattern — it reads the case, lays the caller's
arguments over every key of the vendored `serviceCase` command but `assetId` and `incidentEventId`, and
sends the full replacement; `add_case_entry` sends `POST /v1/service-cases/{id}/entries` with exactly its
golden keys. All five speak routes a phone below schema 12 does not have, so each refuses such a phone —
read and write alike — by name, with nothing sent beyond the pairing's one `/v1/status` read. The global
write minimum stays 8.

**No tool writes a case's status or `closedOn`** except `add_case_entry`: they are in no header command,
so `update_service_case` never sends either, and it carries the stored `resolutionEventId` forward, so an
edit never unlinks a repair record by accident. No tool deletes a case or amends an entry.
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

STATUS_12 = {"appVersion": "1.4.1", "apiVersion": 1, "schemaVersion": 12, "backupFormatVersion": 12,
             "counts": {}}
STATUS_11 = {"appVersion": "1.4.1", "apiVersion": 1, "schemaVersion": 11, "backupFormatVersion": 11,
             "counts": {}}

CASE_TOOLS = ("list_service_cases", "get_service_case", "open_service_case", "update_service_case", "add_case_entry")


def golden(entry: str) -> list[str]:
    return json.loads(GOLDEN.read_text(encoding="utf-8"))[entry]["keys"]


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def case_row(**overrides) -> dict:
    """`GET /v1/service-cases/{id}` from a schema-12 app: the format-12 header and its timeline."""
    row = {
        "id": "c1", "assetId": "a1", "title": "Heater claim", "type": "WARRANTY_SERVICE",
        "openedOn": "2026-09-12", "closedOn": "2026-09-19", "provider": "Northwind Service",
        "contact": "", "caseRef": "RMA-0001", "coverage": "IN_WARRANTY", "status": "CLOSED",
        "outboundTracking": "", "outboundCarrier": "Parcel Co", "returnTracking": "", "returnCarrier": "",
        "costMinor": 0, "currency": "EUR", "notes": "", "incidentEventId": "e-incident",
        "resolutionEventId": "e-repair", "createdAt": 1, "updatedAt": 2,
    }
    row.update(overrides)
    return {"serviceCase": row, "entries": []}


def calls() -> tuple:
    """One call of each tool, every required argument given."""
    return (
        lambda: server_module.list_service_cases(asset_id="a1"),
        lambda: server_module.get_service_case(case_id="c1"),
        lambda: server_module.open_service_case(
            asset_id="a1", title="Heater claim", type="WARRANTY_SERVICE", opened_on="2026-09-12",
            coverage="IN_WARRANTY",
        ),
        lambda: server_module.update_service_case(case_id="c1", title="Heater claim, element"),
        lambda: server_module.add_case_entry(
            case_id="c1", occurred_on="2026-09-13", occurred_time=None, tz_id="Etc/UTC",
            note="Called the provider", status=None,
        ),
    )


def test_the_case_tools_refuse_a_schema_11_phone_with_nothing_sent(paired) -> None:
    """A per-tool minimum beside the global one: a schema-11 phone takes every other write — the
    warranty tools included — and these five refuse by name, the two reads too, because their routes are
    not there, sending nothing but the one `/v1/status` read the pairing makes."""
    paired.reply("GET", "/v1/status", 200, STATUS_11)
    for call in calls():
        with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as raised:
            call()
        assert "schema 11" in str(raised.value)
        assert "12" in str(raised.value)
    assert [(r.method, r.path) for r in paired.requests] == [("GET", "/v1/status")]

    # The global minimum is untouched: the same phone still takes a 1.4 write and #79's warranty tools.
    server_module.archive_asset(asset_id="a1")
    assert [(r.method, r.path) for r in paired.requests][-1] == ("POST", "/v1/assets/a1/archive")
    server_module.set_warranty_reminder(asset_id="a1", lead_days=30)
    assert [(r.method, r.path) for r in paired.requests][-1] == ("POST", "/v1/assets/a1/warranty-reminder")
    assert server_module._MIN_SCHEMA_VERSION == 8


def test_a_status_without_a_schema_version_refuses_the_case_tools_too(paired) -> None:
    paired.reply("GET", "/v1/status", 200, {"appVersion": "1.0.0"})
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD"):
        server_module.list_service_cases(asset_id="a1")
    assert [(r.method, r.path) for r in paired.requests] == [("GET", "/v1/status")]


def test_update_service_case_overlays_and_never_sends_status(paired) -> None:
    """The overlay: every key of the vendored case command but `assetId` and `incidentEventId`, read off
    the row, with the caller's arguments over it — never `status` or `closedOn`, which move only through
    `add_case_entry`, and the stored repair link carried forward. No argument or `clear_fields` name
    reaches the status."""
    paired.reply("GET", "/v1/status", 200, STATUS_12)
    paired.reply("GET", "/v1/service-cases/c1", 200, case_row())

    server_module.update_service_case(case_id="c1", title="Heater claim, element", cost_minor=4500)
    sent = paired.last()
    assert (sent.method, sent.path) == ("PATCH", "/v1/service-cases/c1")
    body = body_of(sent)
    assert sorted(body) == sorted(k for k in golden("serviceCase") if k not in ("assetId", "incidentEventId"))
    assert "status" not in body and "closedOn" not in body
    assert body["title"] == "Heater claim, element"
    assert body["costMinor"] == 4500
    assert body["resolutionEventId"] == "e-repair", "an edit never unlinks the repair record"
    assert body["caseRef"] == "RMA-0001" and body["outboundCarrier"] == "Parcel Co" and body["currency"] == "EUR"

    parameters = inspect.signature(server_module.update_service_case).parameters
    for name in ("status", "closed_on", "asset_id", "incident_event_id"):
        assert name not in parameters, name
    for name in ("status", "closed_on"):
        with pytest.raises(ToolError, match=name):
            server_module.update_service_case(case_id="c1", clear_fields=[name])
    assert [r.method for r in paired.requests].count("PATCH") == 1, "a refused name sends nothing"


def test_update_service_case_clears_by_name_and_null_keeps(paired) -> None:
    """The overlay's two conventions: `None` keeps the stored value; `clear_fields` blanks a text field to
    `""` and a nullable one — the cost, the currency, the repair link — to `null`. The four required
    fields are never clearable."""
    paired.reply("GET", "/v1/status", 200, STATUS_12)
    paired.reply("GET", "/v1/service-cases/c1", 200, case_row())

    server_module.update_service_case(case_id="c1", provider=None, notes="Courier booked")
    body = body_of(paired.last())
    assert body["provider"] == "Northwind Service"
    assert body["notes"] == "Courier booked"

    server_module.update_service_case(
        case_id="c1", clear_fields=["resolution_event_id", "cost_minor", "currency", "case_ref", "outbound_carrier"],
    )
    body = body_of(paired.last())
    assert body["resolutionEventId"] is None, "removing the repair link is by name"
    assert body["costMinor"] is None and body["currency"] is None
    assert body["caseRef"] == "" and body["outboundCarrier"] == ""
    assert body["title"] == "Heater claim"

    for name in ("title", "type", "opened_on", "coverage"):
        with pytest.raises(ToolError, match="clear_fields"):
            server_module.update_service_case(case_id="c1", clear_fields=[name])
    with pytest.raises(ToolError, match="not both"):
        server_module.update_service_case(case_id="c1", currency="USD", clear_fields=["currency"])


def test_a_new_vendored_case_key_is_carried_without_tool_changes(paired, monkeypatch) -> None:
    """`update_service_case` reads the vendored list, not a list of its own: a key the case command gains
    is carried by adding it to `command_shapes`, and the tool never learns its name."""
    monkeypatch.setattr(
        command_shapes, "SERVICE_CASE_KEYS", command_shapes.SERVICE_CASE_KEYS + ("futureCaseKey",),
    )
    paired.reply("GET", "/v1/status", 200, STATUS_12)
    row = case_row()
    row["serviceCase"]["futureCaseKey"] = "kept-c"
    paired.reply("GET", "/v1/service-cases/c1", 200, row)
    server_module.update_service_case(case_id="c1", title="Heater claim, element")
    assert body_of(paired.last())["futureCaseKey"] == "kept-c"


def test_open_service_case_sends_exactly_the_golden_keys(paired) -> None:
    """A create sent as given: every argument given sends exactly the golden `serviceCase` keys; the
    four the API requires are required here too, with no default, and nothing else is invented."""
    paired.reply("GET", "/v1/status", 200, STATUS_12)
    server_module.open_service_case(
        asset_id="a1", title="Heater claim", type="OTHER_SERVICE", opened_on="2026-09-12",
        coverage="PARTLY_COVERED", incident_event_id="e-incident", provider="Northwind Service",
        contact="+1 555 0100", case_ref="RMA-0001", outbound_tracking="T-OUT-1", outbound_carrier="Parcel Co",
        return_tracking="T-RET-2", return_carrier="Freight Ltd", cost_minor=0, currency="EUR", notes="Boxed",
        resolution_event_id="e-repair",
    )
    sent = paired.last()
    assert (sent.method, sent.path) == ("POST", "/v1/service-cases")
    assert sorted(body_of(sent)) == sorted(golden("serviceCase"))
    # Every value under its own key — no two alike, so a swapped or dropped argument cannot pass.
    assert body_of(sent) == {
        "assetId": "a1", "title": "Heater claim", "type": "OTHER_SERVICE", "openedOn": "2026-09-12",
        "provider": "Northwind Service", "contact": "+1 555 0100", "caseRef": "RMA-0001",
        "coverage": "PARTLY_COVERED", "outboundTracking": "T-OUT-1", "outboundCarrier": "Parcel Co",
        "returnTracking": "T-RET-2", "returnCarrier": "Freight Ltd", "costMinor": 0, "currency": "EUR",
        "notes": "Boxed", "incidentEventId": "e-incident", "resolutionEventId": "e-repair",
    }, "every value under its own key, and a zero cost (no charge) is sent"

    server_module.open_service_case(
        asset_id="a1", title="Pump", type="REPAIR", opened_on="2026-09-12", coverage="UNKNOWN",
    )
    assert body_of(paired.last()) == {
        "assetId": "a1", "title": "Pump", "type": "REPAIR", "openedOn": "2026-09-12", "coverage": "UNKNOWN",
    }, "the API applies no default, and neither does this tool"
    parameters = inspect.signature(server_module.open_service_case).parameters
    for name in ("asset_id", "title", "type", "opened_on", "coverage"):
        assert parameters[name].default is inspect.Parameter.empty, name
    assert "status" not in parameters and "closed_on" not in parameters


def test_add_case_entry_sends_exactly_the_golden_keys(paired) -> None:
    """A new fact: no overlay, every argument required, the body exactly the golden `caseEntry` keys —
    `None` sent as `null`."""
    paired.reply("GET", "/v1/status", 200, STATUS_12)
    server_module.add_case_entry(
        case_id="c1", occurred_on="2026-09-19", occurred_time="16:05", tz_id="Etc/UTC", note="Fixed",
        status="CLOSED",
    )
    sent = paired.last()
    assert (sent.method, sent.path) == ("POST", "/v1/service-cases/c1/entries")
    assert body_of(sent) == {
        "occurredOn": "2026-09-19", "occurredTime": "16:05", "tzId": "Etc/UTC", "note": "Fixed", "status": "CLOSED",
    }
    assert sorted(body_of(sent)) == sorted(golden("caseEntry"))
    server_module.add_case_entry(
        case_id="c1", occurred_on="2026-09-19", occurred_time=None, tz_id="Etc/UTC", note="Called", status=None,
    )
    assert body_of(paired.last())["status"] is None
    assert [r.method for r in paired.requests] == ["GET", "POST", "POST"], "nothing is read first"
    for parameter in inspect.signature(server_module.add_case_entry).parameters.values():
        assert parameter.default is inspect.Parameter.empty, parameter.name


def test_the_reads_use_their_own_routes(paired) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_12)
    paired.reply("GET", "/v1/assets/a1/service-cases", 200, {"serviceCases": []})
    paired.reply("GET", "/v1/service-cases/c1", 200, case_row())
    assert server_module.list_service_cases(asset_id="a1") == {"serviceCases": []}
    assert server_module.get_service_case(case_id="c1") == case_row()
    assert [(r.method, r.path) for r in paired.requests] == [
        ("GET", "/v1/status"), ("GET", "/v1/assets/a1/service-cases"), ("GET", "/v1/service-cases/c1"),
    ]
    with pytest.raises(ToolError, match="case_id"):
        server_module.get_service_case(case_id="")


def test_a_refusal_arrives_carrying_the_family_code_and_field(paired) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_12)
    paired.reply("POST", "/v1/service-cases/c1/entries", 422, {"error": {
        "code": "service_case_validation", "message": "an entry needs a note, a status, or both",
        "problems": ["EntryEmpty"], "field": "note",
    }})
    with pytest.raises(ToolError) as raised:
        server_module.add_case_entry(
            case_id="c1", occurred_on="2026-09-19", occurred_time=None, tz_id="Etc/UTC", note="", status=None,
        )
    text = str(raised.value)
    assert "422 service_case_validation" in text
    assert "[field=note]" in text
    assert "(EntryEmpty)" in text


def test_no_tool_deletes_a_case_or_amends_an_entry() -> None:
    """R79-8, R79-9: the five are the whole case surface — the API routes no delete and no entry
    amendment, so no tool offers one."""
    case_tools = {name for name in server_module.TOOL_NAMES if "case" in name}
    assert case_tools == set(CASE_TOOLS)
    assert "serviceCase" in command_shapes.vendored()
    assert command_shapes.SERVICE_CASE_KEYS == tuple(golden("serviceCase"))
