"""#72's five loan tools (C22; R72-3, R72-4, R72-15–R72-18).

`list_loans` and `get_loan` read `GET /v1/assets/{id}/loans` and `GET /v1/loans/{id}`; `lend_asset` sends
`POST /v1/loans` with exactly its golden keys; `update_loan` is an overlay on the `update_service_case`
pattern — it reads the loan through `GET /v1/loans/{id}`, lays the caller's arguments over every key of the
vendored `loan` command but `assetId` and `borrowerName`, and sends the full replacement, an omitted or
`null` argument keeping the stored value and `clear_fields` clearing by name; `return_loan` sends
`POST /v1/loans/{id}/return`. All five speak routes a phone below schema 13 does not have, so each refuses
such a phone — read and write alike — by name, with nothing sent beyond the pairing's one `/v1/status`
read. The global write minimum stays 8.

**No tool takes or sends a contact lookup URI**: a link is made on the phone alone, the API answers only
`contactLinked`, and no loan command carries one. No tool deletes or relinks a loan, and none runs a
reminder sweep. A loan written here posts only at the phone's next sweep at or after the digest hour; a
standing reminder the write takes down (a return; an update to `NONE`, to another mode or to a new due date)
comes down at the next sweep of any kind, the midnight sweep included.
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

STATUS_13 = {"appVersion": "1.4.1", "apiVersion": 1, "schemaVersion": 13, "backupFormatVersion": 13,
             "counts": {}}
STATUS_12 = {"appVersion": "1.4.1", "apiVersion": 1, "schemaVersion": 12, "backupFormatVersion": 12,
             "counts": {}}

LOAN_TOOLS = ("list_loans", "get_loan", "lend_asset", "update_loan", "return_loan")

LINKISH = ("uri", "lookup", "link", "contact")
"""A parameter, a vendored key or a sent key naming any of these would carry a contact link."""


def golden(entry: str) -> list[str]:
    return json.loads(GOLDEN.read_text(encoding="utf-8"))[entry]["keys"]


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def loan_row(**overrides) -> dict:
    """`GET /v1/loans/{id}` from a schema-13 app: a `LoanDto`, which says whether a contact is linked and
    never carries the link."""
    row = {
        "id": "l1", "assetId": "a1", "borrowerName": "Example Rentals Ltd", "contactLinked": True,
        "lentOn": "2026-09-10", "dueOn": "2026-09-25", "reminderMode": "ONCE", "returnedOn": None,
        "notes": "With the long bit", "createdAt": 1, "updatedAt": 2,
    }
    row.update(overrides)
    return {"loan": row}


def calls() -> tuple:
    """One call of each tool, every required argument given."""
    return (
        lambda: server_module.list_loans(asset_id="a1"),
        lambda: server_module.get_loan(loan_id="l1"),
        lambda: server_module.lend_asset(asset_id="a1", borrower_name="Sample Borrower", lent_on="2026-09-10"),
        lambda: server_module.update_loan(loan_id="l1", notes="Back by Friday"),
        lambda: server_module.return_loan(loan_id="l1", returned_on="2026-09-19"),
    )


def test_the_loan_tools_refuse_a_schema_12_phone_with_nothing_sent(paired) -> None:
    """A per-tool minimum beside the global one: a schema-12 phone takes every other write — the case tools
    included — and these five refuse by name, the two reads too, because their routes are not there,
    sending nothing but the one `/v1/status` read the pairing makes."""
    paired.reply("GET", "/v1/status", 200, STATUS_12)
    for call in calls():
        with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as raised:
            call()
        assert "schema 12" in str(raised.value)
        assert "13" in str(raised.value)
    assert [(r.method, r.path) for r in paired.requests] == [("GET", "/v1/status")]

    # The global minimum and the case minimum are untouched: the same phone takes a 1.4 write and a case.
    server_module.archive_asset(asset_id="a1")
    assert [(r.method, r.path) for r in paired.requests][-1] == ("POST", "/v1/assets/a1/archive")
    server_module.open_service_case(
        asset_id="a1", title="Drill claim", type="REPAIR", opened_on="2026-09-12", coverage="UNKNOWN",
    )
    assert [(r.method, r.path) for r in paired.requests][-1] == ("POST", "/v1/service-cases")
    assert server_module._MIN_SCHEMA_VERSION == 8
    assert server_module._MIN_LOAN_SCHEMA_VERSION == 13


def test_a_status_without_a_schema_version_refuses_the_loan_tools_too(paired) -> None:
    paired.reply("GET", "/v1/status", 200, {"appVersion": "1.0.0"})
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD"):
        server_module.list_loans(asset_id="a1")
    assert [(r.method, r.path) for r in paired.requests] == [("GET", "/v1/status")]


def test_update_loan_overlays_and_null_preserves(paired) -> None:
    """The overlay: every key of the vendored loan command but `assetId` and `borrowerName`, read off the
    row through `GET /v1/loans/{id}`, with the caller's non-null arguments over it. An omitted argument
    and one sent as `null` both keep the stored value — never clear it. The borrower, the return date and
    the link are in no argument and no body."""
    paired.reply("GET", "/v1/status", 200, STATUS_13)
    paired.reply("GET", "/v1/loans/l1", 200, loan_row())

    server_module.update_loan(loan_id="l1", notes="Back by Friday", due_on=None, reminder_mode=None)
    sent = paired.last()
    assert (sent.method, sent.path) == ("PATCH", "/v1/loans/l1")
    assert body_of(sent) == {
        "lentOn": "2026-09-10", "dueOn": "2026-09-25", "reminderMode": "ONCE", "notes": "Back by Friday",
    }, "null keeps the due date and the mode; only the notes move"
    assert [(r.method, r.path) for r in paired.requests] == [
        ("GET", "/v1/status"), ("GET", "/v1/loans/l1"), ("PATCH", "/v1/loans/l1"),
    ], "the loan is read through its own route, once"

    server_module.update_loan(loan_id="l1", lent_on="2026-09-11", due_on="2026-09-30", reminder_mode="UNTIL_RETURNED")
    assert body_of(paired.last()) == {
        "lentOn": "2026-09-11", "dueOn": "2026-09-30", "reminderMode": "UNTIL_RETURNED", "notes": "With the long bit",
    }, "every value under its own key, the notes kept"

    parameters = inspect.signature(server_module.update_loan).parameters
    for name in ("borrower_name", "returned_on", "asset_id", "contact_linked"):
        assert name not in parameters, name
    for parameter in ("lent_on", "due_on", "reminder_mode", "notes", "clear_fields"):
        assert parameters[parameter].default is None, parameter


def test_update_loan_submits_exactly_the_vendored_keys(paired, monkeypatch) -> None:
    """`update_loan` reads the vendored list, not a list of its own: its body is exactly the golden `loan`
    keys less `assetId` and `borrowerName`, and a key the loan command gains is carried by adding it to
    `command_shapes` — the tool never learns its name."""
    paired.reply("GET", "/v1/status", 200, STATUS_13)
    paired.reply("GET", "/v1/loans/l1", 200, loan_row())
    server_module.update_loan(loan_id="l1", notes="x")
    assert sorted(body_of(paired.last())) == sorted(k for k in golden("loan") if k not in ("assetId", "borrowerName"))
    assert command_shapes.LOAN_KEYS == tuple(golden("loan"))

    monkeypatch.setattr(command_shapes, "LOAN_KEYS", command_shapes.LOAN_KEYS + ("futureLoanKey",))
    row = loan_row()
    row["loan"]["futureLoanKey"] = "kept-l"
    paired.reply("GET", "/v1/loans/l1", 200, row)
    server_module.update_loan(loan_id="l1", notes="y")
    assert body_of(paired.last())["futureLoanKey"] == "kept-l"


def test_clear_fields_takes_due_on_and_notes_only(paired) -> None:
    """Clearing is by name and out of band: `due_on` to `null` (no due date) and `notes` to `""`. The lent
    date and the mode are required by the command and are never cleared — a mode goes to `NONE` by value —
    and nothing can clear the borrower, the return date or a link, which no body carries. A name given a
    value and cleared at once is refused, and a refused call sends nothing."""
    paired.reply("GET", "/v1/status", 200, STATUS_13)
    paired.reply("GET", "/v1/loans/l1", 200, loan_row())

    server_module.update_loan(loan_id="l1", reminder_mode="NONE", clear_fields=["due_on", "notes"])
    assert body_of(paired.last()) == {"lentOn": "2026-09-10", "dueOn": None, "reminderMode": "NONE", "notes": ""}

    patches = [r.method for r in paired.requests].count("PATCH")
    for name in ("lent_on", "reminder_mode", "borrower_name", "returned_on", "asset_id", "contact_linked"):
        with pytest.raises(ToolError, match="clear_fields"):
            server_module.update_loan(loan_id="l1", clear_fields=[name])
    with pytest.raises(ToolError, match="not both"):
        server_module.update_loan(loan_id="l1", notes="kept", clear_fields=["notes"])
    assert [r.method for r in paired.requests].count("PATCH") == patches, "a refused name sends nothing"
    assert server_module._LOAN_CLEARABLE_FIELDS == frozenset({"due_on", "notes"})


def test_no_loan_tool_takes_or_returns_a_lookup_uri(paired) -> None:
    """R72-4, R72-16: no loan tool has a parameter for a contact link, the vendored `loan` command carries
    none, no body any of them sends names one, and the server's source spells none of the link's names —
    so what a tool returns is the phone's `LoanDto`, which says `contactLinked` and nothing more."""
    for name in LOAN_TOOLS:
        for parameter in inspect.signature(getattr(server_module, name)).parameters:
            assert not any(word in parameter for word in LINKISH), (name, parameter)
    assert not any(word in key.lower() for key in command_shapes.LOAN_KEYS for word in LINKISH)

    paired.reply("GET", "/v1/status", 200, STATUS_13)
    paired.reply("GET", "/v1/loans/l1", 200, loan_row())
    server_module.lend_asset(asset_id="a1", borrower_name="Sample Borrower", lent_on="2026-09-10")
    server_module.update_loan(loan_id="l1", notes="Back by Friday")
    server_module.return_loan(loan_id="l1", returned_on="2026-09-19")
    for sent in paired.requests:
        if sent.method != "GET":
            assert not any(word in key.lower() for key in body_of(sent) for word in LINKISH), sent.path

    source = inspect.getsource(server_module)
    for spelling in ("contactLookupUri", "contact_lookup_uri", "lookupUri", "lookup_uri"):
        assert spelling not in source, spelling
    for name in ("list_loans", "get_loan"):
        doc = getattr(server_module, name).__doc__
        assert "`contactLinked`" in doc, name


def test_lend_asset_sends_exactly_the_golden_keys(paired) -> None:
    """A create sent as given: the body is exactly the golden `loan` keys, every value under its own key.
    The asset, the borrower and the lent date are required, with no default — the phone's form offers
    today, this tool does not; a due date left out is `null` and a mode left out `NONE`."""
    paired.reply("GET", "/v1/status", 200, STATUS_13)
    server_module.lend_asset(
        asset_id="a1", borrower_name="Sample Borrower", lent_on="2026-09-10", due_on="2026-09-25",
        reminder_mode="UNTIL_RETURNED", notes="With the long bit",
    )
    sent = paired.last()
    assert (sent.method, sent.path) == ("POST", "/v1/loans")
    assert body_of(sent) == {
        "assetId": "a1", "borrowerName": "Sample Borrower", "lentOn": "2026-09-10", "dueOn": "2026-09-25",
        "reminderMode": "UNTIL_RETURNED", "notes": "With the long bit",
    }
    assert sorted(body_of(sent)) == sorted(golden("loan"))

    server_module.lend_asset(asset_id="a2", borrower_name="Example Rentals Ltd", lent_on="2026-09-11")
    assert body_of(paired.last()) == {
        "assetId": "a2", "borrowerName": "Example Rentals Ltd", "lentOn": "2026-09-11", "dueOn": None,
        "reminderMode": "NONE", "notes": "",
    }
    parameters = inspect.signature(server_module.lend_asset).parameters
    for name in ("asset_id", "borrower_name", "lent_on"):
        assert parameters[name].default is inspect.Parameter.empty, name
    assert "returned_on" not in parameters


def test_return_loan_sends_exactly_the_golden_keys(paired) -> None:
    """"Mark returned": the body is exactly the golden `loanReturn` keys, nothing is read first, and the
    return date is required."""
    paired.reply("GET", "/v1/status", 200, STATUS_13)
    server_module.return_loan(loan_id="l1", returned_on="2026-09-19")
    sent = paired.last()
    assert (sent.method, sent.path) == ("POST", "/v1/loans/l1/return")
    assert body_of(sent) == {"returnedOn": "2026-09-19"}
    assert sorted(body_of(sent)) == sorted(golden("loanReturn"))
    assert [r.method for r in paired.requests] == ["GET", "POST"], "nothing is read first"
    for parameter in inspect.signature(server_module.return_loan).parameters.values():
        assert parameter.default is inspect.Parameter.empty, parameter.name


def test_the_reads_use_their_own_routes(paired) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_13)
    paired.reply("GET", "/v1/assets/a1/loans", 200, {"loans": []})
    paired.reply("GET", "/v1/loans/l1", 200, loan_row())
    assert server_module.list_loans(asset_id="a1") == {"loans": []}
    assert server_module.get_loan(loan_id="l1") == loan_row()
    assert [(r.method, r.path) for r in paired.requests] == [
        ("GET", "/v1/status"), ("GET", "/v1/assets/a1/loans"), ("GET", "/v1/loans/l1"),
    ]
    with pytest.raises(ToolError, match="loan_id"):
        server_module.get_loan(loan_id="")


def test_a_refusal_arrives_carrying_the_family_code_and_field(paired) -> None:
    paired.reply("GET", "/v1/status", 200, STATUS_13)
    paired.reply("POST", "/v1/loans", 422, {"error": {
        "code": "loan_validation", "message": "a reminderMode other than NONE needs a dueOn",
        "problems": ["ReminderWithoutDueDate"], "field": "reminderMode",
    }})
    with pytest.raises(ToolError) as raised:
        server_module.lend_asset(asset_id="a1", borrower_name="Sample Borrower", lent_on="2026-09-10", reminder_mode="ONCE")
    text = str(raised.value)
    assert "422 loan_validation" in text
    assert "[field=reminderMode]" in text
    assert "(ReminderWithoutDueDate)" in text


def test_no_tool_deletes_or_relinks_a_loan() -> None:
    """R72-3, R72-17: the five are the whole loan surface — the API routes no delete, relink or contact
    read, so no tool offers one."""
    loan_tools = {name for name in server_module.TOOL_NAMES if "loan" in name or name == "lend_asset"}
    assert loan_tools == set(LOAN_TOOLS)
    assert not any(word in name for name in server_module.TOOL_NAMES for word in ("relink", "contact"))
    assert "loan" in command_shapes.vendored()
    assert "loanReturn" not in command_shapes.vendored(), "the return is sent as given, not overlaid"
