"""#113/#114's tools (C29, C29a; rows 84, 109, 110, 111): the "#113/#114" block, appended last after #16's.

A4 opens it with `get_season_readiness`, a read of `GET /v1/assets/{id}/season-readiness` answered as the phone
sends it, behind a schema-22 gate. Readiness is derived on the phone and only read here: no tool takes a value, a
date to publish or READY. Beside the block, A4's wire duties: every schedule read names `role` and `roleSinceOn` and
`update_schedule` never sends them; `list_events` names the read-only `imported` marker; the four routes that can
strand a Season Start role name `SEASON_START_STRANDED`; and `import_merge` reads format 1–21. Every name here is
fictional ("Example Heater").
"""

from __future__ import annotations

import asyncio
import inspect
import json
from pathlib import Path

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import server as server_module

from conftest import schedule_row

README = Path(__file__).resolve().parent.parent / "README.md"
V1 = Path(__file__).resolve().parents[3] / "docs" / "api" / "v1.md"

STATUS_22: dict = {
    "appVersion": "1.9.0", "apiVersion": 1, "schemaVersion": 22, "backupFormatVersion": 21, "counts": {},
}
STATUS_21: dict = dict(STATUS_22, schemaVersion=21, backupFormatVersion=20)

HA_PUBLISHING_TOOLS = ("get_season_readiness", "set_schedule_role", "list_ha_entities")
"""The "#113/#114" block in C1's order, appended last after #16's (the R16-17 tail-pin precedent). A4 opens it with
one tool, taking the registered total to **91**, and A4w appends two, taking it to **93**; each later brief appends
its tools here and moves the pin."""

READINESS_KEYS = {
    "assetId", "readyForSeason", "seasonServiceDoneOn", "reason", "roleScheduleId", "roleTarget", "roleSinceOn",
    "cutoffOn", "boundarySource", "boundaryCheckedAt", "boundaryUnverifiedSince",
}
"""C29's eleven keys, exactly."""

STRAND_WRITES = ("set_season_mode", "update_asset", "update_schedule", "update_group")
"""The four tools over the routes that answer `SEASON_START_STRANDED` (D4)."""

NEVER_SAID = ("release", "authoriz", "permission to start", "safe")
"""Words never used for what ServiceTag publishes or reads (C1): Home Assistant decides, ServiceTag reports."""


def ready() -> dict:
    """A fictional "Example Heater" ready for its season: a CALENDAR window's end set the cutoff."""
    return {
        "assetId": "a1", "readyForSeason": True, "seasonServiceDoneOn": "2026-02-10", "reason": None,
        "roleScheduleId": "s1", "roleTarget": "ASSET", "roleSinceOn": "2025-06-01", "cutoffOn": "2025-11-01",
        "boundarySource": "CALENDAR_WINDOW", "boundaryCheckedAt": None, "boundaryUnverifiedSince": None,
    }


def not_ready() -> dict:
    """The same asset under a group role, its service not yet recorded since the role's boundary."""
    return dict(
        ready(), readyForSeason=False, seasonServiceDoneOn=None, reason="NOT_DONE_SINCE_COMMISSIONING",
        roleScheduleId="s2", roleTarget="GROUP", roleSinceOn="2026-01-15", cutoffOn="2026-01-15",
        boundarySource="COMMISSIONING",
    )


def paths(api) -> list[tuple[str, str]]:
    return [(r.method, r.path) for r in api.requests]


def flat(text: str | None) -> str:
    return " ".join((text or "").split())


@pytest.fixture
def phone22(paired):
    paired.reply("GET", "/v1/status", 200, STATUS_22)
    return paired


# --- get_season_readiness (C29; row 84) -------------------------------------------------------------


def test_get_season_readiness_is_registered_guarded_listed_last_and_takes_only_an_asset_id() -> None:
    assert callable(server_module.get_season_readiness)
    tool = server_module.mcp._tool_manager.get_tool("get_season_readiness")
    assert tool is not None and tool.parameters.get("additionalProperties") is False
    assert list(inspect.signature(server_module.get_season_readiness).parameters) == ["asset_id"]
    assert set(tool.parameters.get("properties", {})) == {"asset_id"}


def test_the_block_is_appended_last_in_c1s_order() -> None:
    """The block's own tail pin: the "#113/#114" tools, whole and in order, after #16's season sync read."""
    names = server_module.TOOL_NAMES
    assert names[-len(HA_PUBLISHING_TOOLS):] == HA_PUBLISHING_TOOLS
    assert names[-len(HA_PUBLISHING_TOOLS) - 1] == "get_season_sync"


def test_it_reads_the_route_once_and_answers_the_body_as_sent(phone22) -> None:
    phone22.reply("GET", "/v1/assets/a1/season-readiness", 200, ready())
    assert server_module.get_season_readiness(asset_id="a1") == ready()
    assert paths(phone22) == [("GET", "/v1/status"), ("GET", "/v1/assets/a1/season-readiness")]
    assert phone22.last().body == b""


def test_the_answer_carries_c29s_keys_and_nothing_more(phone22) -> None:
    for body in (ready(), not_ready()):
        phone22.reply("GET", "/v1/assets/a1/season-readiness", 200, body)
        answer = server_module.get_season_readiness(asset_id="a1")
        assert set(answer) == READINESS_KEYS
        assert answer == body


def test_a_schema_21_phone_is_refused_with_nothing_sent(paired) -> None:
    """The route is schema 22's, so a schema-21 phone is refused by name with nothing sent but the pairing's one
    `/v1/status` read. The behaviour first and the pinned minimum last, so a gate written one lower fails here on
    the request it lets through."""
    paired.reply("GET", "/v1/status", 200, STATUS_21)
    try:
        answer = server_module.get_season_readiness(asset_id="a1")
    except ToolError as refused:
        text = str(refused)
    else:
        text = f"answered {answer!r}"
    assert paths(paired) == [("GET", "/v1/status")], "a schema-21 phone must be sent only the status read"
    assert "APP_SCHEMA_TOO_OLD" in text, text
    assert "reports schema 21; get_season_readiness needs schema 22" in text, text
    assert "(season readiness and Home Assistant publishing)" in text, text
    assert server_module._MIN_HA_PUBLISHING_SCHEMA_VERSION == 22


def test_an_empty_id_or_an_unknown_argument_is_refused_before_any_request(paired) -> None:
    """G17: no argument takes a value, a date to publish or READY."""
    with pytest.raises(ToolError, match="must not be empty"):
        server_module.get_season_readiness(asset_id="")
    for extra in ({"ready_for_season": True}, {"season_service_done_on": "2026-02-10"}, {"reason": None}):
        with pytest.raises(ToolError, match="does not accept"):
            asyncio.run(server_module.mcp.call_tool("get_season_readiness", {"asset_id": "a1", **extra}))
    assert paired.requests == []


def test_no_such_asset_arrives_as_a_tool_error_with_the_phones_code(phone22) -> None:
    phone22.reply(
        "GET", "/v1/assets/a9/season-readiness", 404,
        {"error": {"code": "no_such_asset", "message": "no asset a9", "problems": []}},
    )
    with pytest.raises(ToolError) as raised:
        asyncio.run(server_module.mcp.call_tool("get_season_readiness", {"asset_id": "a9"}))
    assert "404 no_such_asset: no asset a9" in str(raised.value)


def test_the_docstring_names_the_route_its_keys_reasons_and_sources() -> None:
    doc = flat(server_module.get_season_readiness.__doc__)
    for words in (
        "GET /v1/assets/{id}/season-readiness", "read only", "`no_such_asset`", "`APP_SCHEMA_TOO_OLD`", "schema 22",
        "`imported`", "`detailsPending`", "`SCHEDULE_ARCHIVED`", "whatever won the cutoff",
    ):
        assert words in doc, words
    for key in READINESS_KEYS:
        assert f"`{key}`" in doc, key
    for name in (
        "NO_ROLE", "ROLE_NOT_ALLOWED", "TWO_ROLES", "SCHEDULE_ARCHIVED", "NOT_DONE_SINCE_SEASON_END",
        "NOT_DONE_SINCE_COMMISSIONING", "DETAILS_PENDING", "FUTURE_DATED", "INHERITED_SERVICE_ONLY",
        "RECORDED_IN_ADVANCE", "CALENDAR_WINDOW", "PHONE_SEASON_END", "HA_SEASON_ENDED", "HA_UNVERIFIED",
        "COMMISSIONING",
    ):
        assert f"`{name}`" in doc, name
    lowered = doc.lower()
    for word in NEVER_SAID:
        assert word not in lowered, word


def section(text: str, heading: str) -> str:
    """The Markdown section under [heading], up to the next heading of the same or a higher level."""
    lines = text.splitlines()
    start = lines.index(heading)
    level = len(heading) - len(heading.lstrip("#"))
    end = next(
        (i for i in range(start + 1, len(lines))
         if lines[i].startswith("#") and len(lines[i]) - len(lines[i].lstrip("#")) <= level),
        len(lines),
    )
    return "\n".join(lines[start:end])


def paragraph(text: str, opening: str) -> str:
    """The paragraph of [text] that starts with [opening], up to the next blank line."""
    start = text.index(opening)
    end = text.find("\n\n", start)
    return text[start:] if end < 0 else text[start:end]


def test_the_never_said_words_stay_out_of_the_docs_too() -> None:
    """C1's word rule, pinned where ServiceTag publishes it as well as on the tool: v1.md's readiness section and the
    README's entry for the tool."""
    v1 = section(V1.read_text(encoding="utf-8"), "### Season readiness and the Season Start role (#114)")
    entry = paragraph(README.read_text(encoding="utf-8"), "**Season readiness (#114; needs schema 22)**")
    for name, text in (("v1.md", v1), ("README", entry)):
        assert "/v1/assets/{id}/season-readiness" in text, name
        lowered = flat(text).lower()
        for word in NEVER_SAID:
            assert word not in lowered, (name, word)


def test_the_readme_lists_the_tool_and_its_gate() -> None:
    readme = flat(README.read_text(encoding="utf-8"))
    assert "`get_season_readiness`" in readme
    assert "The season readiness tool needs schema 22." in readme


# --- the role on the schedule reads (C29, K11; row 84) ---------------------------------------------


def test_update_schedule_keeps_the_role(phone22) -> None:
    """K11, I16: the role is response only. An edit of a role schedule reads the row — `role` and `roleSinceOn`
    included — and sends back every key of the schedule command but never those two, so the phone keeps the role
    (the app answers a body carrying either with a 400)."""
    row = schedule_row(role="SEASON_START", roleSinceOn="2026-01-15")
    phone22.reply("GET", "/v1/schedules/s1", 200, row)
    phone22.reply("PATCH", "/v1/schedules/s1", 200, {"schedule": dict(row["schedule"], title="Example annual service")})
    answer = server_module.update_schedule(schedule_id="s1", title="Example annual service")
    sent = json.loads(phone22.last().body)
    assert phone22.last().method == "PATCH"
    assert "role" not in sent and "roleSinceOn" not in sent, sorted(sent)
    assert sent["title"] == "Example annual service"
    assert answer["schedule"]["role"] == "SEASON_START"
    assert answer["schedule"]["roleSinceOn"] == "2026-01-15"


def test_the_schedule_reads_name_the_role_keys() -> None:
    for name in ("get_schedule", "list_schedules"):
        doc = flat(getattr(server_module, name).__doc__)
        assert "`role`" in doc and "`roleSinceOn`" in doc and "`SEASON_START`" in doc, name
        assert "response only" in doc, name
    doc = flat(server_module.update_schedule.__doc__)
    assert "never sends `role` or `roleSinceOn`" in doc


# --- the event marker (G-R3; row 109) ---------------------------------------------------------------


def test_list_events_names_the_read_only_imported_marker() -> None:
    doc = flat(server_module.list_events.__doc__)
    assert "`imported`" in doc and "read only" in doc and "Transfer Pack" in doc


# --- the strand code (D4; rows 110, 111) ------------------------------------------------------------


def test_the_four_strand_docstrings_name_season_start_stranded() -> None:
    for name in STRAND_WRITES:
        doc = flat(getattr(server_module, name).__doc__)
        assert "`SEASON_START_STRANDED` (409)" in doc, name
        assert "Season Start" in doc, name
    doc = flat(server_module.update_schedule.__doc__)
    assert "`QUICK_NEEDS_ELECTION`" in doc and "`ROLE_SCHEDULE_RETARGET`" in doc
    assert "`NEEDS_SEASONS`" in flat(server_module.set_season_mode.__doc__)
    assert "`WOULD_BE_LEAF_CHILD`" in flat(server_module.update_asset.__doc__)
    assert "`MEMBER_ALREADY_COVERED`" in flat(server_module.update_group.__doc__)


# --- format 21 on the MCP (D5; row 111) -------------------------------------------------------------


def test_import_merge_reads_format_1_to_21_and_names_the_two_season_start_reasons() -> None:
    doc = flat(server_module.import_merge.__doc__)
    assert "format 1–21" in doc and "1–20" not in doc
    assert "format 21 adds" in doc
    for word in ("`role`", "`roleSinceOn`", "`imported`", "`SEASON_START_HELD_BY_A_LOCAL_ROW`",
                 "`SEASON_START_DUPLICATED_IN_ARCHIVE`"):
        assert word in doc, word

    section = flat(README.read_text(encoding="utf-8").split("### `import_merge`", 1)[1].split("\n## ", 1)[0])
    assert "format **1–21**" in section and "1–20" not in section
    assert "format 21" in section
    for word in ("`role`", "`roleSinceOn`", "`imported`", "`SEASON_START_HELD_BY_A_LOCAL_ROW`",
                 "`SEASON_START_DUPLICATED_IN_ARCHIVE`"):
        assert word in section, word
