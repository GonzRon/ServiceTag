"""O-16 as amended (C39, row 128): a schedule's completion dated after the phone's today.

The phone decides, never this server: `complete_schedule` and `update_event` send a future date as they send any
other, and the app's 422 `COMPLETION_DATE_IN_FUTURE` arrives unchanged as a `ToolError` carrying the code. Both
docstrings name the code, so a caller learns the rule before it meets it.
"""

from __future__ import annotations

import inspect
import json

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import server as server_module

CODE = "COMPLETION_DATE_IN_FUTURE"

_REFUSAL = {
    "error": {
        "code": CODE,
        "message": "occurredOn may not be later than today for a schedule's completion",
        "problems": ["CompletionDateInFuture(occurredOn=2099-01-01, today=2026-02-10)"],
        "field": "occurredOn",
    },
}


def _complete() -> object:
    return server_module.complete_schedule(
        schedule_id="s1", occurred_on="2099-01-01", tz_id="Etc/UTC", asset_id=None, occurred_time=None,
        notes="", values={}, consumables=[],
    )


def _update() -> object:
    return server_module.update_event(
        event_id="e1", asset_id="a1", kind="MAINTENANCE", occurred_on="2099-01-01", tz_id="Etc/UTC",
        title="Example annual service", profile_id=None, occurred_time=None, notes="", values={}, consumables=[],
    )


CASES = [
    pytest.param(_complete, "POST", "/v1/schedules/s1/complete", id="complete_schedule"),
    pytest.param(_update, "PATCH", "/v1/events/e1", id="update_event"),
]


@pytest.mark.parametrize(("call", "method", "path"), CASES)
def test_the_apps_refusal_arrives_unchanged_after_the_date_was_sent(paired, call, method, path) -> None:
    paired.reply(method, path, 422, _REFUSAL)
    with pytest.raises(ToolError) as raised:
        call()
    assert type(raised.value) is ToolError
    assert f"422 {CODE}" in str(raised.value)
    sent = paired.last()
    assert (sent.method, sent.path) == (method, path), "the tool sent the call; the phone refused it"
    assert json.loads(sent.body)["occurredOn"] == "2099-01-01", "the date went as given, with no client-side check"


@pytest.mark.parametrize(("call", "method", "path"), CASES)
def test_a_future_date_is_never_refused_here(paired, call, method, path) -> None:
    paired.reply(method, path, 201 if method == "POST" else 200, {})
    call()
    assert json.loads(paired.last().body)["occurredOn"] == "2099-01-01"


@pytest.mark.parametrize("tool", [server_module.complete_schedule, server_module.update_event])
def test_both_docstrings_name_the_code(tool) -> None:
    doc = inspect.getdoc(tool) or ""
    assert CODE in doc
    assert "today" in doc
