"""#86's one succession tool (C20; R86-18).

`get_asset_succession` reads `GET /v1/assets/{id}/succession` and answers exactly what the phone sent:
`{replaces, replacedBy}`, each the archive's succession row `{id, predecessorAssetId, successorAssetId,
replacedOn, createdAt}` or null. The route is not on a phone below schema 15, so the tool refuses such a phone
by name, with nothing sent beyond the pairing's one `/v1/status` read; the global write minimum stays 8 and
every other per-tool minimum is untouched.

**It is the whole succession read**: a succession is recorded only by a replacement — the phone's Replace asset
or, since #92, `replace_asset` over the same use case (R92-1 supersedes R86-18) — and `import_merge` only inserts
an archive's rows, so no tool edits or removes one, and none but `replace_asset` records one.
"""

from __future__ import annotations

import inspect

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import server as server_module

STATUS_15 = {"appVersion": "1.4.1", "apiVersion": 1, "schemaVersion": 15, "backupFormatVersion": 15,
             "counts": {}}
STATUS_14 = {"appVersion": "1.4.1", "apiVersion": 1, "schemaVersion": 14, "backupFormatVersion": 14,
             "counts": {}}


def succession_row(**overrides) -> dict:
    """One succession as the phone sends it: the archive's own row."""
    row = {
        "id": "s1", "predecessorAssetId": "a1", "successorAssetId": "a2", "replacedOn": "2026-09-20",
        "createdAt": 1_758_960_000_000,
    }
    row.update(overrides)
    return row


def test_get_asset_succession_reads_its_route_and_answers_as_sent(paired) -> None:
    """The middle of a chain: both keys filled, passed through unchanged; and an unrelated asset's two nulls."""
    answer = {
        "replaces": succession_row(),
        "replacedBy": succession_row(id="s2", predecessorAssetId="a2", successorAssetId="a3", replacedOn="2026-09-28"),
    }
    paired.reply("GET", "/v1/status", 200, STATUS_15)
    paired.reply("GET", "/v1/assets/a2/succession", 200, answer)
    paired.reply("GET", "/v1/assets/a9/succession", 200, {"replaces": None, "replacedBy": None})

    assert server_module.get_asset_succession(asset_id="a2") == answer
    assert server_module.get_asset_succession(asset_id="a9") == {"replaces": None, "replacedBy": None}
    assert [(r.method, r.path) for r in paired.requests] == [
        ("GET", "/v1/status"), ("GET", "/v1/assets/a2/succession"), ("GET", "/v1/assets/a9/succession"),
    ]
    with pytest.raises(ToolError, match="asset_id"):
        server_module.get_asset_succession(asset_id="")


def test_get_asset_succession_refuses_a_schema_14_phone_with_nothing_sent(paired) -> None:
    """A per-tool minimum beside the others: a schema-14 phone takes a loan read and a 1.4 write, and this tool
    refuses by name, because its route is not there, sending nothing but the pairing's one `/v1/status` read."""
    paired.reply("GET", "/v1/status", 200, STATUS_14)
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as raised:
        server_module.get_asset_succession(asset_id="a1")
    assert "schema 14" in str(raised.value)
    assert "15" in str(raised.value)
    assert [(r.method, r.path) for r in paired.requests] == [("GET", "/v1/status")]

    server_module.list_loans(asset_id="a1")
    assert [(r.method, r.path) for r in paired.requests][-1] == ("GET", "/v1/assets/a1/loans")
    server_module.archive_asset(asset_id="a1")
    assert [(r.method, r.path) for r in paired.requests][-1] == ("POST", "/v1/assets/a1/archive")
    assert server_module._MIN_SCHEMA_VERSION == 8
    assert server_module._MIN_LOAN_SCHEMA_VERSION == 13
    assert server_module._MIN_SUCCESSION_SCHEMA_VERSION == 15


def test_a_status_without_a_schema_version_refuses_the_succession_tool_too(paired) -> None:
    paired.reply("GET", "/v1/status", 200, {"appVersion": "1.0.0"})
    with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD"):
        server_module.get_asset_succession(asset_id="a1")
    assert [(r.method, r.path) for r in paired.requests] == [("GET", "/v1/status")]


def test_the_succession_surface_is_one_read() -> None:
    """The succession read is still one tool, taking only the asset, and no tool edits or removes a succession.
    Since #92 (R92-1 supersedes R86-18) the replace write is a separate tool, `replace_asset`, beside its offer
    read — the phone's own Replace use case, which records the succession in its one write."""
    named = {name for name in server_module.TOOL_NAMES if "succession" in name}
    assert named == {"get_asset_succession"}
    assert list(inspect.signature(server_module.get_asset_succession).parameters) == ["asset_id"]
    replacing = {name for name in server_module.TOOL_NAMES if "replace" in name}
    # #47's replace closes one installed component and fits its successor; it records no asset succession.
    assert replacing == {"get_replace_offer", "replace_asset", "replace_installed_component"}
